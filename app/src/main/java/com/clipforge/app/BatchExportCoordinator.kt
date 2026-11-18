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

    fun start(context: Context, folder: Uri, source: Uri, clips: List<ClipCandidate>, aspectRatio: Float = 9f / 16f) {
        val previous = mutableStatus.value
        if (previous.running) return

        val sameBatch = previous.sourceUri == source.toString() &&
            previous.folderUri == folder.toString()
        val alreadyCompleted = if (sameBatch) previous.completedClips else emptyList()
        val remaining = clips.filterNot { it in alreadyCompleted }
        if (remaining.isEmpty()) {
            mutableStatus.value = previous.copy(
                running = false,
                total = clips.size,
                message = if (alreadyCompleted.isEmpty()) "No highlights were selected."
                    else "All selected clips have already been exported and verified."
            )
            return
        }

        mutableStatus.value = BatchExportStatus(
            running = true,
            completed = alreadyCompleted.size,
            total = clips.size,
            message = "Resuming export · ${remaining.size} clip(s) remaining…",
            sourceUri = source.toString(),
            folderUri = folder.toString(),
            completedClips = alreadyCompleted
        )
        val appContext = context.applicationContext
        scope.launch {
            try {
                BatchClipExporter.exportAll(appContext, folder, source, remaining, aspectRatio) { done, _, clip ->
                    val verifiedClips = alreadyCompleted + remaining.take(done)
                    mutableStatus.value = BatchExportStatus(
                        running = true,
                        completed = verifiedClips.size,
                        total = clips.size,
                        message = "Exporting ${verifiedClips.size} / ${clips.size} · ${clip.title}",
                        sourceUri = source.toString(),
                        folderUri = folder.toString(),
                        completedClips = verifiedClips
                    )
                }
                val completedClips = alreadyCompleted + remaining
                mutableStatus.value = BatchExportStatus(
                    running = false,
                    completed = completedClips.size,
                    total = clips.size,
                    message = "Batch export complete · ${completedClips.size} clips saved and verified. Source video kept for safety.",
                    sourceUri = source.toString(),
                    folderUri = folder.toString(),
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
