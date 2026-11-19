package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class LocalImportProgress(val copiedBytes: Long, val totalBytes: Long?)

/**
 * Streams a user-selected SAF video into private storage without a 2 GB integer limit.
 * Never holds the full source in RAM. A cancelled or failed copy leaves no partial file.
 */
object LocalVideoImporter {
    suspend fun import(
        context: Context,
        source: Uri,
        onProgress: (LocalImportProgress) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {
        require(source.scheme == "content") { "Choose a local video using the Android file picker." }
        val resolver = context.contentResolver
        val mime = resolver.getType(source)?.lowercase()
        require(mime == null || mime.startsWith("video/") || mime == "application/octet-stream") {
            "The selected file is not a supported video."
        }
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val suffix = when (mime) {
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            "video/x-matroska" -> ".mkv"
            "video/webm" -> ".webm"
            "video/3gpp" -> ".3gp"
            else -> mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }?.let { ".$it" } ?: ".video"
        }
        val target = File.createTempFile("local-", suffix, directory)
        val partial = File(directory, target.name + ".part")
        target.delete()
        try {
            val size = resolver.openAssetFileDescriptor(source, "r")?.use { it.length.takeIf { n -> n >= 0 } }
            if (size != null) {
                val free = StatFs(directory.absolutePath).availableBytes
                check(size < free) { "Not enough free storage to import this video." }
            }
            val input = resolver.openInputStream(source) ?: throw IOException("Cannot read the selected video.")
            input.use { stream ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val free = StatFs(directory.absolutePath).availableBytes
                        check(free > count.toLong() + 1024L * 1024L) {
                            "Not enough free storage to finish importing this video."
                        }
                        copied += count.toLong()
                        output.write(buffer, 0, count)
                        onProgress(LocalImportProgress(copied, size))
                    }
                    check(copied > 0) { "Selected video is empty." }
                    if (size != null) check(copied == size) { "Incomplete video import." }
                }
            }
            check(partial.renameTo(target)) { "Could not finalize imported video." }
            Uri.fromFile(target)
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }
}
