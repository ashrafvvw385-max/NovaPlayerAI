
package com.novaplayer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpeechSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

class NativeWhisper {

    companion object {
        init {
            System.loadLibrary("nova_native")
        }
    }

    private external fun transcribe(
        modelPath: String,
        audio: FloatArray,
        language: String
    ): String

    suspend fun recognize(
        modelPath: String,
        pcm16k: FloatArray,
        language: String = "auto"
    ): List<SpeechSegment> =
        withContext(Dispatchers.Default) {

            require(pcm16k.size >= 16000) {
                "Audio must be 16 kHz mono PCM"
            }

            val raw = transcribe(
                modelPath,
                pcm16k,
                language
            )

            raw.lineSequence().mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size != 3) {
                    null
                } else {
                    val start = parts[0].toLongOrNull()
                    val end = parts[1].toLongOrNull()

                    if (start == null || end == null) {
                        null
                    } else {
                        SpeechSegment(
                            startMs = start * 10,
                            endMs = end * 10,
                            text = parts[2].trim()
                        )
                    }
                }
            }.toList()
        }
}
