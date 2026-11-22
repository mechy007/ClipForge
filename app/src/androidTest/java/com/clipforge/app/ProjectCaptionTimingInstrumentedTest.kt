package com.clipforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectCaptionTimingInstrumentedTest {
    @Test
    fun savedProjectRestoresCompleteTimingAndPreservesUntimedText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timed = TranscriptSegment(100, 600, "Go now", listOf(
            TimedWord(100, 300, "Go"),
            TimedWord(310, 600, "now")
        ))
        val incomplete = TranscriptSegment(700, 1_500, "Stay here", listOf(TimedWord(700, 950, "Stay")))
        val untimed = TranscriptSegment(1_600, 2_000, "Older captions")
        val projectId = ProjectDatabase(context).use { database ->
            database.saveProject("Caption timing test", "file:///caption-timing-test.mp4", 2_000)
        }
        try {
            ProjectDatabase(context).use { database ->
                database.saveTranscript(projectId, listOf(timed, incomplete, untimed))
            }
            ProjectDatabase(context).use { database ->
                assertEquals(
                    listOf(timed, incomplete.copy(words = emptyList()), untimed),
                    database.listTranscript(projectId)
                )
            }
        } finally {
            ProjectDatabase(context).use { it.deleteProject(projectId) }
        }
    }
}
