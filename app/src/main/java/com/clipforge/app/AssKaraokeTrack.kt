package com.clipforge.app

data class TimedWord(val startMs: Long, val endMs: Long, val word: String)

internal data class CaptionFrame(
    val startMs: Long,
    val endMs: Long,
    val words: List<String>,
    val activeIndex: Int = -1
)

/** One caption timeline shared by the Android preview and ASS export. */
object AssKaraokeTrack {
    internal const val POP_DURATION_MS = 120L

    internal fun frames(cues: List<CaptionCue>): List<CaptionFrame> = buildList {
        for (cue in cues) {
            val words = cue.words.sortedBy { it.startMs }
            if (words.isEmpty()) {
                add(CaptionFrame(cue.startMs, cue.endMs, listOf(cue.text)))
                continue
            }
            var offset = 0
            while (offset < words.size) {
                var limit = offset + 1
                var length = words[offset].word.length
                while (limit < words.size && limit - offset < 3) {
                    val next = words[limit]
                    if (next.startMs - words[limit - 1].endMs > 350L ||
                        length + next.word.length + 1 > 24) break
                    length += next.word.length + 1
                    limit++
                }
                val group = words.subList(offset, limit)
                val text = group.map { it.word }
                group.forEachIndexed { index, word ->
                    val nextStart = words.getOrNull(offset + index + 1)?.startMs ?: cue.endMs
                    val end = minOf(word.endMs, nextStart)
                    if (end > word.startMs) add(CaptionFrame(word.startMs, end, text, index))
                    if (index < group.lastIndex && nextStart > end) {
                        add(CaptionFrame(end, nextStart, text))
                    }
                }
                offset = limit
            }
        }
    }.sortedBy { it.startMs }

    fun render(cues: List<CaptionCue>, preset: CaptionPreset, width: Int, height: Int): String = buildString {
        val size = (minOf(width, height) * 0.055).toInt().coerceAtLeast(12)
        val outline = (size / 12).coerceAtLeast(1)
        val margin = (height * if (height > width) 0.20 else 0.10).toInt()
        val horizontal = (width * 0.08).toInt()
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine("PlayResX: $width")
        appendLine("PlayResY: $height")
        appendLine("WrapStyle: 0")
        appendLine("ScaledBorderAndShadow: yes")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        appendLine("Style: Default,sans-serif,$size,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,$outline,1,2,$horizontal,$horizontal,$margin,1")
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (frame in frames(cues)) {
            if (frame.endMs / 10 <= frame.startMs / 10) continue
            val text = frame.words.mapIndexed { index, word ->
                val safe = word.replace('\\', '＼').replace('{', '｛').replace('}', '｝')
                    .replace("\r", "").replace("\n", "\\N")
                if (index != frame.activeIndex) safe
                else if (preset == CaptionPreset.POP) {
                    val transition = minOf(POP_DURATION_MS, frame.endMs - frame.startMs)
                    "{\\c&H6AFF57&\\fscx116\\fscy116\\t(0,$transition,\\fscx100\\fscy100)}$safe{\\r}"
                } else {
                    "{\\c&H1FD2FF&}$safe{\\r}"
                }
            }.joinToString(" ")
            appendLine("Dialogue: 0,${timestamp(frame.startMs)},${timestamp(frame.endMs)},Default,,0,0,0,,$text")
        }
    }

    private fun timestamp(ms: Long): String {
        val cs = ms / 10
        val minutes = ((cs / 6000) % 60).toString().padStart(2, '0')
        val seconds = ((cs / 100) % 60).toString().padStart(2, '0')
        return "${cs / 360000}:$minutes:$seconds.${(cs % 100).toString().padStart(2, '0')}"
    }
}
