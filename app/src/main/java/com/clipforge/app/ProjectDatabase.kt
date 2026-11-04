package com.clipforge.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedProject(val id: Long, val title: String, val sourceUri: String, val durationMs: Long, val status: String, val updatedAt: Long)
data class SavedHighlight(val id: Long, val projectId: Long, val startMs: Long, val endMs: Long, val title: String, val transcript: String, val score: Int, val exportedUri: String?)

/** Local SQLite persistence. Media files remain outside the database. */
class ProjectDatabase(context: Context) : SQLiteOpenHelper(context, "clipforge_projects.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, source_uri TEXT NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'draft', updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, transcript TEXT NOT NULL, score INTEGER NOT NULL, exported_uri TEXT)")
        db.execSQL("CREATE INDEX highlights_project_idx ON highlights(project_id)")
    }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* Add explicit migrations before changing the schema version. */ }

    fun saveProject(title: String, sourceUri: String, durationMs: Long, status: String = "analyzed"): Long {
        require(title.isNotBlank() && sourceUri.isNotBlank())
        val values = ContentValues().apply {
            put("title", title.take(180)); put("source_uri", sourceUri)
            put("duration_ms", durationMs.coerceAtLeast(0)); put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("projects", null, values)
    }
    fun saveHighlights(projectId: Long, clips: List<ClipCandidate>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("highlights", "project_id=?", arrayOf(projectId.toString()))
            clips.forEach { clip -> db.insertOrThrow("highlights", null, ContentValues().apply {
                put("project_id", projectId); put("start_ms", clip.startMs); put("end_ms", clip.endMs)
                put("title", clip.title); put("transcript", clip.hook); put("score", clip.score)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun saveExport(highlightId: Long, uri: String) {
        writableDatabase.update("highlights", ContentValues().apply { put("exported_uri", uri) }, "id=?", arrayOf(highlightId.toString()))
    }
    fun listProjects(): List<SavedProject> {
        val rows = mutableListOf<SavedProject>()
        readableDatabase.query("projects", null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) rows += SavedProject(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getLong(5))
        }
        return rows
    }
    fun listHighlights(projectId: Long): List<SavedHighlight> {
        val rows = mutableListOf<SavedHighlight>()
        readableDatabase.query("highlights", null, "project_id=?", arrayOf(projectId.toString()), null, null, "score DESC").use { c ->
            while (c.moveToNext()) rows += SavedHighlight(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5), c.getInt(6), c.getString(7))
        }
        return rows
    }
    fun deleteProject(projectId: Long) { writableDatabase.delete("projects", "id=?", arrayOf(projectId.toString())) }
}
