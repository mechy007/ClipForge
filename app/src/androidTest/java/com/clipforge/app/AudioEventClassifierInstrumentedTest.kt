package com.clipforge.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEventClassifierInstrumentedTest {
    @Test
    fun bundledModelAnalyzesOfflinePcm() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pcm = File(context.cacheDir, "yamnet-smoke-${System.nanoTime()}.pcm")
        try {
            DataOutputStream(pcm.outputStream().buffered()).use { output ->
                repeat(16_000 * 2) { output.writeFloat(0f) }
            }

            val events = AudioEventClassifier.analyze(context, pcm)

            assertEquals(2, events.size)
            assertTrue(events.all { it.startMs >= 0 && it.endMs > it.startMs })
            assertTrue(events.all {
                it.laughter in 0f..1f && it.shouting in 0f..1f && it.cheering in 0f..1f
            })
        } finally {
            pcm.delete()
        }
    }
}
