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

/** Built-in, offline visual activity analysis. No model download or network connection. */
data class VisualActivity(val timeMs: Long, val motion: Float, val sceneChange: Boolean)
data class VisualUnderstandingResult(val activity: List<VisualActivity>) {
    val sceneCuts: List<Long> get() = activity.filter { it.sceneChange }.map { it.timeMs }
    val highMotionMoments: List<Long> get() = activity.filter { it.motion >= 0.20f }.map { it.timeMs }
}

/**
 * Lightweight frame-difference vision engine for gameplay and reaction footage.
 * This is deterministic image analysis, not semantic object recognition.
 * Frames are sampled and downscaled to keep memory bounded on 8 GB devices.
 */
object VisualUnderstandingEngine {
    private const val WIDTH = 64
    private const val HEIGHT = 36
    private const val SCENE_THRESHOLD = 0.24f

    suspend fun analyze(context: Context, source: Uri, durationMs: Long): VisualUnderstandingResult =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0L) return@withContext VisualUnderstandingResult(emptyList())
            val retriever = MediaMetadataRetriever()
            val events = mutableListOf<VisualActivity>()
            var previous: FloatArray? = null
            try {
                retriever.setDataSource(context, source)
                val step = maxOf(1_000L, durationMs / 600L)
                var time = 0L
                while (time < durationMs) {
                    currentCoroutineContext().ensureActive()
                    val frame = retriever.getFrameAtTime(time * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val small = Bitmap.createScaledBitmap(frame, WIDTH, HEIGHT, true)
                        try {
                            val pixels = IntArray(WIDTH * HEIGHT)
                            small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                            val current = FloatArray(pixels.size) { i ->
                                val color = pixels[i]
                                ((color shr 16 and 255) * 0.2126f +
                                    (color shr 8 and 255) * 0.7152f +
                                    (color and 255) * 0.0722f) / 255f
                            }
                            val motion = previous?.let { before ->
                                before.indices.sumOf { i -> abs(before[i] - current[i]).toDouble() }
                                    .div(current.size).toFloat()
                            } ?: 0f
                            events += VisualActivity(time, motion, previous != null && motion >= SCENE_THRESHOLD)
                            previous = current
                        } finally {
                            if (small !== frame) small.recycle()
                            frame.recycle()
                        }
                    }
                    time += step
                }
            } finally {
                retriever.release()
            }
            VisualUnderstandingResult(events)
        }
}
