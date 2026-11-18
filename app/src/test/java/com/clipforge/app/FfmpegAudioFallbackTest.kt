package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAudioFallbackTest {
    @Test
    fun buildsArgumentArrayWithoutShellQuotingOrCommandConcatenation() {
        val args = FfmpegAudioFallback.buildArguments(
            "/cache/input with spaces.mp4",
            "/cache/output with spaces.pcm"
        )

        assertEquals("/cache/input with spaces.mp4", args[args.indexOf("-i") + 1])
        assertEquals("/cache/output with spaces.pcm", args.last())
        assertTrue(args.containsAll(listOf("-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_f32be", "-f", "f32be")))
        assertTrue(args.none { it.contains("&&") || it.contains(";") })
    }

    @Test
    fun selectsOnlyFirstAudioTrackAndDisablesVideo() {
        val args = FfmpegAudioFallback.buildArguments("input.mp4", "output.pcm")
        assertEquals("0:a:0", args[args.indexOf("-map") + 1])
        assertTrue(args.contains("-vn"))
    }
}
