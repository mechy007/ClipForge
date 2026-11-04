package com.clipforge.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedProject(
    val id: Long,
    val title: String,
    val sourceUri: String,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String
)

data class SavedHighlight(
    val id: Long,
    val projectId: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val preview: String,
    val score: Int,
    val exportedUri: String?
)

/**
 * Small, dependency-free SQLite library for projects and their AI results.
 * Video bytes remain in the user's selected location or app-private downloads;
 * this database stores references and analysis metadata, never video contents.
 */
class ProjectLibrary private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                source_uri TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'imported'
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE transcript_segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                text TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_transcript_project_time ON transcript_segments(project_id, start_ms)")
        db.execSQL(
            """CREATE TABLE highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                preview TEXT NOT NULL,
                score INTEGER NOT NULL,
                exported_uri TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_highlights_project_score ON highlights(project_id, score DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations are intentionally explicit. Never silently drop user projects.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE projects ADD COLUMN status TEXT NOT NULL DEFAULT 'imported'")
        }
    }

    fun createProject(title: String, sourceUri: String, durationMs: Long = 0L): Long {
        require(title.isNotBlank() && sourceUri.isNotBlank())
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title.trim())
            put("source_uri", sourceUri)
            put("duration_ms", durationMs.coerceAtLeast(0L))
            put("created_at", now)
            put("updated_at", now)
            put("status", "imported")
        }
        return writableDatabase.insertOrThrow("projects", null, values)
    }

    fun updateProjectDuration(projectId: Long, durationMs: Long) {
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("duration_ms", durationMs.coerceAtLeast(0L))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(projectId.toString())
        )
    }

    fun updateProjectStatus(projectId: Long, status: String) {
        require(status in setOf("imported", "analyzing", "completed", "exported", "failed"))
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("status", status)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(projectId.toString())
        )
    }

    fun saveAnalysis(projectId: Long, transcript: List<TranscriptSegment>, clips: List<ClipCandidate>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transcript_segments", "project_id=?", arrayOf(projectId.toString()))
            db.delete("highlights", "project_id=?", arrayOf(projectId.toString()))
            transcript.forEach { segment ->
                if (segment.endMs <= segment.startMs || segment.text.isBlank()) return@forEach
                db.insertOrThrow("transcript_segments", null, ContentValues().apply {
                    put("project_id", projectId)
                    put("start_ms", segment.startMs)
                    put("end_ms", segment.endMs)
                    put("text", segment.text)
                })
            }
            clips.forEach { clip ->
                if (clip.endMs <= clip.startMs) return@forEach
                db.insertOrThrow("highlights", null, ContentValues().apply {
                    put("project_id", projectId)
                    put("start_ms", clip.startMs)
                    put("end_ms", clip.endMs)
                    put("title", clip.title)
                    put("preview", clip.hook)
                    put("score", clip.score.coerceIn(0, 100))
                })
            }
            db.update("projects", ContentValues().apply {
                put("status", "completed")
                put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(projectId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordExport(highlightId: Long, outputUri: String) {
        require(outputUri.isNotBlank())
        writableDatabase.update(
            "highlights",
            ContentValues().apply { put("exported_uri", outputUri) },
            "id=?",
            arrayOf(highlightId.toString())
        )
    }

    fun listProjects(): List<SavedProject> =
        readableDatabase.query("projects", null, null, null, null, null, "updated_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toProject()) }
        }

    fun getProject(projectId: Long): SavedProject? =
        readableDatabase.query("projects", null, "id=?", arrayOf(projectId.toString()), null, null, null).use {
            if (it.moveToFirst()) it.toProject() else null
        }

    fun getHighlights(projectId: Long): List<SavedHighlight> =
        readableDatabase.query(
            "highlights", null, "project_id=?", arrayOf(projectId.toString()), null, null, "score DESC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toHighlight()) }
        }

    fun getTranscript(projectId: Long): List<TranscriptSegment> =
        readableDatabase.query(
            "transcript_segments", arrayOf("start_ms", "end_ms", "text"),
            "project_id=?", arrayOf(projectId.toString()), null, null, "start_ms ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    TranscriptSegment(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
                )
            }
        }

    fun deleteProject(projectId: Long) {
        writableDatabase.delete("projects", "id=?", arrayOf(projectId.toString()))
        // SQLite foreign keys are enabled below; explicit cleanup also protects older DBs.
        writableDatabase.delete("transcript_segments", "project_id=?", arrayOf(projectId.toString()))
        writableDatabase.delete("highlights", "project_id=?", arrayOf(projectId.toString()))
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun Cursor.toProject() = SavedProject(
        getLong(getColumnIndexOrThrow("id")),
        getString(getColumnIndexOrThrow("title")),
        getString(getColumnIndexOrThrow("source_uri")),
        getLong(getColumnIndexOrThrow("duration_ms")),
        getLong(getColumnIndexOrThrow("created_at")),
        getLong(getColumnIndexOrThrow("updated_at")),
        getString(getColumnIndexOrThrow("status"))
    )

    private fun Cursor.toHighlight() = SavedHighlight(
        getLong(getColumnIndexOrThrow("id")),
        getLong(getColumnIndexOrThrow("project_id")),
        getLong(getColumnIndexOrThrow("start_ms")),
        getLong(getColumnIndexOrThrow("end_ms")),
        getString(getColumnIndexOrThrow("title")),
        getString(getColumnIndexOrThrow("preview")),
        getInt(getColumnIndexOrThrow("score")),
        getString(getColumnIndexOrThrow("exported_uri"))
    )

    companion object {
        private const val DB_NAME = "clipforge_projects.db"
        // Schema v1 is the initial table set; v2 adds the project status field.
        private const val DB_VERSION = 2
        @Volatile private var instance: ProjectLibrary? = null

        fun get(context: Context): ProjectLibrary =
            instance ?: synchronized(this) {
                instance ?: ProjectLibrary(context).also { instance = it }
            }
    }
}
