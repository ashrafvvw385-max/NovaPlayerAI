
package com.novaplayer.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class WhisperModelManager(
    private val context: Context
) {
    companion object {
        private const val MODEL_NAME = "ggml-small.bin"

        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

        private const val MIN_MODEL_SIZE = 400_000_000L
        private const val MAX_MODEL_SIZE = 1_000_000_000L
    }

    private val modelDirectory: File
        get() = File(
            context.filesDir,
            "whisper_models"
        )

    val modelFile: File
        get() = File(
            modelDirectory,
            MODEL_NAME
        )

    private val temporaryFile: File
        get() = File(
            modelDirectory,
            "$MODEL_NAME.part"
        )

    fun isInstalled(): Boolean {
        return modelFile.isFile &&
            modelFile.length() in
                MIN_MODEL_SIZE..MAX_MODEL_SIZE
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {

        if (isInstalled()) {
            onProgress(100)
            return@withContext modelFile
        }

        if (!modelDirectory.exists() &&
            !modelDirectory.mkdirs()
        ) {
            throw IOException(
                "Cannot create model directory"
            )
        }

        var existingBytes = if (
            temporaryFile.exists()
        ) {
            temporaryFile.length()
        } else {
            0L
        }

        if (existingBytes > MAX_MODEL_SIZE) {
            if (!temporaryFile.delete()) {
                throw IOException(
                    "Cannot reset invalid partial download"
                )
            }
            existingBytes = 0L
        }

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

            if (existingBytes > 0L) {
                setRequestProperty(
                    "Range",
                    "bytes=$existingBytes-"
                )
            }
        }

        try {
            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK &&
                status != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IOException(
                    "Model download failed: HTTP $status"
                )
            }

            val append = (
                status == HttpURLConnection.HTTP_PARTIAL &&
                    existingBytes > 0L
            )

            if (append) {
                val contentRange = connection.getHeaderField(
                    "Content-Range"
                ) ?: throw IOException(
                    "Missing Content-Range"
                )

                val rangeStart = Regex(
                    """bytes\s+(\d+)-\d+/\d+"""
                ).find(contentRange)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()

                if (rangeStart != existingBytes) {
                    throw IOException(
                        "Invalid resume offset"
                    )
                }
            }

            val startingBytes = if (append) {
                existingBytes
            } else {
                0L
            }

            val contentLength =
                connection.contentLengthLong

            val expectedTotal = if (
                contentLength > 0L
            ) {
                contentLength + startingBytes
            } else {
                -1L
            }

            if (expectedTotal > MAX_MODEL_SIZE) {
                throw IOException(
                    "Model exceeds size limit"
                )
            }

            var downloaded = startingBytes
            var lastProgress = -1

            connection.inputStream.use { input ->
                FileOutputStream(
                    temporaryFile,
                    append
                ).use { output ->

                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        coroutineContext.ensureActive()

                        val count = input.read(buffer)

                        if (count == -1) {
                            break
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )

                        downloaded += count

                        if (downloaded > MAX_MODEL_SIZE) {
                            throw IOException(
                                "Downloaded model is too large"
                            )
                        }

                        if (expectedTotal > 0L) {
                            val progress = (
                                downloaded * 100L /
                                    expectedTotal
                            ).toInt().coerceIn(0, 99)

                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }

                    output.flush()
                    output.fd.sync()
                }
            }

            val actualSize = temporaryFile.length()

            if (expectedTotal > 0L &&
                actualSize != expectedTotal
            ) {
                throw IOException(
                    "Incomplete model download"
                )
            }

            if (actualSize !in
                MIN_MODEL_SIZE..MAX_MODEL_SIZE
            ) {
                throw IOException(
                    "Invalid model file size"
                )
            }

            if (modelFile.exists() &&
                !modelFile.delete()
            ) {
                throw IOException(
                    "Cannot replace existing model"
                )
            }

            if (!temporaryFile.renameTo(modelFile)) {
                throw IOException(
                    "Cannot finalize model download"
                )
            }

            onProgress(100)

            modelFile

        } finally {
            connection.disconnect()
        }
    }
}
