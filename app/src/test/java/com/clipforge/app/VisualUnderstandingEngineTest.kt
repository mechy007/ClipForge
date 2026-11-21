package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualUnderstandingEngineTest {
    @Test fun silentGameplayProducesVisualHighlights() {
        val frames = (0..120).map { index ->
            VisualActivity(index * 1_000L, if (index in 30..65) 0.45f else 0.01f, index == 35)
        }
        val clips = VisualOnlyHighlightDetector.detect(VisualUnderstandingResult(frames), 121_000L)
        assertTrue(clips.isNotEmpty())
        assertTrue(clips.any { it.startMs <= 35_000L && it.endMs > 35_000L })
        assertTrue(clips.all { it.startMs >= 0L && it.endMs <= 121_000L })
    }

    @Test fun visualHighlightsNeverOverlapHeavily() {
        val frames = (0..180).map { VisualActivity(it * 1_000L, 0.4f, false) }
        val clips = VisualOnlyHighlightDetector.detect(VisualUnderstandingResult(frames), 181_000L)
        assertTrue(clips.size <= 10)
        for (i in clips.indices) for (j in i + 1 until clips.size) {
            assertTrue(HighlightDetector.overlapOfShorter(
                clips[i].startMs, clips[i].endMs, clips[j].startMs, clips[j].endMs
            ) <= 0.65)
        }
    }

    @Test fun emptyOrTooShortVideoHasNoCandidates() {
        assertTrue(VisualOnlyHighlightDetector.detect(VisualUnderstandingResult(emptyList()), 60_000L).isEmpty())
        assertTrue(VisualOnlyHighlightDetector.detect(
            VisualUnderstandingResult(listOf(VisualActivity(0L, 0.8f, true))), 4_000L
        ).isEmpty())
    }

    @Test fun visualRerankingPreservesClipBoundaries() {
        val original = ClipCandidate(0L, 30_000L, "test", "hook", 50)
        val signals = VisualUnderstandingResult(listOf(
            VisualActivity(1_000L, 0.4f, false), VisualActivity(2_000L, 0.5f, true)
        ))
        val result = VisualHighlightReranker.rerank(listOf(original), signals).single()
        assertEquals(original.startMs, result.startMs)
        assertEquals(original.endMs, result.endMs)
        assertTrue(result.score in 50..58)
    }
    @Test fun staticVideoDoesNotGenerateFalseHighlights() {
        val frames = (0..90).map { VisualActivity(it * 1_000L, 0.01f, false) }
        val clips = VisualOnlyHighlightDetector.detect(VisualUnderstandingResult(frames), 91_000L)
        assertTrue(clips.isEmpty())
    }

    @Test fun visualCandidatesAreCappedAtRequestedCount() {
        val frames = (0..300).map { VisualActivity(it * 1_000L, 0.3f, it % 30 == 0) }
        val clips = VisualOnlyHighlightDetector.detect(
            VisualUnderstandingResult(frames), 301_000L, 20_000L, 3
        )
        assertTrue(clips.isNotEmpty())
        assertTrue(clips.size <= 3)
    }

}
