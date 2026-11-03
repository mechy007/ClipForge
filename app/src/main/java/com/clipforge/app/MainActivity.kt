package com.clipforge.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val Ink = Color(0xFF0B0C12)
private val Panel = Color(0xFF171925)
private val Violet = Color(0xFF9B83FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClipForgeTheme { ClipForgeHome() } }
    }
}

@Composable
private fun ClipForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White),
        content = content
    )
}

@Composable
private fun ClipForgeHome() {
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startSeconds by remember { mutableFloatStateOf(0f) }
    var endSeconds by remember { mutableFloatStateOf(15f) }
    var status by remember { mutableStateOf("Choose a video to begin.") }
    var exporting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedVideo = uri
        durationMs = 0L
        startSeconds = 0f
        endSeconds = 15f
        status = if (uri == null) "No video selected." else "Video loaded. Preparing preview…"
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destination ->
        val source = selectedVideo
        if (destination != null && source != null && endSeconds > startSeconds) {
            exporting = true
            status = "Preparing export…"
            ClipExporter.export(
                context, source, destination,
                (startSeconds * 1000).toLong(), (endSeconds * 1000).toLong(),
                onStatus = { status = it },
                onFinished = { _, message -> exporting = false; status = message }
            )
        } else if (destination == null) {
            status = "Export cancelled."
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(46.dp).background(Violet.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.ContentCut, contentDescription = null, tint = Violet) }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("CLIPFORGE", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("Your videos. Your device. Your AI.", color = Color(0xFF9B9EAE), fontSize = 12.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Create a short clip", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Import a video, choose the moment you want, and export an MP4 directly on your device.", color = Color(0xFFB6B8C5), fontSize = 13.sp)
                    Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text("  Choose a video")
                    }
                }
            }

            if (selectedVideo != null) {
                VideoPreview(uri = selectedVideo!!, onDuration = { duration ->
                    if (duration > 0L && duration != durationMs) {
                        durationMs = duration
                        endSeconds = minOf(15f, duration / 1000f)
                    }
                })
                if (durationMs > 1000L) {
                    val total = durationMs / 1000f
                    Text("TRIM RANGE", color = Violet, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.4.sp)
                    Text("Start · ${formatTime(startSeconds.toLong())}", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = startSeconds.coerceIn(0f, total - 1f),
                        onValueChange = { startSeconds = it; if (endSeconds <= it) endSeconds = minOf(total, it + 1f) },
                        valueRange = 0f..(total - 1f)
                    )
                    Text("End · ${formatTime(endSeconds.toLong())} / ${formatTime(total.toLong())}", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = endSeconds.coerceIn(startSeconds + 0.5f, total),
                        onValueChange = { endSeconds = it },
                        valueRange = (startSeconds + 0.5f)..total
                    )
                    Button(
                        onClick = { exportPicker.launch("ClipForge_${System.currentTimeMillis()}.mp4") },
                        enabled = !exporting && endSeconds > startSeconds,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (exporting) "Exporting…" else "Export selected clip")
                    }
                    Text(status, color = Color(0xFFB6B8C5), fontSize = 12.sp)
                } else {
                    Text("Reading video duration…", color = Color(0xFFB6B8C5), fontSize = 12.sp)
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your next project starts here", fontWeight = FontWeight.SemiBold)
                        Text("Select a local video to preview it and create your first trimmed clip.", color = Color(0xFF9B9EAE), fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Violet, modifier = Modifier.size(16.dp))
                    Text("  ON-DEVICE PROCESSING", color = Color(0xFF9B9EAE), fontSize = 10.sp, letterSpacing = 1.sp)
                }
                Text("v0.2.0", color = Color(0xFF777B8D), fontSize = 11.sp)
            }
        }
    }
}

private fun formatTime(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun VideoPreview(uri: Uri, onDuration: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) onDuration(duration.coerceAtLeast(0L))
                }
            })
            prepare()
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    AndroidView(
        factory = { viewContext -> PlayerView(viewContext).apply { this.player = exoPlayer } },
        modifier = Modifier.fillMaxWidth().height(210.dp)
    )
}
