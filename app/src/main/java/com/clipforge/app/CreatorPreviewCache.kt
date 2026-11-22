package com.clipforge.app

import android.net.Uri
import java.io.File

/** Small session cache for validated rendered previews. Access stays on the UI dispatcher. */
internal class CreatorPreviewCache {
    data class Key(
        val source: Uri, val startMs: Long, val endMs: Long, val aspect: Float,
        val tracking: List<CropFocus>, val facecam: StreamRegion?, val gameplay: StreamRegion?,
        val gameplayTracking: List<CropFocus>, val captions: List<CaptionCue>, val preset: CaptionPreset, val words: List<TimedWord>
    )

    private val files = LinkedHashMap<Key, File>(4, .75f, true)
    private var closed = false

    fun get(key: Key): File? = files[key]?.takeIf { it.isFile && it.length() > 0 }

    fun keep(key: Key, file: File): Boolean {
        if (closed) return false
        files.put(key, file)?.takeIf { it != file }?.delete()
        while (files.size > 3 || (files.size > 1 && files.values.sumOf { it.length() } > 80_000_000L)) {
            val oldest = files.entries.first()
            files.remove(oldest.key)
            oldest.value.delete()
        }
        return true
    }

    fun close() {
        closed = true
        files.values.forEach { it.delete() }
        files.clear()
    }
}
