
package com.novaplayer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class WhisperModelManager(
    private val context: Context
) {
    companion object {
        private const val MODEL_NAME = "ggml-small.bin"

        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

        private const val MIN_MODEL_SIZE = 400_000_000L
    }

    private val modelDirectory: File
        get() = File(context.filesDir, "whisper_models")

    val modelFile: File
        get() = File(modelDirectory, MODEL_NAME)

    fun isInstalled(): Boolean {
        return modelFile.isFile &&
            modelFile.length() >= MIN_MODEL_SIZE
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {

        if (isInstalled()) {
            onProgress(100)
            return@withContext modelFile
        }

        modelDirectory.mkdirs()

        val temporaryFile = File(
            modelDirectory,
            "$MODEL_NAME.part"
        )

        val connection = (
            URL(MODEL_URL).openConnection()
                as HttpURLConnection
        ).apply {
            connectTimeout = 30000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "NovaPlayerAI/1.0"
            )
        }

        try {
            val existing = if (temporaryFile.exists()) {
                temporaryFile.length()
            } else {
                0L
            }

            if (existing > 0L) {
                connection.setRequestProperty(
                    "Range",
                    "bytes=$existing-"
                )
            }

            connection.connect()

            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK &&
                status != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IOException(
                    "Download failed: HTTP $status"
                )
            }

            val append =
                status == HttpURLConnection.HTTP_PARTIAL &&
                    existing > 0L

            val startingBytes = if (append) {
                existing
            } else {
                0L
            }

            val expectedTotal =
                connection.contentLengthLong
                    .takeIf { it > 0L }
                    ?.plus(startingBytes)
                    ?: -1L

            var downloaded = startingBytes

            connection.inputStream.use { input ->
                temporaryFile.outputStream(append).use { output ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)

                        if (count == -1) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        if (expectedTotal > 0L) {
                            val percent = (
                                downloaded * 100L /
                                    expectedTotal
                            ).toInt().coerceIn(0, 99)

                            onProgress(percent)
                        }
                    }
                }
            }

            if (expectedTotal > 0L &&
                temporaryFile.length() != expectedTotal
            ) {
                throw IOException(
                    "Incomplete model download"
                )
            }

            if (temporaryFile.length() < MIN_MODEL_SIZE) {
                throw IOException(
                    "Downloaded model is too small"
                )
            }

            if (modelFile.exists() && !modelFile.delete()) {
                throw IOException(
                    "Cannot replace existing model"
                )
            }

            if (!temporaryFile.renameTo(modelFile)) {
                throw IOException(
                    "Cannot save downloaded model"
                )
            }

            onProgress(100)
            modelFile

        } finally {
            connection.disconnect()
        }
    }
}
