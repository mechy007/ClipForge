package com.clipforge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Imports direct, publicly reachable HTTPS video files only.
 * It deliberately does not scrape video platforms or bypass authentication/DRM.
 */
object DirectVideoImporter {
    private const val MAX_BYTES = 1_073_741_824L
    private val allowedExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")

    suspend fun download(
        context: Context,
        rawUrl: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
    ): Uri = withContext(Dispatchers.IO) {
        val parsed = URL(rawUrl.trim())
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "Use a secure HTTPS link to a direct video file."
        }
        require(parsed.userInfo.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
            "This video link is not valid."
        }

        val connection = (parsed.openConnection() as? HttpURLConnection)
            ?: error("This link cannot be downloaded as a video file.")
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val destination = File(directory, "clipforge_" + System.currentTimeMillis() + ".video")
        val partial = File(directory, destination.name + ".part")

        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "ClipForge-Android")
            connection.connect()

            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "The video host redirected to an insecure link. Download cancelled."
            }
            require(connection.responseCode in 200..299) {
                "Video download failed (HTTP " + connection.responseCode + ")."
            }

            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val extension = parsed.path.substringAfterLast('.', "").lowercase()
            require(mime.startsWith("video/") || extension in allowedExtensions) {
                "This is not a direct video file link. Use an HTTPS link ending in a supported video format."
            }

            val total = connection.contentLengthLong.takeIf { it > 0L }
            require(total == null || total <= MAX_BYTES) { "Video exceeds the 1 GiB import limit." }

            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_BYTES) { "Video exceeds the 1 GiB import limit." }
                        output.write(buffer, 0, count)
                        onProgress(downloaded, total)
                    }
                }
            }
            require(downloaded > 0L) { "The video file was empty." }
            require(partial.renameTo(destination)) { "Could not save the downloaded video." }
            Uri.fromFile(destination)
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
