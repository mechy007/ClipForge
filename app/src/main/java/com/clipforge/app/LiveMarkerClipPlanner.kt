package com.clipforge.app

import kotlin.math.ln
import kotlin.math.roundToInt

/** Turns saved live-chat activity spikes into ranked, recording-relative clip candidates. */
object LiveMarkerClipPlanner {
    fun plan(
        markers: List<LiveChatHighlightMarker>,
        recordingOffsetMs: Long,
        durationMs: Long,
        maxClips: Int = 10,
        beforeMarkerMs: Long = 20_000L,
        afterMarkerMs: Long = 10_000L
    ): List<ClipCandidate> {
        if (durationMs <= 0L || maxClips <= 0) return emptyList()

        val ranked = markers.mapNotNull { marker ->
            if (marker.messageCount <= 0) return@mapNotNull null
            val markerTimeMs = LiveMarkerTimeline.toRecordingTime(marker.startMs, recordingOffsetMs)
                ?: return@mapNotNull null
            if (markerTimeMs >= durationMs) return@mapNotNull null

            val startMs = (markerTimeMs - beforeMarkerMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            val endMs = minOf(durationMs, markerTimeMs + afterMarkerMs.coerceAtLeast(0L))
            if (endMs <= startMs) return@mapNotNull null

            val baseline = marker.baselineMedian.coerceAtLeast(1.0)
            val activityRatio = marker.messageCount / baseline
            val score = (62 + (12.0 * ln(activityRatio.coerceAtLeast(1.0))).roundToInt())
                .coerceIn(62, 98)
            ClipCandidate(
                startMs = startMs,
                endMs = endMs,
                title = "Live chat spike · ${formatTimestamp(markerTimeMs)}",
                hook = "${marker.messageCount} chat messages · ${"%.1f".format(marker.baselineMedian)} usual",
                score = score,
                reason = "Chat activity was ${"%.1f".format(activityRatio)}× its baseline."
            )
        }.sortedWith(compareByDescending<ClipCandidate> { it.score }.thenByDescending { it.endMs - it.startMs })

        val selected = mutableListOf<ClipCandidate>()
        for (candidate in ranked) {
            val overlapsStrongly = selected.any { current ->
                val overlapMs = (minOf(candidate.endMs, current.endMs) - maxOf(candidate.startMs, current.startMs))
                    .coerceAtLeast(0L)
                val shorterDuration = minOf(candidate.endMs - candidate.startMs, current.endMs - current.startMs)
                shorterDuration > 0L && overlapMs * 2L >= shorterDuration
            }
            if (!overlapsStrongly) selected += candidate
            if (selected.size >= maxClips) break
        }
        return selected.sortedBy { it.startMs }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val seconds = (timeMs / 1_000L).coerceAtLeast(0L)
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val remainder = seconds % 60L
        return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, remainder)
        else "%02d:%02d".format(minutes, remainder)
    }
}
