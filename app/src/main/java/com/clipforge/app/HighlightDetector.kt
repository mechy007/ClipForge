package com.clipforge.app

import kotlin.math.max
import kotlin.math.min

data class ClipCandidate(
    val startMs: Long, val endMs: Long, val title: String, val hook: String,
    val score: Int, val reason: String = ""
)

data class RankingWeights(
    val density: Float, val hooks: Float, val punctuation: Float, val vocabulary: Float,
    val context: Float, val novelty: Float, val clarity: Float, val opening: Float
) {
    fun score(f: HighlightFeatures): Int {
        val value = density * f.density + hooks * f.hooks + punctuation * f.punctuation +
            vocabulary * f.vocabulary + context * f.context + novelty * f.novelty +
            clarity * f.clarity + opening * f.opening
        return (value * 100f).toInt().coerceIn(0, 100)
    }

    fun explain(f: HighlightFeatures): String {
        val signals = listOf(
            "speech flow" to density * f.density,
            "hook language" to hooks * f.hooks,
            "expressive punctuation" to punctuation * f.punctuation,
            "varied vocabulary" to vocabulary * f.vocabulary,
            "complete context" to context * f.context,
            "low repetition" to novelty * f.novelty,
            "clear phrasing" to clarity * f.clarity,
            "strong opening" to opening * f.opening
        ).sortedByDescending { it.second }.filter { it.second > 0.015f }.take(2)
        return when (signals.size) {
            0 -> "Ranked using balanced offline content-quality signals."
            1 -> "Ranked highly for " + signals[0].first + "."
            else -> "Ranked for " + signals[0].first + " and " + signals[1].first + "."
        }
    }
}

data class HighlightFeatures(
    val density: Float, val hooks: Float, val punctuation: Float, val vocabulary: Float,
    val context: Float, val novelty: Float, val clarity: Float, val opening: Float
)

/** Offline, explainable ranking. Scores are not predictions of views or virality. */
object HighlightDetector {
    private const val WINDOW_MS = 30_000L
    private const val STEP_MS = 10_000L
    private const val MIN_WORDS = 12
    private const val DEDUPE_IOU = 0.40

    val strategies: Map<String, RankingWeights> = linkedMapOf(
        "V1 density-first" to RankingWeights(.35f,.20f,.15f,.15f,.05f,.03f,.02f,.05f),
        "V2 hook-first" to RankingWeights(.10f,.35f,.15f,.10f,.10f,.05f,.05f,.10f),
        "V3 balanced" to RankingWeights(.13f,.13f,.08f,.08f,.18f,.15f,.13f,.12f),
        "V4 context-first" to RankingWeights(.05f,.08f,.04f,.05f,.30f,.20f,.16f,.12f),
        "V5 novelty-first" to RankingWeights(.05f,.07f,.04f,.05f,.18f,.30f,.18f,.13f),
        "V6 clarity-first" to RankingWeights(.05f,.07f,.04f,.05f,.20f,.15f,.30f,.14f),
        "V7 opening-first" to RankingWeights(.08f,.14f,.06f,.05f,.18f,.12f,.12f,.25f),
        "V8 engagement-proxy" to RankingWeights(.15f,.25f,.15f,.10f,.10f,.08f,.07f,.10f),
        "V9 robust-balanced" to RankingWeights(.08f,.10f,.05f,.06f,.22f,.18f,.17f,.14f),
        "V10 context-novelty-clarity" to RankingWeights(.0673f,.0769f,.0385f,.0481f,.2404f,.2115f,.1827f,.1346f)
    )

    val productionStrategy = "V9 robust-balanced"
    private val productionWeights = strategies.getValue(productionStrategy)
    private val hookWords = setOf(
        "why", "how", "secret", "mistake", "never", "best", "worst", "truth", "imagine",
        "because", "finally", "actually", "important", "remember", "surprising", "problem",
        "solution", "learn", "tip", "reason", "watch", "discover", "instead", "result",
        "change", "revealed", "warning", "impossible", "simple", "hidden", "prove", "lesson"
    )

    fun detect(segments: List<TranscriptSegment>, durationMs: Long): List<ClipCandidate> =
        detectWithWeights(segments, durationMs, productionWeights)

