package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OfflineSpeechBackendsTest {
    @Test fun bundledWhisperIsDefaultLocalBackend() {
        assertSame(BundledWhisperBackend, OfflineSpeechBackends.default)
        assertEquals("whisper-tiny-multilingual", OfflineSpeechBackends.default.id)
    }
}
