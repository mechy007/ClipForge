package com.clipforge.app

import kotlin.math.abs

internal enum class CreatorCut(val label: String, val leadMs: Long, val tailMs: Long) {
    FAST_HOOK("Fast Hook", 4_000, 8_000),
    FULL_CONTEXT("Full Context", 13_000, 13_000),
    REACTION_FIRST("Reaction First", 1_000, 12_000)
}

internal object CreatorClipCuts {
    fun payoff(
        clip: ClipCandidate, transcript: List<TranscriptSegment>,
        energy: List<AudioEnergyWindow>, visual: List<VisualActivity> = emptyList()
    ): Long {
        val speech = transcript.filter { it.startMs in clip.startMs until clip.endMs }
        val reaction = speech.filter { it.text.contains('!') || it.text.contains('?') }
        val peak = energy.asSequence().filter { it.startMs in clip.startMs until clip.endMs && it.peak >= .28f }
            .maxByOrNull { it.peak }
        val action = visual.asSequence().filter { it.timeMs in clip.startMs until clip.endMs && it.motion >= .12f }
            .maxByOrNull { it.motion }
        return when {
            peak != null && reaction.isNotEmpty() -> reaction.minBy { abs(it.startMs - peak.startMs) }.startMs
            peak != null -> peak.startMs
            action != null -> action.timeMs
            reaction.isNotEmpty() -> reaction.last().startMs
            else -> clip.startMs + (clip.endMs - clip.startMs) / 2
        }
    }

    fun cut(
        clip: ClipCandidate, style: CreatorCut, durationMs: Long, transcript: List<TranscriptSegment>, anchor: Long
    ): ClipCandidate {
        if (durationMs <= 0L) return clip
        val peak = anchor.coerceIn(0L, durationMs - 1)
        val before = (peak - style.leadMs).coerceAtLeast(0)
        val after = (peak + style.tailMs).coerceAtMost(durationMs)
        val start = transcript.asSequence().map { it.startMs }
            .filter { it in (before - 1500).coerceAtLeast(0)..minOf(peak, before + 900) }
            .minByOrNull { abs(it - before) } ?: before
        val end = transcript.asSequence().map { it.endMs }
            .filter { it in maxOf(peak + 1, after - 900)..minOf(durationMs, after + 1800) }
            .minByOrNull { abs(it - after) } ?: after
        return clip.copy(startMs = start, endMs = end)
    }
}

internal data class ClipPreferenceExample(val speech: Boolean, val durationMs: Long, val approved: Boolean)

internal class CreatorClipPreferences(private val examples: List<ClipPreferenceExample> = emptyList()) {
    fun adjustment(clip: ClipCandidate): Int {
        val matches = examples.filter {
            it.speech == clip.hook.isNotBlank() && (it.durationMs <= 20_000L) == (clip.endMs - clip.startMs <= 20_000L)
        }
        if (matches.size < 3) return 0
        return ((matches.count { it.approved } * 2 - matches.size) * 8 / (matches.size + 3)).coerceIn(-8, 8)
    }
}
