package com.clipforge.app

/**
 * A bounded, format-independent planning layer for progressive video analysis.
 *
 * The downloader must only mark a segment available after its media bytes are
 * finalized and independently decodable. This planner does not download media,
 * assume that partial MP4 files are playable, or bypass source permissions.
 */
internal data class ProgressiveSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long
)

internal object ProgressiveSegmentPlanner {
    const val DEFAULT_SEGMENT_MS = 60_000L
    const val MAX_READY_SEGMENTS = 2

    fun plan(durationMs: Long, segmentMs: Long = DEFAULT_SEGMENT_MS): List<ProgressiveSegment> {
        require(durationMs >= 0L) { "Duration must be nonnegative" }
        require(segmentMs in 1_000L..300_000L) { "Segment size must be between 1 and 300 seconds" }
        if (durationMs == 0L) return emptyList()
        val count = (durationMs - 1L) / segmentMs + 1L
        require(count <= Int.MAX_VALUE) { "Too many segments" }
        return (0 until count.toInt()).map { index ->
            val start = index.toLong() * segmentMs
            ProgressiveSegment(index, start, start + minOf(segmentMs, durationMs - start))
        }
    }

    /** Returns only contiguous completed segments, bounded for device memory. */
    fun ready(
        segments: List<ProgressiveSegment>,
        finalizedIndices: Set<Int>,
        processedIndices: Set<Int>,
        maxReady: Int = MAX_READY_SEGMENTS
    ): List<ProgressiveSegment> {
        require(maxReady in 1..MAX_READY_SEGMENTS)
        val pending = segments.firstOrNull { it.index !in processedIndices } ?: return emptyList()
        return segments.asSequence()
            .dropWhile { it.index != pending.index }
            .takeWhile { it.index in finalizedIndices && it.index !in processedIndices }
            .take(maxReady)
            .toList()
    }
}
