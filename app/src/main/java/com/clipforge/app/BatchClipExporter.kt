package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
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
        aspectRatio: Float = 9f / 16f,
        transcript: List<TranscriptSegment> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        faceTrackingPath: List<CropFocus> = emptyList(),
        onProgress: (Int, Int, ClipCandidate) -> Unit = { _, _, _ -> }
    ): Int {
        require(clips.isNotEmpty()) { "No highlights were selected." }
        require(clips.all { it.startMs >= 0L && it.endMs > it.startMs }) {
            "All selected highlights must have valid start and end times."
        }
        require(clips.distinctBy { it.startMs to it.endMs }.size == clips.size) {
            "Duplicate highlight ranges would overwrite exported MP4 filenames."
        }
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folder)
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(folder, treeDocumentId)
        var completed = 0
        var batchSourceCache: java.io.File? = null
        try {
        val exportSource = if (faceTrackingPath.isNotEmpty() && source.scheme != "file") {
            val cached = java.io.File(context.cacheDir, "clipforge_batch_source_${System.nanoTime()}.mp4")
            batchSourceCache = cached
            withContext(Dispatchers.IO) {
                resolver.openInputStream(source)?.use { input ->
                    cached.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: error("Could not read the source video for face-aware batch export.")
            }
            check(cached.isFile && cached.length() > 0L) { "The cached batch source video is empty." }
            Uri.fromFile(cached)
        } else source

        clips.forEachIndexed { index, clip ->
            val safeTitle = clip.title.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_').take(32).ifBlank { "Highlight" }
            val ratioTag = when {
                aspectRatio > 1.2f -> "16x9"
                aspectRatio < 0.8f -> "9x16"
                else -> "1x1"
            }
            val name = String.format(
                Locale.US, "ClipForge_%s_%dms-%dms_%s.mp4",
                ratioTag, clip.startMs, clip.endMs, safeTitle
            )
            val destination = withContext(Dispatchers.IO) {
                DocumentsContract.createDocument(resolver, parentDocument, "video/mp4", name)
            } ?: error("Could not create $name in the selected folder.")

            val result = suspendCancellableCoroutine<Pair<Boolean, String>> { continuation ->
                ClipExporter.export(
                    context, exportSource, destination, clip.startMs, clip.endMs,
                    aspectRatio = aspectRatio,
                    faceTrackingPath = faceTrackingPath,
                    captionCues = if (transcript.isEmpty()) emptyList() else CaptionTrack.fromTranscript(transcript, clip.startMs, clip.endMs),
                    captionPreset = captionPreset,
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
            val playable = if (verified) withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, destination)
                        val durationMs = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                        val width = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                        )?.toIntOrNull() ?: 0
                        val height = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                        )?.toIntOrNull() ?: 0
                        durationMs > 0L && width > 0 && height > 0
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(false)
            } else false
            if (!playable) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                }
                error("$name was exported but is empty or not a readable video MP4 with valid duration and dimensions.")
            }

            completed++
            onProgress(completed, clips.size, clip)
        }
        return completed
        } finally {
            batchSourceCache?.delete()
        }
    }
}
