package com.clipforge.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records progress for independently decodable media segments. A segment is only
 * ready for analysis after its producer has finalized the underlying media file.
 * Fingerprints prevent resuming against a different source or encoding profile.
 */
internal class ProgressiveSegmentCheckpoint(
    private val file: File,
    private val fingerprint: String
) {
    private val finalized = sortedSetOf<Int>()
    private val processed = sortedSetOf<Int>()

    fun load(): ProgressiveSegmentCheckpoint {
        finalized.clear()
        processed.clear()
        if (!file.isFile && !File(file.path + ".bak").isFile) return this
        runCatching {
            val root = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (root.getString("fingerprint") != fingerprint) return@runCatching
            val savedFinalized = root.getJSONArray("finalized")
            val savedProcessed = root.getJSONArray("processed")
            repeat(savedFinalized.length()) { index ->
                val value = savedFinalized.getInt(index)
                require(value >= 0 && finalized.add(value)) { "Invalid finalized segment index" }
            }
            repeat(savedProcessed.length()) { index ->
                val value = savedProcessed.getInt(index)
                require(value in finalized && processed.add(value)) { "Invalid processed segment index" }
            }
        }.onFailure {
            finalized.clear()
            processed.clear()
        }
        return this
    }

    fun finalizedIndices(): Set<Int> = finalized.toSet()
    fun processedIndices(): Set<Int> = processed.toSet()

    fun markFinalized(index: Int) {
        require(index >= 0)
        if (index in finalized) return
        finalized.add(index)
        persistOrRollback { finalized.remove(index) }
    }

    fun markProcessed(index: Int) {
        require(index in finalized) { "Cannot process an unfinished segment" }
        if (index in processed) return
        processed.add(index)
        persistOrRollback { processed.remove(index) }
    }

    private inline fun persistOrRollback(rollback: () -> Unit) {
        try {
            val root = JSONObject().put("fingerprint", fingerprint)
            val finished = JSONArray()
            finalized.forEach { finished.put(it) }
            val done = JSONArray()
            processed.forEach { done.put(it) }
            root.put("finalized", finished).put("processed", done)
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        } catch (failure: Exception) {
            rollback()
            throw failure
        }
    }
}
