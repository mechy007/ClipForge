package com.clipforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.view.OpenGlView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreatorLiveStudioActivity : ComponentActivity(), ConnectChecker {
    private lateinit var stream: RtmpStream
    private var prepared by mutableStateOf(false)
    private var status by mutableStateOf("Connect your own YouTube encoder feed. Keep this screen open while live.")
    private var bitrate by mutableLongStateOf(0L)
    private var recordingPath by mutableStateOf<String?>(null)
    private var liveActive by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true &&
            grants[Manifest.permission.RECORD_AUDIO] == true
        ) {
            prepareEncoder()
        } else {
            permissionDenied = true
            status = "Camera and microphone permissions are required for creator capture."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stream = RtmpStream(applicationContext, this)
        stream.getStreamClient().setReTries(5)
        setContent {
            MaterialTheme {
                CreatorLiveStudioScreen(
                    prepared = prepared,
                    status = status,
                    bitrate = bitrate,
                    recordingPath = recordingPath,
                    liveActive = liveActive,
                    permissionDenied = permissionDenied,
                    onRequestPermissions = ::requestCapturePermissions,
                    onStart = ::startLiveAndRecord,
                    onStop = ::stopLiveAndRecord,
                    onOpenRecording = ::openRecording,
                    onPreviewCreated = ::attachPreview
                )
            }
        }
        requestCapturePermissions()
    }

    private fun requestCapturePermissions() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            prepareEncoder()
        } else {
            permissionLauncher.launch(required)
        }
    }

    private fun prepareEncoder() {
        if (prepared) return
        val ready = try {
            stream.prepareVideo(1280, 720, 2_500_000, 0) &&
                stream.prepareAudio(44_100, true, 128_000)
        } catch (error: Exception) {
            status = error.localizedMessage ?: "Could not prepare the video encoder."
            false
        }
        prepared = ready
        status = if (ready) "Camera ready · 720p · hardware encoder when supported." else "This device could not prepare the selected audio/video encoder settings."
    }

    private fun attachPreview(view: OpenGlView) {
        view.setAspectRatioMode(AspectRatioMode.Adjust)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (prepared && !stream.isOnPreview) {
                    runCatching { stream.startPreview(view) }
                        .onFailure { status = it.localizedMessage ?: "Camera preview could not start." }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (prepared) stream.getGlInterface().setPreviewResolution(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (stream.isOnPreview && !stream.isStreaming && !stream.isRecording) {
                    stream.stopPreview()
                }
            }
        })
    }

    private fun startLiveAndRecord(serverUrl: String, streamKey: String) {
        if (!prepared) {
            requestCapturePermissions()
            return
        }
        val base = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()
        if (!base.startsWith("rtmps://", ignoreCase = true) || key.isBlank() || key.any { it.isWhitespace() }) {
            status = "Enter the secure RTMPS server URL and stream key from your YouTube Live encoder settings."
            return
        }
        if (stream.isStreaming || stream.isRecording) {
            status = "A live session is already running."
            return
        }
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!directory.exists() && !directory.mkdirs()) {
            status = "ClipForge could not create its recording folder."
            return
        }
        val file = File(directory, "ClipForge_Live_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        val endpoint = "$base/$key"
        try {
            stream.startRecord(file.absolutePath) { recordStatus ->
                runOnUiThread {
                    if (recordStatus == com.pedro.library.base.recording.RecordController.Status.RECORDING) {
                        status = "Local MP4 recording is active."
                    }
                }
            }
            recordingPath = file.absolutePath
            liveActive = true
            stream.startStream(endpoint)
            status = "Starting secure YouTube stream · local MP4 recording is active."
        } catch (error: Exception) {
            if (stream.isStreaming) stream.stopStream()
            if (stream.isRecording) stream.stopRecord()
            recordingPath = if (file.exists() && file.length() > 0L) file.absolutePath else null
            liveActive = false
            status = error.localizedMessage ?: "Could not start the live session."
        }
    }

    private fun stopLiveAndRecord() {
        runCatching {
            if (stream.isStreaming) stream.stopStream()
            if (stream.isRecording) stream.stopRecord()
        }.onFailure {
            status = it.localizedMessage ?: "Could not stop the live session cleanly."
            return
        }
        liveActive = false
        status = recordingPath?.let { "Live session stopped. Local recording saved." } ?: "Live session stopped."
    }

    private fun openRecording() {
        val path = recordingPath ?: return
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            status = "The recording file is not ready or is empty."
            return
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LIVE_RECORDING_PATH, file.absolutePath))
        finish()
    }

    override fun onConnectionStarted(url: String) {
        runOnUiThread { status = "Connecting to YouTube over RTMPS · local recording continues." }
    }

    override fun onConnectionSuccess() {
        runOnUiThread { status = "LIVE · streaming and recording locally." }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread { status = "Stream connection failed: $reason · local recording remains active until you stop it." }
    }

    override fun onDisconnect() {
        runOnUiThread { status = "Stream disconnected · local recording may still be active." }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread { this.bitrate = bitrate }
    }

    override fun onAuthError() {
        runOnUiThread { status = "YouTube rejected the stream key · check it in YouTube Studio. Local recording remains active." }
    }

    override fun onAuthSuccess() {
        runOnUiThread { status = "YouTube stream key accepted." }
    }

    override fun onDestroy() {
        if (::stream.isInitialized) {
            runCatching {
                if (stream.isStreaming) stream.stopStream()
                if (stream.isRecording) stream.stopRecord()
                stream.release()
            }
        }
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun CreatorLiveStudioScreen(
    prepared: Boolean,
    status: String,
    bitrate: Long,
    recordingPath: String?,
    liveActive: Boolean,
    permissionDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onStart: (String, String) -> Unit,
    onStop: () -> Unit,
    onOpenRecording: () -> Unit,
    onPreviewCreated: (OpenGlView) -> Unit
) {
    val context = LocalContext.current
    var serverUrl by androidx.compose.runtime.remember { mutableStateOf("rtmps://a.rtmps.youtube.com:443/live2") }
    var streamKey by androidx.compose.runtime.remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B10)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("CREATOR LIVE STUDIO", color = Color(0xFF9B83FF), fontWeight = FontWeight.Bold)
            Text("Stream your own feed and keep a local original recording.", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171720))) {
                if (prepared) {
                    AndroidView(
                        factory = { viewContext -> OpenGlView(viewContext).also(onPreviewCreated) },
                        modifier = Modifier.fillMaxWidth().height(230.dp)
                    )
                } else {
                    Column(Modifier.padding(18.dp)) {
                        Text(if (permissionDenied) "Camera/microphone access is needed." else "Preparing camera and microphone…", color = Color.White)
                        Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                    }
                }
            }
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube RTMPS server URL") },
                singleLine = true
            )
            OutlinedTextField(
                value = streamKey,
                onValueChange = { streamKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube stream key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Text("Get both values from YouTube Studio → Go Live → Stream settings. The stream key is used only in memory and is never saved by ClipForge.", color = Color(0xFF9A9AA9), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onStart(serverUrl, streamKey) },
                    enabled = prepared && streamKey.isNotBlank() && !liveActive,
                    modifier = Modifier.weight(1f)
                ) { Text("Go live + record") }
                Button(
                    onClick = onStop,
                    enabled = liveActive,
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }
            Text(status, color = Color(0xFFD6D6E0), style = MaterialTheme.typography.bodySmall)
            if (bitrate > 0L) Text("Upload bitrate: " + String.format(Locale.US, "%.2f Mbps", bitrate / 1_000_000.0), color = Color(0xFF75D6A2))
            if (recordingPath != null) {
                Button(onClick = onOpenRecording, modifier = Modifier.fillMaxWidth()) { Text("Open local recording in ClipForge") }
            }
            Text("Keep ClipForge open during the stream. Background streaming and screen/game capture are not part of this first camera-based studio build.", color = Color(0xFF9A9AA9), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { (context as? ComponentActivity)?.finish() }) { Text("Close studio") }
        }
    }
}
