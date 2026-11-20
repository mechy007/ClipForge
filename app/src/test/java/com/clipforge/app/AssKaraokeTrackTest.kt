package com.clipforge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssKaraokeTrackTest {
    @Test fun highlightsWordsUsingRealTimes() {
        val ass = AssKaraokeTrack.render(listOf(
            TimedWord(100, 350, "GTA"),
            TimedWord(360, 700, "six")
        ), 1000)
        assertTrue(ass.contains("0:00:00.10,0:00:00.35"))
        assertTrue(ass.contains("GTA"))
        assertTrue(ass.contains("SIX"))
        assertTrue(ass.contains("pos(540,1150)"))
    }
    @Test fun invalidWordsAreSkipped() {
        val ass = AssKaraokeTrack.render(listOf(TimedWord(500, 300, "bad")), 1000)
        assertFalse(ass.contains("Dialogue: 0,"))
    }
}
