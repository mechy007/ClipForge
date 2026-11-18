package com.clipforge.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtmp.RtmpStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service(), ConnectChecker {
    companion object {
        const val ACTION_STOP = "com.clipforge.app.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "com.clipforge.app.extra.SCREEN_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.clipforge.app.extra.SCREEN_RESULT_DATA"
        const val EXTRA_RTMP_ENDPOINT = "com.clipforge.app.extra.RTMP_ENDPOINT"
        private const val CHANNEL_ID = "clipforge_screen_capture"
        private const val NOTIFICATION_ID = 3107
    }

    private var stream: RtmpStream? = null
    private var projection: MediaProjection? = null
    private var recordingPath: String? = null
    @Volatile private var finishing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ClipForge screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishCapture()
            return START_NOT_STICKY
        }
        if (stream != null || finishing) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startProjectionForeground()
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val resultData = getProjectionResultData(intent)
                ?: error("Screen-capture permission data is missing.")
            require(resultCode == Activity.RESULT_OK) { "Screen-capture permission was not granted." }
            val endpoint = intent?.getStringExtra(EXTRA_RTMP_ENDPOINT).orEmpty()
            require(endpoint.startsWith("rtmps://", ignoreCase = true)) { "A secure RTMPS endpoint is required." }

            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android did not provide a screen-capture session.")

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { finishCapture() }
                }
            }
            val screenSource = ScreenSource(this, projection!!, callback)
            val audioSource = InternalAudioSource(projection!!)
            val liveStream = RtmpStream(this, this, screenSource, audioSource)
            stream = liveStream
            liveStream.getStreamClient().setLogs(false)
            liveStream.getStreamClient().setReTries(5)
            liveStream.getGlInterface().setForceRender(true, 20)

            val metrics = resources.displayMetrics
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val width = ((metrics.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val height = ((metrics.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
            check(liveStream.prepareVideo(width, height, 2_500_000, rotation = 0)) {
                "The device could not prepare a screen video encoder."
            }
            check(liveStream.prepareAudio(48_000, true, 128_000)) {
                "The device could not prepare internal game audio capture."
            }
            liveStream.getGlInterface().setCameraOrientation(0)

            val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            if (!directory.exists() && !directory.mkdirs()) error("ClipForge could not create its recording folder.")
            val file = File(
                directory,
                "ClipForge_Screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            )
            recordingPath = file.absolutePath
            liveStream.startRecord(file.absolutePath) { status ->
                if (status == RecordController.Status.RECORDING) {
                    updateActiveNotification("Screen recording active · connecting to RTMPS.")
                }
            }
            liveStream.startStream(endpoint)
            updateActiveNotification("Screen capture started · streaming and recording locally.")
        } catch (_: Exception) {
            finishCapture()
        }
        return START_NOT_STICKY
    }

    private fun getProjectionResultData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startProjectionForeground() {
        val notification = buildActiveNotification("Preparing secure screen capture…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, NOTIFICATION_ID, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildActiveNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ClipForge screen capture")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop capture", buildStopPendingIntent())
            .build()

    private fun updateActiveNotification(message: String) {
        if (!finishing) notificationManager.notify(NOTIFICATION_ID, buildActiveNotification(message))
    }

    @Synchronized
    private fun finishCapture() {
        if (finishing) return
        finishing = true
        val liveStream = stream
        runCatching {
            if (liveStream?.isStreaming == true) liveStream.stopStream()
            if (liveStream?.isRecording == true) liveStream.stopRecord()
            liveStream?.release()
        }
        stream = null
        runCatching { projection?.stop() }
        projection = null

        val file = recordingPath?.let(::File)
        if (file != null && file.exists() && file.length() > 0L) {
            val openIntent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LIVE_RECORDING_PATH, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val openPendingIntent = PendingIntent.getActivity(
                this, NOTIFICATION_ID + 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val completed = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ClipForge recording ready")
                .setContentText("Tap to open your screen recording in Video Studio.")
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notificationManager.notify(NOTIFICATION_ID, completed)
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onConnectionStarted(url: String) {
        updateActiveNotification("Connecting to YouTube over RTMPS · local recording continues.")
    }

    override fun onConnectionSuccess() {
        updateActiveNotification("LIVE · screen streaming and recording locally.")
    }

    override fun onConnectionFailed(reason: String) {
        val retrying = runCatching {
            stream?.getStreamClient()?.reTry(5_000, reason, null) == true
        }.getOrDefault(false)
        updateActiveNotification(
            if (retrying) "Stream interrupted · retrying. Local screen recording continues."
            else "Stream connection failed · local recording continues."
        )
        if (!retrying) mainHandler.post { stream?.let { if (it.isStreaming) it.stopStream() } }
    }

    override fun onDisconnect() {
        updateActiveNotification("Stream disconnected · local recording remains active.")
    }

    override fun onAuthError() {
        mainHandler.post { stream?.let { if (it.isStreaming) it.stopStream() } }
        updateActiveNotification("Stream key rejected · local recording remains active.")
    }

    override fun onAuthSuccess() {
        updateActiveNotification("RTMPS stream key accepted.")
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDestroy() {
        if (!finishing) finishCapture()
        super.onDestroy()
    }
}
