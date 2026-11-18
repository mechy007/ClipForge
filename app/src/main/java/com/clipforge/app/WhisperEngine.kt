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
 * A transcription session loads the model once and reuses its native context
 * for every bounded audio chunk, then releases it when the session ends.
 */
object WhisperEngine {
    init {
        System.loadLibrary("clipforge_whisper")
    }

    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(
        audioSamples: FloatArray,
        threadCount: Int,
        language: String?
    ): Array<String>?
    private external fun releaseModel()

    suspend fun beginSession(context: Context) = withContext(Dispatchers.Default) {
        val model = WhisperModelManager.modelFile(context)
        require(model.isFile) { "The bundled offline AI model is not installed." }
        check(loadModel(model.absolutePath)) {
            "Whisper could not load the bundled offline AI model."
        }
    }

    suspend fun endSession() = withContext(Dispatchers.Default) {
        releaseModel()
    }

    suspend fun transcribe16Khz(
        context: Context,
        audioSamples: FloatArray,
        language: String? = null
    ): List<TranscriptSegment> = withContext(Dispatchers.Default) {
        require(audioSamples.isNotEmpty()) { "Audio is empty." }
        val model = WhisperModelManager.modelFile(context)
        require(model.isFile) { "The bundled offline AI model is not installed." }

        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        val rows = transcribe(audioSamples, threads, language)
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
