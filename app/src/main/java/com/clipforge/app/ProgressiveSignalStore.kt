package com.clipforge.app

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Durable per-segment signal snapshots. The caller supplies a JSON payload
 * containing transcript and audio-energy signals in original-video time.
 * Persist snapshots inside onSegmentTranscribed BEFORE the pipeline acknowledges
 * the segment as processed. Never store these in the disposable cache directory.
 */
internal class ProgressiveSignalStore(
    private val directory: File,
    private val sourceFingerprint: String
) {
    init {
        require(sourceFingerprint.isNotBlank())
    }

    private fun path(index: Int): File {
        require(index >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceFingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest-segment-$index.json")
    }

    /** Returns null for absent, corrupt or mismatched snapshots. */
    fun read(index: Int): JSONObject? = runCatching {
        val file = path(index)
        if (!file.exists() && !File(file.path + ".bak").exists()) return@runCatching null
        val root = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (root.opt("version") != 1 ||
            root.getString("fingerprint") != sourceFingerprint ||
            root.opt("index") != index) return@runCatching null
        root.getJSONObject("signals")
    }.getOrNull()

    /** A successful return means the snapshot was written and synced to disk. */
    fun write(index: Int, signals: JSONObject) {
        val file = path(index)
        require(signals.length() > 0) { "Cannot acknowledge empty signals payload" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create signal directory" }
        val root = JSONObject()
            .put("version", 1)
            .put("fingerprint", sourceFingerprint)
            .put("index", index)
            .put("signals", JSONObject(signals.toString()))
        val encoded = root.toString().toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(encoded)
            output.fd.sync()
            atomic.finishWrite(output)
            check(file.isFile && file.readBytes().contentEquals(encoded)) {
                "Signal snapshot commit verification failed"
            }
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }
}
