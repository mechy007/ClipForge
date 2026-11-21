package com.clipforge.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

internal object ProgressiveRecordingAssembler {
    suspend fun assemble(planned: List<ProgressiveSegment>, directory: File, destination: File): Long =
        withContext(Dispatchers.IO) {
            require(planned.isNotEmpty())
            val list = File.createTempFile("sections-", ".txt", directory)
            try {
                val pending = File.createTempFile("recording-", ".mp4", destination.parentFile)
                try {
                    var actualEndMs = 0L
                    var expectedTracks: List<List<Any?>>? = null
                    list.bufferedWriter().use { manifest ->
                        for (segment in planned) {
                            ensureActive()
                            val name = "segment-${segment.index}.mp4"
                            val section = File(directory, name)
                            val tracks = trackFormats(section)
                            val previous = expectedTracks
                            check(previous == null || previous == tracks) {
                                "Downloaded sections use incompatible video or audio formats."
                            }
                            expectedTracks = tracks
                            actualEndMs += mediaDuration(section)
                            val tolerance = if (segment == planned.last()) 1_000L else 350L
                            check(abs(segment.endMs - actualEndMs) <= tolerance) {
                                "Downloaded sections drift from the original timeline at segment ${segment.index + 1}."
                            }
                            manifest.appendLine("file '$name'")
                        }
                    }
                    val session = FFmpegKit.executeWithArguments(arrayOf(
                        "-y", "-loglevel", "error", "-f", "concat", "-safe", "1", "-i", list.absolutePath,
                        "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", pending.absolutePath
                    ))
                    check(ReturnCode.isSuccess(session.returnCode) && session.output.isNullOrBlank()) {
                        "The downloaded sections could not be joined losslessly."
                    }
                    ensureActive()
                    check(YouTubeDownloadValidation.isValidMp4(pending)) { "The combined recording is not a valid MP4." }
                    val duration = mediaDuration(pending)
                    check(trackFormats(pending).map { it.first() } == expectedTracks?.map { it.first() }) {
                        "The combined recording has missing media tracks."
                    }
                    check(abs(duration - planned.last().endMs) <= 1_000L) { "The combined recording duration is invalid." }
                    check(pending.renameTo(destination)) { "Could not publish the combined recording." }
                    duration
                } finally {
                    pending.delete()
                }
            } finally {
                list.delete()
            }
        }

    private fun trackFormats(file: File): List<List<Any?>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            return listOfNotNull(video, audio).map { format ->
                val fields = listOf(
                    MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_ROTATION,
                    MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT
                )
                listOf(format.getString(MediaFormat.KEY_MIME)) +
                    fields.map { if (format.containsKey(it)) format.getInteger(it) else null } +
                    listOf(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"))
            }
        } finally {
            extractor.release()
        }
    }

    fun mediaDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" && duration > 0L) {
                "The recording does not have a readable video track."
            }
            return duration
        } finally {
            retriever.release()
        }
    }
}
