package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionTrackTest {
    @Test
    fun clipsAndOffsetsTranscriptCuesToClipTimeline() {
        val transcript = listOf(
            TranscriptSegment(0L, 1_000L, "outside"),
            TranscriptSegment(900L, 2_500L, "first caption"),
            TranscriptSegment(2_000L, 4_000L, "second caption"),
            TranscriptSegment(4_500L, 5_000L, "after clip")
        )
        val cues = CaptionTrack.fromTranscript(transcript, 1_000L, 3_000L)
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(1_500L, cues[0].endMs)
        assertEquals(1_000L, cues[1].startMs)
        assertEquals(2_000L, cues[1].endMs)
    }

    @Test
    fun exportsValidSrtTimestampsAndSequenceNumbers() {
        val srt = CaptionTrack.toSrt(
            listOf(CaptionCue(0L, 1_250L, "Hello world"), CaptionCue(1_500L, 2_000L, "Next line"))
        )
        assertTrue(srt.contains("1\n00:00:00,000 --> 00:00:01,250\nHello world"))
        assertTrue(srt.contains("2\n00:00:01,500 --> 00:00:02,000\nNext line"))
    }

    @Test
    fun rejectsInvalidClipRange() {
        try {
            CaptionTrack.fromTranscript(emptyList(), 5_000L, 5_000L)
            throw AssertionError("Expected invalid range to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
