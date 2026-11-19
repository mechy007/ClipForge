package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.os.StatFs
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
        val target = File.createTempFile("local-", ".mp4", directory)
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
