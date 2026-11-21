package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Experimental yt-dlp section producer for recordings the user is authorized to download.
 * Each section is remuxed into a standalone MP4 before it is published to the
 * concurrent analysis pipeline. Unlike the normal full-video downloader, this
 * producer requires the known duration and does not provide a complete original.
 *
 * Section extraction may fail on formats/servers that cannot seek; callers must
 * fall back to the existing full-file importer rather than analyze partial MP4s.
 */
internal object YouTubeSectionDownloader {
    suspend fun download(
        context: Context,
        url: String,
        height: Int,
        durationMs: Long,
        directory: File,
        publish: suspend (FinalizedMediaSegment) -> Unit,
        onSegmentReady: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        require(YouTubeLinkClassifier.isYouTubeUrl(url))
        require(height in setOf(720, 1080, 2160))
        require(durationMs > 0L)
        val videoId = YouTubeLiveApiClient.parseVideoId(url)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create segment directory" }
        runInterruptible(Dispatchers.IO) {
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
        }
        val planned = ProgressiveSegmentPlanner.plan(durationMs)
        for (segment in planned) {
            val finalized = File(directory, "segment-${segment.index}.mp4")
            if (!YouTubeDownloadValidation.isValidMp4(finalized)) {
                finalized.delete()
                val staging = File(directory, "part-${segment.index}")
                check(staging.isDirectory || staging.mkdirs()) { "Cannot create segment staging directory" }
                try {
                    val start = segment.startMs / 1000.0
                    val end = segment.endMs / 1000.0
                    val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                        .addOption("--no-playlist")
                        .addOption("--match-filter", "!is_upcoming")
                        .addOption("--socket-timeout", 30)
                        .addOption("--retries", 3)
                        .addOption("--fragment-retries", 3)
                        .addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/best")
                        .addOption("--download-sections", "*$start-$end")
                        .addOption("--force-keyframes-at-cuts")
                        .addOption("--merge-output-format", "mp4")
                        .addOption("-o", File(staging, "section.%(ext)s").absolutePath)
                    runInterruptible(Dispatchers.IO) {
                        YoutubeDL.execute(request, "clipforge-section-${java.util.UUID.randomUUID()}", false)
                    }
                    val output = File(staging, "section.mp4")
                    check(YouTubeDownloadValidation.isValidMp4(output)) {
                        "Section ${segment.index} is not a playable MP4"
                    }
                    check(output.renameTo(finalized)) { "Could not finalize section ${segment.index}" }
                } finally {
                    staging.deleteRecursively()
                }
            }
            // Cached segments must remain independently playable after an interruption.
            // Check duration as well as MP4 structure before publishing to Whisper.
            val retriever = MediaMetadataRetriever()
            val actualDuration = try {
                retriever.setDataSource(finalized.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
            check(actualDuration != null && actualDuration > 0L &&
                kotlin.math.abs(actualDuration - (segment.endMs - segment.startMs)) <= 5_000L) {
                "Section ${segment.index} has an invalid timeline duration"
            }
            publish(FinalizedMediaSegment(segment, finalized))
            onSegmentReady(segment.index + 1, planned.size)
        }
    }
}
