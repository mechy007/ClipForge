package com.clipforge.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.FloatBuffer

data class AudioEventWindow(
    val startMs: Long,
    val endMs: Long,
    val laughter: Float,
    val shouting: Float,
    val cheering: Float
)

/** Runs the bundled YAMNet model on mono 16 kHz PCM and keeps only reaction signals. */
object AudioEventClassifier {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SECONDS = 30
    private const val MIN_MODEL_SAMPLES = 15_360 // YAMNet needs at least one 0.96-second frame.
    private const val MODEL_SIZE = 16_093_603L
    private const val MODEL_ASSET = "models/yamnet.onnx"
    private const val MODEL_NAME = "clipforge-yamnet.onnx"
    private val laughterClasses = intArrayOf(13, 14, 15, 16, 17, 18)
    private val shoutingClasses = intArrayOf(6, 9, 10, 11)
    private val cheeringClasses = intArrayOf(8, 61, 62, 64)

    @Volatile private var cachedSession: OrtSession? = null
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun analyze(
        context: Context,
        pcmFile: File,
        onStatus: (String) -> Unit = {}
    ): List<AudioEventWindow> = withContext(Dispatchers.IO) {
        if (!pcmFile.isFile || pcmFile.length() < Float.SIZE_BYTES || pcmFile.length() % Float.SIZE_BYTES != 0L) {
            return@withContext emptyList()
        }
        val session = try {
            getSession(context.applicationContext)
        } catch (error: Exception) {
            onStatus("Sound-event model unavailable; continuing with speech and audio-energy analysis.")
            return@withContext emptyList()
        } catch (error: LinkageError) {
            onStatus("Sound-event model unavailable; continuing with speech and audio-energy analysis.")
            return@withContext emptyList()
        }
        val durationMs = pcmFile.length() / Float.SIZE_BYTES * 1_000L / SAMPLE_RATE
        val windows = Array(((durationMs + 999L) / 1_000L).toInt().coerceAtLeast(1)) { FloatArray(3) }
        val inputName = session.inputNames.firstOrNull() ?: return@withContext emptyList()
        val chunkSamples = SAMPLE_RATE * CHUNK_SECONDS
        try {
            DataInputStream(BufferedInputStream(pcmFile.inputStream())).use { input ->
                var chunkIndex = 0
                var overlap = FloatArray(0)
                var inputDone = false
                var samplesReadFromFile = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val chunkOriginSamples = samplesReadFromFile - overlap.size
                    val audio = FloatArray(chunkSamples + MIN_MODEL_SAMPLES)
                    overlap.copyInto(audio)
                    var count = overlap.size
                    val targetCount = minOf(audio.size, overlap.size + chunkSamples)
                    while (count < targetCount) {
                        val sample = try { input.readFloat() } catch (_: java.io.EOFException) {
                            inputDone = true
                            break
                        }
                        audio[count++] = sample.coerceIn(-1f, 1f)
                        samplesReadFromFile++
                    }
                    if (count == overlap.size && inputDone) break
                    val actual = when {
                        count >= MIN_MODEL_SAMPLES -> audio.copyOf(count)
                        else -> audio.copyOf(MIN_MODEL_SAMPLES)
                    }
                    onStatus("Checking audio reactions · segment ${chunkIndex + 1}")
                    val probabilities = infer(session, inputName, actual)
                    probabilities.forEachIndexed { frame, scores ->
                        val frameCenterSample = chunkOriginSamples + frame * 7_680L + 7_680L
                        val second = (frameCenterSample / SAMPLE_RATE).toInt()
                        if (second in windows.indices) {
                            val target = windows[second]
                            target[0] = maxOf(target[0], maxClassScore(scores, laughterClasses))
                            target[1] = maxOf(target[1], maxClassScore(scores, shoutingClasses))
                            target[2] = maxOf(target[2], maxClassScore(scores, cheeringClasses))
                        }
                    }
                    overlap = if (inputDone) FloatArray(0)
                    else audio.copyOfRange((count - MIN_MODEL_SAMPLES).coerceAtLeast(0), count)
                    chunkIndex++
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onStatus("Sound-event analysis stopped; continuing with speech and audio-energy signals.")
            return@withContext emptyList()
        } catch (error: LinkageError) {
            onStatus("Sound-event analysis stopped; continuing with speech and audio-energy signals.")
            return@withContext emptyList()
        }
        windows.mapIndexed { second, values ->
            AudioEventWindow(
                startMs = second * 1_000L,
                endMs = minOf(durationMs, (second + 1L) * 1_000L).coerceAtLeast(second * 1_000L + 1L),
                laughter = values[0], shouting = values[1], cheering = values[2]
            )
        }
    }

    @Synchronized
    private fun getSession(context: Context): OrtSession {
        cachedSession?.let { return it }
        val modelFile = File(context.cacheDir, MODEL_NAME)
        if (!modelFile.isFile || modelFile.length() != MODEL_SIZE) {
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(2)
            return environment.createSession(modelFile.absolutePath, options).also { cachedSession = it }
        } finally {
            options.close()
        }
    }

    private fun infer(session: OrtSession, inputName: String, audio: FloatArray): Array<FloatArray> {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(audio), longArrayOf(audio.size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result[0].value
                @Suppress("UNCHECKED_CAST")
                return value as? Array<FloatArray>
                    ?: error("YAMNet returned an unexpected output tensor.")
            }
        }
    }

    private fun maxClassScore(scores: FloatArray, classIndices: IntArray): Float =
        classIndices.maxOfOrNull { index ->
            scores.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        } ?: 0f
}

object AudioEventScorer {
    /** Returns null when this interval has no meaningful reaction-event evidence. */
    fun score(startMs: Long, endMs: Long, events: List<AudioEventWindow>): Int? {
        if (endMs <= startMs || events.isEmpty()) return null
        val relevant = events.filter { it.startMs < endMs && it.endMs > startMs }
        if (relevant.isEmpty()) return null
        val laughter = relevant.maxOf { it.laughter.coerceIn(0f, 1f) }
        val shouting = relevant.maxOf { it.shouting.coerceIn(0f, 1f) }
        val cheering = relevant.maxOf { it.cheering.coerceIn(0f, 1f) }
        val score = ((laughter * 0.55f + shouting * 0.30f + cheering * 0.15f) * 100f).toInt()
        return score.takeIf { it >= 20 || cheering >= 0.7f }
    }

    fun explanation(score: Int): String = when {
        score >= 65 -> " Strong laughter, shouting, or cheering detected ($score/100)."
        score >= 40 -> " A reaction sound was detected ($score/100)."
        else -> " Possible reaction sound detected ($score/100)."
    }
}
