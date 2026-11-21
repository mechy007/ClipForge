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

/** Conservative visual boost for transcript-ranked clips; does not fabricate highlights. */
internal object VisualHighlightReranker {
    fun rerank(clips: List<ClipCandidate>, signals: VisualUnderstandingResult): List<ClipCandidate> =
        clips.map { clip ->
            val samples = signals.activity.filter { it.timeMs >= clip.startMs && it.timeMs < clip.endMs }
            if (samples.isEmpty()) clip else {
                val meanMotion = samples.map { it.motion.coerceIn(0f, 1f) }.average().toFloat()
                val bonus = (meanMotion * 25f).toInt().coerceIn(0, 8)
                clip.copy(
                    score = (clip.score + bonus).coerceAtMost(100),
                    reason = if (bonus > 0) clip.reason + " Visual activity: +$bonus." else clip.reason
                )
            }
        }.sortedByDescending { it.score }
}

/**
 * Generates candidates even when Whisper finds no speech (for example silent
 * gameplay). Peaks are selected by visual activity with non-overlapping windows.
 */
internal object VisualOnlyHighlightDetector {
    fun detect(
        signals: VisualUnderstandingResult,
        durationMs: Long,
        targetDurationMs: Long = 30_000L,
        maxClips: Int = 10
    ): List<ClipCandidate> {
        if (durationMs < 8_000L || signals.activity.isEmpty()) return emptyList()
        val window = targetDurationMs.coerceIn(8_000L, 60_000L).coerceAtMost(durationMs)
        val step = (window / 3L).coerceAtLeast(1_000L)
        val candidates = mutableListOf<ClipCandidate>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(durationMs, start + window)
            if (end - start < 8_000L) break
            val frames = signals.activity.filter { it.timeMs >= start && it.timeMs < end }
            if (frames.size >= 2) {
                val mean = frames.map { it.motion.coerceIn(0f, 1f) }.average().toFloat()
                val peak = frames.maxOf { it.motion.coerceIn(0f, 1f) }
                val changes = frames.count { it.sceneChange }
                val score = ((mean * 100f * 0.6f) + (peak * 100f * 0.3f) +
                    (changes.coerceAtMost(3) * 3f)).toInt().coerceIn(0, 100)
                candidates += ClipCandidate(start, end, "Visual highlight", "",
                    score, "Offline visual motion and scene-change signals; no speech required.")
            }
            if (start >= durationMs - step) break
            start += step
        }
        val chosen = mutableListOf<ClipCandidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.size >= maxClips.coerceIn(1, 100)) break
            if (chosen.none {
                    HighlightDetector.overlapOfShorter(
                        candidate.startMs, candidate.endMs, it.startMs, it.endMs
                    ) > 0.65
                }) chosen += candidate
        }
        return chosen
    }
}
