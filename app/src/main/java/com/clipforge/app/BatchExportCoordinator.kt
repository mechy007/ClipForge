package com.clipforge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BatchExportStatus(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val sourceUri: String? = null,
    val folderUri: String? = null,
    val aspectRatio: Float = 9f / 16f,
    val completedClips: List<ClipCandidate> = emptyList()
)

/**
 * Process-scoped coordinator keeps a batch alive across Activity/Compose recreation.
 * It intentionally does not claim to survive Android process death.
 */
object BatchExportCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(BatchExportStatus())
    val status = mutableStatus.asStateFlow()
    // Retain verified outputs across changed selections during an interrupted batch.
    // This is process-local, just like the coordinator's status.
    private val verifiedBatchRanges = linkedSetOf<Pair<Long, Long>>()

    fun start(
        context: Context, folder: Uri, source: Uri, clips: List<ClipCandidate>,
        aspectRatio: Float = 9f / 16f,
        transcript: List<TranscriptSegment> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        faceTrackingPath: List<CropFocus> = emptyList()
    ) {
        val previous = mutableStatus.value
        if (previous.running) return

        val retryingInterruptedBatch = previous.message.startsWith("Batch export stopped:")
        val sameBatch = retryingInterruptedBatch &&
            previous.sourceUri == source.toString() &&
            previous.folderUri == folder.toString() && previous.aspectRatio == aspectRatio
        // A retry may use a different selection: only count previously exported clips
        // that are still selected, and never count a duplicate more than once.
        val selectedClips = clips.toList()
        require(selectedClips.distinctBy { it.startMs to it.endMs }.size == selectedClips.size) {
            "Duplicate highlight ranges cannot be exported in the same batch."
        }
        if (!sameBatch) verifiedBatchRanges.clear()
        val alreadyCompleted = if (sameBatch) selectedClips.filter { (it.startMs to it.endMs) in verifiedBatchRanges } else emptyList()
        val remaining = selectedClips.filterNot { (it.startMs to it.endMs) in verifiedBatchRanges }
        if (remaining.isEmpty()) {
            mutableStatus.value = previous.copy(
                running = false,
                completed = alreadyCompleted.size,
                completedClips = alreadyCompleted,
                total = selectedClips.size,
                message = if (alreadyCompleted.isEmpty()) "No highlights were selected."
                    else "All selected clips have already been exported and verified."
            )
            return
        }

        mutableStatus.value = BatchExportStatus(
            running = true,
            completed = alreadyCompleted.size,
            total = selectedClips.size,
            message = if (alreadyCompleted.isNotEmpty()) "Resuming export · ${remaining.size} clip(s) remaining…" else "Exporting ${remaining.size} selected clip(s)…",
            sourceUri = source.toString(),
            folderUri = folder.toString(),
            aspectRatio = aspectRatio,
            completedClips = alreadyCompleted
        )
        val appContext = context.applicationContext
        scope.launch {
            try {
                BatchClipExporter.exportAll(appContext, folder, source, remaining, aspectRatio, transcript, captionPreset, faceTrackingPath) { done, _, clip ->
                    verifiedBatchRanges.add(clip.startMs to clip.endMs)
                    val verifiedClips = selectedClips.filter { (it.startMs to it.endMs) in verifiedBatchRanges }
                    mutableStatus.value = BatchExportStatus(
                        running = true,
                        completed = verifiedClips.size,
                        total = selectedClips.size,
                        message = "Exporting ${verifiedClips.size} / ${selectedClips.size} · ${clip.title}",
                        sourceUri = source.toString(),
                        folderUri = folder.toString(),
                        aspectRatio = aspectRatio,
                        completedClips = verifiedClips
                    )
                }
                val completedClips = alreadyCompleted + remaining
                mutableStatus.value = BatchExportStatus(
                    running = false,
                    completed = completedClips.size,
                    total = selectedClips.size,
                    message = "Batch export complete · ${completedClips.size} clips saved and verified. Source video kept for safety.",
                    sourceUri = source.toString(),
                    folderUri = folder.toString(),
                    aspectRatio = aspectRatio,
                    completedClips = completedClips
                )
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(
                    running = false,
                    message = "Batch export stopped: ${error.localizedMessage ?: "unknown error"}. ${mutableStatus.value.completed} clip(s) are verified; retry to continue with the remaining clips."
                )
            }
        }
    }
}
