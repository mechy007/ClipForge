package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssKaraokeTrackTest {
    private val cue = CaptionCue(100, 700, "GTA six", listOf(
        TimedWord(100, 350, "GTA"),
        TimedWord(360, 700, "six")
    ))

    @Test fun highlightsOnlyTheCurrentWord() {
        val frames = AssKaraokeTrack.frames(listOf(cue))
        assertEquals(listOf(0, -1, 1), frames.map { it.activeIndex })
        assertEquals(listOf(100L, 350L, 360L), frames.map { it.startMs })
        val ass = AssKaraokeTrack.render(listOf(cue), CaptionPreset.KARAOKE, 1080, 1920)
        assertTrue(ass.contains("0:00:00.10,0:00:00.35"))
        assertTrue(ass.contains("{\\c&H1FD2FF&}GTA{\\r} six"))
        assertTrue(ass.contains("PlayResY: 1920"))
    }

    @Test fun popRespectsTheWordDurationAndKeepsUntimedText() {
        val short = CaptionCue(0, 50, "Go!", listOf(TimedWord(0, 50, "Go!")))
        val old = CaptionCue(80, 1000, "Older transcript")
        val ass = AssKaraokeTrack.render(listOf(short, old), CaptionPreset.POP, 1080, 1920)
        assertTrue(ass.contains("\\t(0,50,\\fscx100\\fscy100)"))
        assertTrue(ass.contains(",,Older transcript"))
        assertFalse(ass.contains("\\t(0,120,"))
    }

    @Test fun clipCuesShiftAndClampWordTimestamps() {
        val transcript = TranscriptSegment(100, 700, cue.text, cue.words).shiftedBy(30_000)
        val words = CaptionTrack.fromTranscript(listOf(transcript), 30_200, 30_500).single().words
        assertEquals(listOf(TimedWord(0, 150, "GTA"), TimedWord(160, 300, "six")), words)
    }
}
