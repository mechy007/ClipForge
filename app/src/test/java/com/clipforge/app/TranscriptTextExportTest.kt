package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextExportTest {
    @Test
    fun exportsSortedTimestampedTranscript() {
        val result = TranscriptTextExport.format(
            listOf(
                TranscriptSegment(2_500L, 3_500L, " second line "),
                TranscriptSegment(500L, 1_500L, "first line")
            )
        )
        assertEquals(
            "ClipForge transcript\n====================\n\n" +
                "00:00:00.500  first line\n" +
                "00:00:02.500  second line\n",
            result
        )
    }

    @Test
    fun skipsBlankAndInvalidSegments() {
        val result = TranscriptTextExport.format(
            listOf(
                TranscriptSegment(0L, 0L, "invalid"),
                TranscriptSegment(1L, 2L, "  "),
                TranscriptSegment(3L, 4L, "kept")
            )
        )
        assertEquals(
            "ClipForge transcript\n====================\n\n00:00:00.003  kept\n",
            result
        )
    }
}
