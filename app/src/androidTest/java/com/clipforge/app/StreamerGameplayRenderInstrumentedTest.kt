package com.clipforge.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StreamerGameplayRenderInstrumentedTest {
    @Test
    fun rendersTrimmedStreamerAboveGameplayWithAudioInAllCorners() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "split-fixture-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (top in listOf(true, false)) for (left in listOf(true, false)) {
                val x = if (left) 0 else 480
                val y = if (top) 0 else 216
                val source = File(directory, "source.mp4")
                val output = File(directory, "split.mp4")
                encode(
                    "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=15:d=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                    "-vf", "drawbox=x=$x:y=$y:w=160:h=144:color=red:t=fill",
                    "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                    source.absolutePath
                )
                val region = StreamRegion(x / 640f, y / 360f, (x + 160) / 640f, (y + 144) / 360f)
                StreamerGameplayRenderer.render(context, Uri.fromFile(source), output, 400L, 1400L, region)
                assertPanels(output)
                EncodedClipVerifier.verify(context, Uri.fromFile(output), 1000L, 9f / 16f, true, split = true)
                assertTrue(runCatching {
                    EncodedClipVerifier.verify(context, Uri.fromFile(output), 1000L, 1f, true)
                }.isFailure)
                assertTrue(runCatching { StreamerGameplayRenderer.verify(output, 2000L, true) }.isFailure)
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun rejectsCorruptFilesAndVerifiesSilentInputWithCaptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "split-silent-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "source.mp4")
            val output = File(directory, "split.mp4")
            output.writeText("This file has no video track.")
            assertTrue(runCatching { StreamerGameplayRenderer.verify(output, 1000L, false) }.isFailure)
            encode(
                "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=15:d=1",
                "-vf", "drawbox=x=0:y=0:w=160:h=144:color=red:t=fill",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", source.absolutePath
            )
            StreamerGameplayRenderer.render(
                context, Uri.fromFile(source), output, 0L, 1000L, StreamRegion(0f, 0f, 0.25f, 0.4f),
                listOf(CaptionCue(100L, 850L, "Caption preview")), CaptionPreset.BOLD
            )
            assertPanels(output)
            assertTrue(runCatching { StreamerGameplayRenderer.verify(output, 1000L, true) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun acceptsStableBoundsAndRejectsCompetingCorners() {
        val face = StreamRegion(0.10f, 0.10f, 0.18f, 0.23f)
        val competitor = StreamRegion(0.82f, 0.10f, 0.90f, 0.23f)
        val stable = List(4) { face }
        assertNull(StreamFacecamDetector.proposeRegion(listOf(stable, List(3) { competitor })))
        val region = checkNotNull(StreamFacecamDetector.proposeRegion(listOf(stable, listOf(competitor))))
        assertTrue(region.left < face.left && region.right > face.right)
        assertTrue(region.top < face.top && region.bottom > face.bottom)
    }

    private fun encode(vararg args: String) {
        val session = FFmpegKit.executeWithArguments(arrayOf(*args))
        assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
    }

    private fun assertPanels(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = checkNotNull(retriever.getScaledFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST, 54, 96))
            try {
                val top = frame.getPixel(27, 24)
                val bottom = frame.getPixel(27, 72)
                assertTrue("Top panel should show the red facecam", Color.red(top) > 170 && Color.blue(top) < 80)
                assertTrue("Bottom panel should show the blue gameplay", Color.blue(bottom) > 170 && Color.red(bottom) < 80)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }
}
