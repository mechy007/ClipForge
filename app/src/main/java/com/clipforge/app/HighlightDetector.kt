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

data class RankingWeights(
    val density: Float,
    val hooks: Float,
    val punctuation: Float,
    val vocabulary: Float,
    val context: Float,
    val novelty: Float,
    val clarity: Float,
    val opening: Float
) {
    fun score(f: HighlightFeatures): Int {
        val value = density * f.density + hooks * f.hooks +
            punctuation * f.punctuation + vocabulary * f.vocabulary +
            context * f.context + novelty * f.novelty +
            clarity * f.clarity + opening * f.opening
        return (value * 100f).toInt().coerceIn(0, 100)
    }
}

data class HighlightFeatures(
    val density: Float,
    val hooks: Float,
    val punctuation: Float,
    val vocabulary: Float,
    val context: Float,
    val novelty: Float,
    val clarity: Float,
    val opening: Float
)

/**
 * Offline, explainable highlight ranking. V9 is the production default:
 * context and novelty matter more than raw speech speed or punctuation.
 * Scores are content-quality heuristics, not predictions of views or virality.
 */
object HighlightDetector {
    private const val WINDOW_MS = 30_000L
    private const val STEP_MS = 10_000L
    private const val MIN_WORDS = 12

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
        "V10 context-novelty-clarity" to RankingWeights(.07f,.08f,.04f,.05f,.25f,.22f,.19f,.14f)
    )

    val productionStrategy: String = "V9 robust-balanced"
    private val productionWeights = strategies.getValue(productionStrategy)
    private val hookWords = setOf(
        "why", "how", "secret", "mistake", "never", "best", "worst", "truth",
        "imagine", "because", "finally", "actually", "important", "remember",
        "surprising", "problem", "solution", "learn", "tip", "reason", "watch",
        "discover", "instead", "result", "change"
    )

    fun detect(segments: List<TranscriptSegment>, durationMs: Long): List<ClipCandidate> =
        detectWithWeights(segments, durationMs, productionWeights)

    /** Public for deterministic tests and offline strategy comparisons. */
    fun detectWithWeights(
        segments: List<TranscriptSegment>,
        durationMs: Long,
        weights: RankingWeights
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
                    val features = features(rows, words, text, start, end)
                    val score = weights.score(features)
                    val firstSentence = text.split(Regex("(?<=[.!?])\\s+"))
                        .firstOrNull().orEmpty().trim()
                    val title = firstSentence.take(64).ifBlank { "Highlight at " + format(start) }
                    candidates += ClipCandidate(start, end, title, text.take(180), score)
                }
            }
            start += STEP_MS
        }

        // Greedy NMS removes near-duplicate windows while preserving score order.
        val chosen = mutableListOf<ClipCandidate>()
        candidates.sortedByDescending { it.score }.forEach { candidate ->
            val duration = (candidate.endMs - candidate.startMs).coerceAtLeast(1L)
            val duplicate = chosen.any { existing ->
                val overlap = max(0L, min(existing.endMs, candidate.endMs) -
                    max(existing.startMs, candidate.startMs))
                val shorter = min(existing.endMs - existing.startMs, duration).coerceAtLeast(1L)
                overlap.toDouble() / shorter.toDouble() > 0.55
            }
            if (!duplicate) chosen += candidate
        }
        return chosen.sortedByDescending { it.score }.take(8)
    }

    private fun features(
        rows: List<TranscriptSegment>,
        words: List<String>,
        text: String,
        start: Long,
        end: Long
    ): HighlightFeatures {
        val seconds = ((end - start).coerceAtLeast(1L)).toFloat() / 1000f
        val density = (words.size / seconds / 2.2f).coerceIn(0f, 1f)
        val hooks = (words.count { it in hookWords } / 4f).coerceIn(0f, 1f)
        val punctuation = (text.count { it == '?' || it == '!' } / 3f).coerceIn(0f, 1f)
        val vocabulary = (words.toSet().size.toFloat() / words.size).coerceIn(0f, 1f)

        val startsCleanly = text.firstOrNull()?.isLetterOrDigit() == true
        val endsCleanly = text.lastOrNull() in listOf('.', '!', '?', '"', '\'')
        val boundary = (if (startsCleanly) 0.5f else 0f) + (if (endsCleanly) 0.5f else 0f)
        val context = (boundary * 0.55f + (if (words.size >= 24) 0.45f else words.size / 24f * 0.45f))
            .coerceIn(0f, 1f)

        // Novelty penalizes repeated filler while avoiding a reward for rare words alone.
        val repeatedRatio = words.groupingBy { it }.eachCount().values
            .filter { it > 2 }.sum().toFloat() / words.size
        val novelty = (vocabulary * 0.75f + (1f - repeatedRatio.coerceIn(0f, 1f)) * 0.25f)
            .coerceIn(0f, 1f)

        val sentenceCount = text.count { it == '.' || it == '!' || it == '?' }
        val clarity = ((if (sentenceCount > 0) 0.55f else 0.2f) +
            (if (words.size in 18..90) 0.45f else 0.2f)).coerceIn(0f, 1f)

        val first = rows.firstOrNull()
        val opening = if (first != null && first.startMs - start <= 4_000L) 1f else 0.35f

        return HighlightFeatures(density, hooks, punctuation, vocabulary, context, novelty, clarity, opening)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    private fun format(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)
}
