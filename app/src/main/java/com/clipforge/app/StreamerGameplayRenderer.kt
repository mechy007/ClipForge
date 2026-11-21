package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

/** Renders a single-source 50/50 vertical composition with synchronized audio. */
internal object StreamerGameplayRenderer {
    fun render(
        context: Context,
        source: Uri,
        destination: File,
        startMs: Long,
        endMs: Long,
        facecam: StreamRegion,
        gameplay: StreamRegion = StreamRegion(0f, 0f, 1f, 1f),
        onFinished: (Boolean, String) -> Unit
    ) {
        require(startMs >= 0L && endMs > startMs)
        val temporaryInput = File(context.cacheDir, "clipforge_split_source_${System.nanoTime()}.mp4")
        Thread {
            val existing = source.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.isFile }
            val input = existing ?: temporaryInput
            try {
                if (existing == null) {
                    context.contentResolver.openInputStream(source)?.use { stream ->
                        input.outputStream().use { stream.copyTo(it) }
                    } ?: error("Source video could not be opened")
                }
                val retriever = MediaMetadataRetriever()
                val dimensions = try {
                    retriever.setDataSource(input.absolutePath)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    w to h
                } finally { retriever.release() }
                check(dimensions.first > 0 && dimensions.second > 0) { "Invalid source dimensions" }
                val cam = StreamerGameplayLayout.facecamCrop(facecam, dimensions.first, dimensions.second)
                val game = StreamerGameplayLayout.gameplayCrop(gameplay, dimensions.first, dimensions.second)
                fun crop(r: StreamRegion): String {
                    val x = (r.left * dimensions.first).toInt().coerceAtLeast(0)
                    val y = (r.top * dimensions.second).toInt().coerceAtLeast(0)
                    val w = (r.width * dimensions.first).toInt().coerceAtLeast(2).coerceAtMost(dimensions.first - x)
                    val h = (r.height * dimensions.second).toInt().coerceAtLeast(2).coerceAtMost(dimensions.second - y)
                    return "crop=$w:$h:$x:$y,scale=1080:960:flags=bicubic,setsar=1"
                }
                val filter = "[0:v]split=2[cam][game];[cam]${crop(cam)}[top];[game]${crop(game)}[bottom];[top][bottom]vstack=inputs=2[v]"
                val args = arrayOf(
                    "-y", "-loglevel", "error", "-ss", String.format(Locale.US, "%.3f", startMs / 1000.0),
                    "-i", input.absolutePath, "-t", String.format(Locale.US, "%.3f", (endMs - startMs) / 1000.0),
                    "-filter_complex", filter, "-map", "[v]", "-map", "0:a?",
                    "-c:v", "libopenh264", "-b:v", "4M", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", destination.absolutePath
                )
                FFmpegKit.executeWithArgumentsAsync(args) { session ->
                    if (existing == null) temporaryInput.delete()
                    onFinished(
                        ReturnCode.isSuccess(session.returnCode) && destination.isFile && destination.length() > 0L,
                        session.output ?: "Split-screen render finished"
                    )
                }
            } catch (error: Exception) {
                if (existing == null) temporaryInput.delete()
                onFinished(false, error.localizedMessage ?: "Split-screen render failed")
            }
        }.start()
    }
}
