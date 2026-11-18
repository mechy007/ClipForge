package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.Locale
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object ClipExporter {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private fun copyToDestination(
        context: Context,
        source: File,
        destination: Uri,
        onFinished: (Boolean, String) -> Unit,
        cleanup: File? = null
    ) {
        ioExecutor.execute {
            try {
                val stream = context.contentResolver.openOutputStream(destination, "w")
                    ?: error("Could not open the selected save location.")
                stream.use { target -> source.inputStream().use { it.copyTo(target) } }
                source.delete()
                cleanup?.delete()
                mainHandler.post { onFinished(true, "Clip exported successfully.") }
            } catch (error: Exception) {
                source.delete()
                cleanup?.delete()
                mainHandler.post { onFinished(false, "Save failed: ${error.localizedMessage ?: "unknown error"}") }
            }
        }
    }

    private fun exportFaceAware(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
        path: List<CropFocus>,
        captionCues: List<CaptionCue>,
        captionPreset: CaptionPreset,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        val input = File(context.cacheDir, "clipforge_source_${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "clipforge_face_${System.nanoTime()}.mp4")
        ioExecutor.execute {
            try {
                val sourceFile = if (source.scheme == "file") source.path?.let(::File) else null
                if (sourceFile != null && sourceFile.isFile) {
                    sourceFile.copyTo(input, overwrite = true)
                } else {
                    context.contentResolver.openInputStream(source)?.use { stream ->
                        input.outputStream().buffered().use { target -> stream.copyTo(target) }
                    } ?: error("Could not read the selected source video.")
                }
                check(input.isFile && input.length() > 0L) { "The source video is empty." }
                val filter = FaceCropFilterBuilder.build(path, startMs, endMs)
                val command = "-y -ss ${seconds(startMs)} -i ${quote(input.absolutePath)} " +
                    "-t ${seconds(endMs - startMs)} -vf ${quote(filter)} " +
                    "-map 0:v:0 -map 0:a? -c:v libx264 -preset ultrafast -crf 23 " +
                    "-c:a aac -b:a 128k -movflags +faststart ${quote(output.absolutePath)}"
                mainHandler.post { onStatus("Rendering face-aware vertical framing…") }
                FFmpegKit.executeAsync(command) { session ->
                    input.delete()
                    if (!ReturnCode.isSuccess(session.returnCode) || !output.isFile || output.length() <= 0L) {
                        output.delete()
                        mainHandler.post {
                            onFinished(false, "Face-aware export failed: ${session.failStackTrace ?: session.returnCode}")
                        }
                    } else {
                        finishRendered(context, output, destination, captionCues, captionPreset, onStatus, onFinished)
                    }
                }
            } catch (error: Exception) {
                input.delete()
                output.delete()
                mainHandler.post {
                    onFinished(false, "Could not prepare face-aware export: ${error.localizedMessage ?: "unknown error"}")
                }
            }
        }
    }

    private fun finishRendered(
        context: Context,
        output: File,
        destination: Uri,
        captionCues: List<CaptionCue>,
        captionPreset: CaptionPreset,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (captionCues.isEmpty()) {
            copyToDestination(context, output, destination, onFinished)
        } else {
            mainHandler.post { onStatus("Burning captions into the video…") }
            val captioned = File(context.cacheDir, "clipforge_captioned_${System.currentTimeMillis()}.mp4")
            CaptionBurnIn.render(context, output, captioned, captionCues, captionPreset) { success, message ->
                if (!success) {
                    output.delete()
                    captioned.delete()
                    mainHandler.post { onFinished(false, message) }
                } else {
                    copyToDestination(context, captioned, destination, onFinished, output)
                }
            }
        }
    }

    private fun seconds(milliseconds: Long): String = String.format(Locale.US, "%.3f", milliseconds / 1000.0)
    private fun quote(value: String): String = "'" + value.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'") + "'"

    fun export(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
        aspectRatio: Float = 9f / 16f,
        faceTrackingPath: List<CropFocus> = emptyList(),
        captionCues: List<CaptionCue> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (startMs < 0L || endMs <= startMs) {
            onFinished(false, "Choose a valid trim range.")
            return
        }

        if (faceTrackingPath.isNotEmpty()) {
            exportFaceAware(
                context, source, destination, startMs, endMs, faceTrackingPath,
                captionCues, captionPreset, onStatus, onFinished
            )
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
        val verticalCrop = Presentation.createForAspectRatio(
            aspectRatio.coerceIn(0.5f, 2.0f),
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val editedItem = EditedMediaItem.Builder(clippedItem)
            .setEffects(Effects(emptyList(), listOf(verticalCrop)))
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (captionCues.isEmpty()) {
                        copyToDestination(context, output, destination, onFinished)
                    } else {
                        mainHandler.post { onStatus("Burning captions into the video…") }
                        val captioned = File(context.cacheDir, "clipforge_captioned_${System.currentTimeMillis()}.mp4")
                        CaptionBurnIn.render(context, output, captioned, captionCues, captionPreset) { success, message ->
                            if (!success) {
                                output.delete()
                                captioned.delete()
                                mainHandler.post { onFinished(false, message) }
                            } else {
                                copyToDestination(context, captioned, destination, onFinished, output)
                            }
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
