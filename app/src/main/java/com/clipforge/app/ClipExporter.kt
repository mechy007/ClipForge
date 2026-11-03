package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors

object ClipExporter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun export(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (startMs < 0L || endMs <= startMs) {
            onFinished(false, "Choose a valid trim range.")
            return
        }

        val output = File(context.cacheDir, "clipforge_${System.currentTimeMillis()}.mp4")
        val clippedItem = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val editedItem = EditedMediaItem.Builder(clippedItem).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.post { onStatus("Saving MP4 to your selected location…") }
                    ioExecutor.execute {
                        try {
                            val stream = context.contentResolver.openOutputStream(destination, "w")
                                ?: error("Could not open the selected save location.")
                            stream.use { target -> output.inputStream().use { it.copyTo(target) } }
                            output.delete()
                            mainHandler.post { onFinished(true, "Clip exported successfully.") }
                        } catch (error: Exception) {
                            output.delete()
                            mainHandler.post { onFinished(false, "Save failed: ${error.localizedMessage ?: "unknown error"}") }
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    mainHandler.post { onFinished(false, "Export failed: ${exportException.localizedMessage ?: "media error"}") }
                }
            })
            .build()

        mainHandler.post { onStatus("Rendering MP4…") }
        try {
            transformer.start(editedItem, output.absolutePath)
        } catch (error: Exception) {
            output.delete()
            mainHandler.post { onFinished(false, "Could not start export: ${error.localizedMessage ?: "unknown error"}") }
        }
    }
}
