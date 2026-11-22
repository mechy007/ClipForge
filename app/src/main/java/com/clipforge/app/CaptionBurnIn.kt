package com.clipforge.app

import android.content.Context
import android.media.MediaMetadataRetriever
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
        val wordAligned = preset.animated && cues.any { it.words.isNotEmpty() }
        val subtitle = File(context.cacheDir, "captions_${System.nanoTime()}.${if (wordAligned) "ass" else "srt"}")
        try {
            require(input.isFile && input.length() > 0L) { "Input video is missing or empty." }
            require(cues.isNotEmpty()) { "There are no captions to burn in." }
            val style = if (wordAligned) {
                val (width, height) = videoSize(input)
                subtitle.writeText(AssKaraokeTrack.render(cues, preset, width, height), Charsets.UTF_8)
                null
            } else {
                subtitle.writeText(CaptionTrack.toSrt(cues), Charsets.UTF_8)
                when (preset) {
                    CaptionPreset.CLEAN -> "FontName=Arial,FontSize=18,PrimaryColour=&H00FFFFFF,OutlineColour=&H80000000,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=48"
                    CaptionPreset.BOLD, CaptionPreset.KARAOKE, CaptionPreset.POP -> "FontName=Arial,FontSize=22,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Shadow=1,Alignment=2,MarginV=52"
                    CaptionPreset.BOXED -> "FontName=Arial,FontSize=19,Bold=1,PrimaryColour=&H00FFFFFF,BackColour=&H90000000,BorderStyle=3,Outline=1,Alignment=2,MarginV=48"
                }
            }
            val arguments = buildArguments(input.absolutePath, subtitle.absolutePath, output.absolutePath, style)
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

    private fun videoSize(input: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            require(width != null && height != null && width > 0 && height > 0) {
                "Could not read the rendered video size."
            }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            return if (rotation == 90 || rotation == 270) height to width else width to height
        } finally {
            retriever.release()
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

        val styleOption = style?.let { ":force_style=${filterOption(it)}" }.orEmpty()
        val filter = "subtitles=filename=${filterOption(subtitle)}:fontsdir=/system/fonts$styleOption," +
            "pad=ceil(iw/2)*2:ceil(ih/2)*2"
        return arrayOf(
            "-y", "-loglevel", "error", "-i", input, "-vf", filter,
            "-c:v", "libopenh264", "-b:v", "4M", "-pix_fmt", "yuv420p",
            "-c:a", "copy", "-movflags", "+faststart", output
        )
    }
}
