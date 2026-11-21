package com.clipforge.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges progressive transcription and durable per-segment storage to the
 * existing offline highlight ranker. Only fully persisted snapshots are read.
 * The caller must use the same fingerprint for the store and checkpoint.
 */
internal object ProgressiveSignalIntegration {
    fun persist(store: ProgressiveSignalStore, result: ProgressiveTranscriptionSignals) {
        val span = result.segment
        require(span.index >= 0 && span.startMs >= 0 && span.endMs > span.startMs)
        require(result.transcript.all {
            it.startMs >= span.startMs && it.endMs > it.startMs && it.endMs <= span.endMs
        })
        require(result.audioEnergy.all {
            it.startMs >= span.startMs && it.endMs > it.startMs && it.endMs <= span.endMs &&
                it.rms.isFinite() && it.peak.isFinite() && it.rms >= 0f && it.peak >= 0f
        })
        val words = JSONArray()
        result.transcript.forEach {
            words.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs).put("text", it.text))
        }
        val energy = JSONArray()
        result.audioEnergy.forEach {
            energy.put(JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)
                .put("rms", it.rms.toDouble()).put("peak", it.peak.toDouble()))
        }
        store.write(span.index, JSONObject()
            .put("startMs", span.startMs).put("endMs", span.endMs)
            .put("transcript", words).put("audioEnergy", energy))
    }

    data class Signals(val transcript: List<TranscriptSegment>, val audioEnergy: List<AudioEnergyWindow>)

    /** Fail closed: missing/corrupt snapshots must never silently produce partial highlights. */
    fun restore(store: ProgressiveSignalStore, processedIndices: Set<Int>): Signals {
        val transcript = mutableListOf<TranscriptSegment>()
        val energy = mutableListOf<AudioEnergyWindow>()
        for (index in processedIndices.sorted()) {
            require(index >= 0)
            val data = requireNotNull(store.read(index)) { "Missing signal snapshot for segment $index" }
            val start = data.getLong("startMs")
            val end = data.getLong("endMs")
            require(start >= 0 && end > start)
            val words = data.getJSONArray("transcript")
            for (i in 0 until words.length()) {
                val item = words.getJSONObject(i)
                val a = item.getLong("startMs")
                val b = item.getLong("endMs")
                require(a >= start && b > a && b <= end)
                transcript += TranscriptSegment(a, b, item.getString("text"))
            }
            val windows = data.getJSONArray("audioEnergy")
            for (i in 0 until windows.length()) {
                val item = windows.getJSONObject(i)
                val a = item.getLong("startMs")
                val b = item.getLong("endMs")
                val rms = item.getDouble("rms")
                val peak = item.getDouble("peak")
                require(a >= start && b > a && b <= end &&
                    rms.isFinite() && peak.isFinite() && rms >= 0.0 && peak >= 0.0 &&\n                    rms.toFloat().isFinite() && peak.toFloat().isFinite())
                energy += AudioEnergyWindow(a, b, rms.toFloat(), peak.toFloat())
            }
        }
        return Signals(transcript.sortedBy { it.startMs }, energy.sortedBy { it.startMs })
    }

    fun detect(
        store: ProgressiveSignalStore,
        processedIndices: Set<Int>,
        durationMs: Long,
        maxClips: Int = 10
    ): List<ClipCandidate> {
        require(durationMs > 0L)
        val signals = restore(store, processedIndices)
        return HighlightDetector.detect(
            signals.transcript, durationMs,
            audioEnergy = signals.audioEnergy, maxClips = maxClips
        )
    }
}
