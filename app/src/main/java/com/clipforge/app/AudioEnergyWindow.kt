package com.clipforge.app

import kotlin.math.sqrt

/** One-second audio-energy measurement produced while decoding the source audio. */
data class AudioEnergyWindow(
    val startMs: Long,
    val endMs: Long,
    val rms: Float,
    val peak: Float
)

/**
 * Relative audio activity scorer. It is deliberately a modest ranking signal:
 * loudness alone is not treated as excitement, and all values are normalized
 * against the current source so quiet recordings are not automatically penalized.
 */
object AudioSignalScorer {
    fun score(
        startMs: Long,
        endMs: Long,
        windows: List<AudioEnergyWindow>,
        referenceRms: Float,
        referencePeak: Float
    ): Int {
        if (windows.isEmpty() || endMs <= startMs || referenceRms <= 0f || referencePeak <= 0f) return 0
        val relevant = windows.filter { it.startMs < endMs && it.endMs > startMs }
        if (relevant.isEmpty()) return 0

        val meanRms = relevant.map { it.rms.coerceAtLeast(0f) }.average().toFloat()
        val maxPeak = relevant.maxOf { it.peak.coerceAtLeast(0f) }
        val mean = meanRms.coerceAtLeast(0.0001f)
        val variance = relevant.map { (it.rms - meanRms) * (it.rms - meanRms) }.average().toFloat()
        val variability = (sqrt(variance) / mean).coerceIn(0f, 1f)

        val loudness = (meanRms / referenceRms).coerceIn(0f, 1f)
        val peak = (maxPeak / referencePeak).coerceIn(0f, 1f)
        return ((loudness * 0.55f + peak * 0.30f + variability * 0.15f) * 100f)
            .toInt().coerceIn(0, 100)
    }
}
