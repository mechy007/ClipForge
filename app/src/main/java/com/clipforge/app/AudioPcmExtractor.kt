package com.clipforge.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Decodes a video's audio track to app-private, mono 16 kHz float PCM.
 * Audio is streamed to a temporary file so long videos do not occupy RAM.
 */
object AudioPcmExtractor {
    suspend fun decodeToMono16Khz(
        context: Context,
        uri: Uri,
        outputFile: File
    ): Long = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = candidate
                    break
                }
            }

            check(audioTrack >= 0 && inputFormat != null) {
                "This video does not contain an audio track."
            }

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The audio format is unavailable.")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            started = true

            outputFile.parentFile?.mkdirs()
            var inputDone = false
            var outputDone = false
            var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var sourceFramesSeen = 0L
            var outputSamplesWritten = 0L
            val info = MediaCodec.BufferInfo()

            DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { pcmOut ->
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()

                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("Could not access the audio decoder input buffer.")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = decoder.outputFormat
                            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = decoder.getOutputBuffer(outputIndex)
                                    ?: error("Could not access decoded audio.")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.order(ByteOrder.LITTLE_ENDIAN)

                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT,
                                    AudioFormat.ENCODING_PCM_32BIT -> 4
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> 2
                                }
                                val frameBytes = bytesPerSample * outputChannels
                                val frames = buffer.remaining() / frameBytes
                                val sourceStart = sourceFramesSeen

                                for (frame in 0 until frames) {
                                    var mono = 0f
                                    repeat(outputChannels) {
                                        val sample = when (pcmEncoding) {
                                            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                                            AudioFormat.ENCODING_PCM_32BIT -> {
                                                (buffer.int.toDouble() / Int.MAX_VALUE.toDouble()).toFloat()
                                            }
                                            AudioFormat.ENCODING_PCM_8BIT -> {
                                                (((buffer.get().toInt() and 0xff) - 128) / 128f)
                                            }
                                            else -> buffer.short / 32768f
                                        }
                                        mono += sample
                                    }
                                    mono = (mono / outputChannels).coerceIn(-1f, 1f)

                                    // Use a global output-sample clock to preserve resampling phase
                                    // across decoder output-buffer boundaries.
                                    val frameIndex = sourceStart + frame
                                    while (outputSamplesWritten.toDouble() * outputSampleRate / 16000.0 <= frameIndex.toDouble()) {
                                        val targetSourceFrame =
                                            outputSamplesWritten.toDouble() * outputSampleRate / 16000.0
                                        if (targetSourceFrame >= frameIndex.toDouble()) {
                                            pcmOut.writeFloat(mono)
                                            outputSamplesWritten++
                                        } else {
                                            break
                                        }
                                    }
                                }
                                sourceFramesSeen += frames
                            }

                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (eos) outputDone = true
                        }
                    }
                }
            }

            check(outputSamplesWritten > 0L) { "No decodable audio samples were found." }
            outputSamplesWritten
        } finally {
            if (started) {
                try { decoder?.stop() } catch (_: Exception) { }
            }
            decoder?.release()
            extractor.release()
        }
    }
}
