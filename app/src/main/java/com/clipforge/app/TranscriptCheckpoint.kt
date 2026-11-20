package com.clipforge.app

import android.content.Context
import android.util.AtomicFile
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Durable per-video transcript checkpoint. Every completed 30-second chunk is
 * committed atomically; a resumed run skips already transcribed chunks.
 * Audio is still decoded again on resume until PCM decoding is checkpointed.
 */
internal class TranscriptCheckpoint(private val file: File, private val fingerprint: String) {
    private val chunks = sortedMapOf<Int, List<TranscriptSegment>>()

    fun load(): TranscriptCheckpoint {
        chunks.clear()
        if (!file.isFile && !File(file.path + ".bak").isFile) return this
        runCatching {
            val root = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (root.optString("fingerprint") != fingerprint) return@runCatching
            val saved = root.getJSONArray("chunks")
            for (i in 0 until saved.length()) {
                val chunk = saved.getJSONObject(i)
                val index = chunk.getInt("index")
                require(index >= 0 && !chunks.containsKey(index)) { "Invalid or repeated checkpoint chunk index" }
                val segments = chunk.getJSONArray("segments")
                chunks[index] = (0 until segments.length()).map { j ->
                    val item = segments.getJSONObject(j)
                    val start = item.getLong("startMs")
                    val end = item.getLong("endMs")
                    require(start >= 0L && end > start) { "Invalid checkpoint segment timestamps" }
                    TranscriptSegment(start, end, item.getString("text"))
                }
            }
        }.onFailure { chunks.clear() }
        return this
    }

    fun hasChunk(index: Int): Boolean = chunks.containsKey(index)
    fun segments(): List<TranscriptSegment> = chunks.values.flatten().sortedBy { it.startMs }

    fun save(index: Int, segments: List<TranscriptSegment>) {
        require(index >= 0)
        val previous = chunks.put(index, segments)
        var committed = false
        try {
        val root = JSONObject().put("fingerprint", fingerprint)
        val rows = JSONArray()
        chunks.forEach { (chunkIndex, items) ->
            val segmentRows = JSONArray()
            items.forEach { item ->
                segmentRows.put(JSONObject().put("startMs", item.startMs).put("endMs", item.endMs).put("text", item.text))
            }
            rows.put(JSONObject().put("index", chunkIndex).put("segments", segmentRows))
        }
        root.put("chunks", rows)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val payload = root.toString().toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(payload)
            atomic.finishWrite(output)
            committed = true
        } catch (failure: Exception) {
            // A failed write can be rolled back; a finished write must not be.
            atomic.failWrite(output)
            throw failure
        }
        check(atomic.openRead().use { it.readBytes().contentEquals(payload) }) {
            "Checkpoint contents differ after atomic commit"
        }
        } catch (failure: Exception) {
            if (!committed) {
                if (previous == null) chunks.remove(index) else chunks[index] = previous
            }
            throw failure
        }
    }

    companion object {
        fun forVideo(context: Context, uri: Uri, language: String?): TranscriptCheckpoint {
            // Invalidate cached chunks when a video is replaced at the same URI.
            val metadata = if (uri.scheme == "file") {
                val source = File(requireNotNull(uri.path))
                "${source.length()}|${source.lastModified()}"
            } else {
                runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        null, null, null
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).toString() else "unknown"
                            val time = if (timeIndex >= 0 && !cursor.isNull(timeIndex)) cursor.getLong(timeIndex).toString() else "unknown"
                            "$size|$time"
                        }
                    }
                }.getOrNull() ?: "unknown|unknown"
            }
            val identity = "${uri}|${language.orEmpty()}|$metadata|whisper-tiny-v1"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return TranscriptCheckpoint(File(context.filesDir, "transcript-checkpoints/$digest.json"), identity).load()
        }
    }
}
