package com.clipforge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * End-to-end offline transcription. Decoded PCM is spooled to cache and sent
 * to Whisper in bounded 30-second chunks. The native model context is loaded
 * once per video and reused across chunks to avoid repeatedly loading 78 MB.
 */
data class TranscriptionResult(
    val segments: List<TranscriptSegment>,
    val audioEnergy: List<AudioEnergyWindow>
)

object VideoTranscriber {
    private const val CHUNK_SECONDS = 30
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = CHUNK_SECONDS * SAMPLE_RATE

    suspend fun transcribe(
        context: Context,
        videoUri: Uri,
        language: String? = null,
        onStatus: (String) -> Unit = {}
    ): List<TranscriptSegment> = transcribeWithSignals(context, videoUri, language, onStatus).segments

    suspend fun transcribeWithSignals(
        context: Context,
        videoUri: Uri,
        language: String? = null,
        onStatus: (String) -> Unit = {}
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val pcmFile = File(context.cacheDir, "clipforge-audio-" + System.nanoTime() + ".pcm")
        var whisperSessionStarted = false
        try {
            onStatus("Preparing the bundled offline speech model…")
            WhisperModelManager.ensureModel(context) { _, _ -> }
            onStatus("Decoding video audio on this device…")
            val audioEnergy = mutableListOf<AudioEnergyWindow>()
            AudioPcmExtractor.decodeToMono16Khz(context, videoUri, pcmFile) { window ->
                audioEnergy += window
            }

            whisperSessionStarted = true
            WhisperEngine.beginSession(context)
            onStatus("Starting on-device transcription…")

            val checkpoint = TranscriptCheckpoint.forVideo(context, videoUri, language)
            onStatus("Resuming any previously completed transcription chunks…")
            DataInputStream(BufferedInputStream(pcmFile.inputStream())).use { input ->
                val chunk = FloatArray(CHUNK_SAMPLES)
                var chunkIndex = 0

                while (true) {
                    currentCoroutineContext().ensureActive()
                    var count = 0
                    while (count < chunk.size) {
                        try {
                            chunk[count] = input.readFloat()
                            count++
                        } catch (_: EOFException) {
                            break
                        }
                    }
                    if (count == 0) break

                    if (!checkpoint.hasChunk(chunkIndex)) {
                        onStatus("Transcribing audio · part " + (chunkIndex + 1))
                        val audio = if (count == chunk.size) chunk else chunk.copyOf(count)
                        val offsetMs = chunkIndex.toLong() * CHUNK_SECONDS * 1000L
                        val segments = WhisperEngine.transcribe16Khz(context, audio, language)
                        checkpoint.save(chunkIndex, segments.map { segment ->
                            segment.copy(
                                startMs = segment.startMs + offsetMs,
                                endMs = segment.endMs + offsetMs
                            )
                        })
                    } else onStatus("Restored transcription · part " + (chunkIndex + 1))
                    chunkIndex++
                }
            }

            TranscriptionResult(
                segments = checkpoint.segments(),
                audioEnergy = audioEnergy
            )
        } finally {
            if (whisperSessionStarted) WhisperEngine.endSession()
            pcmFile.delete()
        }
    }
}
