package com.clipforge.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Keeps the destination URI before encoding so a retry can check or reuse it. */
internal class BatchExportJournal(private val file: AtomicFile?, private val records: JSONObject) {
    fun destination(clip: ClipCandidate): Uri? = records.optString(range(clip))
        .takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun record(clip: ClipCandidate, uri: Uri) {
        records.put(range(clip), uri.toString())
        write()
    }

    fun forget(clip: ClipCandidate) {
        records.remove(range(clip))
        write()
    }

    private fun write() {
        val target = file ?: return
        val bytes = records.toString().toByteArray(Charsets.UTF_8)
        val output = target.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    private fun range(clip: ClipCandidate): String = "${clip.startMs}-${clip.endMs}"

    companion object {
        private val sessionRecords = mutableMapOf<String, JSONObject>()

        fun open(
            context: Context, folder: Uri, source: Uri, aspect: Float, transcript: List<TranscriptSegment>,
            preset: CaptionPreset, tracking: List<CropFocus>, split: StreamRegion?
        ): BatchExportJournal {
            val revision = sourceRevision(context, source)
            val hash = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                hash.update(':'.code.toByte())
                hash.update(bytes)
            }
            listOf("v1", source.toString(), revision.orEmpty(), folder.toString(), aspect.toString(), preset.name, split.toString()).forEach(::add)
            tracking.forEach { add(it.toString()) }
            transcript.forEach { add(it.toString()) }
            val key = hash.digest().joinToString("") { "%02x".format(it) }
            if (revision == null) {
                val records = synchronized(sessionRecords) { sessionRecords.getOrPut(key) { JSONObject() } }
                return BatchExportJournal(null, records)
            }
            val folderPath = File(context.filesDir, "batch-export-journals")
            check(folderPath.isDirectory || folderPath.mkdirs()) { "Could not open the export journal directory." }
            return load(File(folderPath, "$key.json"))
        }

        internal fun load(path: File): BatchExportJournal {
            val file = AtomicFile(path)
            val previous = try {
                file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            } catch (_: java.io.FileNotFoundException) {
                JSONObject()
            }
            return BatchExportJournal(file, previous)
        }

        private fun sourceRevision(context: Context, source: Uri): String? {
            if (source.scheme == "file") {
                val local = File(checkNotNull(source.path))
                return if (local.isFile) "${local.length()}:${local.lastModified()}" else null
            }
            return runCatching {
                val fields = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                context.contentResolver.query(source, fields, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val size = cursor.getColumnIndex(fields[0]).takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
                    val changed = cursor.getColumnIndex(fields[1]).takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
                    if (size > 0L && changed > 0L) "$size:$changed" else null
                }
            }.getOrNull()
        }
    }
}
