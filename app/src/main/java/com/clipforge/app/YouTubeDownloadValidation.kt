package com.clipforge.app

import java.io.File
import java.io.FileInputStream

/** Conservative validation: a successful process exit alone is not a completed import. */
internal object YouTubeDownloadValidation {
    fun isForbidden(message: String): Boolean =
        message.contains("403") || message.contains("forbidden", ignoreCase = true)

    fun isValidMp4(file: File): Boolean {
        if (!file.isFile || file.length() < 12L) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                if (input.read(header) != header.size) return false
                // ISO BMFF file-type box: size (4 bytes), then 'ftyp'.
                val boxSize = ((header[0].toLong() and 255) shl 24) or
                    ((header[1].toLong() and 255) shl 16) or
                    ((header[2].toLong() and 255) shl 8) or
                    (header[3].toLong() and 255)
                boxSize >= 8 && boxSize <= file.length() &&
                    header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))
            }
        } catch (_: java.io.IOException) {
            false
        }
    }
}
