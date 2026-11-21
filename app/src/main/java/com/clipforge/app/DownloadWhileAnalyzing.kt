package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs offline analysis concurrently with an authorized segmented download.
 *
 * The producer MUST publish only fully written, independently decodable media
 * files, never a growing .part file. Its completion closes the channel; a failed
 * download propagates to analysis instead of producing partial final highlights.
 * The session retains durable per-segment checkpoints for a subsequent retry.
 */
internal object DownloadWhileAnalyzing {
    data class ImportedAnalysis(
        val source: Uri,
        val durationMs: Long,
        val highlights: List<ClipCandidate>,
        val transcript: List<TranscriptSegment>
    )

    /** Returns the reviewed timeline and the playable recording it belongs to as one result. */
    suspend fun runYouTubeSections(
        context: Context,
        url: String,
        height: Int,
        durationMs: Long,
        stateDirectory: File,
        language: String? = null,
        targetClipDurationMs: Long = 30_000L,
        onStatus: (String) -> Unit = {},
        onDownloadProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportedAnalysis {
        val id = YouTubeLiveApiClient.parseVideoId(url)
        val fingerprint = "youtube:$id:$height:$durationMs:${language ?: "auto"}"
        val scopedDirectory = File(stateDirectory, "youtube-$id-$height-$durationMs")
        val sections = File(scopedDirectory, "downloaded-sections")
        val importedFile = File(scopedDirectory, "recording.mp4")
        val planned = ProgressiveSegmentPlanner.plan(durationMs)
        val existing = withContext(Dispatchers.IO) {
            if (importedFile.isFile && YouTubeDownloadValidation.isValidMp4(importedFile)) {
                runCatching { ProgressiveRecordingAssembler.mediaDuration(importedFile) }
                    .getOrNull()?.takeIf { kotlin.math.abs(it - durationMs) <= 1_000L }
            } else null
        }
        val complete = withContext(Dispatchers.IO) {
            ProgressiveAnalysisSession(context, fingerprint, scopedDirectory).completedSegments().size == planned.size
        }
        if (existing == null || !complete) {
            run(context, fingerprint, scopedDirectory, durationMs, language, onStatus) { publish ->
                YouTubeSectionDownloader.download(
                    context, url, height, durationMs, sections, publish, onDownloadProgress
                )
            }
        }
        val signals = withContext(Dispatchers.IO) {
            ProgressiveAnalysisSession(context, fingerprint, scopedDirectory).completedSignals(durationMs)
        }
        val playableDuration = existing ?: run {
            onStatus("Preparing recording for preview and export…")
            try {
                ProgressiveRecordingAssembler.assemble(planned, sections, importedFile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onStatus("Sections could not be joined. Downloading the complete recording…")
                val downloaded = YouTubeVideoImporter.download(context, url, height) { }
                withContext(Dispatchers.IO) {
                    val file = File(checkNotNull(downloaded.path))
                    try {
                        val actual = ProgressiveRecordingAssembler.mediaDuration(file)
                        check(kotlin.math.abs(actual - durationMs) <= 1_000L) {
                            "The downloaded recording differs from the analyzed timeline."
                        }
                        check(file.renameTo(importedFile)) { "Could not save the completed recording." }
                        actual
                    } finally {
                        file.delete()
                    }
                }
            }
        }
        val source = Uri.fromFile(importedFile)
        onStatus("Checking motion and scene changes…")
        val visual = VisualUnderstandingEngine.analyze(context, source, playableDuration)
        val clips = withContext(Dispatchers.Default) {
            rankVideoHighlights(signals.transcript, signals.audioEnergy, visual, playableDuration, targetClipDurationMs, 10)
        }
        // Keep sections for recovery until the assembled recording and checkpoint are usable.
        withContext(Dispatchers.IO) { sections.deleteRecursively() }
        val transcript = signals.transcript.mapNotNull { segment ->
            val end = minOf(segment.endMs, playableDuration)
            if (segment.startMs < end) segment.copy(endMs = end) else null
        }
        return ImportedAnalysis(source, playableDuration, clips, transcript)
    }

    suspend fun run(
        context: Context,
        sourceFingerprint: String,
        stateDirectory: File,
        durationMs: Long,
        language: String? = null,
        onStatus: (String) -> Unit = {},
        download: suspend (publish: suspend (FinalizedMediaSegment) -> Unit) -> Unit
    ): List<ClipCandidate> = coroutineScope {
        require(durationMs > 0L)
        val planned = ProgressiveSegmentPlanner.plan(durationMs)
        val session = ProgressiveAnalysisSession(context, sourceFingerprint, stateDirectory)
        val processed = session.completedSegments()
        var nextExpected = 0
        val queue = Channel<FinalizedMediaSegment>(capacity = 2)
        val producer = launch {
            try {
                download { segment ->
                    require(planned.getOrNull(segment.segment.index) == segment.segment) {
                        "Downloaded segment does not match the source timeline"
                    }
                    check(segment.file.isFile && segment.file.length() > 0L) {
                        "Segment must be finalized before publishing"
                    }
                    require(segment.segment.index == nextExpected) {
                        "Downloader must publish segments in timeline order"
                    }
                    nextExpected++
                    // A nonempty download fragment is not necessarily a decodable video.
                    // Reject it before recording an irreversible analysis checkpoint.
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(segment.file.absolutePath)
                        check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()?.let { it > 0L } == true) {
                            "Downloaded segment is not independently playable"
                        }
                    } finally {
                        retriever.release()
                    }
                    if (segment.segment.index !in processed) queue.send(segment)
                }
                check(nextExpected == planned.size) {
                    "Downloader ended before all planned segments were finalized"
                }
                queue.close()
            } catch (error: Throwable) {
                queue.close(error)
                throw error
            }
        }
        try {
            val source = ProgressiveMediaSource { emit ->
                for (segment in queue) emit(segment)
            }
            val clips = session.analyze(source, durationMs, language, onStatus)
            producer.join()
            clips
        } catch (error: Throwable) {
            producer.cancel(CancellationException("Progressive analysis stopped", error))
            throw error
        } finally {
            queue.cancel()
        }
    }
}
