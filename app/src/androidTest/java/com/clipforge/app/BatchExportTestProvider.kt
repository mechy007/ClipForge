package com.clipforge.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/** Test-only document destination with an injectable create failure. */
class BatchExportTestProvider : ContentProvider() {
    private val root get() = File(checkNotNull(context).cacheDir, "batch-export-fixtures")

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        requireNotNull(extras)
        if (method == "fixture:allow") {
            File(folder(requireNotNull(arg)), "allow").createNewFile()
            return Bundle()
        }
        if (method == "fixture:cleanup") {
            folder(requireNotNull(arg)).deleteRecursively()
            return Bundle()
        }
        if (method == "fixture:count") {
            val count = folder(requireNotNull(arg)).listFiles()?.count { it.extension == "mp4" } ?: 0
            return Bundle().apply { putInt("outputs", count) }
        }
        @Suppress("DEPRECATION")
        val uri = extras.getParcelable<Uri>("uri") ?: return null
        val id = DocumentsContract.getDocumentId(uri)
        return when (method) {
            "android:createDocument" -> {
                val name = requireNotNull(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                require(name.matches(Regex("[A-Za-z0-9._-]+\\.mp4")))
                val destination = folder(id)
                if (name.endsWith("_blocked.mp4") && !File(destination, "allow").exists()) return null
                val output = File(destination, name)
                check(output.createNewFile()) { "The batch attempted to create a duplicate document." }
                Bundle().apply {
                    putParcelable("uri", DocumentsContract.buildDocumentUri(AUTHORITY, "$id/$name"))
                }
            }
            "android:deleteDocument" -> {
                document(id).delete()
                Bundle()
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(document(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri): String = "video/mp4"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun folder(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        return File(root, id).apply { mkdirs() }
    }

    private fun document(id: String): File {
        val parts = id.split('/')
        require(parts.size == 2 && parts[1].matches(Regex("[A-Za-z0-9._-]+\\.mp4")))
        return File(folder(parts[0]), parts[1])
    }

    companion object {
        const val AUTHORITY = "com.clipforge.app.test.batch"
    }
}
