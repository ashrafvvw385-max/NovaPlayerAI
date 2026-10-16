
package com.novaplayer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File

data class OnlineSubtitle(
    val fileId: Int,
    val title: String,
    val language: String,
    val downloads: Int
)

class OnlineSubtitles(
    private val apiKey: String,
    private val userToken: String = ""
) {
    private val baseUrl =
        "https://api.opensubtitles.com/api/v1"

    private fun request(
        url: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            connection.setRequestProperty(
                "Api-Key", apiKey
            )
            connection.setRequestProperty(
                "User-Agent", "NovaPlayerAI v1.0"
            )
            connection.setRequestProperty(
                "Accept", "application/json"
            )

            if (userToken.isNotBlank()) {
                connection.setRequestProperty(
                    "Authorization", "Bearer $userToken"
                )
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type", "application/json"
                )
                connection.outputStream.use {
                    it.write(
                        body.toString().toByteArray(Charsets.UTF_8)
                    )
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (status !in 200..299) {
                throw IllegalStateException(
                    "OpenSubtitles HTTP $status: " +
                        response.take(300)
                )
            }

            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun searchArabic(
        videoName: String
    ): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) {
            "OpenSubtitles API key is required"
        }

        val query = URLEncoder.encode(
            videoName, "UTF-8"
        )

        val json = request(
            "$baseUrl/subtitles?languages=ar&query=$query"
        )

        val data = json.optJSONArray("data")
            ?: return@withContext emptyList()

        val results = mutableListOf<OnlineSubtitle>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val attributes =
                item.optJSONObject("attributes") ?: continue

            val files = attributes.optJSONArray("files")
                ?: continue

            val firstFile = files.optJSONObject(0)
                ?: continue

            val fileId = firstFile.optInt("file_id", -1)
            if (fileId <= 0) continue

            results.add(
                OnlineSubtitle(
                    fileId = fileId,
                    title = attributes.optString(
                        "release", "Arabic Subtitle"
                    ),
                    language = attributes.optString(
                        "language", "ar"
                    ),
                    downloads = attributes.optInt(
                        "download_count", 0
                    )
                )
            )
        }

        results
    }

    suspend fun downloadSubtitle(
        context: Context,
        subtitle: OnlineSubtitle
    ): File = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) {
            "OpenSubtitles API key is required"
        }

        val body = JSONObject().apply {
            put("file_id", subtitle.fileId)
        }

        val response = request(
            "$baseUrl/download",
            method = "POST",
            body = body
        )

        val link = response.getString("link")
        val url = URL(link)

        require(url.protocol == "https") {
            "HTTPS is required"
        }

        val file = File(
            context.filesDir,
            "subtitle_${subtitle.fileId}.srt"
        )

        val connection =
            url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(
                    "Subtitle download failed: HTTP $status"
                )
            }

            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        file
    }
}
