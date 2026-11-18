package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalScorerTest {
    @Test fun emptyAudioReturnsNoSignal() {
        assertEquals(0, AudioSignalScorer.score(0, 10_000, emptyList(), 0.5f, 0.8f))
    }

    @Test fun higherRelativeEnergyScoresAboveQuietWindow() {
        val windows = listOf(
            AudioEnergyWindow(0, 1_000, 0.10f, 0.20f),
            AudioEnergyWindow(1_000, 2_000, 0.80f, 0.95f),
            AudioEnergyWindow(2_000, 3_000, 0.15f, 0.25f)
        )
        val quiet = AudioSignalScorer.score(0, 1_000, windows, 0.80f, 0.95f)
        val loud = AudioSignalScorer.score(1_000, 2_000, windows, 0.80f, 0.95f)
        assertTrue(loud > quiet)
        assertTrue(loud in 0..100 && quiet in 0..100)
    }

    @Test fun ignoresWindowsOutsideClipBounds() {
        val windows = listOf(
            AudioEnergyWindow(0, 1_000, 0.9f, 0.9f),
            AudioEnergyWindow(1_000, 2_000, 0.1f, 0.1f)
        )
        val score = AudioSignalScorer.score(1_000, 2_000, windows, 0.9f, 0.9f)
        assertEquals(9, score)
    }
}
