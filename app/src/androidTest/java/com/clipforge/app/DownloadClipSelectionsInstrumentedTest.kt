package com.clipforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadClipSelectionsInstrumentedTest {
    @Test fun reopeningRetainsTheLatestTrimForASelectedMoment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("download-review-test", 0)
        val key = "example-${System.nanoTime()}"
        try {
            val initial = ClipCandidate(10_000, 25_000, "Reaction", "Wow!", 75)
            val review = DownloadClipSelections(prefs, key)
            review.add(initial)
            review.add(initial.copy(startMs = 12_000, endMs = 24_000))
            val restored = DownloadClipSelections(prefs, key)
            assertEquals(12_000L, restored.selected().single().startMs)
            assertEquals(24_000L, restored.selected().single().endMs)
            restored.clear()
            assertTrue(DownloadClipSelections(prefs, key).selected().isEmpty())
        } finally {
            prefs.edit().remove(key).apply()
        }
    }
}
