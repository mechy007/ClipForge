package com.clipforge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
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
    val completedClips: List<ClipCandidate> = emptyList(),
    val retryAvailable: Boolean = false
)

/**
 * Process-scoped coordinator keeps a batch alive across Activity/Compose recreation.
 * A restarted batch uses saved document URIs when the source exposes a stable revision.
 */
object BatchExportCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(BatchExportStatus())
    val status = mutableStatus.asStateFlow()

    fun start(
        context: Context, folder: Uri, source: Uri, clips: List<ClipCandidate>,
        aspectRatio: Float = 9f / 16f,
        transcript: List<TranscriptSegment> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        faceTrackingPath: List<CropFocus> = emptyList(),
        splitFacecamRegion: StreamRegion? = null,
        gameplayRegion: StreamRegion? = null,
        gameplayTracking: List<CropFocus> = emptyList()
    ) {
        val previous = mutableStatus.value
        if (previous.running) return

        val selectedClips = clips.toList()
        if (selectedClips.isEmpty()) return
        if (selectedClips.size > BatchClipExporter.MAX_CLIPS_PER_BATCH) {
            mutableStatus.value = previous.copy(message = "Select up to ${BatchClipExporter.MAX_CLIPS_PER_BATCH} clips per batch.")
            return
        }
        if (selectedClips.any { it.startMs < 0L || it.endMs <= it.startMs }) {
            mutableStatus.value = previous.copy(message = "Selected clips must have valid start and end times.")
            return
        }
        if (selectedClips.distinctBy { it.startMs to it.endMs }.size != selectedClips.size) {
            mutableStatus.value = previous.copy(message = "Select only one clip for each start and end time.")
            return
        }

        mutableStatus.value = BatchExportStatus(
            running = true,
            total = selectedClips.size,
            message = "Checking and exporting ${selectedClips.size} selected clip(s)…",
            sourceUri = source.toString(),
            folderUri = folder.toString(),
            aspectRatio = aspectRatio
        )
        val appContext = context.applicationContext
        scope.launch {
            try {
                val verifiedClips = mutableListOf<ClipCandidate>()
                BatchClipExporter.exportAll(appContext, folder, source, selectedClips, aspectRatio, transcript, captionPreset, faceTrackingPath, splitFacecamRegion, gameplayRegion, gameplayTracking) { done, _, clip ->
                    verifiedClips += clip
                    mutableStatus.value = BatchExportStatus(
                        running = true,
                        completed = done,
                        total = selectedClips.size,
                        message = "Exporting ${verifiedClips.size} / ${selectedClips.size} · ${clip.title}",
                        sourceUri = source.toString(),
                        folderUri = folder.toString(),
                        aspectRatio = aspectRatio,
                        completedClips = verifiedClips.toList()
                    )
                }
                mutableStatus.value = BatchExportStatus(
                    running = false,
                    completed = verifiedClips.size,
                    total = selectedClips.size,
                    message = "Batch export complete · ${verifiedClips.size} clips saved and verified. Source video kept for safety.",
                    sourceUri = source.toString(),
                    folderUri = folder.toString(),
                    aspectRatio = aspectRatio,
                    completedClips = verifiedClips.toList()
                )
            } catch (cancelled: CancellationException) {
                mutableStatus.value = mutableStatus.value.copy(
                    running = false,
                    retryAvailable = true,
                    message = "Batch export interrupted. Previously verified clips can be reused on retry."
                )
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = mutableStatus.value.copy(
                    running = false,
                    retryAvailable = true,
                    message = "${mutableStatus.value.completed}/${selectedClips.size} clips saved and verified. ${error.localizedMessage ?: "Export failed."}"
                )
            }
        }
    }
}
