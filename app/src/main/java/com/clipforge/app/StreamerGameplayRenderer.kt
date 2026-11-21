package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.suspendCoroutine

/** Creates the same composition for review playback and export. */
internal object StreamerGameplayRenderer {
    suspend fun render(
        context: Context,
        source: Uri,
        destination: File,
        startMs: Long,
        endMs: Long,
        facecam: StreamRegion,
        captions: List<CaptionCue> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        words: List<TimedWord> = emptyList(),
        gameplayRegion: StreamRegion? = null
    ) = withContext(Dispatchers.IO) {
        require(startMs >= 0L && endMs > startMs)
        val existing = source.takeIf { it.scheme == "file" }?.path?.let(::File)
        val staged = if (existing == null) File.createTempFile("split-source-", ".media", context.cacheDir) else null
        val input = existing ?: checkNotNull(staged)
        var uncaptioned: File? = null
        try {
            if (staged != null) {
                val stream = context.contentResolver.openInputStream(source)
                    ?: error("Source video could not be opened.")
                stream.use {
                    staged.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val media = metadata(input)
            ensureActive()
            if (captions.isNotEmpty()) {
                uncaptioned = File.createTempFile("split-uncaptioned-", ".mp4", context.cacheDir)
            }
            val raw = uncaptioned ?: destination
            val session = FFmpegKit.executeWithArguments(
                arguments(input, raw, startMs, endMs, facecam, media.width, media.height, gameplayRegion)
            )
            check(ReturnCode.isSuccess(session.returnCode)) {
                session.output?.trim()?.takeIf { it.isNotEmpty() } ?: "Split-screen encoding failed."
            }
            ensureActive()
            if (uncaptioned != null) {
                // Wait for the callback before deleting files the encoder may still be using.
                suspendCoroutine<Unit> { continuation ->
                    CaptionBurnIn.render(
                        context, raw, destination, captions, captionPreset, words, endMs - startMs
                    ) { ok, message ->
                        continuation.resumeWith(if (ok) Result.success(Unit) else Result.failure(IllegalStateException(message)))
                    }
                }
            }
            ensureActive()
            verify(destination, endMs - startMs, media.hasAudio)
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            uncaptioned?.delete()
            staged?.delete()
        }
    }

    internal fun verify(file: File, durationMs: Long, expectAudio: Boolean) {
        check(file.isFile && file.length() > 0L) { "The split-screen MP4 is empty." }
        val rendered = metadata(file, decodeFrame = true)
        check(rendered.width == StreamerGameplayLayout.OUTPUT_WIDTH &&
            rendered.height == StreamerGameplayLayout.OUTPUT_HEIGHT) { "Expected a 1080 × 1920 split-screen MP4." }
        val toleranceMs = minOf(350L, maxOf(100L, durationMs / 5))
        check(rendered.durationMs in (durationMs - toleranceMs)..(durationMs + toleranceMs) &&
            rendered.durationMs > 0L) { "The rendered clip duration differs from the selected trim." }
        check(!expectAudio || rendered.hasAudio) { "The rendered clip is missing its original audio." }
    }

    private data class VideoMetadata(val width: Int, val height: Int, val durationMs: Long, val hasAudio: Boolean)

    private fun metadata(file: File, decodeFrame: Boolean = false): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            check(w > 0 && h > 0) { "Video track has invalid dimensions." }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            val rotated = rotation == 90 || rotation == 270
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (decodeFrame) {
                val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 54, 96)
                } else {
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                checkNotNull(frame) { "The first frame of the split-screen MP4 could not be decoded." }.recycle()
            }
            return if (rotated) VideoMetadata(h, w, duration, audio) else VideoMetadata(w, h, duration, audio)
        } finally {
            retriever.release()
        }
    }

    internal fun arguments(
        input: File, output: File, startMs: Long, endMs: Long, facecam: StreamRegion, width: Int, height: Int, gameplayRegion: StreamRegion? = null
    ): Array<String> {
        val cam = StreamerGameplayLayout.facecamCrop(facecam, width, height)
        val game = StreamerGameplayLayout.gameplayCrop(gameplayRegion ?: StreamRegion(0f, 0f, 1f, 1f), width, height)
        fun crop(region: StreamRegion): String {
            val x = (region.left * width).toInt().coerceIn(0, width - 2)
            val y = (region.top * height).toInt().coerceIn(0, height - 2)
            val w = (region.width * width).toInt().coerceIn(2, width - x)
            val h = (region.height * height).toInt().coerceIn(2, height - y)
            return "crop=$w:$h:$x:$y:exact=1,scale=${StreamerGameplayLayout.OUTPUT_WIDTH}:${StreamerGameplayLayout.PANEL_HEIGHT}:flags=bicubic,setsar=1"
        }
        val filter = "[0:v:0]split=2[cam][game];[cam]${crop(cam)}[top];[game]${crop(game)}[bottom];[top][bottom]vstack=inputs=2[v]"
        fun seconds(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
        return arrayOf(
            "-y", "-loglevel", "error", "-ss", seconds(startMs), "-i", input.absolutePath,
            "-t", seconds(endMs - startMs), "-filter_complex_threads", "1", "-filter_complex", filter,
            "-map", "[v]", "-map", "0:a:0?",
            "-c:v", "libopenh264", "-b:v", "4M", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", output.absolutePath
        )
    }
}
