package com.clipforge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TimedWord> = emptyList()
) {
    internal fun withCompleteWordTiming(timing: List<TimedWord>): TranscriptSegment {
        val complete = timing.joinToString(" ") { it.word } == text.trim().replace(Regex("\\s+"), " ") &&
            timing.zipWithNext().all { (first, second) -> first.startMs < second.startMs }
        return copy(words = if (complete) timing else emptyList())
    }

    fun shiftedBy(offsetMs: Long) = copy(
        startMs = Math.addExact(startMs, offsetMs),
        endMs = Math.addExact(endMs, offsetMs),
        words = words.map {
            it.copy(startMs = Math.addExact(it.startMs, offsetMs), endMs = Math.addExact(it.endMs, offsetMs))
        }
    )
}

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
    ): Array<Array<String>>?
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

        fun parse(row: String): TimedWord? {
            val fields = row.split('\t', limit = 3)
            if (fields.size != 3) return null
            val start = fields[0].toLongOrNull() ?: return null
            val end = fields[1].toLongOrNull() ?: return null
            return TimedWord(start, end, fields[2].trim()).takeIf {
                start >= 0 && end > start && it.word.isNotBlank()
            }
        }
        rows.mapNotNull { row ->
            val segment = row.firstOrNull()?.let(::parse) ?: return@mapNotNull null
            val words = row.drop(1).mapNotNull(::parse)
            TranscriptSegment(segment.startMs, segment.endMs, segment.word).withCompleteWordTiming(words)
        }
    }
}
