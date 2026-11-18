package com.clipforge.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Sparse, low-resolution scene-cut detector. At most two tiny frames are held at once. */
object SceneChangeAnalyzer {
    private const val SAMPLE_INTERVAL_MS = 1_000L
    private const val WIDTH = 96
    private const val HEIGHT = 54
    private const val CUT_THRESHOLD = 0.24f

    suspend fun analyze(context: Context, source: Uri, durationMs: Long): List<Long> =
        withContext(Dispatchers.IO) {
            if (durationMs < 2_000L) return@withContext emptyList()
            val retriever = MediaMetadataRetriever()
            val cuts = mutableListOf<Long>()
            var previous: FloatArray? = null
            try {
                retriever.setDataSource(context, source)
                var timeMs = SAMPLE_INTERVAL_MS
                while (timeMs < durationMs) {
                    currentCoroutineContext().ensureActive()
                    val frame = retriever.getFrameAtTime(timeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val small = Bitmap.createScaledBitmap(frame, WIDTH, HEIGHT, true)
                        try {
                            val current = luminance(small)
                            val before = previous
                            if (before != null && difference(before, current) >= CUT_THRESHOLD) {
                                cuts += timeMs
                            }
                            previous = current
                        } finally {
                            small.recycle()
                            frame.recycle()
                        }
                    }
                    timeMs += SAMPLE_INTERVAL_MS
                }
            } finally {
                retriever.release()
            }
            cuts.distinct().sorted()
        }

    internal fun difference(first: FloatArray, second: FloatArray): Float {
        if (first.isEmpty() || first.size != second.size) return 0f
        var total = 0f
        for (index in first.indices) total += abs(first[index] - second[index])
        return total / first.size
    }

    private fun luminance(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size) { index ->
            val color = pixels[index]
            (((color shr 16 and 0xff) * 0.2126f) +
                ((color shr 8 and 0xff) * 0.7152f) +
                ((color and 0xff) * 0.0722f)) / 255f
        }
    }
}
