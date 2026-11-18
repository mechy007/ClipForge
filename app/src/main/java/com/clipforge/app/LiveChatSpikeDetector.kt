package com.clipforge.app

/**
 * Converts live-chat message activity into timestamp markers.
 * Message timestamps are relative to the broadcast's actual start time.
 */
data class LiveChatHighlightMarker(
    val startMs: Long,
    val messageCount: Int,
    val baselineMedian: Double,
    val reason: String = "Live chat activity spike"
)

class LiveChatSpikeDetector(
    private val minimumMessages: Int = 5,
    private val bucketDurationMs: Long = 10_000L,
    private val baselineBuckets: Int = 18,
    private val minimumBaselineSamples: Int = 3,
    private val spikeMultiplier: Double = 2.5,
    private val cooldownMs: Long = 30_000L,
    monitorStartOffsetMs: Long = 0L
) {
    private val bucketCounts = sortedMapOf<Long, Int>()
    private val history = ArrayDeque<Int>()
    private var nextBucketStartMs = 0L
    private var lastMarkerMs = Long.MIN_VALUE
    var lateMessageCount: Long = 0L
        private set

    init {
        require(minimumMessages > 0)
        require(bucketDurationMs > 0L)
        require(baselineBuckets >= minimumBaselineSamples && minimumBaselineSamples > 0)
        require(spikeMultiplier > 0.0)
        require(cooldownMs >= 0L)
        nextBucketStartMs =
            (monitorStartOffsetMs.coerceAtLeast(0L) / bucketDurationMs) * bucketDurationMs
    }

    /**
     * Returns false for messages older than the finalized watermark.
     * Such messages are counted explicitly and never silently folded into a later bucket.
     */
    fun addMessage(broadcastOffsetMs: Long): Boolean {
        if (broadcastOffsetMs < nextBucketStartMs) {
            lateMessageCount += 1
            return false
        }
        val bucketStart = (broadcastOffsetMs / bucketDurationMs) * bucketDurationMs
        bucketCounts[bucketStart] = (bucketCounts[bucketStart] ?: 0) + 1
        return true
    }

    /** Completes buckets strictly before [broadcastOffsetMs] and returns new markers. */
    fun completeBucketsThrough(broadcastOffsetMs: Long): List<LiveChatHighlightMarker> {
        val markers = mutableListOf<LiveChatHighlightMarker>()
        val safeNow = broadcastOffsetMs.coerceAtLeast(0L)
        while (nextBucketStartMs + bucketDurationMs <= safeNow) {
            val count = bucketCounts.remove(nextBucketStartMs) ?: 0
            val median = median(history)
            val enoughBaseline = history.size >= minimumBaselineSamples
            val isSpike = enoughBaseline &&
                count >= minimumMessages &&
                count.toDouble() >= median * spikeMultiplier &&
                (lastMarkerMs == Long.MIN_VALUE || nextBucketStartMs - lastMarkerMs >= cooldownMs)

            if (isSpike) {
                markers += LiveChatHighlightMarker(
                    startMs = nextBucketStartMs,
                    messageCount = count,
                    baselineMedian = median
                )
                lastMarkerMs = nextBucketStartMs
            }

            if (history.size == baselineBuckets) history.removeFirst()
            history.addLast(count)
            nextBucketStartMs += bucketDurationMs
        }
        bucketCounts.headMap(nextBucketStartMs).clear()
        return markers
    }

    private fun median(values: Collection<Int>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].toDouble()
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
