package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMarkerTimelineTest {
    @Test
    fun alignsMarkerWhenRecordingStartsAfterBroadcast() {
        assertEquals(30_000L, LiveMarkerTimeline.toRecordingTime(150_000L, 120_000L))
    }

    @Test
    fun dropsMarkersThatHappenedBeforeRecordingStarted() {
        assertNull(LiveMarkerTimeline.toRecordingTime(90_000L, 120_000L))
    }

    @Test
    fun alignsMarkerWhenRecordingStartsBeforeBroadcast() {
        assertEquals(40_000L, LiveMarkerTimeline.toRecordingTime(10_000L, -30_000L))
    }

    @Test
    fun preservesTimelineWhenOffsetIsZero() {
        assertEquals(42_000L, LiveMarkerTimeline.toRecordingTime(42_000L, 0L))
    }

    @Test
    fun rejectsNegativeBroadcastMarker() {
        assertNull(LiveMarkerTimeline.toRecordingTime(-1L, 0L))
    }
}
