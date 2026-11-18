package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipBoundaryRefinerTest {
    @Test fun snapsToQuietWindowsAroundSpeech() {
        val transcript = listOf(TranscriptSegment(2_000L, 8_000L, "A complete spoken moment"))
        val audio = listOf(
            AudioEnergyWindow(0L, 1_000L, .001f, .01f),
            AudioEnergyWindow(1_000L, 2_000L, .001f, .01f),
            AudioEnergyWindow(2_000L, 3_000L, .2f, .7f),
            AudioEnergyWindow(8_000L, 9_000L, .2f, .7f),
            AudioEnergyWindow(9_000L, 10_000L, .001f, .01f)
        )
        val result = ClipBoundaryRefiner.refine(2_500L, 8_500L, 12_000L, transcript, audio)
        assertEquals(2_000L, result.first)
        assertEquals(9_000L, result.second)
    }

    @Test fun neverSnapsThroughASpeechSegment() {
        val transcript = listOf(TranscriptSegment(1_000L, 4_000L, "speech"))
        val audio = listOf(AudioEnergyWindow(0L, 1_000L, .001f, .01f))
        val result = ClipBoundaryRefiner.refine(1_500L, 4_500L, 8_000L, transcript, audio, listOf(2_000L))
        assertEquals(1_500L, result.first)
    }

    @Test fun ignoresSceneCutsOutsideSnapWindow() {
        val result = ClipBoundaryRefiner.refine(5_000L, 10_000L, 20_000L, emptyList(), emptyList(), listOf(1_000L, 15_000L))
        assertEquals(5_000L, result.first)
        assertEquals(10_000L, result.second)
    }
}
