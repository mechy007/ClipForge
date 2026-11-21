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
import kotlinx.coroutines.runBlocking
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
        cleanup: File? = null,
        verify: (File) -> Unit = {}
    ) {
        ioExecutor.execute {
            try {
                check(source.isFile && source.length() > 0L) { "The render did not produce a video." }
                verify(source)
                val stream = context.contentResolver.openOutputStream(destination, "wt")
                    ?: error("Could not open the selected save location.")
                stream.use { target -> source.inputStream().use { it.copyTo(target) } }
                mainHandler.post { onFinished(true, "Clip exported successfully.") }
            } catch (error: Exception) {
                mainHandler.post { onFinished(false, "Export could not be saved: ${error.localizedMessage ?: "unknown error"}") }
            } finally {
                source.delete()
                cleanup?.delete()
            }
        }
    }

    private data class Captions(
        val cues: List<CaptionCue>,
        val preset: CaptionPreset,
        val words: List<TimedWord>,
        val durationMs: Long,
        val aspect: Float
    )

    private fun exportFaceAware(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
        path: List<CropFocus>,
        captions: Captions,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit,
        verify: (File) -> Unit
    ) {
        val temporaryInput = File(context.cacheDir, "clipforge_source_${System.nanoTime()}.mp4")
        val output = File(context.cacheDir, "clipforge_face_${System.nanoTime()}.mp4")
        ioExecutor.execute {
            val existingSource = if (source.scheme == "file") source.path?.let(::File)?.takeIf { it.isFile } else null
            val input = existingSource ?: temporaryInput
            try {
                if (existingSource == null) {
                    context.contentResolver.openInputStream(source)?.use { stream ->
                        temporaryInput.outputStream().buffered().use { target -> stream.copyTo(target) }
                    } ?: error("Could not read the selected source video.")
                }
                check(input.isFile && input.length() > 0L) { "The source video is empty." }
                val filter = FaceCropFilterBuilder.build(path, startMs, endMs)
                val arguments = arrayOf(
                    "-y", "-loglevel", "error",
                    "-ss", seconds(startMs), "-i", input.absolutePath,
                    "-t", seconds(endMs - startMs), "-vf", filter,
                    "-map", "0:v:0", "-map", "0:a?",
                    "-c:v", "libopenh264", "-b:v", "4M", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output.absolutePath
                )
                mainHandler.post { onStatus("Rendering face-aware framing…") }
                FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
                    if (existingSource == null) input.delete()
                    if (!ReturnCode.isSuccess(session.returnCode) || !output.isFile || output.length() <= 0L) {
                        output.delete()
                        val detail = session.output?.trim()?.takeIf { it.isNotEmpty() }
                            ?: session.failStackTrace ?: "No video was produced (FFmpeg ${session.returnCode})."
                        mainHandler.post {
                            onFinished(false, "Face-aware export failed: $detail")
                        }
                    } else {
                        finishRendered(context, output, destination, captions, onStatus, onFinished, verify)
                    }
                }
            } catch (error: Exception) {
                if (existingSource == null) input.delete()
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
        captions: Captions,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit,
        verify: (File) -> Unit
    ) {
        if (captions.cues.isEmpty()) {
            copyToDestination(context, output, destination, onFinished, verify = verify)
        } else {
            mainHandler.post { onStatus("Burning captions into the video…") }
            val captioned = File(context.cacheDir, "clipforge_captioned_${System.nanoTime()}.mp4")
            CaptionBurnIn.render(
                context, output, captioned, captions.cues, captions.preset,
                captions.words, captions.durationMs, captions.aspect
            ) { success, message ->
                if (!success) {
                    output.delete()
                    captioned.delete()
                    mainHandler.post { onFinished(false, message) }
                } else {
                    copyToDestination(context, captioned, destination, onFinished, output, verify)
                }
            }
        }
    }

    private fun seconds(milliseconds: Long): String = String.format(Locale.US, "%.3f", milliseconds / 1000.0)

    fun export(
        context: Context,
        source: Uri,
        destination: Uri,
        startMs: Long,
        endMs: Long,
        aspectRatio: Float = 9f / 16f,
        faceTrackingPath: List<CropFocus> = emptyList(),
        splitFacecamRegion: StreamRegion? = null,
        captionCues: List<CaptionCue> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        captionWords: List<TimedWord> = emptyList(),
        gameplayRegion: StreamRegion? = null,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (startMs < 0L || endMs <= startMs) {
            onFinished(false, "Choose a valid trim range.")
            return
        }

        if (splitFacecamRegion != null) {
            if (aspectRatio !in 0.55f..0.58f) {
                onFinished(false, "Streamer/gameplay split is only available in 9:16.")
                return
            }
            val output = File(context.cacheDir, "clipforge_split_${System.nanoTime()}.mp4")
            mainHandler.post { onStatus("Rendering streamer/gameplay split…") }
            ioExecutor.execute {
                try {
                    runBlocking {
                        StreamerGameplayRenderer.render(
                            context, source, output, startMs, endMs, splitFacecamRegion, captionCues, captionPreset, captionWords, gameplayRegion
                        )
                    }
                    copyToDestination(context, output, destination, onFinished)
                } catch (error: Exception) {
                    output.delete()
                    mainHandler.post { onFinished(false, "Split export failed: ${error.localizedMessage ?: "media error"}") }
                }
            }
            return
        }

        val normalizedAspect = aspectRatio.coerceIn(0.5f, 2.0f)
        val captions = Captions(captionCues, captionPreset, captionWords, endMs - startMs, normalizedAspect)
        val verify: (File) -> Unit = { rendered ->
            EncodedClipVerifier.verify(
                context, Uri.fromFile(rendered), endMs - startMs, normalizedAspect,
                EncodedClipVerifier.sourceHasAudio(context, source), decodeFrame = true
            )
        }
        if (faceTrackingPath.isNotEmpty()) {
            exportFaceAware(
                context, source, destination, startMs, endMs, faceTrackingPath,
                captions, onStatus, onFinished, verify
            )
            return
        }

        val output = File(context.cacheDir, "clipforge_${System.nanoTime()}.mp4")
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
            normalizedAspect,
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val editedItem = EditedMediaItem.Builder(clippedItem)
            .setEffects(Effects(emptyList(), listOf(verticalCrop)))
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finishRendered(context, output, destination, captions, onStatus, onFinished, verify)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    mainHandler.post { onFinished(false, "Export failed: ${exportException.localizedMessage ?: "media error"}") }
                }
            })
            .build()

        mainHandler.post {
            onStatus("Rendering MP4…")
            try {
                // Transformer must run on the looper used when it was created.
                transformer.start(editedItem, output.absolutePath)
            } catch (error: Exception) {
                output.delete()
                onFinished(false, "Could not start export: ${error.localizedMessage ?: "unknown error"}")
            }
        }
    }
}
