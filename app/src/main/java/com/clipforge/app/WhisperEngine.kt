package com.clipforge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TimedWord> = emptyList()
) {
    internal fun moveToSource(offsetMs: Long, latestMs: Long = Long.MAX_VALUE): TranscriptSegment = copy(
        startMs = startMs + offsetMs,
        endMs = minOf(endMs, latestMs) + offsetMs,
        words = words.mapNotNull { word ->
            val end = minOf(word.endMs, latestMs)
            if (end <= word.startMs) null
            else word.copy(startMs = word.startMs + offsetMs, endMs = end + offsetMs)
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

    private val sessionLock = Mutex()

    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(
        audioSamples: FloatArray,
        threadCount: Int,
        language: String?
    ): Array<String>?
    private external fun releaseModel()

    private suspend fun beginSession(context: Context) = withContext(Dispatchers.Default) {
        val model = WhisperModelManager.modelFile(context)
        require(model.isFile) { "The bundled offline AI model is not installed." }
        check(loadModel(model.absolutePath)) {
            "Whisper could not load the bundled offline AI model."
        }
    }

    internal suspend fun <T> withSession(context: Context, block: suspend () -> T): T = sessionLock.withLock {
        try {
            beginSession(context)
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { releaseModel() }
        }
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

        parseWhisperRows(rows)
    }
}

internal fun parseWhisperRows(rows: Array<String>): List<TranscriptSegment> {
    val segments = mutableListOf<TranscriptSegment>()
    var segment: TranscriptSegment? = null
    val words = mutableListOf<TimedWord>()
    val whitespace = Regex("\\s+")
    fun flush() {
        segment?.let { item ->
            val recognizedText = words.joinToString(" ") { it.word }.replace(whitespace, " ").trim()
            val complete = recognizedText == item.text.replace(whitespace, " ").trim() &&
                words.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }
            segments += item.copy(words = if (complete) words.toList() else emptyList())
        }
        words.clear()
    }
    for (row in rows) {
        val fields = row.split('\t', limit = 4)
        if (fields.size != 4) continue
        val start = fields[1].toLongOrNull() ?: continue
        val end = fields[2].toLongOrNull() ?: continue
        val text = fields[3].trim()
        when (fields[0]) {
            "S" -> {
                flush()
                segment = if (start >= 0 && end > start && text.isNotBlank()) TranscriptSegment(start, end, text) else null
            }
            "W" -> {
                val parent = segment ?: continue
                if (start >= parent.startMs && end <= parent.endMs && end > start &&
                    text.isNotBlank() && text.none { it.isWhitespace() }
                ) words += TimedWord(start, end, text)
            }
        }
    }
    flush()
    return segments
}
