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
        // A crash after writing a snapshot but before acknowledging the checkpoint
        // is safe: the segment is replayed and its snapshot is overwritten.
        ProgressiveOfflineTranscription.run(
            context, source, checkpoint, language, onStatus
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

    fun completedSegments(): Set<Int> = checkpoint.processedIndices()

    private fun String.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
