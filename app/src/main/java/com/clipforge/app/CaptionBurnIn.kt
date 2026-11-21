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
        alignedWords: List<TimedWord> = emptyList(),
        clipDurationMs: Long = 0L,
        aspectRatio: Float = 9f / 16f,
        onFinished: (Boolean, String) -> Unit
    ) {
        val wordAligned = preset == CaptionPreset.KARAOKE && alignedWords.isNotEmpty() && clipDurationMs > 0L
        val subtitle = File(context.cacheDir, "captions_${System.nanoTime()}.${if (wordAligned) "ass" else "srt"}")
        try {
            require(input.isFile && input.length() > 0L) { "Input video is missing or empty." }
            require(cues.isNotEmpty() || wordAligned) { "There are no captions to burn in." }
            subtitle.writeText(if (wordAligned) AssKaraokeTrack.render(alignedWords, clipDurationMs, aspectRatio = aspectRatio) else CaptionTrack.toSrt(cues), Charsets.UTF_8)
            val style = when (preset) {
                CaptionPreset.CLEAN -> "FontName=Arial,FontSize=18,PrimaryColour=&H00FFFFFF,OutlineColour=&H80000000,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=48"
                CaptionPreset.BOLD -> "FontName=Arial,FontSize=22,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Shadow=1,Alignment=2,MarginV=52"
                CaptionPreset.KARAOKE -> "FontName=Arial,FontSize=21,Bold=1,PrimaryColour=&H0000FFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Alignment=2,MarginV=52"
                CaptionPreset.BOXED -> "FontName=Arial,FontSize=19,Bold=1,PrimaryColour=&H00FFFFFF,BackColour=&H90000000,BorderStyle=3,Outline=1,Alignment=2,MarginV=48"
            }
            val arguments = buildArguments(input.absolutePath, subtitle.absolutePath, output.absolutePath, style.takeUnless { wordAligned })
            FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
                subtitle.delete()
                val success = ReturnCode.isSuccess(session.returnCode) && output.isFile && output.length() > 0L
                if (success) {
                    onFinished(true, "Captions burned into video.")
                } else {
                    val detail = session.output?.trim()?.takeIf { it.isNotEmpty() }
                        ?: session.failStackTrace ?: "No video was produced (FFmpeg ${session.returnCode})."
                    onFinished(false, "Caption rendering failed: $detail")
                }
            }
        } catch (error: Exception) {
            subtitle.delete()
            onFinished(false, error.localizedMessage ?: "Could not prepare captions.")
        }
    }

    internal fun buildArguments(input: String, subtitle: String, output: String, style: String?): Array<String> {
        fun escape(value: String, specialCharacters: String) = buildString {
            for (character in value) {
                if (character in specialCharacters) append('\\')
                append(character)
            }
        }

        // FFmpeg parses filtergraph separators, then parses each filter's options.
        fun filterOption(value: String) = escape(escape(value, "\\':"), "\\'[],;")

        val forcedStyle = style?.let { ":force_style=${filterOption(it)}" }.orEmpty()
        val filter = "subtitles=filename=${filterOption(subtitle)}:fontsdir=/system/fonts$forcedStyle," +
            "pad=ceil(iw/2)*2:ceil(ih/2)*2"
        return arrayOf(
            "-y", "-loglevel", "error", "-i", input, "-vf", filter,
            "-c:v", "libopenh264", "-b:v", "4M", "-pix_fmt", "yuv420p",
            "-c:a", "copy", "-movflags", "+faststart", output
        )
    }
}
