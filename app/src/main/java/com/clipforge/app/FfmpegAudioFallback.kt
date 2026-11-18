package com.clipforge.app

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpeg fallback for media that Android MediaCodec cannot decode.
 * Produces mono 16 kHz float PCM consumed by WhisperEngine.
 */
object FfmpegAudioFallback {
    internal fun buildArguments(inputPath: String, outputPath: String): Array<String> = arrayOf(
        "-nostdin",
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", inputPath,
        "-map", "0:a:0",
        "-vn",
        "-ac", "1",
        "-ar", "16000",
        "-c:a", "pcm_f32be",
        "-f", "f32be",
        outputPath
    )

    suspend fun decodeToMono16Khz(
        context: Context,
        uri: Uri,
        outputFile: File
    ): Long = withContext(Dispatchers.IO) {
        val inputFile = File.createTempFile("clipforge-ffmpeg-input-", ".media", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected video for FFmpeg decoding.")
            input.use { source -> inputFile.outputStream().buffered().use { target -> source.copyTo(target) } }

            outputFile.parentFile?.mkdirs()
            outputFile.delete()
            val session = FFmpegKit.executeWithArguments(
                buildArguments(inputFile.absolutePath, outputFile.absolutePath)
            )
            check(ReturnCode.isSuccess(session.returnCode)) {
                "FFmpeg could not decode this video's audio (return code ${session.returnCode})."
            }
            check(outputFile.isFile && outputFile.length() >= 4L) {
                "FFmpeg completed without producing audio samples."
            }
            outputFile.length() / 4L
        } finally {
            inputFile.delete()
        }
    }
}
