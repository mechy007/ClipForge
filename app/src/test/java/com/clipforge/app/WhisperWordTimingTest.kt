package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperWordTimingTest {
    @Test
    fun keepsMeasuredWordsAndConvertsToClipRelativeTimes() {
        val transcript = parseWhisperRows(arrayOf(
            "S\t1000\t2600\t नमस्ते दोस्त!",
            "W\t1100\t1700\tनमस्ते",
            "W\t1850\t2500\tदोस्त!"
        ))
        assertEquals(listOf(
            TimedWord(0, 500, "नमस्ते"),
            TimedWord(650, 1000, "दोस्त!")
        ), CaptionTrack.alignedWords(transcript, 1200, 2200))
        assertEquals(31_100L, transcript.single().moveToSource(30_000L).words.first().startMs)
    }

    @Test
    fun fallsBackToPhraseCaptionsWhenAlignmentIsMissingOrIncomplete() {
        val transcript = parseWhisperRows(arrayOf(
            "S\t0\t2000\tHello again",
            "W\t0\t800\tHello"
        ))
        assertEquals("Hello again", transcript.single().text)
        assertTrue(CaptionTrack.alignedWords(transcript, 0, 2000).isEmpty())
        assertEquals("Hello again", CaptionTrack.fromTranscript(transcript, 0, 2000).single().text)
    }
}
