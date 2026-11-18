package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightDetectorTest {
    @Test
    fun allStrategiesHaveNormalizedWeights() {
        HighlightDetector.strategies.values.forEach { weights ->
            val total = weights.density + weights.hooks + weights.punctuation +
                weights.vocabulary + weights.context + weights.novelty +
                weights.clarity + weights.opening
            assertEquals(1f, total, 0.002f)
        }
    }

    @Test
    fun productionStrategyIsRobustBalanced() {
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
    fun detectorReturnsBoundedNonOverlappingClipsWithReasons() {
        val transcript = (0..11).map { index ->
            val start = index * 5_000L
            TranscriptSegment(
                start, start + 4_500L,
                "Here is an important discovery because the result changes everything. " +
                    "The best solution explains why this surprising method works!"
            )
        }
        val clips = HighlightDetector.detect(transcript, 90_000L)
        assertTrue(clips.size <= 8)
        assertTrue(clips.all {
            it.startMs >= 0L && it.endMs > it.startMs &&
                it.score in 0..100 && it.reason.isNotBlank()
        })
        clips.forEachIndexed { index, clip ->
            clips.drop(index + 1).forEach { other ->
                assertTrue(
                    HighlightDetector.intersectionOverUnion(
                        clip.startMs, clip.endMs, other.startMs, other.endMs
                    ) <= 0.40
                )
            }
        }
    }

    @Test
    fun intersectionOverUnionUsesUnionNotShorterClip() {
        assertEquals(0.5, HighlightDetector.intersectionOverUnion(0, 30_000, 10_000, 40_000), 0.0001)
        assertEquals(0.0, HighlightDetector.intersectionOverUnion(0, 10, 10, 20), 0.0)
        assertEquals(0.0, HighlightDetector.intersectionOverUnion(10, 10, 0, 20), 0.0)
    }

    @Test
    fun reasonsAreGeneratedFromWeightedSignals() {
        val transcript = (0..5).map { index ->
            val start = index * 5_000L
            TranscriptSegment(start, start + 4_500L, "Why this simple solution works is surprising!")
        }
        val clips = HighlightDetector.detect(transcript, 30_000L)
        assertFalse(clips.isEmpty())
        assertTrue(clips.all { it.reason.startsWith("Ranked") })
    }
}
