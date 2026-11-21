package com.clipforge.app

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BatchExportInstrumentedTest {
    @Test
    fun standardExportProducesPlayablePortraitSquareAndLandscapeMp4s() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("standard-export-", ".mp4", context.cacheDir)
        val output = File.createTempFile("standard-clip-", ".mp4", context.cacheDir)
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=15:d=2",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", source.absolutePath
            ))
            assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
            for (aspect in listOf(9f / 16f, 1f, 16f / 9f)) {
                val result = withContext(Dispatchers.Main) {
                    suspendCoroutine<Pair<Boolean, String>> { continuation ->
                        ClipExporter.export(context, Uri.fromFile(source), Uri.fromFile(output), 0L, 1200L,
                            aspectRatio = aspect, onStatus = {}, onFinished = { success, message ->
                                continuation.resume(success to message)
                            })
                    }
                }
                assertTrue("Aspect $aspect: ${result.second}", result.first)
                EncodedClipVerifier.verify(context, Uri.fromFile(output), 1200L, aspect, false, decodeFrame = true)
            }
        } finally {
            source.delete()
            output.delete()
        }
        Unit
    }

    @Test
    fun exportsTenRealMp4sAcrossFailureAndRetryWithoutRecreatingVerifiedDocuments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("batch-smoke-", ".mp4", context.cacheDir)
        val authority = BatchExportTestProvider.AUTHORITY
        val runId = "test-${System.nanoTime()}"
        val folder = DocumentsContract.buildTreeDocumentUri(authority, runId)
        val resolver = context.contentResolver
        val provider = requireNotNull(resolver.acquireContentProviderClient(authority)) { "Export fixture provider is unavailable." }
        val clips = (0 until 10).map { index ->
            val start = index * 1000L
            ClipCandidate(start, start + 900L, if (index == 4) "blocked" else "Clip $index", "", 60)
        }
        val split = StreamRegion(0f, 0f, 0.25f, 0.4f)
        try {
            val session = FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=s=320x180:r=12:d=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-c:a", "aac", source.absolutePath
            ))
            assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
            val completed = mutableListOf<ClipCandidate>()
            val first = runCatching {
                BatchClipExporter.exportAll(context, folder, Uri.fromFile(source), clips, splitFacecamRegion = split) { _, _, clip ->
                    completed += clip
                }
            }
            assertTrue(first.exceptionOrNull()?.message, first.isFailure)
            assertEquals(9, completed.size)
            assertFalse(clips[4] in completed)
            assertEquals(9, provider.call("fixture:count", runId, Bundle())?.getInt("outputs"))

            provider.call("fixture:allow", runId, Bundle())
            assertEquals(10, BatchClipExporter.exportAll(context, folder, Uri.fromFile(source), clips, splitFacecamRegion = split))
            assertEquals(10, provider.call("fixture:count", runId, Bundle())?.getInt("outputs"))
        } finally {
            provider.call("fixture:cleanup", runId, Bundle())
            provider.close()
            source.delete()
        }
        Unit
    }
}
