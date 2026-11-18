package com.clipforge.app

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Burns an existing timed caption track into a rendered MP4 with FFmpeg.
 * The caller should first render/trim the clip, then pass the clip-relative cues.
 */
object CaptionBurnIn {
    fun render(
        context: Context,
        input: File,
        output: File,
        cues: List<CaptionCue>,
        preset: CaptionPreset = CaptionPreset.CLEAN,
        onFinished: (Boolean, String) -> Unit
    ) {
        require(input.isFile && input.length() > 0L) { "Input video is missing or empty." }
        require(cues.isNotEmpty()) { "There are no captions to burn in." }
        val subtitle = File(context.cacheDir, "captions_${System.currentTimeMillis()}.srt")
        try {
            subtitle.writeText(CaptionTrack.toSrt(cues), Charsets.UTF_8)
            val style = when (preset) {
                CaptionPreset.CLEAN -> "FontName=Arial,FontSize=18,PrimaryColour=&H00FFFFFF,OutlineColour=&H80000000,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=48"
                CaptionPreset.BOLD -> "FontName=Arial,FontSize=22,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Shadow=1,Alignment=2,MarginV=52"
                CaptionPreset.KARAOKE -> "FontName=Arial,FontSize=21,Bold=1,PrimaryColour=&H0000FFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Alignment=2,MarginV=52"
                CaptionPreset.BOXED -> "FontName=Arial,FontSize=19,Bold=1,PrimaryColour=&H00FFFFFF,BackColour=&H90000000,BorderStyle=3,Outline=1,Alignment=2,MarginV=48"
            }
            val command = buildCommand(input.absolutePath, subtitle.absolutePath, output.absolutePath, style)
            FFmpegKit.executeAsync(command) { session ->
                subtitle.delete()
                val success = ReturnCode.isSuccess(session.returnCode)
                onFinished(success, if (success) "Captions burned into video." else
                    "Caption rendering failed: ${session.failStackTrace ?: session.returnCode}")
            }
        } catch (error: Exception) {
            subtitle.delete()
            onFinished(false, error.localizedMessage ?: "Could not prepare captions.")
        }
    }

    internal fun buildCommand(input: String, subtitle: String, output: String, style: String): String {
        fun quote(value: String) = "'" + value.replace("\\", "\\\\")
            .replace(":", "\\:").replace("'", "\\'") + "'"
        val filter = "subtitles=filename=${quote(subtitle)}:force_style=${quote(style)}"
        return "-y -i ${quote(input)} -vf ${quote(filter)} -c:v libx264 -preset ultrafast -crf 23 -c:a copy ${quote(output)}"
    }
}
