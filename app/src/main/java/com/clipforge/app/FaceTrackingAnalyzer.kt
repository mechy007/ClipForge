package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sparse on-device face sampling; bounded to one downscaled frame at a time. */
object FaceTrackingAnalyzer {
    suspend fun analyze(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        targetAspectRatio: Float = 9f / 16f
    ): List<CropFocus> = withContext(Dispatchers.IO) {
        require(startMs >= 0L && endMs > startMs) { "Choose a valid clip range." }
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        val retriever = MediaMetadataRetriever()
        val observations = mutableListOf<FaceObservation>()
        var sourceAspect = 1f
        try {
            retriever.setDataSource(context, source)
            val duration = (endMs - startMs).coerceAtLeast(1L)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1f
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1f
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawAspect = (width / height.coerceAtLeast(1f)).coerceIn(0.25f, 4f)
            sourceAspect = if (rotation == 90 || rotation == 270) 1f / rawAspect else rawAspect
            val step = maxOf(1_500L, duration / 120L)
            var offset = 0L
            while (offset < duration) {
                currentCoroutineContext().ensureActive()
                val timeUs = (startMs + offset) * 1_000L
                val original = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                if (original != null) {
                    val scale = minOf(1f, 640f / maxOf(original.width, original.height))
                    val frame = if (scale < 1f) Bitmap.createScaledBitmap(
                        original, (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1), true
                    ) else original
                    try {
                        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                        val primary = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (primary != null) {
                            val box = primary.boundingBox
                            val w = frame.width.toFloat()
                            val h = frame.height.toFloat()
                            val left = (box.left / w).coerceIn(0f, 1f)
                            val top = (box.top / h).coerceIn(0f, 1f)
                            val right = (box.right / w).coerceIn(0f, 1f)
                            val bottom = (box.bottom / h).coerceIn(0f, 1f)
                            if (right > left && bottom > top) {
                                observations += FaceObservation(startMs + offset,
                                    NormalizedFace(left, top, right, bottom, 0.9f))
                            }
                        }
                    } finally {
                        if (frame !== original) frame.recycle()
                        original.recycle()
                    }
                }
                offset += step
            }
        } finally {
            retriever.release()
            detector.close()
        }
        FaceTrackingPlanner.plan(observations, targetAspectRatio, sourceAspectRatio = sourceAspect)
    }
}
