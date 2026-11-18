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
data class AudioSignalReference(val rms: Float, val peak: Float)

object AudioSignalScorer {
    /**
     * Uses the existing source-relative percentiles. If a mostly silent source
     * makes a percentile zero, fall back to the strongest positive sample.
     * Fully silent or invalid audio has no usable reference.
     */
    fun reference(windows: List<AudioEnergyWindow>): AudioSignalReference? {
        val valid = windows.filter { it.rms.isFinite() && it.peak.isFinite() && it.rms >= 0f && it.peak >= 0f }
        if (valid.isEmpty()) return null
        val rmsValues = valid.map { it.rms }.sorted()
        val peakValues = valid.map { it.peak }.sorted()
        val rmsPercentile = rmsValues.getOrNull(((rmsValues.size - 1) * 0.90).toInt()) ?: 0f
        val peakPercentile = peakValues.getOrNull(((peakValues.size - 1) * 0.95).toInt()) ?: 0f
        val rmsReference = if (rmsPercentile > 0f) rmsPercentile else rmsValues.filter { it > 0f }.maxOrNull() ?: 0f
        val peakReference = if (peakPercentile > 0f) peakPercentile else peakValues.filter { it > 0f }.maxOrNull() ?: 0f
        if (rmsReference <= 0f || peakReference <= 0f) return null
        return AudioSignalReference(rmsReference, peakReference)
    }

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
