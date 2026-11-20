package com.clipforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranscriptCheckpointInstrumentedTest {
    @Test fun completedChunksSurviveReopenAndEmptyChunksCountAsComplete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "checkpoint-test-${System.nanoTime()}.json")
        try {
            val checkpoint = TranscriptCheckpoint(file, "same-video").load()
            checkpoint.save(0, listOf(TranscriptSegment(100, 900, "hello")))
            checkpoint.save(1, emptyList())
            val resumed = TranscriptCheckpoint(file, "same-video").load()
            assertTrue(resumed.hasChunk(0))
            assertTrue(resumed.hasChunk(1))
            assertEquals("hello", resumed.segments().single().text)
            assertFalse(resumed.hasChunk(2))
        } finally {
            file.delete()
        }
    }

    @Test fun fingerprintMismatchNeverRestoresOldChunks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "checkpoint-fingerprint-${System.nanoTime()}.json")
        try {
            TranscriptCheckpoint(file, "old-video").save(0, listOf(TranscriptSegment(0, 100, "old")))
            assertFalse(TranscriptCheckpoint(file, "new-video").load().hasChunk(0))
        } finally {
            file.delete()
        }
    }
    @Test fun failedWriteDoesNotMarkChunkCompleteInMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "checkpoint-unwritable-${System.nanoTime()}.json")
        assertTrue(directory.mkdir())
        try {
            val checkpoint = TranscriptCheckpoint(directory, "test-video")
            var failed = false
            try {
                checkpoint.save(0, listOf(TranscriptSegment(0, 100, "unsaved")))
            } catch (_: Exception) {
                failed = true
            }
            assertTrue("Writing checkpoint to a directory must fail", failed)
            assertFalse("Failed checkpoint must be retried", checkpoint.hasChunk(0))
        } finally {
            directory.deleteRecursively()
            File(context.cacheDir, directory.name + ".new").delete()
            File(context.cacheDir, directory.name + ".bak").delete()
        }
    }

}
