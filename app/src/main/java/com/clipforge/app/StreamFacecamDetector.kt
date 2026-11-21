package com.clipforge.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Samples a few frames and proposes a stable corner webcam rectangle.
 * This is a conservative heuristic, not a guarantee of webcam segmentation.
 * Callers must allow manual adjustment if no consistent corner face is found.
 */
internal object StreamFacecamDetector {
    suspend fun detect(context: Context, source: Uri, durationMs: Long): StreamRegion? =
        withContext(Dispatchers.IO) {
            require(durationMs > 0L)
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            )
            val retriever = MediaMetadataRetriever()
            val votes = IntArray(4)
            try {
                retriever.setDataSource(context, source)
                val times = listOf(0.1, 0.3, 0.5, 0.7, 0.9).map { (durationMs * it).toLong() }
                for (time in times) {
                    val original = retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: continue
                    val scale = minOf(1f, 640f / maxOf(original.width, original.height))
                    val frame = if (scale < 1f) Bitmap.createScaledBitmap(
                        original, (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1), true
                    ) else original
                    try {
                        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                        for (face in faces) {
                            val cx = face.boundingBox.centerX() / frame.width.toFloat()
                            val cy = face.boundingBox.centerY() / frame.height.toFloat()
                            val fw = face.boundingBox.width() / frame.width.toFloat()
                            val fh = face.boundingBox.height() / frame.height.toFloat()
                            if (fw !in 0.025f..0.24f || fh !in 0.04f..0.35f) continue
                            if (cx < 0.33f && cy < 0.35f) votes[0]++
                            if (cx > 0.67f && cy < 0.35f) votes[1]++
                            if (cx < 0.33f && cy > 0.65f) votes[2]++
                            if (cx > 0.67f && cy > 0.65f) votes[3]++
                        }
                    } finally {
                        if (frame !== original) frame.recycle()
                        original.recycle()
                    }
                }
            } finally {
                retriever.release()
                detector.close()
            }
            val best = votes.indices.maxByOrNull { votes[it] } ?: return@withContext null
            if (votes[best] < 3) return@withContext null
            val left = if (best % 2 == 0) 0f else 0.67f
            val top = if (best < 2) 0f else 0.64f
            StreamRegion(left, top, (left + 0.33f).coerceAtMost(1f), (top + 0.36f).coerceAtMost(1f))
        }
}
