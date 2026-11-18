package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Sequentially exports selected clips and verifies each output before continuing. */
object BatchClipExporter {
    suspend fun exportAll(
        context: Context,
        folder: Uri,
        source: Uri,
        clips: List<ClipCandidate>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Int {
        require(clips.isNotEmpty()) { "No highlights were selected." }
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folder)
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(folder, treeDocumentId)
        var completed = 0

        clips.forEachIndexed { index, clip ->
            val safeTitle = clip.title.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_').take(32).ifBlank { "Highlight" }
            val name = String.format(
                Locale.US, "ClipForge_%02d_%s_%ds.mp4",
                index + 1, safeTitle, clip.startMs / 1000L
            )
            val destination = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(resolver, parentDocument, "video/mp4", name)
            } ?: error("Could not create $name in the selected folder.")

            val result = suspendCancellableCoroutine<Pair<Boolean, String>> { continuation ->
                ClipExporter.export(
                    context, source, destination, clip.startMs, clip.endMs,
                    onStatus = { },
                    onFinished = { success, message ->
                        if (continuation.isActive) continuation.resume(success to message)
                    }
                )
            }

            if (!result.first) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                }
                error("$name: ${result.second}")
            }

            val verified = withContext(Dispatchers.IO) {
                resolver.openFileDescriptor(destination, "r")?.use { descriptor ->
                    when {
                        descriptor.statSize > 0L -> true
                        descriptor.statSize == 0L -> false
                        else -> resolver.openInputStream(destination)?.use { input ->
                            input.read() != -1
                        } ?: false
                    }
                } ?: false
            }
            if (!verified) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                }
                error("$name was exported but could not be verified.")
            }

            completed++
            onProgress(completed, clips.size, clip.title)
        }
        return completed
    }
}
