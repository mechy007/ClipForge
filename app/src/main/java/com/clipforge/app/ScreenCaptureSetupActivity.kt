package com.clipforge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class ScreenCaptureSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScreenCaptureSetupScreen()
            }
        }
    }
}

@Composable
private fun ScreenCaptureSetupScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val projectionManager = remember { context.getSystemService(MediaProjectionManager::class.java) }
    var serverUrl by remember { mutableStateOf("rtmps://a.rtmps.youtube.com:443/live2") }
    var streamKey by remember { mutableStateOf("") }
    var pendingEndpoint by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Capture your Android screen and supported game audio while streaming and recording locally.") }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && pendingEndpoint.isNotBlank()) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_RTMP_ENDPOINT, pendingEndpoint)
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                activity.finish()
            } catch (_: Exception) {
                status = "ClipForge could not start screen capture. Check notification permission and try again."
            }
        } else {
            status = "Screen capture was cancelled. Nothing was streamed or recorded."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            status = "Allow notifications so Android can show the persistent capture controls and Stop action."
        }
    }

    fun requestCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status = "Screen capture with internal game audio requires Android 10 or newer."
            return
        }
        val base = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()
        if (!base.startsWith("rtmps://", ignoreCase = true) || key.isBlank() || key.any { it.isWhitespace() }) {
            status = "Enter the secure RTMPS server URL and stream key from YouTube Studio."
            return
        }
        pendingEndpoint = "$base/$key"
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B10)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("SCREEN / GAME CAPTURE", color = Color(0xFF9B83FF), fontWeight = FontWeight.Bold)
            Text("Stream gameplay. Keep the original recording.", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(
                "ClipForge captures the Android screen and supported internal game/media audio, sends it over RTMPS, and records a local MP4 at the same time.",
                color = Color(0xFFB7B7C4)
            )
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                singleLine = true
            )
            Text(
                "Copy both values from YouTube Studio → Go Live → Stream settings. ClipForge does not save the stream key or log the full RTMPS URL.",
                color = Color(0xFF9A9AA9),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = ::requestCapture,
                enabled = streamKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant screen access and start streaming") }
            Text(status, color = Color(0xFFD6D6E0), style = MaterialTheme.typography.bodySmall)
            Text(
                "Before starting: choose portrait/landscape orientation, hide private notifications, and close sensitive apps. Android will show a screen-sharing consent prompt and a persistent ClipForge notification with a Stop button. Protected apps may block capture; internal audio depends on the game's playback-capture policy.",
                color = Color(0xFF9A9AA9),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { activity.finish() }) { Text("Cancel") }
            }
        }
    }
}
