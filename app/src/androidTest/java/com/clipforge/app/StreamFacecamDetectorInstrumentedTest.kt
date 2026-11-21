package com.clipforge.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StreamFacecamDetectorInstrumentedTest {
    @Test
    fun findsPhotoInsetInFourCornersAndLeavesNoFaceUnselected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "facecam-detector-${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = File(directory, "portrait.jpg")
            instrumentation.context.assets.open("facecam-portrait.jpg").use { input ->
                image.outputStream().use { input.copyTo(it) }
            }
            val source = File(directory, "source.mp4")
            for (top in listOf(true, false)) for (left in listOf(true, false)) {
                val x = if (left) 0 else 490
                val y = if (top) 0 else 210
                encode(
                    "-f", "lavfi", "-i", "testsrc2=s=640x360:r=12:d=2",
                    "-i", image.absolutePath, "-filter_complex", "[0:v][1:v]overlay=$x:$y",
                    source.absolutePath
                )
                val region = StreamFacecamDetector.detect(context, Uri.fromFile(source), 2000L)
                assertNotNull("Facecam should be detected at ($x, $y)", region)
                val rect = checkNotNull(region)
                assertTrue("Facecam horizontal position is incorrect", (rect.left + rect.right < 1f) == left)
                assertTrue("Facecam vertical position is incorrect", (rect.top + rect.bottom < 1f) == top)
            }

            encode("-f", "lavfi", "-i", "color=c=blue:s=640x360:r=12:d=2", source.absolutePath)
            assertNull(StreamFacecamDetector.detect(context, Uri.fromFile(source), 2000L))
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    private fun encode(vararg args: String) {
        val source = args.last()
        val arguments = arrayOf("-y", "-loglevel", "error") + args.dropLast(1) + arrayOf(
            "-c:v", "libopenh264", "-pix_fmt", "yuv420p", source
        )
        val session = FFmpegKit.executeWithArguments(arguments)
        assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
    }
}
