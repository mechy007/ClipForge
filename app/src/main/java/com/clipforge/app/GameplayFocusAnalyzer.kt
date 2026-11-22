package com.clipforge.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Estimate a point of interest from concentrated movement inside the selected gameplay area. */
internal object GameplayFocusAnalyzer {
    private const val WIDTH = VisualFrameSignals.WIDTH
    private const val HEIGHT = VisualFrameSignals.HEIGHT

    suspend fun analyze(context: Context, source: Uri, durationMs: Long, gameplay: StreamRegion, facecam: StreamRegion?): List<CropFocus> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                val result = mutableListOf<CropFocus>()
                var before: FloatArray? = null
                var x = (gameplay.left + gameplay.right) / 2
                var y = (gameplay.top + gameplay.bottom) / 2
                val step = maxOf(1_000L, durationMs / 120)
                val pixels = IntArray(WIDTH * HEIGHT)
                for (time in 0L until durationMs step step) {
                    ensureActive()
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, WIDTH, HEIGHT)
                    } else {
                        retriever.getFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } ?: continue
                    val resized = Bitmap.createScaledBitmap(frame, WIDTH, HEIGHT, true)
                    try {
                        resized.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    } finally {
                        if (resized !== frame) resized.recycle()
                        frame.recycle()
                    }
                    val current = FloatArray(pixels.size) { i ->
                        val color = pixels[i]
                        ((color shr 16 and 255) * .2126f + (color shr 8 and 255) * .7152f + (color and 255) * .0722f) / 255f
                    }
                    val previous = before
                    before = current
                    if (previous == null) continue
                    val activity = VisualFrameSignals.compare(time, previous, current)
                    if (activity.sceneChange || activity.cameraMotion > .12f) continue
                    val focus = focus(previous, current, gameplay, facecam) ?: continue
                    x += (focus.first - x) * .30f
                    y += (focus.second - y) * .30f
                    result += CropFocus(time, x, y, gameplay.width, gameplay.height)
                }
                result.takeIf { it.size >= 2 }.orEmpty()
            } finally { retriever.release() }
        }

    internal fun focus(
        previous: FloatArray, current: FloatArray, gameplay: StreamRegion, facecam: StreamRegion?
    ): Pair<Float, Float>? {
        var weight = 0f
        var xTotal = 0f
        var yTotal = 0f
        var count = 0
        var inside = 0
        for (i in current.indices) {
            val x = (i % WIDTH + .5f) / WIDTH
            val y = (i / WIDTH + .5f) / HEIGHT
            if (x !in gameplay.left..gameplay.right || y !in gameplay.top..gameplay.bottom) continue
            if (facecam != null && x in facecam.left..facecam.right && y in facecam.top..facecam.bottom) continue
            inside++
            val delta = abs(current[i] - previous[i])
            if (delta < .16f) continue
            count++
            weight += delta
            xTotal += x * delta
            yTotal += y * delta
        }
        if (count < 8 || count > inside * .45f) return null
        return xTotal / weight to yTotal / weight
    }
}
