package com.clipforge.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineSpeechInstrumentedTest {
    @Test
    fun transcribesRealSpeechAndReusesWordTimingCheckpoint() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("clipforge-speech-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("speech-fixture.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val source = Uri.fromFile(file)
            val speech = VideoTranscriber.transcribeWithSignals(context, source, "en")
            assertTrue(speech.segments.joinToString(" ") { it.text }.contains("country", ignoreCase = true))
            assertTrue(speech.segments.flatMap { it.words }.size >= 6)
            assertTrue(speech.audioEnergy.isNotEmpty())

            val messages = mutableListOf<String>()
            val restored = VideoTranscriber.transcribeWithSignals(context, source, "en") { messages += it }
            assertEquals(speech.segments, restored.segments)
            assertTrue(messages.any { it.contains("Restored transcription") })
            assertTrue(messages.none { it.startsWith("Transcribing audio · part") })
        } finally {
            file.delete()
        }
        Unit
    }
}
