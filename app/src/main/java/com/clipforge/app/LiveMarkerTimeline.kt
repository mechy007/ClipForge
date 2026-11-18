package com.clipforge.app

/** Converts broadcast-relative chat markers into the local recording timeline. */
object LiveMarkerTimeline {
    fun toRecordingTime(markerBroadcastMs: Long, recordingOffsetMs: Long): Long? {
        if (markerBroadcastMs < 0L) return null
        val localMs = markerBroadcastMs - recordingOffsetMs
        return localMs.takeIf { it >= 0L }
    }
}