    fun detectWithWeights(
        segments: List<TranscriptSegment>, durationMs: Long, weights: RankingWeights
    ): List<ClipCandidate> {
        if (segments.isEmpty() || durationMs <= 0L) return emptyList()
        val sorted = segments.filter {
            it.startMs >= 0L && it.endMs > it.startMs && it.text.isNotBlank()
        }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val candidates = mutableListOf<ClipCandidate>()
        var start = 0L
        while (start < durationMs) {
            val end = min(durationMs, start + WINDOW_MS)
            if (end - start < 8_000L) break
            val rows = sorted.filter { it.startMs < end && it.endMs > start }
            if (rows.isNotEmpty()) {
                val text = rows.joinToString(" ") { it.text }.trim()
                val words = tokenize(text)
                if (words.size >= MIN_WORDS) {
                    val f = features(rows, words, text, start, end)
                    val firstSentence = text.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().trim()
                    candidates += ClipCandidate(
                        startMs = start, endMs = end,
                        title = firstSentence.take(64).ifBlank { "Highlight at " + format(start) },
                        hook = text.take(180), score = weights.score(f), reason = weights.explain(f)
                    )
                }
            }
            start += STEP_MS
        }

        // Greedy interval NMS using intersection-over-union, following AutoClip's
        // word-range IoU technique but operating only on available segment times.
        val chosen = mutableListOf<ClipCandidate>()
        candidates.sortedByDescending { it.score }.forEach { candidate ->
            if (chosen.none { existing ->
                    intersectionOverUnion(candidate.startMs, candidate.endMs, existing.startMs, existing.endMs) > DEDUPE_IOU
                }) chosen += candidate
        }
        return chosen.sortedByDescending { it.score }.take(8)
    }

    fun intersectionOverUnion(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Double {
        if (aEnd <= aStart || bEnd <= bStart) return 0.0
        val intersection = max(0L, min(aEnd, bEnd) - max(aStart, bStart))
        if (intersection == 0L) return 0.0
        val union = (aEnd - aStart) + (bEnd - bStart) - intersection
        return if (union > 0L) intersection.toDouble() / union.toDouble() else 0.0
    }

    private fun features(
        rows: List<TranscriptSegment>, words: List<String>, text: String, start: Long, end: Long
    ): HighlightFeatures {
        val seconds = (end - start).coerceAtLeast(1L).toFloat() / 1000f
        val density = (words.size / seconds / 2.2f).coerceIn(0f, 1f)
        val hooks = (words.count { it in hookWords } / 4f).coerceIn(0f, 1f)
        val punctuation = (text.count { it == '?' || it == '!' } / 3f).coerceIn(0f, 1f)
        val vocabulary = (words.toSet().size.toFloat() / words.size).coerceIn(0f, 1f)
        val startsCleanly = text.firstOrNull()?.isLetterOrDigit() == true
        val endsCleanly = text.lastOrNull() in listOf('.', '!', '?', '"', '\'')
        val boundary = (if (startsCleanly) 0.5f else 0f) + (if (endsCleanly) 0.5f else 0f)
        val context = (boundary * 0.55f +
            (if (words.size >= 24) 0.45f else words.size / 24f * 0.45f)).coerceIn(0f, 1f)
        val repeatedRatio = words.groupingBy { it }.eachCount().values
            .filter { it > 2 }.sum().toFloat() / words.size
        val novelty = (vocabulary * 0.75f + (1f - repeatedRatio.coerceIn(0f, 1f)) * 0.25f).coerceIn(0f, 1f)
        val sentenceCount = text.count { it == '.' || it == '!' || it == '?' }
        val clarity = ((if (sentenceCount > 0) 0.55f else 0.2f) +
            (if (words.size in 18..90) 0.45f else 0.2f)).coerceIn(0f, 1f)
        val first = rows.firstOrNull()
        val opening = if (first != null && (max(first.startMs, start) - start) <= 4_000L) 1f else 0.35f
        return HighlightFeatures(density, hooks, punctuation, vocabulary, context, novelty, clarity, opening)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun format(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)
}
