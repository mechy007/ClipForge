package com.clipforge.app

/** A timed subtitle cue using milliseconds on the source-video timeline. */
data class CaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

enum class CaptionPreset(val label: String) {
    CLEAN("Clean"),
    BOLD("Bold"),
    KARAOKE("Karaoke"),
    BOXED("Boxed")
}

/**
 * Builds subtitle sidecars from the existing local transcript.
 * This deliberately does not claim to burn captions into the MP4; visual
 * rendering is handled by the video export pipeline in a later stage.
 */
object CaptionTrack {
    fun fromTranscript(
        transcript: List<TranscriptSegment>,
        clipStartMs: Long,
        clipEndMs: Long,
        maxCharactersPerCue: Int = 84
    ): List<CaptionCue> {
        require(clipStartMs >= 0L) { "Clip start must not be negative." }
        require(clipEndMs > clipStartMs) { "Clip end must be after clip start." }
        require(maxCharactersPerCue >= 12) { "Caption cue limit is too small." }

        return transcript.asSequence()
            .filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .mapNotNull { segment ->
                val start = maxOf(segment.startMs, clipStartMs)
                val end = minOf(segment.endMs, clipEndMs)
                if (end <= start) return@mapNotNull null
                CaptionCue(
                    startMs = start - clipStartMs,
                    endMs = end - clipStartMs,
                    text = wrapText(segment.text.trim(), maxCharactersPerCue)
                )
            }
            .sortedBy { it.startMs }
            .toList()
    }

    fun toSrt(cues: List<CaptionCue>): String = buildString {
        cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .sortedBy { it.startMs }
            .forEachIndexed { index, cue ->
                append(index + 1).append('\n')
                append(timestamp(cue.startMs)).append(" --> ")
                    .append(timestamp(cue.endMs)).append('\n')
                append(cue.text.replace("\r", "").trim()).append("\n\n")
            }
    }

    private fun wrapText(raw: String, maxCharacters: Int): String {
        val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        words.forEach { word ->
            if (line.isNotEmpty() && line.length + 1 + word.length > maxCharacters) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n")
    }

    private fun timestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
