package com.clipforge.app

import java.io.File

/**
 * Publishes already finalized, independently decodable local segment files.
 * Can be used by an authorized downloader, local transcoder, or recovery flow.
 * It never treats an unfinished MP4 download as a playable media segment.
 */
internal class FinalizedLocalSegmentSource(
    private val segments: List<FinalizedMediaSegment>,
    private val skipProcessed: Set<Int> = emptySet()
) : ProgressiveMediaSource {
    init {
        require(skipProcessed.all { it >= 0 })
        require(segments.map { it.segment.index }.distinct().size == segments.size) {
            "Duplicate segment indices"
        }
        segments.forEach { item ->
            require(item.segment.index >= 0 && item.segment.startMs >= 0L &&
                item.segment.endMs > item.segment.startMs)
        }
    }

    override suspend fun produce(emit: suspend (FinalizedMediaSegment) -> Unit) {
        for (item in segments.sortedBy { it.segment.index }) {
            if (item.segment.index in skipProcessed) continue
            check(item.file.isFile && item.file.length() > 0L) {
                "Missing or empty finalized media segment: ${item.segment.index}"
            }
            emit(item)
        }
    }

    companion object {
        fun fromFiles(
            durationMs: Long,
            filesByIndex: Map<Int, File>,
            processedIndices: Set<Int> = emptySet()
        ): FinalizedLocalSegmentSource {
            val plan = ProgressiveSegmentPlanner.plan(durationMs)
            require(filesByIndex.keys.all { it in plan.indices }) { "Unexpected segment index" }
            val finalized = plan.mapNotNull { segment ->
                filesByIndex[segment.index]?.let { FinalizedMediaSegment(segment, it) }
            }
            return FinalizedLocalSegmentSource(finalized, processedIndices)
        }
    }
}
