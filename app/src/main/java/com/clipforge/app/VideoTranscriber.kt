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
 * to Whisper in bounded 30-second chunks to limit peak memory on phones.
 */
object VideoTranscriber {
    private const val CHUNK_SECONDS = 30
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = CHUNK_SECONDS * SAMPLE_RATE

    suspend fun transcribe(
        context: Context,
        videoUri: Uri,
        language: String? = null,
        onStatus: (String) -> Unit = {}
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val pcmFile = File(context.cacheDir, "clipforge-audio-" + System.nanoTime() + ".pcm")
        try {
            onStatus("Decoding video audio on this device…")
            AudioPcmExtractor.decodeToMono16Khz(context, videoUri, pcmFile)

            val transcript = mutableListOf<TranscriptSegment>()
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

                    onStatus("Transcribing audio · part " + (chunkIndex + 1))
                    val audio = if (count == chunk.size) chunk else chunk.copyOf(count)
                    val offsetMs = chunkIndex.toLong() * CHUNK_SECONDS * 1000L
                    val segments = WhisperEngine.transcribe16Khz(context, audio, language)
                    transcript += segments.map { segment ->
                        segment.copy(
                            startMs = segment.startMs + offsetMs,
                            endMs = segment.endMs + offsetMs
                        )
                    }
                    chunkIndex++
                }
            }

            transcript.sortedBy { it.startMs }
        } finally {
            pcmFile.delete()
        }
    }
}
