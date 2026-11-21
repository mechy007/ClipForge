package com.clipforge.app

import android.content.Context
import java.io.File

/**
 * End-to-end progressive analysis for an authorized source of independently playable segments.
 * Signal snapshots are committed before segment checkpoints acknowledge processing.
 * All completed segments must be present before final highlight ranking.
 */
internal class ProgressiveAnalysisSession(
    private val context: Context,
    private val sourceFingerprint: String,
    private val stateDirectory: File
) {
    init { require(sourceFingerprint.isNotBlank()) }

    private val checkpoint = ProgressiveSegmentCheckpoint(
        File(stateDirectory, "progressive-checkpoint-${sourceFingerprint.sha256()}.json"),
        sourceFingerprint
    ).load()
    private val store = ProgressiveSignalStore(File(stateDirectory, "signals"), sourceFingerprint)

    suspend fun analyze(
        source: ProgressiveMediaSource,
        durationMs: Long,
        language: String? = null,
        onStatus: (String) -> Unit = {}
    ): List<ClipCandidate> {
        require(durationMs > 0L)
        val planned = ProgressiveSegmentPlanner.plan(durationMs)
        val indices = planned.map { it.index }.toSet()
        val alreadyProcessed = checkpoint.processedIndices()
        check(alreadyProcessed.all { it in indices }) {
            "Checkpoint contains segments outside the current source timeline"
        }
        // Verify that an acknowledged segment has its durable signals before
        // resuming. Otherwise missing snapshots could be silently skipped.
        if (alreadyProcessed.isNotEmpty()) {
            ProgressiveSignalIntegration.restore(store, alreadyProcessed)
        }
        // A crash after writing a snapshot but before acknowledging the checkpoint
        // is safe: the segment is replayed and its snapshot is overwritten.
        // Reject mismatched timeline spans before they can be acknowledged as
        // finalized or sent through expensive offline transcription.
        val validatedSource = ProgressiveMediaSource { emit ->
            source.produce { item ->
                require(planned.getOrNull(item.segment.index) == item.segment) {
                    "Source segment differs from planned timeline"
                }
                emit(item)
            }
        }
        ProgressiveOfflineTranscription.run(
            context, validatedSource, checkpoint, language, onStatus
        ) { signals ->
            val expected = planned.getOrNull(signals.segment.index)
            require(expected == signals.segment) { "Source segment differs from planned timeline" }
            ProgressiveSignalIntegration.persist(store, signals)
        }
        val processed = checkpoint.processedIndices()
        check(processed == indices) {
            "Progressive analysis incomplete: ${processed.size}/${indices.size} segments processed"
        }
        return ProgressiveSignalIntegration.detect(store, processed, durationMs, maxClips = 10)
    }

    /**
     * Convenience entry point for a local transcoder or authorized segment downloader.
     * Files are finalized before publication; already acknowledged segments are
     * omitted so resuming does not waste offline Whisper inference.
     */
    suspend fun analyzeFinalizedFiles(
        durationMs: Long,
        filesByIndex: Map<Int, File>,
        language: String? = null,
        onStatus: (String) -> Unit = {}
    ): List<ClipCandidate> {
        val source = FinalizedLocalSegmentSource.fromFiles(
            durationMs, filesByIndex, checkpoint.processedIndices()
        )
        return analyze(source, durationMs, language, onStatus)
    }

    /**
     * Restore the complete timeline for the editor, caption track and clip review.
     * A partially processed video is not a valid source for final export.
     */
    fun completedSignals(durationMs: Long): ProgressiveSignalIntegration.Signals {
        require(durationMs > 0L)
        val expected = ProgressiveSegmentPlanner.plan(durationMs).map { it.index }.toSet()
        val processed = checkpoint.processedIndices()
        check(processed == expected) {
            "Progressive analysis incomplete: ${processed.size}/${expected.size} segments processed"
        }
        val signals = ProgressiveSignalIntegration.restore(store, processed)
        require(signals.transcript.all { it.startMs >= 0L && it.endMs <= durationMs })
        require(signals.audioEnergy.all { it.startMs >= 0L && it.endMs <= durationMs })
        return signals
    }

    fun completedSegments(): Set<Int> = checkpoint.processedIndices()

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
