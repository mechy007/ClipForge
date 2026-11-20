package com.clipforge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YouTubeDownloadValidationTest {
    @Test fun forbiddenErrorsAreRecognized() {
        assertTrue(YouTubeDownloadValidation.isForbidden("HTTP Error 403: Forbidden"))
        assertTrue(YouTubeDownloadValidation.isForbidden("server returned FORBIDDEN"))
        assertFalse(YouTubeDownloadValidation.isForbidden("connection timed out"))
    }

    @Test fun missingEmptyAndTruncatedDownloadsAreRejected() {
        val missing = File.createTempFile("clipforge-missing-", ".mp4")
        missing.delete()
        assertFalse(YouTubeDownloadValidation.isValidMp4(missing))
        val truncated = File.createTempFile("clipforge-truncated-", ".mp4")
        try {
            truncated.writeBytes(byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112))
            assertFalse(YouTubeDownloadValidation.isValidMp4(truncated))
            truncated.writeBytes("not an mp4 video".toByteArray())
            assertFalse(YouTubeDownloadValidation.isValidMp4(truncated))
        } finally {
            truncated.delete()
        }
    }

    @Test fun completeFileTypeBoxIsAccepted() {
        val mp4 = File.createTempFile("clipforge-header-", ".mp4")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 12) + "ftypisom".toByteArray())
            assertTrue(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }
}
