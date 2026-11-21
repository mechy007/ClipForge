package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
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
    data class SectionPreview(val source: Uri, val clip: ClipCandidate, val sourceOffsetMs: Long)

    data class ImportedAnalysis(
        val source: Uri,
        val durationMs: Long,
        val highlights: List<ClipCandidate>,
        val transcript: List<TranscriptSegment>,
        val sectionTimelineUsable: Boolean
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
        preferences: CreatorClipPreferences = CreatorClipPreferences(),
        onPreview: (SectionPreview) -> Unit = {},
        onDownloadProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportedAnalysis {
        val id = YouTubeLiveApiClient.parseVideoId(url)
        val fingerprint = "youtube-timeline-v2:$id:$height:$durationMs:${language ?: "auto"}"
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
        val source: Uri
        val playableDuration: Long
        val signals: ProgressiveSignalIntegration.Signals
        try {
            if (existing == null || !complete) {
                run(context, fingerprint, scopedDirectory, durationMs, language, onStatus,
                    onSectionAnalyzed = { file, signals ->
                        val span = signals.segment
                        val length = span.endMs - span.startMs
                        val text = signals.transcript.map { it.moveToSource(-span.startMs) }
                        val energy = signals.audioEnergy.map {
                            it.copy(startMs = it.startMs - span.startMs, endMs = it.endMs - span.startMs)
                        }
                        val speech = withContext(Dispatchers.Default) {
                            HighlightDetector.detect(text, length, targetClipDurationMs, energy, maxClips = 3)
                                .firstOrNull { it.startMs >= 500L && it.endMs <= length - 500L }
                        }
                        val best = speech ?: try {
                            val visual = VisualUnderstandingEngine.analyze(context, Uri.fromFile(file.file), length)
                            VisualOnlyHighlightDetector.detect(visual, length, targetClipDurationMs, 3)
                                .firstOrNull { it.startMs >= 500L && it.endMs <= length - 500L }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (best != null) {
                            withContext(Dispatchers.Main) {
                                onPreview(SectionPreview(Uri.fromFile(file.file), best, span.startMs))
                            }
                        }
                    }
                ) { publish ->
                    YouTubeSectionDownloader.download(
                        context, url, height, durationMs, sections, publish
                    ) { completed, total ->
                        withContext(Dispatchers.Main) { onDownloadProgress(completed, total) }
                    }
                }
            }
            val restored = withContext(Dispatchers.IO) {
                ProgressiveAnalysisSession(context, fingerprint, scopedDirectory).completedSignals(durationMs)
            }
            if (existing == null) onStatus("Preparing recording for preview and export…")
            val actualDuration = existing ?: ProgressiveRecordingAssembler.assemble(planned, sections, importedFile)
            playableDuration = actualDuration
            source = Uri.fromFile(importedFile)
            signals = restored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onStatus("Sections unavailable. Downloading the complete recording…")
            val fullSource = YouTubeVideoImporter.download(context, url, height) { }
            val fullDuration = withContext(Dispatchers.IO) {
                ProgressiveRecordingAssembler.mediaDuration(File(checkNotNull(fullSource.path)))
            }
            val full = VideoTranscriber.transcribeWithSignals(context, fullSource, language, onStatus)
            val visual = VisualUnderstandingEngine.analyze(context, fullSource, fullDuration)
            val clips = withContext(Dispatchers.Default) {
                rankVideoHighlights(full.segments, full.audioEnergy, visual, fullDuration, targetClipDurationMs, 10, preferences)
            }
            return ImportedAnalysis(fullSource, fullDuration, clips, full.segments, sectionTimelineUsable = false)
        }
        onStatus("Checking motion and scene changes…")
        val visual = VisualUnderstandingEngine.analyze(context, source, playableDuration)
        val clips = withContext(Dispatchers.Default) {
            rankVideoHighlights(signals.transcript, signals.audioEnergy, visual, playableDuration, targetClipDurationMs, 10, preferences)
        }
        // Keep sections for recovery until the assembled recording and checkpoint are usable.
        withContext(Dispatchers.IO) { sections.deleteRecursively() }
        val transcript = signals.transcript.mapNotNull { segment ->
            val end = minOf(segment.endMs, playableDuration)
            if (segment.startMs < end) segment.moveToSource(0L, end) else null
        }
        return ImportedAnalysis(source, playableDuration, clips, transcript, sectionTimelineUsable = true)
    }

    suspend fun run(
        context: Context,
        sourceFingerprint: String,
        stateDirectory: File,
        durationMs: Long,
        language: String? = null,
        onStatus: (String) -> Unit = {},
        onSectionAnalyzed: suspend (FinalizedMediaSegment, ProgressiveTranscriptionSignals) -> Unit = { _, _ -> },
        download: suspend (publish: suspend (FinalizedMediaSegment) -> Unit) -> Unit
    ): List<ClipCandidate> = withContext(Dispatchers.IO) {
        require(durationMs > 0L)
        val planned = ProgressiveSegmentPlanner.plan(durationMs)
        val session = ProgressiveAnalysisSession(context, sourceFingerprint, stateDirectory)
        val mainHandler = Handler(Looper.getMainLooper())
        val reportStatus: (String) -> Unit = { message ->
            if (Looper.myLooper() == Looper.getMainLooper()) onStatus(message)
            else mainHandler.post { onStatus(message) }
        }
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
            val clips = session.analyze(source, durationMs, language, reportStatus, onSectionAnalyzed)
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
