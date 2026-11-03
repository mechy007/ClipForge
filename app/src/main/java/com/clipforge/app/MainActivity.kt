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
import androidx.compose.material.icons.filled.Movie
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
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White), content = content)
}

@Composable
private fun ClipForgeHome() {
    val selectedVideo = remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> selectedVideo.value = uri }
    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(46.dp).background(Violet.copy(alpha = 0.18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, tint = Violet)
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("CLIPFORGE", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("Your videos. Your device. Your AI.", color = Color(0xFF9B9EAE), fontSize = 12.sp)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Turn long videos into", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("short, shareable moments.", color = Violet, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Private, on-device AI editing starts with a video from your phone.", color = Color(0xFFB6B8C5), fontSize = 14.sp)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text("  Choose a video")
                    }
                }
            }
            if (selectedVideo.value != null) {
                Text("SELECTED VIDEO", color = Violet, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
                VideoPreview(uri = selectedVideo.value!!)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = Violet)
                            Text("  Your next project starts here", fontWeight = FontWeight.SemiBold)
                        }
                        Text("Import a local video to preview it. AI analysis, clip selection and export are the next milestones.", color = Color(0xFF9B9EAE), fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Violet, modifier = Modifier.size(16.dp))
                    Text("  LOCAL AI • NO CLOUD", color = Color(0xFF9B9EAE), fontSize = 11.sp, letterSpacing = 1.sp)
                }
                Text("v0.1.0", color = Color(0xFF777B8D), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { player = this@apply.player; useController = true } }, modifier = Modifier.fillMaxWidth().height(230.dp))
}
