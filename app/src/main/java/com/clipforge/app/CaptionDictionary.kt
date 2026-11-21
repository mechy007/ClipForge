package com.clipforge.app

/** Whole-word spelling corrections shared by preview, SRT and MP4 export. */
internal object CaptionDictionary {
    private val token = Regex("[\\p{L}\\p{M}\\p{N}_]+")
    private val term = Regex("[\\p{L}\\p{M}\\p{N}_]+")

    fun apply(entries: String, transcript: List<TranscriptSegment>, romanHindi: Boolean = false): List<TranscriptSegment> {
        val corrections = entries.lineSequence().take(100).mapNotNull { line ->
            val from = line.substringBefore('=').trim()
            val to = line.substringAfter('=', "").trim()
            if (term.matches(from) && term.matches(to)) from.lowercase() to to else null
        }.toMap()
        if (corrections.isEmpty() && !romanHindi) return transcript
        fun correct(text: String) = token.replace(if (romanHindi) RomanHindi.apply(text) else text) { corrections[it.value.lowercase()] ?: it.value }
        return transcript.map { segment ->
            segment.copy(text = correct(segment.text), words = segment.words.map { it.copy(word = correct(it.word)) })
        }
    }
}

internal fun creatorExportName(clip: ClipCandidate): String {
    val title = clip.title.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .trim().replace(Regex("\\s+"), "_").take(55).ifBlank { "Highlight" }
    return "ClipForge_${clip.startMs / 1000}s_$title"
}
