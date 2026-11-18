package com.clipforge.app

/** Formats the complete local transcript as a readable, timestamped UTF-8 text file. */
object TranscriptTextExport {
    fun format(segments: List<TranscriptSegment>): String = buildString {
        append("ClipForge transcript\n")
        append("====================\n\n")
        segments.asSequence()
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
            .forEach { segment ->
                append(timestamp(segment.startMs))
                    .append("  ")
                    .append(segment.text.replace("\r", "").trim())
                    .append('\n')
            }
    }

    private fun timestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }
}
