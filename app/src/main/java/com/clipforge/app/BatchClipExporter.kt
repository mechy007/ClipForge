package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Sequentially exports selected clips and verifies each output before continuing. */
object BatchClipExporter {
    const val MAX_CLIPS_PER_BATCH = 10

    suspend fun exportAll(
        context: Context,
        folder: Uri,
        source: Uri,
        clips: List<ClipCandidate>,
        aspectRatio: Float = 9f / 16f,
        transcript: List<TranscriptSegment> = emptyList(),
        captionPreset: CaptionPreset = CaptionPreset.CLEAN,
        faceTrackingPath: List<CropFocus> = emptyList(),
        splitFacecamRegion: StreamRegion? = null,
        gameplayRegion: StreamRegion? = null,
        gameplayTracking: List<CropFocus> = emptyList(),
        onProgress: (Int, Int, ClipCandidate) -> Unit = { _, _, _ -> }
    ): Int {
        require(clips.isNotEmpty()) { "No highlights were selected." }
        require(clips.size <= MAX_CLIPS_PER_BATCH) { "Select up to $MAX_CLIPS_PER_BATCH clips per batch." }
        require(clips.all { it.startMs >= 0L && it.endMs > it.startMs }) {
            "All selected highlights must have valid start and end times."
        }
        require(clips.distinctBy { it.startMs to it.endMs }.size == clips.size) {
            "Duplicate highlight ranges would overwrite exported MP4 filenames."
        }
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folder)
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(folder, treeDocumentId)
        var sourceCache: java.io.File? = null
        var exportSource = source
        val failures = mutableListOf<String>()
        var completed = 0
        try {
            val expectAudio = withContext(Dispatchers.IO) { EncodedClipVerifier.sourceHasAudio(context, source) }
            val journal = withContext(Dispatchers.IO) {
                BatchExportJournal.open(context, folder, source, aspectRatio, transcript, captionPreset, faceTrackingPath, splitFacecamRegion, gameplayRegion, gameplayTracking)
            }

            for (clip in clips) {
                val safeTitle = clip.title.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .trim('_').take(32).ifBlank { "Highlight" }
                val ratioTag = when {
                    splitFacecamRegion != null -> "9x16_split"
                    aspectRatio > 1.2f -> "16x9"
                    aspectRatio < 0.8f -> "9x16"
                    else -> "1x1"
                }
                val name = String.format(
                    Locale.US, "ClipForge_%s_%dms-%dms_%s.mp4", ratioTag, clip.startMs, clip.endMs, safeTitle
                )
                var created: Uri? = null
                var verified = false
                try {
                    val previous = withContext(Dispatchers.IO) { journal.destination(clip) }
                    val previouslyValid = previous != null && withContext(Dispatchers.IO) {
                        runCatching {
                            EncodedClipVerifier.verify(
                                context, previous, clip.endMs - clip.startMs, aspectRatio, expectAudio,
                                split = splitFacecamRegion != null, decodeFrame = true
                            )
                        }.isSuccess
                    }
                    if (previouslyValid) {
                        verified = true
                    } else {
                        if (sourceCache == null && source.scheme != "file" &&
                            (faceTrackingPath.isNotEmpty() || splitFacecamRegion != null)
                        ) {
                            withContext(Dispatchers.IO) {
                                val cache = java.io.File.createTempFile("clipforge-batch-", ".media", context.cacheDir)
                                try {
                                    resolver.openInputStream(source)?.use { input ->
                                        cache.outputStream().buffered().use { output -> input.copyTo(output) }
                                    } ?: error("Could not read the source video for batch export.")
                                    check(cache.length() > 0L) { "The cached batch source video is empty." }
                                    sourceCache = cache
                                    exportSource = Uri.fromFile(cache)
                                } finally {
                                    if (sourceCache != cache) cache.delete()
                                }
                            }
                        }
                        val destination = withContext(Dispatchers.IO) {
                            // Reuse the original document when it is writable; a retry must not add a duplicate.
                            val writable = previous?.takeIf { uri ->
                                runCatching {
                                    resolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
                                }.getOrDefault(false)
                            }
                            val target = writable ?: DocumentsContract.createDocument(resolver, parentDocument, "video/mp4", name)
                                ?: error("Could not create the output document.")
                            created = target
                            journal.record(clip, target)
                            target
                        }
                        val result = suspendCancellableCoroutine<Pair<Boolean, String>> { continuation ->
                            ClipExporter.export(
                                context, exportSource, destination, clip.startMs, clip.endMs,
                                aspectRatio = aspectRatio,
                                faceTrackingPath = faceTrackingPath,
                                splitFacecamRegion = splitFacecamRegion,
                                gameplayRegion = gameplayRegion,
                                gameplayTracking = gameplayTracking,
                                captionCues = CaptionTrack.fromTranscript(transcript, clip.startMs, clip.endMs),
                                captionPreset = captionPreset,
                                captionWords = if (captionPreset == CaptionPreset.KARAOKE) {
                                    CaptionTrack.alignedWords(transcript, clip.startMs, clip.endMs)
                                } else emptyList(),
                                onStatus = { },
                                onFinished = { success, message ->
                                    if (continuation.isActive) continuation.resume(success to message)
                                }
                            )
                        }
                        check(result.first) { result.second }
                        withContext(Dispatchers.IO) {
                            EncodedClipVerifier.verify(
                                context, destination, clip.endMs - clip.startMs, aspectRatio, expectAudio,
                                split = splitFacecamRegion != null, decodeFrame = true
                            )
                        }
                        verified = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failures += "$name: ${failure.localizedMessage ?: "media error"}"
                } finally {
                    val unverified = created
                    if (!verified && unverified != null) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching {
                                if (DocumentsContract.deleteDocument(resolver, unverified)) journal.forget(clip)
                            }
                        }
                    }
                }

                if (verified) {
                    completed++
                    onProgress(completed, clips.size, clip)
                }
            }
            check(failures.isEmpty()) { "${failures.size} clip(s) failed. ${failures.first()}" }
            return completed
        } finally {
            sourceCache?.delete()
        }
    }
}
