package com.clipforge.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
        private const val CHANNEL = "clipforge_imports"
        private const val NOTIFICATION_ID = 4301
    }

    override suspend fun doWork(): Result {
        val source = inputData.getString(SOURCE_URI) ?: return Result.failure(
            Data.Builder().putString(ERROR, "Missing video URI").build()
        )
        return try {
            setForeground(notification("Importing video", 0))
            var lastPercent = -1
            val imported = LocalVideoImporter.import(applicationContext, Uri.parse(source)) { p ->
                val percent = p.totalBytes?.takeIf { it > 0L }
                    ?.let { ((p.copiedBytes * 100L) / it).toInt().coerceIn(0, 100) }
                    ?: -1
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgressAsync(Data.Builder().putInt(PROGRESS, percent).build())
                    setForegroundAsync(notification("Importing video", percent))
                }
            }
            Result.success(Data.Builder().putString(RESULT_URI, imported.toString()).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(Data.Builder().putString(ERROR, error.localizedMessage ?: "Video import failed").build())
        }
    }

    private fun notification(title: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video imports", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (progress >= 0) "$progress% copied" else "Copying to offline storage")
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
