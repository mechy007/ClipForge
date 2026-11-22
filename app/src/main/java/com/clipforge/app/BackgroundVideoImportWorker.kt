package com.clipforge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * A user-initiated, persistent SAF copy. WorkManager owns the coroutine after
 * the activity is backgrounded and exposes progress to a persistent notification.
 */
class BackgroundVideoImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val SOURCE_URI = "source_uri"
        const val RESULT_URI = "result_uri"
        const val ERROR = "error"
        const val PROGRESS = "progress"
        const val COPIED_BYTES = "copied_bytes"
        private const val CHANNEL = "clipforge_imports"
        private const val NOTIFICATION_ID = 4301
    }

    override suspend fun doWork(): Result {
        val source = inputData.getString(SOURCE_URI) ?: return Result.failure(
            Data.Builder().putString(ERROR, "Missing video URI").build()
        )
        return try {
            setForeground(notification("Importing video", -1))
            // Throttle progress updates: sending a foreground-service request for
            // every 256 KiB block can overwhelm WorkManager on multi-GB imports.
            var lastPercent = -2
            var lastReportedBytes = 0L
            var lastReportAt = 0L
            val imported = LocalVideoImporter.import(applicationContext, Uri.parse(source)) { p ->
                val percent = p.totalBytes?.takeIf { it > 0L }
                    ?.let { ((p.copiedBytes * 100L) / it).toInt().coerceIn(0, 100) }
                    ?: -1
                val now = android.os.SystemClock.elapsedRealtime()
                val finished = p.totalBytes != null && p.copiedBytes >= p.totalBytes
                val first = lastPercent == -2
                val due = now - lastReportAt >= 1000L
                val significantBytes = p.copiedBytes - lastReportedBytes >= 4L * 1024 * 1024
                if (first || finished || (due && (percent != lastPercent || significantBytes))) {
                    lastPercent = percent
                    lastReportedBytes = p.copiedBytes
                    lastReportAt = now
                    setProgress(Data.Builder()
                        .putInt(PROGRESS, percent)
                        .putLong(COPIED_BYTES, p.copiedBytes)
                        .build())
                    setForeground(notification("Importing video", percent, p.copiedBytes))
                }
            }
            Result.success(Data.Builder().putString(RESULT_URI, imported.toString()).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(Data.Builder().putString(ERROR, error.localizedMessage ?: "Video import failed").build())
        }
    }

    private fun notification(title: String, progress: Int, copiedBytes: Long = 0L): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video imports", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (progress >= 0) "$progress% copied" else "${copiedBytes / 1_048_576L} MB copied")
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel import", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
