package com.clipforge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Thin Kotlin boundary around the pinned whisper.cpp JNI runtime.
 * Input must be mono 16 kHz float PCM normalized to [-1, 1].
 */
object WhisperEngine {
    init {
        System.loadLibrary("clipforge_whisper")
    }

    private external fun transcribe(
        modelPath: String,
        audioSamples: FloatArray,
        threadCount: Int,
        language: String?
    ): Array<String>?

    suspend fun transcribe16Khz(
        context: Context,
        audioSamples: FloatArray,
        language: String? = null
    ): List<TranscriptSegment> = withContext(Dispatchers.Default) {
        require(audioSamples.isNotEmpty()) { "Audio is empty." }
        val model = WhisperModelManager.modelFile(context)
        require(model.isFile) { "Download the offline AI model first." }

        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        val rows = transcribe(model.absolutePath, audioSamples, threads, language)
            ?: error("Whisper could not transcribe this audio.")

        rows.mapNotNull { row ->
            val fields = row.split('\t', limit = 3)
            if (fields.size != 3) return@mapNotNull null
            TranscriptSegment(
                startMs = fields[0].toLongOrNull() ?: return@mapNotNull null,
                endMs = fields[1].toLongOrNull() ?: return@mapNotNull null,
                text = fields[2].trim()
            )
        }.filter { it.endMs > it.startMs && it.text.isNotBlank() }
    }
}
