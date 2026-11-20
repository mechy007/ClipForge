package com.clipforge.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Applies conservative silence and shot-boundary snapping without cutting through speech. */
object ClipBoundaryRefiner {
    private const val MAX_SNAP_MS = 1_600L
    private const val SPEECH_GUARD_MS = 180L

    fun refine(
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        transcript: List<TranscriptSegment>,
        audio: List<AudioEnergyWindow>,
        sceneCuts: List<Long> = emptyList()
    ): Pair<Long, Long> {
        if (durationMs <= 0L || endMs <= startMs) return startMs to endMs
        val startSpeech = transcript.filter { it.endMs > startMs && it.startMs < endMs }
            .minByOrNull { it.startMs }
        val endSpeech = transcript.filter { it.endMs > startMs && it.startMs < endMs }
            .maxByOrNull { it.endMs }
        val reference = AudioSignalScorer.reference(audio)
        val quietThreshold = reference?.rms?.let { max(0.0035f, it * 0.14f) } ?: 0.0035f

        val quietStart = audio.asSequence()
            .filter { it.startMs < startMs && startMs - it.startMs <= MAX_SNAP_MS }
            .filter { it.rms.isFinite() && it.rms <= quietThreshold }
            .filter { window -> transcript.none { it.startMs < window.endMs + SPEECH_GUARD_MS && it.endMs > window.startMs - SPEECH_GUARD_MS } }
            .minByOrNull { abs(startMs - it.endMs) }
            ?.endMs
        val quietEnd = audio.asSequence()
            .filter { it.endMs > endMs && it.endMs - endMs <= MAX_SNAP_MS }
            .filter { it.rms.isFinite() && it.rms <= quietThreshold }
            .filter { window -> transcript.none { it.startMs < window.endMs + SPEECH_GUARD_MS && it.endMs > window.startMs - SPEECH_GUARD_MS } }
            .minByOrNull { abs(it.startMs - endMs) }
            ?.startMs

        val safeSceneStart = sceneCuts.filter { cut ->
            cut in 0 until durationMs && abs(cut - startMs) <= MAX_SNAP_MS &&
                (startSpeech == null || cut <= startSpeech.startMs - SPEECH_GUARD_MS) &&
                transcript.none { it.startMs < cut + SPEECH_GUARD_MS && it.endMs > cut - SPEECH_GUARD_MS }
        }.minByOrNull { abs(it - startMs) }
        val safeSceneEnd = sceneCuts.filter { cut ->
            cut in 1..durationMs && abs(cut - endMs) <= MAX_SNAP_MS &&
                (endSpeech == null || cut >= endSpeech.endMs + SPEECH_GUARD_MS) &&
                transcript.none { it.startMs < cut + SPEECH_GUARD_MS && it.endMs > cut - SPEECH_GUARD_MS }
        }.minByOrNull { abs(it - endMs) }

        val refinedStart = (safeSceneStart ?: quietStart ?: startMs).coerceIn(0L, durationMs - 1L)
        val refinedEnd = (safeSceneEnd ?: quietEnd ?: endMs).coerceIn(refinedStart + 1L, durationMs)
        // Never accept a snap that removes a detected speech segment.
        val safeStart = if (transcript.any { it.startMs < refinedStart && it.endMs > refinedStart }) startMs else refinedStart
        val safeEnd = if (transcript.any { it.startMs < refinedEnd && it.endMs > refinedEnd }) endMs else refinedEnd
        return safeStart to max(safeStart + 1L, min(durationMs, safeEnd))
    }
}
