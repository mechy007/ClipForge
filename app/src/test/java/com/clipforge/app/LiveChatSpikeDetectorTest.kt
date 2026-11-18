package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatSpikeDetectorTest {
    @Test
    fun detectsSpikeAfterThreeBaselineBuckets() {
        val detector = LiveChatSpikeDetector(minimumMessages = 5)
        repeat(3) { bucket ->
            repeat(2) { detector.addMessage(bucket * 10_000L + it) }
            assertTrue(detector.completeBucketsThrough((bucket + 1) * 10_000L).isEmpty())
        }
        repeat(7) { detector.addMessage(30_000L + it) }
        val markers = detector.completeBucketsThrough(40_000L)
        assertEquals(1, markers.size)
        assertEquals(30_000L, markers.single().startMs)
        assertEquals(7, markers.single().messageCount)
    }

    @Test
    fun cooldownSuppressesRepeatedNearbySpikes() {
        val detector = LiveChatSpikeDetector(minimumMessages = 5)
        repeat(3) { bucket ->
            repeat(2) { detector.addMessage(bucket * 10_000L + it) }
            detector.completeBucketsThrough((bucket + 1) * 10_000L)
        }
        repeat(8) { detector.addMessage(30_000L + it) }
        assertEquals(1, detector.completeBucketsThrough(40_000L).size)
        repeat(8) { detector.addMessage(40_000L + it) }
        assertTrue(detector.completeBucketsThrough(50_000L).isEmpty())
    }

    @Test
    fun ignoresMessagesOlderThanMonitoringWindow() {
        val detector = LiveChatSpikeDetector(minimumMessages = 5, monitorStartOffsetMs = 25_000L)
        repeat(10) { detector.addMessage(it * 1_000L) }
        assertTrue(detector.completeBucketsThrough(30_000L).isEmpty())
    }

    @Test
    fun rejectsInvalidBucketDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveChatSpikeDetector(bucketDurationMs = 0L)
        }
    }
}
