package com.clipforge.app

import kotlin.math.abs

/** Normalized face box from a sampled video frame (coordinates are in 0..1). */
data class NormalizedFace(val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float = 1f) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
        require(confidence in 0f..1f)
    }
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Time-stamped face observation produced by an Android face detector. */
data class FaceObservation(val timeMs: Long, val face: NormalizedFace)

/** Stable crop centre for a target aspect ratio. This planner is detector-agnostic. */
data class CropFocus(val timeMs: Long, val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/**
 * Converts face observations into a smoothed crop path. A detector (for example
 * ML Kit or OpenCV YuNet) supplies observations; this class only plans framing.
 */
object FaceTrackingPlanner {
    fun plan(
        observations: List<FaceObservation>,
        targetAspectRatio: Float = 9f / 16f,
        smoothing: Float = 0.28f,
        minimumConfidence: Float = 0.45f,
        sourceAspectRatio: Float = 1f
    ): List<CropFocus> {
        require(targetAspectRatio in 0.25f..4f)
        require(sourceAspectRatio in 0.25f..4f)
        require(smoothing in 0.01f..1f)
        val valid = observations.filter { it.timeMs >= 0L && it.face.confidence >= minimumConfidence }
            .sortedBy { it.timeMs }
        if (valid.isEmpty()) return emptyList()

        // Compute the crop in normalized source coordinates while preserving
        // the requested output ratio for both landscape and portrait sources.
        val cropWidth: Float
        val cropHeight: Float
        if (targetAspectRatio <= sourceAspectRatio) {
            cropHeight = 1f
            cropWidth = targetAspectRatio / sourceAspectRatio
        } else {
            cropWidth = 1f
            cropHeight = sourceAspectRatio / targetAspectRatio
        }
        var x = valid.first().face.centerX
        var y = (valid.first().face.centerY - 0.08f).coerceIn(0f, 1f)
        return valid.map { observation ->
            val desiredX = observation.face.centerX.coerceIn(cropWidth / 2f, 1f - cropWidth / 2f)
            val desiredY = (observation.face.centerY - 0.08f).coerceIn(cropHeight / 2f, 1f - cropHeight / 2f)
            if (abs(desiredX - x) > 0.015f) x += (desiredX - x) * smoothing
            if (abs(desiredY - y) > 0.015f) y += (desiredY - y) * smoothing
            CropFocus(observation.timeMs, x, y, cropWidth, cropHeight)
        }
    }
}
