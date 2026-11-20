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
                // Refresh the yt-dlp extractor before using it: the library's bundled
                // extractor can become stale when YouTube changes its player API.
                // A network/update failure is not a successful download; the
                // subsequent extraction and MP4 validation still determine success.
                try {
                    YoutubeDL.updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel._STABLE)
                } catch (updateError: Exception) {
                    android.util.Log.w("ClipForgeYouTube", "yt-dlp update unavailable; trying installed extractor", updateError)
                }
                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                    .addOption("--no-playlist")
                    // Allow active livestreams, but reject broadcasts that have not started.\n                    .addOption("--match-filter", "!is_upcoming")
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
                    if (YouTubeDownloadValidation.isForbidden(detail)) {
                        throw IllegalStateException(
                            "YouTube refused this download (HTTP 403). Try a different permitted recording, or import a local MP4. The bundled downloader may need updating.", error
                        )
                    }
                    throw error
                }
                val rendered = File(staging, "video.mp4")
                check(YouTubeDownloadValidation.isValidMp4(rendered)) {
                    "Download did not produce a valid MP4. Try a lower quality or import a local video."
                }
                val destination = File(imports, "youtube-$videoId-$id.mp4")
                check(rendered.renameTo(destination)) { "Could not save the downloaded video." }
                Uri.fromFile(destination).also { onProgress(100) }
            } finally {
                staging.deleteRecursively()
            }
        }
}
