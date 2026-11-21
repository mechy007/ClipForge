package com.clipforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProgressiveAnalysisInstrumentedTest {
    private fun directory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "progressive-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test fun persistedSignalsRestoreAfterRestartAndRejectWrongFingerprint() {
        val dir = directory()
        try {
            val segment = ProgressiveSegment(0, 0L, 60_000L)
            val original = ProgressiveSignalStore(dir, "source-A")
            ProgressiveSignalIntegration.persist(
                original,
                ProgressiveTranscriptionSignals(
                    segment,
                    listOf(TranscriptSegment(100L, 700L, "amazing reaction")),
                    listOf(AudioEnergyWindow(100L, 700L, 0.4f, 0.9f))
                )
            )
            val restored = ProgressiveSignalIntegration.restore(
                ProgressiveSignalStore(dir, "source-A"), setOf(0)
            )
            assertEquals(1, restored.transcript.size)
            assertEquals("amazing reaction", restored.transcript.single().text)
            assertEquals(0.9f, restored.audioEnergy.single().peak, 0f)
            assertNull(ProgressiveSignalStore(dir, "source-B").read(0))
            try {
                ProgressiveSignalIntegration.restore(ProgressiveSignalStore(dir, "source-B"), setOf(0))
                fail("Cross-source restore must fail closed")
            } catch (_: IllegalArgumentException) {
                // Missing snapshots are rejected.
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteSnapshotsFailClosedAndFloatOverflowIsRejected() {
        val dir = directory()
        try {
            val store = ProgressiveSignalStore(dir, "source")
            store.write(0, org.json.JSONObject()
                .put("startMs", 0L).put("endMs", 60_000L)
                .put("transcript", org.json.JSONArray())
                .put("audioEnergy", org.json.JSONArray().put(org.json.JSONObject()
                    .put("startMs", 0L).put("endMs", 100L)
                    .put("rms", 1e100).put("peak", 1.0))))
            try {
                ProgressiveSignalIntegration.restore(store, setOf(0))
                fail("Overflowed Float energy must be rejected")
            } catch (_: IllegalArgumentException) { }
            try {
                ProgressiveSignalIntegration.restore(store, setOf(0, 1))
                fail("Missing segment must be rejected")
            } catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

    @Test fun checkpointReopensAndOnlyAcknowledgesFinalizedSegments() {
        val dir = directory()
        try {
            val path = File(dir, "checkpoint.json")
            val checkpoint = ProgressiveSegmentCheckpoint(path, "source").load()
            checkpoint.markFinalized(0)
            checkpoint.markProcessed(0)
            assertEquals(setOf(0), ProgressiveSegmentCheckpoint(path, "source").load().processedIndices())
            assertTrue(ProgressiveSegmentCheckpoint(path, "other").load().processedIndices().isEmpty())
            try {
                checkpoint.markProcessed(1)
                fail("Unfinalized segment must not be acknowledged")
            } catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

    @Test fun localSourceRejectsDuplicateAndMissingFiles() {
        val segment = ProgressiveSegment(0, 0L, 1_000L)
        val missing = File("/nonexistent-clipforge-test-${System.nanoTime()}.mp4")
        val item = FinalizedMediaSegment(segment, missing)
        try {
            FinalizedLocalSegmentSource(listOf(item, item))
            fail("Duplicate segment index must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            FinalizedLocalSegmentSource.fromFiles(1_000L, mapOf(1 to missing))
            fail("Out-of-range segment index must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun incompleteSourceNeverReturnsPartialHighlights() {
        val dir = directory()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = ProgressiveAnalysisSession(context, "incomplete-source", dir)
            try {
                kotlinx.coroutines.runBlocking {
                    session.analyze(FinalizedLocalSegmentSource(emptyList()), 120_000L)
                }
                fail("An incomplete source must not return partial highlight results")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("incomplete"))
            }
            assertTrue(session.completedSegments().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun restoredProcessedSegmentsRequireDurableSignals() {
        val dir = directory()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fingerprint = "missing-snapshot"
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val checkpoint = ProgressiveSegmentCheckpoint(
                File(dir, "progressive-checkpoint-$digest.json"), fingerprint
            ).load()
            checkpoint.markFinalized(0)
            checkpoint.markProcessed(0)
            val session = ProgressiveAnalysisSession(context, fingerprint, dir)
            try {
                kotlinx.coroutines.runBlocking {
                    session.analyze(FinalizedLocalSegmentSource(emptyList()), 60_000L)
                }
                fail("A processed checkpoint without its snapshot must fail")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Missing signal snapshot"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun localFileEntryPointRejectsMissingSegmentsAndInvalidIndices() {
        val dir = directory()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = ProgressiveAnalysisSession(context, "local-file-entry", dir)
            try {
                kotlinx.coroutines.runBlocking {
                    session.analyzeFinalizedFiles(120_000L, emptyMap())
                }
                fail("Missing finalized media segments must fail closed")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("incomplete"))
            }
            try {
                kotlinx.coroutines.runBlocking {
                    session.analyzeFinalizedFiles(60_000L, mapOf(3 to File(dir, "missing.mp4")))
                }
                fail("Unexpected segment indices must be rejected")
            } catch (_: IllegalArgumentException) { }
        } finally { dir.deleteRecursively() }
    }

}
