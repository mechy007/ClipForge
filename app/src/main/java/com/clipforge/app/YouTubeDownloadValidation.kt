package com.clipforge.app

import java.io.File
import java.io.RandomAccessFile

/** Conservative validation: a successful process exit alone is not a completed import. */
internal object YouTubeDownloadValidation {
    fun isForbidden(message: String): Boolean =
        message.contains("403") || message.contains("forbidden", ignoreCase = true)

    fun isValidMp4(file: File): Boolean {
        if (!file.isFile || file.length() < 24L) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                var offset = 0L
                var boxCount = 0
                var ftyp = false
                var media = false
                var metadata = false
                while (offset <= length - 8 && boxCount++ < 100_000) {
                    input.seek(offset)
                    val size32 = input.readInt().toLong() and 0xffffffffL
                    val nameBytes = ByteArray(4)
                    input.readFully(nameBytes)
                    val name = String(nameBytes, Charsets.US_ASCII)
                    val header = if (size32 == 1L) 16L else 8L
                    if (length - offset < header) return false
                    val size = when (size32) {
                        0L -> length - offset
                        1L -> input.readLong()
                        else -> size32
                    }
                    if (size < header || size > length - offset) return false
                    if (offset == 0L && name != "ftyp") return false
                    when (name) {
                        "ftyp" -> ftyp = true
                        "mdat" -> if (size > header) media = true
                        "moov", "moof" -> if (size > header) metadata = true
                    }
                    offset += size
                }
                offset == length && ftyp && media && metadata
            }
        } catch (_: java.io.IOException) {
            false
        }
    }
}
