package com.novaplayer.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class VideoAudioExtractor(
    private val context: Context
) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        const val CHUNK_MS = 30000L
        private const val TIMEOUT_US = 10000L
    }

    suspend fun getDurationMs(uri: Uri): Long =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()

            try {
                extractor.setDataSource(context, uri, null)

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(
                        MediaFormat.KEY_MIME
                    ) ?: continue

                    if (
                        mime.startsWith("audio/") &&
                        format.containsKey(
                            MediaFormat.KEY_DURATION
                        )
                    ) {
                        val durationUs = format.getLong(
                            MediaFormat.KEY_DURATION
                        )

                        if (durationUs > 0L) {
                            return@withContext durationUs / 1000L
                        }
                    }
                }

                throw IllegalStateException(
                    "Audio duration is unavailable"
                )
            } finally {
                extractor.release()
            }
        }

    suspend fun extractFirst30Seconds(
        videoUri: Uri
    ): FloatArray {
        return extractChunk(
            uri = videoUri,
            startMs = 0L,
            durationMs = CHUNK_MS
        )
    }

    suspend fun extractChunk(
        uri: Uri,
        startMs: Long,
        durationMs: Long = CHUNK_MS
    ): FloatArray = withContext(Dispatchers.IO) {
        require(startMs >= 0L)
        require(durationMs > 0L)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false

        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(
                    MediaFormat.KEY_MIME
                ) ?: continue

                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = format
                    break
                }
            }

            require(trackIndex >= 0) {
                "No audio track found"
            }

            val format = requireNotNull(trackFormat)
            val mime = requireNotNull(
                format.getString(MediaFormat.KEY_MIME)
            )

            extractor.selectTrack(trackIndex)

            val startUs = Math.multiplyExact(startMs, 1000L)
            val endUs = Math.addExact(
                startUs,
                Math.multiplyExact(durationMs, 1000L)
            )

            extractor.seekTo(
                startUs,
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true

            val decoder = codec
            val info = MediaCodec.BufferInfo()

            var sampleRate = format.getInteger(
                MediaFormat.KEY_SAMPLE_RATE
            )
            var channels = format.getInteger(
                MediaFormat.KEY_CHANNEL_COUNT
            )
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            val chunks = ArrayList<FloatArray>()
            var totalSamples = 0

            var inputEnded = false
            var outputEnded = false
            var idleCount = 0

            while (!outputEnded) {
                coroutineContext.ensureActive()

                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(
                        TIMEOUT_US
                    )

                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(
                            decoder.getInputBuffer(inputIndex)
                        )
                        inputBuffer.clear()

                        val presentationUs =
                            extractor.sampleTime

                        if (
                            presentationUs < 0L ||
                            presentationUs >= endUs
                        ) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            val size = extractor.readSampleData(
                                inputBuffer,
                                0
                            )

                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    presentationUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(
                    info,
                    TIMEOUT_US
                )

                when (outputIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat

                        sampleRate = outputFormat.getInteger(
                            MediaFormat.KEY_SAMPLE_RATE
                        )
                        channels = outputFormat.getInteger(
                            MediaFormat.KEY_CHANNEL_COUNT
                        )

                        encoding = if (
                            outputFormat.containsKey(
                                MediaFormat.KEY_PCM_ENCODING
                            )
                        ) {
                            outputFormat.getInteger(
                                MediaFormat.KEY_PCM_ENCODING
                            )
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }

                        require(
                            encoding ==
                                AudioFormat.ENCODING_PCM_16BIT
                        ) {
                            "Unsupported PCM encoding: $encoding"
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleCount++

                        if (idleCount > 3000) {
                            throw IllegalStateException(
                                "Audio decoder timed out"
                            )
                        }
                    }

                    else -> {
                        if (outputIndex >= 0) {
                            idleCount = 0

                            val outputBuffer =
                                decoder.getOutputBuffer(outputIndex)

                            if (
                                outputBuffer != null &&
                                info.size > 0
                            ) {
                                require(
                                    sampleRate > 0 &&
                                        channels > 0
                                ) {
                                    "Invalid decoded audio format"
                                }

                                require(
                                    encoding ==
                                        AudioFormat.ENCODING_PCM_16BIT
                                ) {
                                    "Only PCM 16-bit is supported"
                                }

                                val bytesPerFrame = channels * 2
                                val frames =
                                    info.size / bytesPerFrame

                                if (frames > 0) {
                                    val buffer = outputBuffer.duplicate()
                                        .order(ByteOrder.LITTLE_ENDIAN)

                                    buffer.position(info.offset)
                                    buffer.limit(
                                        info.offset +
                                            frames * bytesPerFrame
                                    )

                                    val shorts = buffer.slice()
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer()

                                    val bufferStartUs =
                                        info.presentationTimeUs

                                    val firstFrame = ceil(
                                        (startUs - bufferStartUs)
                                            .toDouble() *
                                            sampleRate / 1_000_000.0
                                    ).toInt().coerceIn(0, frames)

                                    val lastFrame = ceil(
                                        (endUs - bufferStartUs)
                                            .toDouble() *
                                            sampleRate / 1_000_000.0
                                    ).toInt().coerceIn(
                                        firstFrame,
                                        frames
                                    )

                                    val count =
                                        lastFrame - firstFrame

                                    if (count > 0) {
                                        val mono = FloatArray(count)

                                        shorts.position(
                                            firstFrame * channels
                                        )

                                        for (frame in 0 until count) {
                                            var sum = 0f

                                            for (
                                                channel in 0 until channels
                                            ) {
                                                sum += shorts.get()
                                                    .toFloat() / 32768f
                                            }

                                            mono[frame] =
                                                sum / channels
                                        }

                                        chunks.add(mono)
                                        totalSamples += count
                                    }
                                }
                            }

                            decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                            )

                            if (
                                info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            ) {
                                outputEnded = true
                            }
                        }
                    }
                }
            }

            require(totalSamples > 0) {
                "No audio samples in selected interval"
            }

            val mono = FloatArray(totalSamples)
            var offset = 0

            for (chunk in chunks) {
                chunk.copyInto(mono, offset)
                offset += chunk.size
            }

            resample(
                input = mono,
                sourceRate = sampleRate,
                targetRate = TARGET_SAMPLE_RATE
            )
        } finally {
            if (codec != null) {
                if (started) {
                    try {
                        codec.stop()
                    } catch (_: Exception) {
                    }
                }

                try {
                    codec.release()
                } catch (_: Exception) {
                }
            }

            extractor.release()
        }
    }

    private fun resample(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        require(sourceRate > 0)
        require(targetRate > 0)

        if (sourceRate == targetRate) {
            return input
        }

        if (input.isEmpty()) {
            return FloatArray(0)
        }

        val outputSize = (
            input.size.toLong() * targetRate / sourceRate
        ).toInt()

        if (outputSize <= 0) {
            return FloatArray(0)
        }

        val output = FloatArray(outputSize)

        for (i in output.indices) {
            val position =
                i.toDouble() * sourceRate / targetRate

            val left = floor(position).toInt()
                .coerceIn(0, input.lastIndex)

            val right = min(
                left + 1,
                input.lastIndex
            )

            val fraction =
                (position - left).toFloat()

            output[i] =
                input[left] * (1f - fraction) +
                    input[right] * fraction
        }

        return output
    }
}
