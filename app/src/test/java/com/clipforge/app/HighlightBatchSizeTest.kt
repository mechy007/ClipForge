package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightBatchSizeTest {
    @Test fun emptyTranscriptReturnsNoClipsForLargeBatch() {
        assertTrue(HighlightDetector.detect(emptyList(), 18L * 60 * 60 * 1000, maxClips = 100).isEmpty())
    }

    @Test fun invalidLimitsCannotProduceClips() {
        assertEquals(emptyList<ClipCandidate>(), HighlightDetector.detect(emptyList(), 60_000, maxClips = -1))
    }
}
