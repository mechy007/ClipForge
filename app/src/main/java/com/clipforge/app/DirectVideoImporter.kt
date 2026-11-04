package com.clipforge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class VideoDownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

/** Imports direct video file URLs only; platform pages and protected streams are unsupported. */
object DirectVideoImporter {
    private const val MAX_BYTES = 2L * 1024L * 1024L * 1024L
    private val allowedTypes = setOf("video/mp4", "video/webm", "video/quicktime", "video/x-matroska")

    suspend fun download(context: Context, rawUrl: String, onProgress: (VideoDownloadProgress) -> Unit = {}): Uri =
        withContext(Dispatchers.IO) {
            val cleanUrl = rawUrl.trim()
            val parsed = Uri.parse(cleanUrl)
            require(parsed.scheme == "https" || parsed.scheme == "http") { "Enter a valid HTTP or HTTPS video link." }
            require(!parsed.host.isNullOrBlank() && parsed.userInfo == null) { "This video link is invalid or contains embedded credentials." }
            val connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                requestMethod = "GET"; setRequestProperty("User-Agent", "ClipForge-Android")
            }
            val target = File(context.filesDir, "imports/video-${System.currentTimeMillis()}.mp4")
            val partial = File(target.parentFile, target.name + ".part")
            try {
                connection.connect()
                check(connection.responseCode in 200..299) { "Video server returned HTTP ${connection.responseCode}." }
                val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                check(type in allowedTypes) { "This link did not return a supported direct video file. Platform pages are not supported." }
                val length = connection.contentLengthLong.takeIf { it > 0L }
                check(length == null || length <= MAX_BYTES) { "Video exceeds the 2 GB import limit." }
                target.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count
                            check(total <= MAX_BYTES) { "Video exceeds the 2 GB import limit." }
                            output.write(buffer, 0, count); onProgress(VideoDownloadProgress(total, length))
                        }
                        check(total > 0L) { "The downloaded video is empty." }
                        check(length == null || total == length) { "The download was incomplete. Please retry." }
                    }
                }
                check(partial.renameTo(target)) { "Could not finalize the downloaded video." }
                Uri.fromFile(target)
            } catch (error: Throwable) {
                partial.delete(); throw error
            } finally { connection.disconnect() }
        }
}
