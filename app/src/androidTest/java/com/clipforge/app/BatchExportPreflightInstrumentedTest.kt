package com.clipforge.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
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
