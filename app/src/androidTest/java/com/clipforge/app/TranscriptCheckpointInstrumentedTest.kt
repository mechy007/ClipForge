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
    @Test
    fun alignedWordsSurviveCheckpointReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("word-checkpoint-", ".json", context.cacheDir)
        try {
            val speech = TranscriptSegment(10L, 900L, "hello world", listOf(
                TimedWord(20L, 380L, "hello"), TimedWord(400L, 890L, "world")
            ))
            TranscriptCheckpoint(file, "word-timing-test").save(0, listOf(speech))
            val restored = TranscriptCheckpoint(file, "word-timing-test").load()
            assertEquals(listOf(speech), restored.segments())
        } finally {
            file.delete()
        }
    }

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
        val directory = File(context.cacheDir, "checkpoint-unwritable-${System.nanoTime()}")
        assertTrue(directory.createNewFile())
        val target = File(directory, "checkpoint.json")
        try {
            val checkpoint = TranscriptCheckpoint(target, "test-video")
            var failed = false
            try {
                checkpoint.save(0, listOf(TranscriptSegment(0, 100, "unsaved")))
            } catch (_: Exception) {
                failed = true
            }
            assertTrue("Writing beneath a regular file must fail", failed)
            assertFalse("Failed checkpoint must be retried", checkpoint.hasChunk(0))
        } finally {
            directory.delete()
        }
    }

    @Test fun interruptedWriteRestoresCommittedBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "checkpoint-backup-${System.nanoTime()}.json")
        try {
            TranscriptCheckpoint(file, "same-video").save(0, listOf(TranscriptSegment(0, 100, "saved")))
            val backup = File(file.path + ".bak")
            assertTrue(file.renameTo(backup))
            file.writeText("{truncated")
            val resumed = TranscriptCheckpoint(file, "same-video").load()
            assertTrue("Committed chunk must survive interruption", resumed.hasChunk(0))
            assertEquals("saved", resumed.segments().single().text)
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }
}
