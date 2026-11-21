package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
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
    /** Connects yt-dlp's finalized section downloads to the analysis queue. */
    suspend fun runYouTubeSections(
        context: Context,
        url: String,
        height: Int,
        durationMs: Long,
        stateDirectory: File,
        language: String? = null,
        onStatus: (String) -> Unit = {},
        onDownloadProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ClipCandidate> {
        val id = YouTubeLiveApiClient.parseVideoId(url)
        val fingerprint = "youtube:$id:$height:$durationMs"
        val scopedDirectory = File(stateDirectory, "youtube-$id-$height-$durationMs")
        return run(
            context = context,
            sourceFingerprint = fingerprint,
            stateDirectory = scopedDirectory,
            durationMs = durationMs,
            language = language,
            onStatus = onStatus
        ) { publish ->
            YouTubeSectionDownloader.download(
                context, url, height, durationMs,
                File(scopedDirectory, "downloaded-sections"), publish
            )
        }
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
