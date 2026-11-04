package com.clipforge.app

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF0B0B10)
private val Panel = Color(0xFF171720)
private val Violet = Color(0xFF9B83FF)
private val Muted = Color(0xFF9A9AA9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Ink, surface = Panel)) { ClipForgeHome() } }
    }
}

@Composable
private fun ClipForgeHome() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var ready by remember { mutableStateOf(WhisperModelManager.isModelAvailable(context)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var modelMessage by remember { mutableStateOf("One-time download · works offline afterwards") }
    var analyzing by remember { mutableStateOf(false) }
    var analysisElapsedSeconds by remember { mutableLongStateOf(0L) }
    var transcriptionElapsedSeconds by remember { mutableLongStateOf(0L) }
    var currentPart by remember { mutableIntStateOf(0) }
    var analysisStartedAt by remember { mutableLongStateOf(0L) }
    var transcriptionStartedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(analyzing) {
        while (analyzing) {
            val now = SystemClock.elapsedRealtime()
            analysisElapsedSeconds = ((now - analysisStartedAt) / 1000L).coerceAtLeast(0L)
            if (transcriptionStartedAt > 0L) {
                transcriptionElapsedSeconds = ((now - transcriptionStartedAt) / 1000L).coerceAtLeast(0L)
            }
            delay(1000L)
        }
    }
    var message by remember { mutableStateOf("Import a video to discover its best moments.") }
    var clips by remember { mutableStateOf<List<ClipCandidate>>(emptyList()) }
    var editing by remember { mutableStateOf<ClipCandidate?>(null) }
    var editStart by remember { mutableLongStateOf(0L) }
    var editEnd by remember { mutableLongStateOf(0L) }
    var exportMessage by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ClipCandidate?>(null) }
    var activeProjectId by remember { mutableStateOf<Long?>(null) }
    var savedProjects by remember { mutableStateOf(ProjectLibrary.get(context).listProjects()) }
    var linkInput by remember { mutableStateOf("") }
    var linkLoading by remember { mutableStateOf(false) }
    var linkMessage by remember { mutableStateOf("") }
    var linkProgress by remember { mutableFloatStateOf(0f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        video = uri; duration = 0L; clips = emptyList(); editing = null
        activeProjectId = null
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Provider may not support persistable grants. */ }
            activeProjectId = ProjectLibrary.get(context).createProject(
                uri.lastPathSegment?.substringAfterLast('/')?.take(80)?.ifBlank { "Untitled video" } ?: "Untitled video",
                uri.toString()
            )
            savedProjects = ProjectLibrary.get(context).listProjects()
        }
        message = if (uri == null) "No video selected." else "Video ready · run AI highlight detection."
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dest ->
        val src = video; val clip = pending
        if (dest != null && src != null && clip != null) {
            ClipExporter.export(context, src, dest, clip.startMs, clip.endMs,
                onStatus = { exportMessage = it },
                onFinished = { success, result ->
                    exportMessage = result
                    if (success && activeProjectId != null) {
                        val saved = ProjectLibrary.get(context).getHighlights(activeProjectId!!)
                            .firstOrNull { it.startMs == clip.startMs && it.endMs == clip.endMs }
                        if (saved != null) ProjectLibrary.get(context).recordExport(saved.id, dest.toString())
                    }
                    pending = null
                })
        } else { pending = null; if (dest == null) exportMessage = "Export cancelled." }
    }
    fun save(clip: ClipCandidate) {
        pending = clip
        savePicker.launch("ClipForge_\${clip.startMs / 1000}s.mp4")
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Violet.copy(alpha = .18f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ContentCut, null, tint = Violet)
                }
                Column(Modifier.padding(start = 11.dp)) {
                    Text("CLIPFORGE", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("Turn long videos into short clips", color = Muted, fontSize = 11.sp)
                }
            }
            Text("Your next great moment.", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Find highlights. Create clips. Keep your work on your device.", color = Muted, fontSize = 13.sp)

            if (savedProjects.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SAVED PROJECTS", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        savedProjects.take(6).forEach { project ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(project.status.uppercase() + " · " + (project.durationMs / 1000L) + "s · " +
                                        ProjectLibrary.get(context).getHighlights(project.id).size + " highlights",
                                        color = Muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = {
                                    activeProjectId = project.id
                                    video = Uri.parse(project.sourceUri)
                                    duration = project.durationMs
                                    clips = ProjectLibrary.get(context).getHighlights(project.id).map {
                                        ClipCandidate(it.startMs, it.endMs, it.title, it.preview, it.score)
                                    }
                                    editing = null
                                    message = "Project reopened · saved highlights restored"
                                }) { Text("Open") }
                            }
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("START A PROJECT", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text(if (video == null) "Import your long-form video" else "Your video is ready", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Podcasts, gameplay, interviews and more. Choose a video stored on your phone.", color = Muted, fontSize = 12.sp)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Default.FolderOpen, null); Text(if (video == null) "  Import video" else "  Change video")
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("IMPORT FROM A DIRECT VIDEO LINK", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://example.com/video.mp4") },
                        enabled = !linkLoading
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                linkLoading = true
                                linkProgress = 0f
                                linkMessage = "Checking secure video link…"
                                try {
                                    val imported = DirectVideoImporter.download(context, linkInput) { done, total ->
                                        if (total != null && total > 0L) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                linkProgress = (done.toFloat() / total).coerceIn(0f, 1f)
                                            }
                                        }
                                    }
                                    video = imported
                                    duration = 0L
                                    clips = emptyList()
                                    editing = null
                                    activeProjectId = ProjectLibrary.get(context).createProject(
                                        "Linked video " + System.currentTimeMillis().toString().takeLast(6),
                                        imported.toString()
                                    )
                                    savedProjects = withContext(Dispatchers.IO) { ProjectLibrary.get(context).listProjects() }
                                    linkMessage = "Video imported to private app storage."
                                    message = "Video ready · run AI highlight detection."
                                } catch (error: Exception) {
                                    linkMessage = error.localizedMessage ?: "Could not import this link."
                                } finally { linkLoading = false }
                            }
                        },
                        enabled = !linkLoading && linkInput.startsWith("https://"),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (linkLoading) "Downloading video…" else "Import video link") }
                    if (linkLoading) LinearProgressIndicator(progress = linkProgress, modifier = Modifier.fillMaxWidth())
                    if (linkMessage.isNotBlank()) Text(linkMessage, color = Muted, fontSize = 11.sp)
                    Text("Direct HTTPS video files only · up to 1 GiB · no platform scraping or DRM bypass",
                        color = Muted, fontSize = 10.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Violet)
                        Text("  ON-DEVICE AI ENGINE", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (ready) "READY" else "SETUP", color = if (ready) Color(0xFF75D6A2) else Muted, fontSize = 10.sp)
                    }
                    Text(WhisperModelManager.MODEL_NAME, fontWeight = FontWeight.SemiBold)
                    Text("Approx. 78 MB · downloaded once · stays on this device", color = Muted, fontSize = 11.sp)
                    if (downloading) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            downloading = true
                            try {
                                WhisperModelManager.ensureModel(context) { done, total ->
                                    withContext(Dispatchers.Main) { progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) }
                                }
                                ready = true; modelMessage = "Verified. Ready for offline analysis."
                            } catch (e: Exception) { modelMessage = e.localizedMessage ?: "Model download failed." }
                            finally { downloading = false }
                        }
                    }, enabled = !ready && !downloading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (downloading) "Downloading…" else if (ready) "AI model ready" else "Download offline AI model")
                    }
                    Text(modelMessage, color = Muted, fontSize = 11.sp)
                }
            }

            if (video != null) {
                VideoPreview(video!!, onDuration = {
                    if (it > 0L) {
                        duration = it
                        activeProjectId?.let { id -> ProjectLibrary.get(context).updateProjectDuration(id, it) }
                    }
                })
                Button(onClick = {
                    val src = video ?: return@Button
                    scope.launch {
                        analyzing = true; clips = emptyList(); editing = null
                        analysisStartedAt = SystemClock.elapsedRealtime()
                        transcriptionStartedAt = 0L
                        analysisElapsedSeconds = 0L
                        transcriptionElapsedSeconds = 0L
                        currentPart = 0
                        message = "Preparing audio locally…"
                        try {
                            val transcript = VideoTranscriber.transcribe(context, src, onStatus = { status ->
                                message = status
                                val partMatch = Regex("part (\\d+)").find(status)
                                if (partMatch != null) {
                                    if (transcriptionStartedAt == 0L) transcriptionStartedAt = SystemClock.elapsedRealtime()
                                    currentPart = partMatch.groupValues[1].toIntOrNull() ?: currentPart
                                }
                            })
                            message = "Finding strong moments…"
                            val found = withContext(Dispatchers.Default) { HighlightDetector.detect(transcript, duration) }
                            clips = found
                            message = if (found.isEmpty()) "No clear speech highlights found. Try a video with spoken audio."
                                else "Analysis complete · \${found.size} clips ranked"
                        } catch (e: Exception) { message = e.localizedMessage ?: "Analysis failed. Try again." }
                        finally { analyzing = false }
                    }
                }, enabled = ready && !analyzing && duration > 0L, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Text(if (analyzing) "  Analyzing video…" else "  Find my best clips")
                }
                if (analyzing) {
                    val totalParts = ((duration / 1000.0) / 30.0).toInt().coerceAtLeast(1)
                    val progressFraction = if (currentPart > 0) (currentPart.toFloat() / totalParts).coerceIn(0f, 0.98f) else 0f
                    val remainingSeconds = if (currentPart > 0 && transcriptionElapsedSeconds > 0L) {
                        ((transcriptionElapsedSeconds.toDouble() / currentPart) * (totalParts - currentPart).coerceAtLeast(0)).toLong()
                    } else null
                    LinearProgressIndicator(progress = progressFraction, modifier = Modifier.fillMaxWidth())
                    Text("Elapsed · ${formatElapsed(analysisElapsedSeconds)}", color = Muted, fontSize = 12.sp)
                    Text(
                        when {
                            currentPart > 0 && remainingSeconds != null -> "Audio transcription · $currentPart / $totalParts parts · about ${formatElapsed(remainingSeconds)} remaining"
                            else -> "Preparing audio · elapsed ${formatElapsed(analysisElapsedSeconds)} · estimating time…"
                        },
                        color = Muted, fontSize = 11.sp
                    )
                }
                Text(message, color = Muted, fontSize = 12.sp)
            }

            if (clips.isNotEmpty()) {
                Text("Your highlights", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Ranked using local transcript signals · scores are estimates", color = Muted, fontSize = 11.sp)
                clips.forEachIndexed { index, clip ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).background(Violet.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text("\${index + 1}", color = Violet, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(clip.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text("\${time(clip.startMs)} – \${time(clip.endMs)}", color = Muted, fontSize = 11.sp)
                                }
                                Text("\${clip.score}", color = Violet, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(clip.hook, color = Color(0xFFC7C6D1), fontSize = 11.sp, maxLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editing = clip; editStart = clip.startMs; editEnd = clip.endMs }, modifier = Modifier.weight(1f)) { Text("Edit") }
                                Button(onClick = { save(clip) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.PlayArrow, null); Text("  Export")
                                }
                            }
                        }
                    }
                }
            }
            editing?.let { clip ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("QUICK CLIP EDITOR", color = Violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(clip.title, fontWeight = FontWeight.Bold)
                        Text("Start · \${time(editStart)}", color = Muted, fontSize = 12.sp)
                        Slider(editStart.toFloat(), { editStart = it.toLong() }, valueRange = 0f..(editEnd - 1000).coerceAtLeast(0).toFloat())
                        Text("End · \${time(editEnd)}", color = Muted, fontSize = 12.sp)
                        Slider(editEnd.toFloat(), { editEnd = it.toLong() }, valueRange = (editStart + 1000).toFloat()..duration.coerceAtLeast(editStart + 1000).toFloat())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = null }, modifier = Modifier.weight(1f)) { Text("Close") }
                            Button(onClick = { save(clip.copy(startMs = editStart, endMs = editEnd)); editing = null },
                                enabled = editEnd > editStart, modifier = Modifier.weight(1f)) { Text("Export edit") }
                        }
                    }
                }
            }
            if (exportMessage.isNotBlank()) Text(exportMessage, color = Muted, fontSize = 12.sp)
            if (video == null) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Movie, null, tint = Violet, modifier = Modifier.size(27.dp))
                        Text("Your clip studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Import a video to preview it, transcribe speech, rank standout moments, fine-tune clips and export MP4s.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PRIVATE · ON-DEVICE WORKFLOW", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                Text("v0.5.0", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun formatElapsed(seconds: Long): String = "%d:%02d".format(seconds / 60L, seconds % 60L)

private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)

@Composable
private fun VideoPreview(uri: Uri, onDuration: (Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) onDuration(duration.coerceAtLeast(0L)) }
            })
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxWidth().height(210.dp))
}
