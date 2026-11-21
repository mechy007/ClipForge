package com.clipforge.app

import android.content.Context
import android.net.Uri

/** Signals from a completed media segment, expressed on the original video's timeline. */
internal data class ProgressiveTranscriptionSignals(
    val segment: ProgressiveSegment,
    val transcript: List<TranscriptSegment>,
    val audioEnergy: List<AudioEnergyWindow>
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
        onSegmentTranscribed: suspend (FinalizedMediaSegment, ProgressiveTranscriptionSignals) -> Unit
    ) {
        ProgressiveMediaPipeline.run(source, checkpoint) { finalized ->
            val span = finalized.segment
            val result = VideoTranscriber.transcribeWithSignals(
                context,
                Uri.fromFile(finalized.file),
                language,
                onStatus
            )
            val durationMs = span.endMs - span.startMs
            // Encoders can round the last frame or AAC packet past the requested cut.
            // Keep that padding outside the next segment's source timeline.
            fun endInsideSegment(startMs: Long, endMs: Long): Long {
                require(startMs >= 0L && endMs >= startMs && endMs - durationMs <= 350L) {
                    "Transcription signals fall outside the finalized segment"
                }
                return minOf(endMs, durationMs)
            }
            val transcript = result.segments.mapNotNull { item ->
                val end = endInsideSegment(item.startMs, item.endMs)
                if (end <= item.startMs) null else item.moveToSource(span.startMs, end)
            }
            val energy = result.audioEnergy.mapNotNull { item ->
                val end = endInsideSegment(item.startMs, item.endMs)
                if (end <= item.startMs) null else item.copy(
                    startMs = Math.addExact(item.startMs, span.startMs),
                    endMs = Math.addExact(end, span.startMs)
                )
            }
            onSegmentTranscribed(finalized, ProgressiveTranscriptionSignals(span, transcript, energy))
        }
    }
}
