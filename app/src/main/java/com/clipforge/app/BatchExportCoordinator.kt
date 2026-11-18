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
    val message: String = ""
)

/**
 * Process-scoped coordinator keeps a batch alive across Activity/Compose recreation.
 * It intentionally does not claim to survive Android process death.
 */
object BatchExportCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(BatchExportStatus())
    val status = mutableStatus.asStateFlow()

    fun start(context: Context, folder: Uri, source: Uri, clips: List<ClipCandidate>) {
        if (mutableStatus.value.running) return
        mutableStatus.value = BatchExportStatus(
            running = true,
            total = clips.size,
            message = "Preparing batch export…"
        )
        val appContext = context.applicationContext
        scope.launch {
            try {
                val count = BatchClipExporter.exportAll(appContext, folder, source, clips) { done, total, title ->
                    mutableStatus.value = BatchExportStatus(
                        running = true,
                        completed = done,
                        total = total,
                        message = "Exporting $done / $total · $title"
                    )
                }
                mutableStatus.value = BatchExportStatus(
                    running = false,
                    completed = count,
                    total = clips.size,
                    message = "Batch export complete · $count clips saved and verified. Source video kept for safety."
                )
            } catch (error: Exception) {
                mutableStatus.value = BatchExportStatus(
                    running = false,
                    completed = mutableStatus.value.completed,
                    total = clips.size,
                    message = "Batch export stopped: ${error.localizedMessage ?: "unknown error"}. Completed clips remain saved; retry the remaining clips."
                )
            }
        }
    }
}
