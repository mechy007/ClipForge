package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightDetectorTest {
    @Test
    fun allTenStrategiesHaveNormalizedWeights() {
        HighlightDetector.strategies.values.forEach { weights ->
            val total = weights.density + weights.hooks + weights.punctuation +
                weights.vocabulary + weights.context + weights.novelty +
                weights.clarity + weights.opening
            assertEquals(1f, total, 0.002f)
        }
    }

    @Test
    fun productionStrategyIsTheRobustBalancedProfile() {
        assertEquals("V9 robust-balanced", HighlightDetector.productionStrategy)
    }

    @Test
    fun detectorRejectsEmptyAndInvalidInput() {
        assertTrue(HighlightDetector.detect(emptyList(), 60_000L).isEmpty())
        val invalid = listOf(
            TranscriptSegment(-10L, 100L, "invalid negative start"),
            TranscriptSegment(200L, 100L, "invalid reversed range"),
            TranscriptSegment(100L, 200L, "   ")
        )
        assertTrue(HighlightDetector.detect(invalid, 60_000L).isEmpty())
    }

    @Test
    fun detectorReturnsBoundedNonOverlappingClips() {
        val transcript = (0..11).map { index ->
            val start = index * 5_000L
            TranscriptSegment(
                start,
                start + 4_500L,
                "Here is an important discovery because the result changes everything. " +
                    "The best solution explains why this surprising method works!"
            )
        }
        val clips = HighlightDetector.detect(transcript, 90_000L)
        assertTrue(clips.size <= 8)
        assertTrue(clips.all { it.startMs >= 0L && it.endMs > it.startMs && it.score in 0..100 })
        clips.forEachIndexed { index, clip ->
            clips.drop(index + 1).forEach { other ->
                val overlap = (minOf(clip.endMs, other.endMs) - maxOf(clip.startMs, other.startMs))
                    .coerceAtLeast(0L)
                val shorter = minOf(clip.endMs - clip.startMs, other.endMs - other.startMs)
                assertTrue(overlap.toDouble() / shorter.coerceAtLeast(1L) <= 0.55)
            }
        }
    }
}
