package com.clipforge.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchExportPreflightInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = Uri.parse("content://clipforge.invalid/tree/test")
    private val source = Uri.parse("content://clipforge.invalid/video")

    @Test fun duplicateRangesAreRejectedBeforeWritingDocuments() {
        val clips = listOf(
            ClipCandidate(1_000L, 20_000L, "one", "", 80),
            ClipCandidate(1_000L, 20_000L, "two", "", 75)
        )
        try {
            runBlocking { BatchClipExporter.exportAll(context, folder, source, clips) }
            fail("Duplicate ranges must fail preflight")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Duplicate"))
        }
    }

    @Test fun destinationCanBeRecoveredFromTheJournalAndRemovedAfterFailure() {
        val file = File(context.cacheDir, "batch-journal-test-${System.nanoTime()}.json")
        val clip = ClipCandidate(0L, 30_000L, "Highlight", "", 80)
        val output = Uri.parse("content://clipforge.test/document/result.mp4")
        try {
            BatchExportJournal.load(file).record(clip, output)
            val resumed = BatchExportJournal.load(file)
            assertEquals(output, resumed.destination(clip))
            assertNull(resumed.destination(clip.copy(startMs = 1000L)))
            resumed.forget(clip)
            assertNull(BatchExportJournal.load(file).destination(clip))
        } finally {
            file.delete()
        }
    }

    @Test fun oversizedBatchIsRejectedBeforeWritingDocuments() {
        val clips = (0..BatchClipExporter.MAX_CLIPS_PER_BATCH).map { index ->
            ClipCandidate(index * 30000L, (index + 1) * 30000L, "Clip $index", "", 70)
        }
        val result = runCatching { runBlocking { BatchClipExporter.exportAll(context, folder, source, clips) } }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun invalidRangesAreRejectedBeforeWritingDocuments() {
        try {
            runBlocking {
                BatchClipExporter.exportAll(
                    context, folder, source, listOf(ClipCandidate(20_000L, 1_000L, "bad", "", 40))
                )
            }
            fail("Invalid ranges must fail preflight")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("valid start"))
        }
    }
}
