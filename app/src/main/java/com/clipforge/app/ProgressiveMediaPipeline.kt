package com.clipforge.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** An independently decodable, fully finalized local media segment. */
internal data class FinalizedMediaSegment(
    val segment: ProgressiveSegment,
    val file: File
)

/**
 * Source implementations must publish only independently playable, fully
 * finalized files. This interface does not assume arbitrary partial MP4 bytes
 * can be decoded or authorize downloading protected media.
 */
internal fun interface ProgressiveMediaSource {
    suspend fun produce(emit: suspend (FinalizedMediaSegment) -> Unit)
}

/**
 * Overlaps production and analysis with bounded backpressure. The consumer
 * marks completion only after successful processing; failed segments remain
 * eligible for retry. The caller owns lifecycle and cancellation.
 */
internal object ProgressiveMediaPipeline {
    suspend fun run(
        source: ProgressiveMediaSource,
        checkpoint: ProgressiveSegmentCheckpoint,
        analyze: suspend (FinalizedMediaSegment) -> Unit
    ) = coroutineScope {
        val ready = Channel<FinalizedMediaSegment>(capacity = ProgressiveSegmentPlanner.MAX_READY_SEGMENTS)
        val producer = launch {
            try {
                source.produce { item ->
                    require(item.segment.index >= 0)
                    require(item.file.isFile && item.file.length() > 0L) {
                        "Segment must be finalized on disk before publication"
                    }
                    checkpoint.markFinalized(item.segment.index)
                    ready.send(item)
                }
            } finally {
                ready.close()
            }
        }
        try {
            for (item in ready) {
                if (item.segment.index !in checkpoint.processedIndices()) {
                    require(item.file.isFile && item.file.length() > 0L) {
                        "Finalized segment disappeared before analysis"
                    }
                    analyze(item)
                    checkpoint.markProcessed(item.segment.index)
                }
            }
            producer.join()
        } finally {
            producer.cancel()
            ready.cancel()
        }
    }
}
