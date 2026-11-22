package com.clipforge.app

import android.content.Context
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
        onSegmentReady: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
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
        var cumulativeDuration = 0L
        for (segment in planned) {
            val expectedDuration = segment.endMs - segment.startMs
            fun validDuration(file: File): Long? {
                if (!YouTubeDownloadValidation.isValidMp4(file)) return null
                val actual = runCatching { ProgressiveRecordingAssembler.mediaDuration(file) }.getOrNull() ?: return null
                return actual.takeIf {
                    val tolerance = if (segment == planned.last()) 1_000L else 350L
                    kotlin.math.abs(it - expectedDuration) <= tolerance &&
                        kotlin.math.abs(cumulativeDuration + it - segment.endMs) <= tolerance
                }
            }
            val finalized = File(directory, "segment-${segment.index}.mp4")
            val cachedDuration = validDuration(finalized)
            if (cachedDuration == null) {
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
                    val actual = checkNotNull(validDuration(output)) {
                        "Section ${segment.index} does not match the recording timeline."
                    }
                    check(output.renameTo(finalized)) { "Could not finalize section ${segment.index}" }
                    cumulativeDuration += actual
                } finally {
                    staging.deleteRecursively()
                }
            }
            if (cachedDuration != null) cumulativeDuration += cachedDuration
            publish(FinalizedMediaSegment(segment, finalized))
            onSegmentReady(segment.index + 1, planned.size)
        }
    }
}
