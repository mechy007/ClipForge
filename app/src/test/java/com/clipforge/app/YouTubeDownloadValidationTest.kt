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

    @Test fun headerOnlyFileIsRejected() {
        val mp4 = File.createTempFile("clipforge-header-only-", ".mp4")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 12) + "ftypisom".toByteArray())
            assertFalse(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }

    @Test fun completeFileTypeBoxIsAccepted() {
        val mp4 = File.createTempFile("clipforge-header-", ".mp4")
        try {
            mp4.writeBytes(byteArrayOf(0, 0, 0, 12) + "ftypisom".toByteArray() +
                byteArrayOf(0, 0, 0, 12) + "mdatdata".toByteArray() +
                byteArrayOf(0, 0, 0, 12) + "moovmeta".toByteArray())
            assertTrue(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }
    @Test fun incompleteOrEmptyMediaBoxNeverCountsAsReady() {
        val mp4 = File.createTempFile("clipforge-incomplete-", ".mp4")
        try {
            val ftyp = byteArrayOf(0, 0, 0, 12) + "ftypisom".toByteArray()
            val moov = byteArrayOf(0, 0, 0, 12) + "moovmeta".toByteArray()
            val emptyMdat = byteArrayOf(0, 0, 0, 8) + "mdat".toByteArray()
            mp4.writeBytes(ftyp + emptyMdat + moov)
            assertFalse(YouTubeDownloadValidation.isValidMp4(mp4))
            val truncatedMdat = byteArrayOf(0, 0, 0, 24) + "mdatdata".toByteArray()
            mp4.writeBytes(ftyp + truncatedMdat + moov)
            assertFalse(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }

    @Test fun extendedSizeMp4BoxesAreAccepted() {
        val mp4 = File.createTempFile("clipforge-extended-", ".mp4")
        try {
            fun extendedBox(type: String, payload: String): ByteArray {
                val bytes = payload.toByteArray(Charsets.US_ASCII)
                val size = (16L + bytes.size).toLong()
                return java.nio.ByteBuffer.allocate(16 + bytes.size)
                    .putInt(1).put(type.toByteArray(Charsets.US_ASCII))
                    .putLong(size).put(bytes).array()
            }
            mp4.writeBytes(
                extendedBox("ftyp", "isom") +
                    extendedBox("mdat", "data") +
                    extendedBox("moov", "meta")
            )
            assertTrue(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }

    @Test fun malformedExtendedBoxSizeIsRejected() {
        val mp4 = File.createTempFile("clipforge-bad-extended-", ".mp4")
        try {
            val invalid = java.nio.ByteBuffer.allocate(20)
                .putInt(1).put("ftyp".toByteArray(Charsets.US_ASCII))
                .putLong(Long.MAX_VALUE).put("isom".toByteArray(Charsets.US_ASCII))
                .array()
            mp4.writeBytes(invalid)
            assertFalse(YouTubeDownloadValidation.isValidMp4(mp4))
        } finally {
            mp4.delete()
        }
    }

}
