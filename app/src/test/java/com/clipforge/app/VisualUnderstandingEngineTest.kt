package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun synchronizedReactionReceivesSmallBonus() {
        val clip = ClipCandidate(0L, 20_000L, "reaction", "", 50)
        val visual = VisualUnderstandingResult(listOf(VisualActivity(5_000L, 0.4f, true)))
        val audio = listOf(
            AudioEnergyWindow(4_800L, 5_800L, 0.4f, 0.9f),
            AudioEnergyWindow(8_000L, 9_000L, 0.1f, 0.2f)
        )
        val ranked = ReactionSignalFusion.rerank(listOf(clip), visual, audio).single()
        assertTrue(ranked.score > 50)
        assertTrue(ranked.score <= 56)
    }

    @Test fun unrelatedAudioDoesNotBoostVisualMoment() {
        val clip = ClipCandidate(0L, 20_000L, "reaction", "", 50)
        val visual = VisualUnderstandingResult(listOf(VisualActivity(5_000L, 0.4f, true)))
        val audio = listOf(AudioEnergyWindow(15_000L, 16_000L, 0.5f, 0.9f))
        assertEquals(50, ReactionSignalFusion.rerank(listOf(clip), visual, audio).single().score)
    }

    @Test fun separatesCameraTranslationFromSceneCutAndLocalAction() {
        val width = VisualFrameSignals.WIDTH
        val height = VisualFrameSignals.HEIGHT
        val random = kotlin.random.Random(99)
        val before = FloatArray(width * height) { random.nextFloat() }
        val moved = FloatArray(before.size) { index ->
            val x = index % width
            if (x + 4 < width) before[index + 4] else 0f
        }
        val pan = VisualFrameSignals.compare(1000L, before, moved)
        assertFalse(pan.sceneChange)
        assertTrue(pan.cameraMotion > 0.15f)
        assertEquals(0f, pan.motion, 0.001f)

        val cut = VisualFrameSignals.compare(2000L, FloatArray(before.size), FloatArray(before.size) { 1f })
        assertTrue(cut.sceneChange)
        assertEquals(0f, cut.cameraMotion, 0.001f)
    }

    @Test fun repeatedVisualSignatureIsNotRecommendedTwice() {
        val samples = (0L..100L).map { second ->
            val active = second in 10L..20L || second in 60L..70L
            VisualActivity(second * 1000L, if (active) 0.4f else 0f, false,
                signature = if (active) 0x556633aa55aacc33L else null)
        }
        val clips = VisualOnlyHighlightDetector.detect(VisualUnderstandingResult(samples), 101_000L, 15_000L)
        assertEquals(1, clips.size)
    }

    @Test fun longSilentRecordingStillFindsLocalizedAction() {
        // Match the production 7.5-second upper sampling interval on a two-hour source.
        // Quiet portions must not suppress a short but clearly active segment.
        val duration = 2L * 60L * 60L * 1000L
        val actionStart = 87L * 60L * 1000L
        val frames = (0L..(duration / 7_500L)).map { sample ->
            val time = sample * 7_500L
            val active = time in actionStart until actionStart + 45_000L
            VisualActivity(time, if (active) 0.48f else 0.005f, active && time == actionStart)
        }
        val clips = VisualOnlyHighlightDetector.detect(
            VisualUnderstandingResult(frames), duration, 30_000L, 10
        )
        assertTrue(clips.isNotEmpty())
        assertTrue(clips.any { it.startMs < actionStart + 45_000L && it.endMs > actionStart })
        assertTrue(clips.all { it.startMs >= 0L && it.endMs <= duration })
    }


}
