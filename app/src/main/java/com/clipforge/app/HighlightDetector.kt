package com.clipforge.app

import kotlin.math.max
import kotlin.math.min

data class ClipCandidate(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val hook: String,
    val score: Int
)

/** Offline heuristic ranking; scores are not predictions of views or virality. */
object HighlightDetector {
    private const val WINDOW_MS = 30_000L
    private const val STEP_MS = 10_000L
    private val hookWords = setOf(
        "why", "how", "secret", "mistake", "never", "best", "worst", "truth",
        "imagine", "because", "finally", "actually", "important", "remember",
        "surprising", "problem", "solution", "learn", "tip", "reason"
    )

    fun detect(segments: List<TranscriptSegment>, durationMs: Long): List<ClipCandidate> {
        if (segments.isEmpty() || durationMs <= 0L) return emptyList()
        val sorted = segments.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val candidates = mutableListOf<ClipCandidate>()
        var start = 0L
        while (start < durationMs) {
            val end = min(durationMs, start + WINDOW_MS)
            if (end - start < 8_000L) break
            val rows = sorted.filter { it.startMs < end && it.endMs > start }
            if (rows.isNotEmpty()) {
                val words = rows.flatMap { it.text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }
                    .filter { it.isNotBlank() }
                val text = rows.joinToString(" ") { it.text }.trim()
                val density = words.size.toFloat() / ((end - start).toFloat() / 1000f)
                val hookCount = words.count { it in hookWords }
                val punctuation = text.count { it == '?' || it == '!' }
                val uniqueRatio = if (words.isEmpty()) 0f else words.toSet().size.toFloat() / words.size
                val densityScore = (density / 2.2f * 35f).toInt().coerceIn(0, 35)
                val hookScore = (hookCount * 7).coerceAtMost(28)
                val punctuationScore = (punctuation * 5).coerceAtMost(15)
                val varietyScore = (uniqueRatio * 22f).toInt().coerceIn(0, 22)
                val score = (densityScore + hookScore + punctuationScore + varietyScore).coerceIn(0, 100)
                if (words.size >= 12) {
                    val firstSentence = text.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
                    val title = firstSentence.take(54).ifBlank { "Highlight at " + format(start) }
                    candidates += ClipCandidate(start, end, title, text.take(150), score)
                }
            }
            start += STEP_MS
        }
        val chosen = mutableListOf<ClipCandidate>()
        candidates.sortedByDescending { it.score }.forEach { candidate ->
            if (chosen.none { existing ->
                    val overlap = max(0L, min(existing.endMs, candidate.endMs) - max(existing.startMs, candidate.startMs))
                    overlap.toDouble() / (candidate.endMs - candidate.startMs).coerceAtLeast(1L) > 0.55
                }) chosen += candidate
        }
        return chosen.sortedByDescending { it.score }.take(8)
    }

    private fun format(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)
}
