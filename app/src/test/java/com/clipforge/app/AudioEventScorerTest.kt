package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioEventScorerTest {
    @Test
    fun scoresReactionEventsInTheClipWindow() {
        val events = listOf(
            AudioEventWindow(0, 1_000, 0.9f, 0.1f, 0f),
            AudioEventWindow(1_000, 2_000, 0.1f, 0.8f, 0.6f)
        )

        assertEquals(52, AudioEventScorer.score(0, 1_000, events))
    }

    @Test
    fun ignoresLowConfidenceAndOutOfRangeSignals() {
        val events = listOf(AudioEventWindow(1_000, 2_000, 0.1f, 0.05f, 0f))

        assertNull(AudioEventScorer.score(0, 900, events))
        assertNull(AudioEventScorer.score(1_000, 2_000, events))
    }
}
