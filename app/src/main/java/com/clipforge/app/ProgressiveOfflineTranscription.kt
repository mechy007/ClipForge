package com.clipforge.app

import android.content.Context
import android.net.Uri

/** Signals from a completed media segment, expressed on the original video's timeline. */
internal data class ProgressiveTranscriptionSignals(
    val segment: ProgressiveSegment,
    val transcript: List<TranscriptSegment>,
    val audioEnergy: List<AudioEnergyWindow>,
    val audioEvents: List<AudioEventWindow> = emptyList()
)

/**
 * Connects finalized local media files to the existing offline Whisper transcriber.
 *
 * Source implementations must provide independently decodable files with their
 * timestamps starting at zero. No network download or MP4 slicing is performed here.
 * The callback must durably consume its results before this pipeline acknowledges
 * the segment; otherwise a restart could skip a segment whose signals were lost.
 */
internal object ProgressiveOfflineTranscription {
    suspend fun run(
        context: Context,
        source: ProgressiveMediaSource,
        checkpoint: ProgressiveSegmentCheckpoint,
        language: String? = null,
        onStatus: (String) -> Unit = {},
        onSegmentTranscribed: suspend (ProgressiveTranscriptionSignals) -> Unit
    ) {
        ProgressiveMediaPipeline.run(source, checkpoint) { finalized ->
            val span = finalized.segment
            val result = VideoTranscriber.transcribeWithSignals(
                context,
                Uri.fromFile(finalized.file),
                language,
                onStatus
            )
            val transcript = result.segments.map { item ->
                require(item.startMs >= 0 && item.endMs > item.startMs && item.endMs <= span.endMs - span.startMs) {
                    "Transcript timestamps outside finalized segment"
                }
                item.copy(
                    startMs = Math.addExact(item.startMs, span.startMs),
                    endMs = Math.addExact(item.endMs, span.startMs)
                )
            }
            val energy = result.audioEnergy.mapNotNull { item ->
                // PCM extraction may emit a sub-millisecond tail rounded to zero duration.
                if (item.startMs == item.endMs && item.startMs >= 0 && item.startMs <= span.endMs - span.startMs) return@mapNotNull null
                require(item.startMs >= 0 && item.endMs > item.startMs && item.endMs <= span.endMs - span.startMs) {
                    "Audio energy timestamps outside finalized segment"
                }
                item.copy(
                    startMs = Math.addExact(item.startMs, span.startMs),
                    endMs = Math.addExact(item.endMs, span.startMs)
                )
            }
            val events = result.audioEvents.map { item ->
                require(item.startMs >= 0 && item.endMs > item.startMs && item.endMs <= span.endMs - span.startMs &&
                    item.laughter.isFinite() && item.shouting.isFinite() && item.cheering.isFinite() &&
                    item.laughter in 0f..1f && item.shouting in 0f..1f && item.cheering in 0f..1f) {
                    "Audio-event timestamps or values are invalid"
                }
                item.copy(
                    startMs = Math.addExact(item.startMs, span.startMs),
                    endMs = Math.addExact(item.endMs, span.startMs)
                )
            }
            onSegmentTranscribed(ProgressiveTranscriptionSignals(span, transcript, energy, events))
        }
    }
}
