package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
            val samples = List(4) { mutableListOf<StreamRegion>() }
            try {
                retriever.setDataSource(context, source)
                val times = listOf(0.1, 0.3, 0.5, 0.7, 0.9).map { (durationMs * it).toLong() }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                val rotated = rotation == "90" || rotation == "270"
                val displayWidth = if (rotated) height else width
                val displayHeight = if (rotated) width else height
                if (minOf(displayWidth, displayHeight) <= 0) return@withContext null
                val scale = minOf(1f, 640f / maxOf(displayWidth, displayHeight))
                for (time in times) {
                    ensureActive()
                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(
                            time * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                            (displayWidth * scale).toInt().coerceAtLeast(1),
                            (displayHeight * scale).toInt().coerceAtLeast(1)
                        )
                    } else {
                        retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    } ?: continue
                    try {
                        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                        val inFrame = mutableMapOf<Int, StreamRegion>()
                        for (face in faces) {
                            val box = face.boundingBox
                            val cx = box.exactCenterX() / frame.width
                            val cy = box.exactCenterY() / frame.height
                            val fw = box.width().toFloat() / frame.width
                            val fh = box.height().toFloat() / frame.height
                            if (fw !in 0.025f..0.22f || fh !in 0.04f..0.32f) continue
                            val corner = when {
                                cx < 0.33f && cy < 0.35f -> 0
                                cx > 0.67f && cy < 0.35f -> 1
                                cx < 0.33f && cy > 0.65f -> 2
                                cx > 0.67f && cy > 0.65f -> 3
                                else -> continue
                            }
                            val candidate = StreamRegion(
                                (cx - fw / 2f).coerceAtLeast(0f), (cy - fh / 2f).coerceAtLeast(0f),
                                (cx + fw / 2f).coerceAtMost(1f), (cy + fh / 2f).coerceAtMost(1f)
                            )
                            val previous = inFrame[corner]
                            if (previous == null || fw * fh > previous.width * previous.height) {
                                inFrame[corner] = candidate
                            }
                        }
                        inFrame.forEach { (corner, region) -> samples[corner].add(region) }
                    } finally {
                        frame.recycle()
                    }
                }
            } finally {
                retriever.release()
                detector.close()
            }
            proposeRegion(samples)
        }

    internal fun proposeRegion(samples: List<List<StreamRegion>>): StreamRegion? {
        val ranked = samples.sortedByDescending { it.size }
        val winner = ranked.firstOrNull() ?: return null
        if (winner.size < 3 || winner.size - (ranked.getOrNull(1)?.size ?: 0) < 2) return null
        val left = winner.minOf { it.left }
        val right = winner.maxOf { it.right }
        val top = winner.minOf { it.top }
        val bottom = winner.maxOf { it.bottom }
        val width = winner.map { it.width }.sorted()[winner.size / 2]
        val height = winner.map { it.height }.sorted()[winner.size / 2]
        if (right - left > width * 2.2f || bottom - top > height * 2.2f) return null
        return StreamRegion(
            (left - width * 0.45f).coerceAtLeast(0f),
            (top - height * 0.35f).coerceAtLeast(0f),
            (right + width * 0.45f).coerceAtMost(1f),
            (bottom + height * 0.65f).coerceAtMost(1f)
        )
    }
}
