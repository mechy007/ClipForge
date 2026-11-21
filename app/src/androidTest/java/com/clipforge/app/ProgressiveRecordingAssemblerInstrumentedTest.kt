package com.clipforge.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProgressiveRecordingAssemblerInstrumentedTest {
    @Test
    fun joinsSectionsOnTheirOriginalTimelineAndRejectsDrift() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "progressive-recording-${System.nanoTime()}").apply { mkdirs() }
        try {
            for ((index, color) in listOf("red", "blue").withIndex()) {
                val session = FFmpegKit.executeWithArguments(arrayOf(
                    "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=$color:s=320x180:r=15:d=1",
                    "-c:v", "libopenh264", "-pix_fmt", "yuv420p", File(directory, "segment-$index.mp4").absolutePath
                ))
                assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
            }
            val destination = File(directory, "joined.mp4")
            val planned = listOf(ProgressiveSegment(0, 0, 1000), ProgressiveSegment(1, 1000, 2000))
            val duration = ProgressiveRecordingAssembler.assemble(planned, directory, destination)
            assertTrue(duration in 1900L..2100L)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(destination.absolutePath)
                val first = checkNotNull(retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST))
                val second = checkNotNull(retriever.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertTrue(Color.red(first.getPixel(20, 20)) > 170)
                    assertTrue(Color.blue(second.getPixel(20, 20)) > 170)
                } finally {
                    first.recycle()
                    second.recycle()
                }
            } finally {
                retriever.release()
            }
            val rejected = File(directory, "rejected.mp4")
            val shifted = listOf(ProgressiveSegment(0, 0, 1600), ProgressiveSegment(1, 1600, 2000))
            assertTrue(runCatching { ProgressiveRecordingAssembler.assemble(shifted, directory, rejected) }.isFailure)
            assertFalse(rejected.exists())
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }
}
