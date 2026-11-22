package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.abs

internal object EncodedClipVerifier {
    fun sourceHasAudio(context: Context, source: Uri): Boolean =
        read(context, source) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes" }

    fun verify(
        context: Context, uri: Uri, durationMs: Long, aspect: Float, expectAudio: Boolean,
        split: Boolean = false, decodeFrame: Boolean = false
    ) {
        read(context, uri) { metadata ->
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotated = rotation == "90" || rotation == "270"
            val visibleWidth = if (rotated) height else width
            val visibleHeight = if (rotated) width else height
            check(width > 0 && height > 0) { "The saved MP4 has no readable video track." }
            check(abs(visibleWidth - visibleHeight * aspect) <= maxOf(2f, visibleWidth * 0.02f)) {
                "The saved MP4 has the wrong aspect ratio."
            }
            check(!split || (visibleWidth == 1080 && visibleHeight == 1920)) {
                "The saved split-screen MP4 has the wrong resolution."
            }
            val actual = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val tolerance = (durationMs / 5).coerceIn(100L, 350L)
            check(actual > 0 && abs(actual - durationMs) <= tolerance) { "The saved MP4 has the wrong duration." }
            check(!expectAudio || metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") {
                "The saved MP4 is missing audio."
            }
            if (decodeFrame) {
                fun decode(timeUs: Long, mode: Int) {
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        metadata.getScaledFrameAtTime(timeUs, mode, 64, 64)
                    } else {
                        metadata.getFrameAtTime(timeUs, mode)
                    }
                    checkNotNull(frame) { "A frame in the saved MP4 could not be decoded." }.recycle()
                }
                decode(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                decode((actual - 100L).coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)

            }
        }
    }

    private inline fun <T> read(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        val metadata = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") metadata.setDataSource(checkNotNull(uri.path))
            else metadata.setDataSource(context, uri)
            return block(metadata)
        } finally {
            metadata.release()
        }
    }
}
