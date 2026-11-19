package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
        if (!file.isFile) return this
        runCatching {
            val root = JSONObject(file.readText())
            if (root.optString("fingerprint") != fingerprint) return@runCatching
            val saved = root.getJSONArray("chunks")
            for (i in 0 until saved.length()) {
                val chunk = saved.getJSONObject(i)
                val index = chunk.getInt("index")
                val segments = chunk.getJSONArray("segments")
                chunks[index] = (0 until segments.length()).map { j ->
                    val item = segments.getJSONObject(j)
                    TranscriptSegment(item.getLong("startMs"), item.getLong("endMs"), item.getString("text"))
                }
            }
        }.onFailure { chunks.clear() }
        return this
    }

    fun hasChunk(index: Int): Boolean = chunks.containsKey(index)
    fun segments(): List<TranscriptSegment> = chunks.values.flatten().sortedBy { it.startMs }

    fun save(index: Int, segments: List<TranscriptSegment>) {
        require(index >= 0)
        chunks[index] = segments
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
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not atomically save transcription checkpoint")
        }
    }

    companion object {
        fun forVideo(context: Context, uri: Uri, language: String?): TranscriptCheckpoint {
            val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).toString() else "unknown" }
                ?: "unknown"
            val identity = "${uri}|${language.orEmpty()}|$metadata|whisper-tiny-v1"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return TranscriptCheckpoint(File(context.filesDir, "transcript-checkpoints/$digest.json"), identity).load()
        }
    }
}
