package com.clipforge.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedProject(val id: Long, val title: String, val sourceUri: String, val durationMs: Long, val status: String, val updatedAt: Long)
data class SavedHighlight(val id: Long, val projectId: Long, val startMs: Long, val endMs: Long, val title: String, val transcript: String, val score: Int, val exportedUri: String?, val reason: String, val reviewStatus: String = "PENDING")
data class SavedLiveSession(val id: Long, val videoId: String, val title: String, val broadcastStartMs: Long, val monitoringStartedAtMs: Long, val recordingStartMs: Long, val recordingOffsetMs: Long, val recordingPath: String)
data class SavedLiveMarker(val id: Long, val sessionId: Long, val startMs: Long, val messageCount: Int, val baselineMedian: Double, val reason: String)

/** Local SQLite persistence. Media files remain outside the database. */
class ProjectDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, "clipforge_projects.db", null, 7) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, source_uri TEXT NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'draft', updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, transcript TEXT NOT NULL, score INTEGER NOT NULL, exported_uri TEXT, reason TEXT NOT NULL DEFAULT '', review_status TEXT NOT NULL DEFAULT 'PENDING')")
        db.execSQL("CREATE INDEX highlights_project_idx ON highlights(project_id)")
        createTranscriptTable(db)
        createLiveTables(db)
    }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTranscriptTable(db)
        if (oldVersion < 3) createLiveTables(db)
        if (oldVersion >= 3 && oldVersion < 4) {
            db.execSQL("ALTER TABLE live_sessions ADD COLUMN recording_start_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE live_sessions ADD COLUMN recording_offset_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion >= 3 && oldVersion < 5) {
            db.execSQL("ALTER TABLE live_sessions ADD COLUMN recording_path TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE highlights ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE highlights ADD COLUMN review_status TEXT NOT NULL DEFAULT 'PENDING'")
        }
    }

    private fun createLiveTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS live_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, video_id TEXT NOT NULL, title TEXT NOT NULL, broadcast_start_ms INTEGER NOT NULL, monitoring_started_at_ms INTEGER NOT NULL, recording_start_ms INTEGER NOT NULL DEFAULT 0, recording_offset_ms INTEGER NOT NULL DEFAULT 0, recording_path TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS live_markers (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE, start_ms INTEGER NOT NULL, message_count INTEGER NOT NULL, baseline_median REAL NOT NULL, reason TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS live_markers_session_idx ON live_markers(session_id, start_ms)")
    }

    private fun createTranscriptTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_segments (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS transcript_project_time_idx ON transcript_segments(project_id, start_ms)")
    }

    fun saveProject(title: String, sourceUri: String, durationMs: Long, status: String = "analyzed"): Long {
        require(title.isNotBlank() && sourceUri.isNotBlank())
        val values = ContentValues().apply {
            put("title", title.take(180)); put("source_uri", sourceUri)
            put("duration_ms", durationMs.coerceAtLeast(0)); put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("projects", null, values)
    }
    fun saveCompletedAnalysis(
        title: String,
        sourceUri: String,
        durationMs: Long,
        clips: List<ClipCandidate>,
        transcript: List<TranscriptSegment>
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = saveProject(title, sourceUri, durationMs, "completed")
            saveHighlights(id, clips)
            saveTranscript(id, transcript)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun saveHighlights(projectId: Long, clips: List<ClipCandidate>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("highlights", "project_id=?", arrayOf(projectId.toString()))
            clips.forEach { clip -> db.insertOrThrow("highlights", null, ContentValues().apply {
                put("project_id", projectId); put("start_ms", clip.startMs); put("end_ms", clip.endMs)
                put("title", clip.title); put("transcript", clip.hook); put("score", clip.score); put("reason", clip.reason)
            }) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun saveTranscript(projectId: Long, segments: List<TranscriptSegment>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transcript_segments", "project_id=?", arrayOf(projectId.toString()))
            segments.forEach { segment ->
                if (segment.startMs < 0L || segment.endMs <= segment.startMs || segment.text.isBlank()) return@forEach
                db.insertOrThrow("transcript_segments", null, ContentValues().apply {
                    put("project_id", projectId)
                    put("start_ms", segment.startMs)
                    put("end_ms", segment.endMs)
                    put("text", segment.text)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listTranscript(projectId: Long): List<TranscriptSegment> {
        val rows = mutableListOf<TranscriptSegment>()
        readableDatabase.query(
            "transcript_segments", arrayOf("start_ms", "end_ms", "text"),
            "project_id=?", arrayOf(projectId.toString()), null, null, "start_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += TranscriptSegment(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
            }
        }
        return rows
    }

    fun saveLiveSession(session: YouTubeLiveSession, monitoringStartedAtMs: Long): Long {
        require(session.videoId.isNotBlank() && session.title.isNotBlank())
        require(session.actualStartTimeMs > 0L && monitoringStartedAtMs > 0L)
        return writableDatabase.insertOrThrow("live_sessions", null, ContentValues().apply {
            put("video_id", session.videoId)
            put("title", session.title.take(300))
            put("broadcast_start_ms", session.actualStartTimeMs)
            put("monitoring_started_at_ms", monitoringStartedAtMs)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun saveLiveRecordingStart(sessionId: Long, recordingStartMs: Long, recordingPath: String): Boolean {
        if (sessionId <= 0L || recordingStartMs <= 0L || recordingPath.isBlank()) return false
        val broadcastStartMs = readableDatabase.query(
            "live_sessions", arrayOf("broadcast_start_ms"),
            "id=?", arrayOf(sessionId.toString()), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else return false
        }
        val offsetMs = recordingStartMs - broadcastStartMs
        val updated = writableDatabase.update(
            "live_sessions",
            ContentValues().apply {
                put("recording_start_ms", recordingStartMs)
                put("recording_offset_ms", offsetMs)
                put("recording_path", recordingPath)
            },
            "id=?", arrayOf(sessionId.toString())
        )
        return updated == 1
    }

    fun saveLiveMarker(sessionId: Long, marker: LiveChatHighlightMarker): Long {
        require(sessionId > 0L && marker.startMs >= 0L && marker.messageCount > 0)
        return writableDatabase.insertOrThrow("live_markers", null, ContentValues().apply {
            put("session_id", sessionId)
            put("start_ms", marker.startMs)
            put("message_count", marker.messageCount)
            put("baseline_median", marker.baselineMedian)
            put("reason", marker.reason.take(300))
        })
    }

    fun listLiveSessions(limit: Int = 20): List<SavedLiveSession> {
        val rows = mutableListOf<SavedLiveSession>()
        readableDatabase.query(
            "live_sessions", arrayOf("id", "video_id", "title", "broadcast_start_ms", "monitoring_started_at_ms", "recording_start_ms", "recording_offset_ms", "recording_path"),
            null, null, null, null, "created_at DESC", limit.coerceIn(1, 100).toString()
        ).use { c ->
            while (c.moveToNext()) rows += SavedLiveSession(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getString(7))
        }
        return rows
    }

    fun listLiveMarkers(sessionId: Long): List<SavedLiveMarker> {
        val rows = mutableListOf<SavedLiveMarker>()
        readableDatabase.query(
            "live_markers", arrayOf("id", "session_id", "start_ms", "message_count", "baseline_median", "reason"),
            "session_id=?", arrayOf(sessionId.toString()), null, null, "start_ms ASC"
        ).use { c ->
            while (c.moveToNext()) rows += SavedLiveMarker(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getDouble(4), c.getString(5))
        }
        return rows
    }

    fun updateHighlightTrim(projectId: Long, oldStartMs: Long, oldEndMs: Long, newStartMs: Long, newEndMs: Long): Boolean {
        require(newStartMs >= 0L && newEndMs > newStartMs)
        return writableDatabase.update(
            "highlights",
            ContentValues().apply { put("start_ms", newStartMs); put("end_ms", newEndMs) },
            "project_id=? AND start_ms=? AND end_ms=?",
            arrayOf(projectId.toString(), oldStartMs.toString(), oldEndMs.toString())
        ) > 0
    }

    fun updateHighlightReview(projectId: Long, startMs: Long, endMs: Long, status: String): Boolean {
        require(status in setOf("PENDING", "APPROVED", "REJECTED"))
        return writableDatabase.update(
            "highlights",
            ContentValues().apply { put("review_status", status) },
            "project_id=? AND start_ms=? AND end_ms=?",
            arrayOf(projectId.toString(), startMs.toString(), endMs.toString())
        ) > 0
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
            while (c.moveToNext()) rows += SavedHighlight(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4), c.getString(5), c.getInt(6), c.getString(7), c.getString(c.getColumnIndexOrThrow("reason")), c.getString(c.getColumnIndexOrThrow("review_status")))
        }
        return rows
    }
    fun deleteProject(projectId: Long) { writableDatabase.delete("projects", "id=?", arrayOf(projectId.toString())) }
}
