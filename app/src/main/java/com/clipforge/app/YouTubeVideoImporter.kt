package com.clipforge.app

import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.UUID

object YouTubeVideoImporter {
    suspend fun download(context: Context, url: String, height: Int, onProgress: (Int) -> Unit): Uri =
        runInterruptible(Dispatchers.IO) {
            require(YouTubeLinkClassifier.isYouTubeUrl(url))
            val videoId = YouTubeLiveApiClient.parseVideoId(url)
            require(height in setOf(720, 1080, 2160))
            val imports = File(context.filesDir, "imports")
            val id = UUID.randomUUID().toString()
            val staging = File(imports, ".$id")
            check(staging.mkdirs()) { "Could not create the download directory." }
            try {
                YoutubeDL.init(context.applicationContext)
                FFmpeg.init(context.applicationContext)
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    .addOption("--no-playlist")
                    .addOption("--match-filter", "!is_live & !is_upcoming")
                    .addOption("--socket-timeout", 30)
                    .addOption("--retries", 3)
                    .addOption("--fragment-retries", 3)
                    .addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/best")
                    .addOption("--merge-output-format", "mp4")
                    .addOption("-o", File(staging, "video.%(ext)s").absolutePath)
                try {
                    YoutubeDL.execute(request, id, false) { progress, _, _ ->
                        if (progress >= 0) onProgress(progress.toInt().coerceIn(0, 99))
                    }
                } catch (error: YoutubeDLException) {
                    val detail = error.message.orEmpty()
                    if ("403" in detail || "Forbidden" in detail) {
                        throw IllegalStateException(
                            "YouTube refused this download (HTTP 403). Try a different permitted recording, or import a local MP4. The bundled downloader may need updating.", error
                        )
                    }
                    throw error
                }
                val rendered = File(staging, "video.mp4")
                check(rendered.length() > 0) { "No completed video was available to download." }
                val destination = File(imports, "youtube-$videoId-$id.mp4")
                check(rendered.renameTo(destination)) { "Could not save the downloaded video." }
                onProgress(100)
                Uri.fromFile(destination)
            } finally {
                staging.deleteRecursively()
            }
        }
}
