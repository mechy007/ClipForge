package com.clipforge.app

/**
 * Word-timed ASS subtitles for vertical clips.
 * Adapted conceptually from NaufalRizqullah/opensource-clipping's ASS karaoke
 * renderer (MIT, copyright 2026 Muhammad Naufal Rizqullah).
 * This is a Kotlin implementation for Android's FFmpeg subtitle filter.
 * Times must come from actual word alignment, never guessed sentence splits.
 */
data class TimedWord(val startMs: Long, val endMs: Long, val word: String)

object AssKaraokeTrack {
    private const val HEADER = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Montserrat,100,&H00FFFFFF,&H0000E6FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,12,6,5,130,130,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

    fun render(words: List<TimedWord>, clipDurationMs: Long, maxWords: Int = 3): String {
        require(clipDurationMs > 0)
        require(maxWords in 1..3)
        val valid = words.filter { it.startMs >= 0 && it.endMs > it.startMs && it.startMs < clipDurationMs && it.word.isNotBlank() }
            .sortedBy { it.startMs }
        return buildString {
            append(HEADER)
            valid.chunked(maxWords).forEach { group ->
                val groupEnd = minOf(clipDurationMs, group.maxOf { it.endMs })
                group.forEachIndexed { activeIndex, active ->
                    val start = active.startMs
                    val end = minOf(groupEnd, active.endMs)
                    if (end <= start) return@forEachIndexed
                    val text = group.mapIndexed { i, w ->
                        val escaped = w.word.uppercase().replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", " ")
                        if (i == activeIndex) "{\\c&H0000E6FF&}$escaped{\\c&H00FFFFFF&}" else escaped
                    }.joinToString(" ")
                    append("Dialogue: 0,").append(timestamp(start)).append(",")
                        .append(timestamp(end)).append(",Default,,0,0,0,,")
                        .append("{\\an5\\pos(540,1150)\\t(0,120,\\fscx110\\fscy110)}")
                        .append(text).append("\n")
                }
            }
        }
    }

    private fun timestamp(ms: Long): String {
        val cs = ms.coerceAtLeast(0) / 10
        return "${cs / 360000}:${(cs / 6000) % 60}".let { hM ->
            hM.substringBefore(':') + ":" + hM.substringAfter(':').padStart(2, '0') +
                ":" + ((cs / 100) % 60).toString().padStart(2, '0') +
                "." + (cs % 100).toString().padStart(2, '0')
        }
    }
}
