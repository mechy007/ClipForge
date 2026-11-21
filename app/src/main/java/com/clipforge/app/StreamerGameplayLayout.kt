package com.clipforge.app

/** A source-space rectangle; coordinates are normalized to the original stream frame. */
data class StreamRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(left < right && top < bottom)
    }
    val width get() = right - left
    val height get() = bottom - top
}

/** Pure geometry for a 1080x1920, two-panel streamer/gameplay composition.
 * Detection supplies the facecam rectangle; the renderer must crop each source
 * region independently, synchronize timestamps and preserve original audio.
 */
internal object StreamerGameplayLayout {
    const val OUTPUT_WIDTH = 1080
    const val OUTPUT_HEIGHT = 1920
    const val PANEL_HEIGHT = OUTPUT_HEIGHT / 2

    /** Preserve the entire facecam region, expanding within the source if necessary. */
    fun facecamCrop(region: StreamRegion, sourceWidth: Int, sourceHeight: Int): StreamRegion {
        require(sourceWidth > 0 && sourceHeight > 0)
        return fitAspect(region, sourceWidth.toFloat() / sourceHeight, OUTPUT_WIDTH.toFloat() / PANEL_HEIGHT)
    }

    /** Crop the gameplay area around its center to the 9:8 panel aspect. */
    fun gameplayCrop(region: StreamRegion, sourceWidth: Int, sourceHeight: Int): StreamRegion {
        require(sourceWidth > 0 && sourceHeight > 0)
        return fitAspect(region, sourceWidth.toFloat() / sourceHeight, OUTPUT_WIDTH.toFloat() / PANEL_HEIGHT)
    }

    private fun fitAspect(region: StreamRegion, sourceAspect: Float, targetAspect: Float): StreamRegion {
        val desiredNormalizedAspect = targetAspect / sourceAspect
        val w: Float
        val h: Float
        if (region.width / region.height > desiredNormalizedAspect) {
            w = region.width
            h = (w / desiredNormalizedAspect).coerceAtMost(1f)
        } else {
            h = region.height
            w = (h * desiredNormalizedAspect).coerceAtMost(1f)
        }
        val centerX = (region.left + region.right) / 2f
        val centerY = (region.top + region.bottom) / 2f
        val left = (centerX - w / 2f).coerceIn(0f, 1f - w)
        val top = (centerY - h / 2f).coerceIn(0f, 1f - h)
        return StreamRegion(left, top, left + w, top + h)
    }
}
