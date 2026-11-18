package com.clipforge.app

import kotlin.math.max
import kotlin.math.min

data class PassiveClipInsight(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val suggestedTitle: String,
    val note: String
)

/**
 * Lightweight, offline post-processing for every transcript-derived candidate.
 * It never calls a remote service and uses only timestamps/text already produced by Whisper.
 */
object PassiveClipInsights {
    private val categories = linkedMapOf(
        "Gaming" to setOf("game", "gaming", "player", "level", "boss", "match", "minecraft", "win", "rank"),
        "Education" to setOf("learn", "lesson", "explain", "because", "example", "study", "teach", "knowledge"),
        "Technology" to setOf("software", "computer", "phone", "android", "code", "app", "ai", "model", "technology"),
        "Business" to setOf("business", "money", "market", "customer", "profit", "price", "company", "work"),
        "Lifestyle" to setOf("health", "food", "fitness", "sleep", "travel", "life", "habit", "routine"),
        "Storytime" to setOf("story", "happened", "yesterday", "remember", "experience", "once", "finally")
    )

    fun analyse(
        segments: List<TranscriptSegment>,
        text: String,
        startMs: Long,
        endMs: Long,
        durationMs: Long
    ): PassiveClipInsight {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        val category = categories.maxByOrNull { (_, terms) -> words.count { it in terms } }
            ?.takeIf { (_, terms) -> words.count { it in terms } >= 2 }?.key ?: "General"

        val relevant = segments.filter { it.startMs < endMs && it.endMs > startMs }
            .sortedBy { it.startMs }
        val first = relevant.minByOrNull { it.startMs }
        val refinedStart = first?.let { (it.startMs - 650L).coerceAtLeast(0L) }
            ?: startMs.coerceAtLeast(0L)
        val latestSpeechEnd = relevant.maxOfOrNull { it.endMs }
        var refinedEnd = latestSpeechEnd?.let { (it + 650L).coerceAtMost(durationMs) }
            ?: endMs.coerceAtMost(durationMs)

        // Keep a sensible window when Whisper timestamps are sparse or malformed.
        var finalStart = refinedStart
        if (refinedEnd - finalStart < 8_000L) {
            finalStart = startMs.coerceAtLeast(0L)
            refinedEnd = endMs.coerceAtMost(durationMs)
        }
        if (refinedEnd <= finalStart) {
            finalStart = startMs.coerceAtLeast(0L)
            refinedEnd = min(durationMs, max(finalStart + 1L, endMs))
        }

        val sentence = text.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
            .trim().trim('"', '\'')
        val title = sentence.take(64).ifBlank { "$category highlight" }

        var latestCoveredEnd = Long.MIN_VALUE
        var gaps = 0
        relevant.forEach { segment ->
            if (latestCoveredEnd != Long.MIN_VALUE && segment.startMs - latestCoveredEnd > 2_000L) {
                gaps++
            }
            latestCoveredEnd = max(latestCoveredEnd, segment.endMs)
        }

        val boundsChanged = finalStart != startMs || refinedEnd != endMs
        val note = when {
            relevant.size < 2 -> "Short transcript sample; review the cut manually."
            gaps > 0 -> "Contains pauses; check that the selected moment flows naturally."
            words.size < 20 -> "Concise moment; verify it has enough context."
            !boundsChanged -> "Original clip window retained because speech timestamps did not provide a safer cut; review manually."
            else -> "Transcript boundaries refined automatically; review before publishing."
        }
        return PassiveClipInsight(finalStart, refinedEnd, category, title, note)
    }
}
