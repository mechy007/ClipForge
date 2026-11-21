package com.clipforge.app

import kotlin.math.abs

/** Measurements from two small luma frames. Overlay evidence is heuristic. */
internal object VisualFrameSignals {
    const val WIDTH = 64
    const val HEIGHT = 36
    private const val SEARCH_RADIUS = 4

    fun compare(timeMs: Long, previous: FloatArray?, current: FloatArray): VisualActivity {
        val fingerprint = fingerprint(current)
        if (previous == null) return VisualActivity(timeMs, 0f, false, signature = fingerprint)
        val center = difference(previous, current, 0, 0)
        var best = center
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS step 2) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS step 2) {
                if (dx != 0 || dy != 0) best = minOf(best, difference(previous, current, dx, dy))
            }
        }
        val camera = (center - best).coerceIn(0f, 1f)
        val cut = best >= 0.23f && camera < center * 0.24f
        val hud = !cut && center >= 0.10f && hasStableOverlay(previous, current)
        return VisualActivity(timeMs, best, cut, if (cut) 0f else camera, hud, fingerprint)
    }

    private fun difference(previous: FloatArray, current: FloatArray, dx: Int, dy: Int): Float {
        var total = 0f
        var count = 0
        for (y in SEARCH_RADIUS until HEIGHT - SEARCH_RADIUS step 2) {
            for (x in SEARCH_RADIUS until WIDTH - SEARCH_RADIUS step 2) {
                total += abs(previous[(y + dy) * WIDTH + x + dx] - current[y * WIDTH + x])
                count++
            }
        }
        return total / count
    }

    private fun hasStableOverlay(previous: FloatArray, current: FloatArray): Boolean {
        var stable = 0
        var textured = 0
        for (y in 1 until HEIGHT - 1 step 2) {
            if (y in HEIGHT / 4 until HEIGHT * 3 / 4) continue
            for (x in 1 until WIDTH - 1 step 2) {
                if (x in WIDTH / 5 until WIDTH * 4 / 5) continue
                val index = y * WIDTH + x
                val pixel = current[index]
                val edge = current[index + 1]
                if (abs(pixel - edge) < 0.23f) continue
                textured++
                if (abs(pixel - previous[index]) < 0.07f &&
                    abs(edge - previous[index + 1]) < 0.07f
                ) stable++
            }
        }
        return textured >= 8 && stable >= 8 && stable * 4 >= textured * 3
    }

    private fun fingerprint(frame: FloatArray): Long? {
        var hash = 0L
        var detail = 0f
        for (row in 0 until 8) {
            val y = 3 + row * 4
            for (column in 0 until 8) {
                val x = 4 + column * 7
                val gradient = frame[y * WIDTH + x + 5] - frame[y * WIDTH + x]
                if (gradient > 0f) hash = hash or (1L shl (row * 8 + column))
                detail += abs(gradient)
            }
        }
        return hash.takeIf { detail >= 4f }
    }
}
