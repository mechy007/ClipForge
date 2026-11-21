package com.clipforge.app

import android.net.Uri
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val Ink = ClipForgePalette.Background
private val Panel = ClipForgePalette.Surface
private val Accent = ClipForgePalette.Accent
private val Muted = ClipForgePalette.Muted
private val AccentSoft = ClipForgePalette.AccentSoft

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LIVE_RECORDING_PATH = "com.clipforge.app.EXTRA_LIVE_RECORDING_PATH"
        const val EXTRA_LIVE_SESSION_ID = "com.clipforge.app.EXTRA_LIVE_SESSION_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liveRecording = intent.getStringExtra(EXTRA_LIVE_RECORDING_PATH)
            ?.let { Uri.fromFile(java.io.File(it)) }
        window.statusBarColor = android.graphics.Color.rgb(10, 15, 24)
        window.navigationBarColor = android.graphics.Color.rgb(18, 27, 41)
        setContent {
            ClipForgeTheme { ClipForgeHome(liveRecording) }
        }
    }
}

@Composable
private fun ClipForgeHome(initialVideoUri: Uri? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var video by rememberSaveable(initialVideoUri) { mutableStateOf(initialVideoUri) }
    val projectDb = remember { ProjectDatabase(context) }
    var savedProjects by remember { mutableStateOf(projectDb.listProjects()) }
    var activeProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedProjectHighlights by remember { mutableStateOf<List<SavedHighlight>>(emptyList()) }
    var linkText by remember { mutableStateOf("") }
    var pendingYoutubeUrl by rememberSaveable { mutableStateOf("") }
    var linkStatus by remember { mutableStateOf("") }
    var youtubeQuality by rememberSaveable { mutableStateOf(1080) }
    var importingLink by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var ready by remember { mutableStateOf(WhisperModelManager.isModelAvailable(context)) }
    var transcriptLanguage by rememberSaveable { mutableStateOf("auto") }
    var targetClipDurationMs by rememberSaveable { mutableLongStateOf(30_000L) }
    var maxHighlights by rememberSaveable { mutableIntStateOf(10) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var preparingModel by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var modelMessage by remember { mutableStateOf("Preparing the bundled offline AI model…") }
    var analyzing by remember { mutableStateOf(false) }
    var analysisElapsedSeconds by remember { mutableLongStateOf(0L) }
    var transcriptionElapsedSeconds by remember { mutableLongStateOf(0L) }
    var currentPart by remember { mutableIntStateOf(0) }
    var analysisStartedAt by remember { mutableLongStateOf(0L) }
    var transcriptionStartedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        if (!ready) {
            preparingModel = true
            modelMessage = "Installing bundled AI model · no internet required…"
            try {
                WhisperModelManager.ensureModel(context) { done, total ->
                    withContext(Dispatchers.Main) {
                        progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                }
                ready = true
                modelMessage = "Verified and ready. AI works offline."
            } catch (error: Exception) {
                modelMessage = error.localizedMessage ?: "Bundled AI model setup failed. Retry below."
            } finally {
                preparingModel = false
            }
        }
    }

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
    var message by remember { mutableStateOf(if (initialVideoUri == null) "Import a video to discover its best moments." else "Your creator live recording is ready. Run AI highlight detection.") }
    var clips by remember { mutableStateOf<List<ClipCandidate>>(emptyList()) }
    var selectedClips by remember { mutableStateOf<Set<ClipCandidate>>(emptySet()) }
    val batchLimit = BatchClipExporter.MAX_CLIPS_PER_BATCH
    val batchStatus by BatchExportCoordinator.status.collectAsState()
    val batchExporting = batchStatus.running
    var activeSection by rememberSaveable { mutableStateOf("Editor") }
    var liveMarkers by remember { mutableStateOf<List<LiveChatHighlightMarker>>(emptyList()) }
    var activeLiveSessionId by remember { mutableStateOf<Long?>(null) }
    var transcriptSegments by remember { mutableStateOf<List<TranscriptSegment>>(emptyList()) }
    var pendingCaptionCues by remember { mutableStateOf<List<CaptionCue>?>(null) }
    var editing by remember { mutableStateOf<ClipCandidate?>(null) }
    var previewingClip by remember { mutableStateOf<ClipCandidate?>(null) }
    var editStart by remember { mutableLongStateOf(0L) }
    var editEnd by remember { mutableLongStateOf(0L) }
    var editorStartMs by rememberSaveable { mutableLongStateOf(0L) }
    var editorEndMs by rememberSaveable { mutableLongStateOf(0L) }
    var exportMessage by remember { mutableStateOf("") }
    var exportAspectRatio by rememberSaveable { mutableFloatStateOf(9f / 16f) }
    var exportAspectLabel by rememberSaveable { mutableStateOf("9:16 Shorts") }
    var verticalLayoutChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var facecamCorner by remember(video) { mutableStateOf<String?>(null) }
    var facecamRegion by remember(video) { mutableStateOf<StreamRegion?>(null) }
    var facecamDetectionBusy by remember(video) { mutableStateOf(false) }
    var facecamDetectionMessage by remember(video) { mutableStateOf("") }
    var facecamScanRequest by remember(video) { mutableIntStateOf(0) }
    var adjustFacecam by remember(video) { mutableStateOf(false) }
    val splitSelected = exportAspectRatio == 9f / 16f && verticalLayoutChoice == "split"
    val splitRegion = facecamRegion.takeIf { splitSelected }
    LaunchedEffect(video, splitSelected, duration > 0L, facecamScanRequest) {
        val source = video ?: return@LaunchedEffect
        if (!splitSelected || duration <= 0L || facecamRegion != null) return@LaunchedEffect
        facecamDetectionBusy = true
        facecamDetectionMessage = "Finding the facecam…"
        try {
            facecamRegion = StreamFacecamDetector.detect(context, source, duration)
            facecamDetectionMessage = if (facecamRegion == null) {
                "Facecam could not be located reliably. Select a corner and adjust the region."
            } else {
                "Facecam detected. Preview the split or adjust the region below."
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            facecamDetectionMessage = "Facecam detection failed. Select a corner to set the region."
        } finally {
            facecamDetectionBusy = false
        }
    }
    var previewRetryKey by remember(video) { mutableIntStateOf(0) }
    var previewError by remember(video) { mutableStateOf<String?>(null) }
    var videoDisplayName by remember(video) { mutableStateOf("") }
    var pending by remember { mutableStateOf<ClipCandidate?>(null) }
    var burnCaptions by rememberSaveable { mutableStateOf(true) }
    var captionPreset by rememberSaveable { mutableStateOf(CaptionPreset.CLEAN.name) }
    var reviewed by remember { mutableStateOf<Map<ClipCandidate, ClipReviewDecision>>(emptyMap()) }
    var faceTrackingBusy by remember { mutableStateOf(false) }
    var faceTrackingMessage by remember { mutableStateOf("Face tracking analyzes sampled frames on-device.") }
    var faceTrackingPath by remember { mutableStateOf<List<CropFocus>>(emptyList()) }

    LaunchedEffect(video) {
        editorStartMs = 0L
        editorEndMs = 0L
        previewingClip = null
        faceTrackingPath = emptyList()
        faceTrackingMessage = "Face tracking analyzes sampled frames on-device."
    }

    LaunchedEffect(video) {
        val selected = video
        if (selected == null) {
            videoDisplayName = ""
            duration = 0L
        } else {
            val metadata = withContext(Dispatchers.IO) {
                val providerName = if (selected.scheme == "content") {
                    runCatching {
                        context.contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (index >= 0) cursor.getString(index) else null
                                } else null
                            }
                    }.getOrNull()
                } else null
                val displayName = providerName?.takeIf { it.isNotBlank() }
                    ?: selected.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "Selected video"
                val mediaDuration = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, selected)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(0L)
                displayName to mediaDuration
            }
            videoDisplayName = metadata.first
            if (metadata.second > 0L) duration = metadata.second
        }
    }

    LaunchedEffect(activeProjectId) {
        val projectId = activeProjectId ?: return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            projectDb.listHighlights(projectId) to projectDb.listTranscript(projectId)
        }
        savedProjectHighlights = restored.first
        clips = restored.first.map { ClipCandidate(it.startMs, it.endMs, it.title, it.transcript, it.score, it.reason) }
        transcriptSegments = restored.second
        reviewed = restored.first.mapNotNull { saved ->
            val candidate = ClipCandidate(saved.startMs, saved.endMs, saved.title, saved.transcript, saved.score, saved.reason)
            val decision = runCatching { ClipReviewDecision.valueOf(saved.reviewStatus) }.getOrDefault(ClipReviewDecision.PENDING)
            candidate to decision
        }.toMap()
        selectedClips = clips.filter { reviewed[it] != ClipReviewDecision.REJECTED }.take(batchLimit).toSet()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        video = uri; duration = 0L; clips = emptyList(); transcriptSegments = emptyList(); activeProjectId = null; editing = null
        message = if (uri == null) "No video selected." else "Video ready · run AI highlight detection."
    }
    var localImportBusy by remember { mutableStateOf(false) }
    var localImportStatus by remember { mutableStateOf("") }
    val offlineImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !localImportBusy) {
            localImportBusy = true
            localImportStatus = "Copying video into offline project storage…"
            scope.launch {
                try {
                    val imported = LocalVideoImporter.import(context, uri) { progress ->
                        val percent = progress.totalBytes?.takeIf { it > 0L }
                            ?.let { (progress.copiedBytes * 100L / it).coerceIn(0L, 100L) }
                        scope.launch {
                            localImportStatus = percent?.let { "Importing video · $it%" }
                                ?: "Importing video · ${progress.copiedBytes / (1024L * 1024L)} MB"
                        }
                    }
                    video = imported
                    duration = 0L
                    clips = emptyList()
                    transcriptSegments = emptyList()
                    activeProjectId = null
                    editing = null
                    message = "Offline video imported. Ready for resumable AI transcription."
                    localImportStatus = "Offline video imported successfully."
                    activeSection = "Editor"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    localImportStatus = e.localizedMessage ?: "Offline video import failed."
                } finally {
                    localImportBusy = false
                }
            }
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dest ->
        val src = video; val clip = pending
        if (dest != null && src != null && clip != null && (!splitSelected || splitRegion != null)) {
            val cues = if (burnCaptions) CaptionTrack.fromTranscript(transcriptSegments, clip.startMs, clip.endMs) else emptyList()
            ClipExporter.export(context, src, dest, clip.startMs, clip.endMs,
                aspectRatio = exportAspectRatio,
                faceTrackingPath = faceTrackingPath,
                splitFacecamRegion = splitRegion,
                captionCues = cues,
                captionPreset = runCatching { CaptionPreset.valueOf(captionPreset) }.getOrDefault(CaptionPreset.CLEAN),
                captionWords = if (burnCaptions && captionPreset == CaptionPreset.KARAOKE.name) {
                    CaptionTrack.alignedWords(transcriptSegments, clip.startMs, clip.endMs)
                } else emptyList(),
                onStatus = { exportMessage = it },
                onFinished = { _, result -> exportMessage = result; pending = null })
        } else { pending = null; if (dest == null) exportMessage = "Export cancelled." }
    }
    val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val source = video
        val chosen = clips.filter { it in selectedClips }
        if (treeUri == null) {
            exportMessage = "Batch export cancelled."
        } else if (splitSelected && splitRegion == null) {
            exportMessage = "Set the facecam region in Studio before exporting."
        } else if (source == null || chosen.isEmpty()) {
            exportMessage = "Select at least one highlight before exporting."
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) { }
            BatchExportCoordinator.start(
                context, treeUri, source, chosen, exportAspectRatio,
                if (burnCaptions) transcriptSegments else emptyList(),
                runCatching { CaptionPreset.valueOf(captionPreset) }.getOrDefault(CaptionPreset.CLEAN),
                faceTrackingPath = faceTrackingPath,
                splitFacecamRegion = splitRegion
            )
        }
    }
    val captionPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { dest ->
        val cues = pendingCaptionCues
        if (dest != null && cues != null) {
            try {
                val output = context.contentResolver.openOutputStream(dest, "w")
                    ?: error("Could not open the selected subtitle save location.")
                output.use { it.write(CaptionTrack.toSrt(cues).toByteArray(Charsets.UTF_8)) }
                exportMessage = "Subtitles exported as SRT."
            } catch (error: Exception) {
                exportMessage = "Subtitle export failed: ${error.localizedMessage ?: "unknown error"}"
            }
        } else if (dest == null) {
            exportMessage = "Subtitle export cancelled."
        }
        pendingCaptionCues = null
    }
    val transcriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { dest ->
        if (dest != null) {
            try {
                val output = context.contentResolver.openOutputStream(dest, "w")
                    ?: error("Could not open the selected transcript save location.")
                output.use { it.write(TranscriptTextExport.format(transcriptSegments).toByteArray(Charsets.UTF_8)) }
                exportMessage = "Full transcript exported as TXT."
            } catch (error: Exception) {
                exportMessage = "Transcript export failed: ${error.localizedMessage ?: "unknown error"}"
            }
        } else {
            exportMessage = "Transcript export cancelled."
        }
    }
    fun save(clip: ClipCandidate) {
        if (splitSelected && splitRegion == null) {
            exportMessage = "Set the facecam region in Studio before exporting."
            return
        }
        pending = clip
        savePicker.launch("ClipForge_${clip.startMs / 1000}s.mp4")
    }
    fun saveCaptions(clip: ClipCandidate) {
        val cues = CaptionTrack.fromTranscript(transcriptSegments, clip.startMs, clip.endMs)
        if (cues.isEmpty()) {
            exportMessage = "No transcript captions are available for this clip."
        } else {
            pendingCaptionCues = cues
            captionPicker.launch("ClipForge_${clip.startMs / 1000}s.srt")
        }
    }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Scaffold(
            containerColor = Ink,
            topBar = {
                Column(
                    Modifier.fillMaxWidth().background(Ink).statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).background(Accent, RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = null, tint = Ink)
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("ClipForge", fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                            Text("CREATOR STUDIO", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.size(6.dp).background(if (ready) Accent else Muted, CircleShape))
                                Text(if (ready) "ON-DEVICE AI" else "SETTING UP", color = if (ready) Accent else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (activeSection) {
                            "Editor" -> "Your workspace"
                            "Studio" -> "Create with AI"
                            "Highlights" -> "Your highlights"
                            "Projects" -> "Recent projects"
                            else -> "Live workspace"
                        },
                        color = Color(0xFFF0F2E8), fontSize = 27.sp, lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.85).sp, maxLines = 1
                    )
                    Text(
                        when (activeSection) {
                            "Editor" -> if (video == null) "Import footage to start trimming and framing." else videoDisplayName
                            "Studio" -> "Import footage and let on-device AI find the moments."
                            "Highlights" -> "Choose, refine and export your clips."
                            "Projects" -> "Continue editing a saved project."
                            else -> "Capture authorised streams and inspect live signals."
                        },
                        Modifier.padding(top = 3.dp), color = Muted, fontSize = 11.sp, maxLines = 1
                    )
                }
            },
            bottomBar = {
                val destinations = listOf(
                    Triple("Editor", "Editor", Icons.Default.Tune),
                    Triple("Studio", "AI Studio", Icons.Default.AutoAwesome),
                    Triple("Highlights", "Clips", Icons.Default.ContentCut),
                    Triple("Projects", "Library", Icons.Default.FolderOpen),
                    Triple("Live", "Live", Icons.Default.LiveTv)
                )
                NavigationBar(containerColor = Panel, contentColor = Color(0xFFF0F2E8), tonalElevation = 0.dp) {
                    destinations.forEach { (section, label, icon) ->
                        NavigationBarItem(
                            selected = activeSection == section,
                            onClick = {
                                activeSection = section
                                if (section != "Highlights") previewingClip = null
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent,
                                indicatorColor = Accent.copy(alpha = 0.13f),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { contentPadding ->
            Column(
                Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            if (activeSection == "Editor") {
                val source = video
                when {
                    source == null -> Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(30.dp))
                            Text("Start creating", fontWeight = FontWeight.Bold, fontSize = 21.sp)
                            Text("Bring in footage, refine the timeline, and export clips ready for Shorts or Reels.", color = Muted, fontSize = 13.sp)
                            Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FolderOpen, null)
                                Text("  Choose video")
                            }
                        }
                    }
                    duration <= 0L -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> MiniEditorWorkspace(
                        uri = source,
                        sourceName = videoDisplayName,
                        durationMs = duration,
                        initialStartMs = editorStartMs.coerceIn(0L, (duration - 500L).coerceAtLeast(0L)),
                        initialEndMs = if (editorEndMs <= 0L || editorEndMs > duration) duration else editorEndMs,
                        aspectRatio = exportAspectRatio,
                        burnCaptions = burnCaptions,
                        onStartChange = { editorStartMs = it },
                        onEndChange = { editorEndMs = it },
                        onAspectRatioChange = { ratio ->
                            if (exportAspectRatio != ratio) {
                                faceTrackingPath = emptyList()
                                faceTrackingMessage = "Output ratio changed. Re-analyze faces before exporting for accurate framing."
                            }
                            exportAspectRatio = ratio
                            exportAspectLabel = when (ratio) {
                                1f -> "1:1 Square"
                                else -> if (ratio < 1f) "9:16 Shorts" else "16:9 YouTube"
                            }
                        },
                        onBurnCaptionsChange = { burnCaptions = it },
                        onExport = { startMs, endMs ->
                            save(ClipCandidate(startMs, endMs, "Manual edit", "", 0, "Edited in timeline"))
                        }
                    )
                }
            }
            if (activeSection == "Studio" && video != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("PREVIEW CANVAS", color = Color(0xFFE4E4E7), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                                Text(videoDisplayName.ifBlank { "Selected video" }, color = Color.White, fontSize = 12.sp, maxLines = 1)
                            }
                            Text(when {
                                previewError != null -> "Preview error"
                                duration > 0L -> time(duration)
                                else -> "Preparing…"
                            }, color = Muted, fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val source = video ?: return@OutlinedButton
                                scope.launch {
                                    faceTrackingBusy = true
                                    faceTrackingMessage = "Scanning frames for faces…"
                                    try {
                                        faceTrackingPath = FaceTrackingAnalyzer.analyze(context, source, 0L, duration, exportAspectRatio)
                                        faceTrackingMessage = if (faceTrackingPath.isEmpty()) "No faces detected. Center crop will be used."
                                            else "Tracked a face across ${faceTrackingPath.size} sampled moments. Tracking path is ready."
                                    } catch (error: Exception) {
                                        faceTrackingMessage = "Face scan failed: ${error.localizedMessage ?: "unknown error"}"
                                    } finally { faceTrackingBusy = false }
                                }
                            },
                            enabled = !faceTrackingBusy && duration > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (faceTrackingBusy) "Detecting faces…" else "Analyze face tracking") }
                        Text(faceTrackingMessage, color = Muted, fontSize = 11.sp)
                        Text(
                            if (faceTrackingPath.isEmpty()) "No face path is active. Export uses a centered crop unless faces are detected."
                            else "Face-aware framing is active for exports and follows the detected subject.",
                            color = Muted, fontSize = 10.sp
                        )
                        VideoPreview(
                            uri = video!!,
                            onDuration = { if (it > 0L) { duration = it; previewError = null } },
                            onError = { previewError = it },
                            retryKey = previewRetryKey,
                            aspectRatio = exportAspectRatio
                        )
                        if (previewError != null) {
                            Text("Could not preview this video: ${previewError}", color = Color(0xFFFF9A9A), fontSize = 11.sp)
                            OutlinedButton(onClick = { previewError = null; previewRetryKey++ }, modifier = Modifier.fillMaxWidth()) {
                                Text("Retry preview")
                            }
                        }
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("9:16" to (9f / 16f), "16:9" to (16f / 9f), "1:1" to 1f).forEach { (label, ratio) ->
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                        .background(if (exportAspectRatio == ratio) Accent.copy(alpha = .22f) else Ink)
                                        .selectable(
                                            selected = exportAspectRatio == ratio,
                                            enabled = !batchExporting,
                                            role = Role.RadioButton
                                        ) {
                                            if (exportAspectRatio != ratio) {
                                                faceTrackingPath = emptyList()
                                                faceTrackingMessage = "Output ratio changed. Re-analyze faces before exporting for accurate framing."
                                            }
                                            exportAspectRatio = ratio
                                            exportAspectLabel = when (label) {
                                                "9:16" -> "9:16 Shorts"
                                                "16:9" -> "16:9 YouTube"
                                                else -> "1:1 Square"
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (exportAspectRatio == ratio) Color.White else Muted,
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            if (activeSection == "Studio") {
            val youtubeLink = YouTubeLinkClassifier.isYouTubeUrl(linkText)
            OutlinedTextField(value = linkText, onValueChange = { linkText = it }, enabled = !importingLink,
                modifier = Modifier.fillMaxWidth(), label = { Text("YouTube or direct video URL") }, singleLine = true)
            if (youtubeLink) {
                Text("Maximum download quality", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720, 1080, 2160).forEach { height ->
                        FilterChip(selected = youtubeQuality == height, onClick = { youtubeQuality = height },
                            enabled = !importingLink, label = { Text("${height}p") })
                    }
                }
            }
            OutlinedButton(onClick = {
                val url = linkText.trim()
                importingLink = true
                linkStatus = if (youtubeLink) "Preparing YouTube download…" else "Checking direct video link…"
                scope.launch {
                    try {
                        val imported = if (youtubeLink) {
                            YouTubeVideoImporter.download(context, url, youtubeQuality) { percent ->
                                scope.launch { if (importingLink && !linkStatus.startsWith("Download failed:")) linkStatus = "Downloading and preparing video · $percent%" }
                            }
                        } else DirectVideoImporter.download(context, url) { p ->
                            if (p.downloadedBytes % (1024L * 1024L) < 64L * 1024L) scope.launch { linkStatus = "Downloaded ${p.downloadedBytes / 1_048_576} MB" }
                        }
                        video = imported; duration = 0L; clips = emptyList(); transcriptSegments = emptyList(); activeProjectId = null
                        editing = null
                        linkStatus = "Video imported."
                        activeSection = "Editor"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) { linkStatus = "Download failed: ${e.localizedMessage ?: "Video import failed."}" }
                    finally { importingLink = false }
                }
            }, enabled = linkText.isNotBlank() && !importingLink && !analyzing, modifier = Modifier.fillMaxWidth()) {
                Text(if (importingLink) "Importing video…" else if (youtubeLink) "Download and clip" else "Import from direct link")
            }
            if (youtubeLink) {
                if (exportAspectRatio == 9f / 16f) {
                    VerticalLayoutChoices(verticalLayoutChoice, !importingLink) { verticalLayoutChoice = it }
                }
                OutlinedButton(
                    onClick = {
                        val url = linkText.trim()
                        val quality = youtubeQuality
                        val language = transcriptLanguage.takeUnless { it == "auto" }
                        val clipLength = targetClipDurationMs
                        importingLink = true
                        scope.launch {
                            try {
                                linkStatus = "Reading recording duration…"
                                val recordingMs = YouTubeDurationProbe.durationMs(context, url)
                                val results = DownloadWhileAnalyzing.runYouTubeSections(
                                    context = context,
                                    url = url,
                                    height = quality,
                                    durationMs = recordingMs,
                                    stateDirectory = File(context.filesDir, "progressive-analysis"),
                                    language = language,
                                    targetClipDurationMs = clipLength,
                                    onStatus = { status -> linkStatus = status },
                                    onDownloadProgress = { completed, total ->
                                        linkStatus = "Sections downloaded: $completed / $total"
                                    }
                                )
                                val project = withContext(Dispatchers.IO) {
                                    val id = projectDb.saveCompletedAnalysis(
                                        "YouTube ${YouTubeLiveApiClient.parseVideoId(url)}", results.source.toString(),
                                        results.durationMs, results.highlights, results.transcript
                                    )
                                    id to projectDb.listProjects()
                                }
                                video = results.source
                                duration = results.durationMs
                                clips = results.highlights
                                selectedClips = results.highlights.take(batchLimit).toSet()
                                transcriptSegments = results.transcript
                                reviewed = emptyMap()
                                editing = null
                                activeProjectId = project.first
                                savedProjects = project.second
                                linkStatus = "Recording imported and analyzed · ${results.highlights.size} highlights"
                                activeSection = if (results.highlights.isNotEmpty() && !splitSelected) "Highlights" else "Studio"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                linkStatus = "Progressive analysis unavailable: ${e.localizedMessage ?: "Try standard download."}"
                            } finally {
                                importingLink = false
                            }
                        }
                    },
                    enabled = ready && !importingLink && !analyzing && linkText.isNotBlank() &&
                        (exportAspectRatio != 9f / 16f || verticalLayoutChoice != null),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Analyze during download (experimental)") }
                Text(
                    "Analyzes playable sections while the recording downloads, then opens the recording and its clips for review.",
                    color = Muted, fontSize = 11.sp
                )
            }
            if (linkStatus.isNotBlank()) Text(linkStatus, color = Muted, fontSize = 11.sp)
            }
            if (activeSection == "Projects") {
                if (savedProjects.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.FolderOpen, null, tint = Accent, modifier = Modifier.size(28.dp))
                            Text("Your library is empty", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Completed analyses are saved here so you can reopen their highlights and transcripts.", color = Muted, fontSize = 12.sp)
                            Button(onClick = { activeSection = "Studio" }, modifier = Modifier.fillMaxWidth()) { Text("Start a project") }
                        }
                    }
                }
            if (savedProjects.isNotEmpty()) {
                Text("Saved projects", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                savedProjects.take(8).forEach { project ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(project.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${project.status} · ${project.durationMs / 60000} min · ${projectDb.listHighlights(project.id).size} highlights", color = Muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                video = Uri.parse(project.sourceUri); duration = project.durationMs
                                activeProjectId = project.id
                                savedProjectHighlights = projectDb.listHighlights(project.id)
                                clips = savedProjectHighlights.map { ClipCandidate(it.startMs, it.endMs, it.title, it.transcript, it.score) }
                                selectedClips = clips.take(batchLimit).toSet()
                                transcriptSegments = projectDb.listTranscript(project.id)
                                message = "Restored saved project."
                                activeSection = if (clips.isNotEmpty()) "Highlights" else "Editor"
                            }) { Text("Open") }
                        }
                    }
                }
            }
            }
            if (activeSection == "Live") {
            Text("Your live tools", fontSize = 21.sp, fontWeight = FontWeight.Bold)

            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WHAT LIVE MODE DOES", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("ClipForge can monitor public chat activity and mark likely moments. A YouTube watch URL does not give the app a video recording.", color = Color(0xFFE0E4D9), fontSize = 12.sp, lineHeight = 17.sp)
                    Text("Import an authorised recording to turn those markers into clips. Check the broadcast-to-recording offset before exporting.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            if (liveMarkers.isNotEmpty()) {
                Text("${liveMarkers.size} live highlight marker(s) collected", color = Muted, fontSize = 12.sp)
                Button(
                    onClick = {
                        if (video == null || duration <= 0L) {
                            message = "Import your authorised original recording before creating clips from live markers."
                        } else {
                            val liveSession = projectDb.listLiveSessions().firstOrNull()
                            val recordingMatchesSession = liveSession != null && liveSession.recordingPath.isNotBlank() &&
                                video?.scheme == "file" && video?.path == liveSession.recordingPath
                            val recordingOffsetMs = if (recordingMatchesSession) liveSession?.recordingOffsetMs ?: 0L else 0L
                            val hasRecordingStart = recordingMatchesSession
                            val candidates = liveMarkers.mapNotNull { marker ->
                                val markerTimeMs = LiveMarkerTimeline.toRecordingTime(marker.startMs, recordingOffsetMs)
                                    ?: return@mapNotNull null
                                val clipStart = (markerTimeMs - 20_000L).coerceAtLeast(0L)
                                val clipEnd = minOf(duration, markerTimeMs + 10_000L)
                                if (clipEnd <= clipStart) null else ClipCandidate(
                                    startMs = clipStart,
                                    endMs = clipEnd,
                                    title = "Live chat spike · ${time(markerTimeMs)}",
                                    hook = "${marker.messageCount} chat messages · baseline median ${"%.1f".format(marker.baselineMedian)}",
                                    score = 70
                                )
                            }
                            if (candidates.isEmpty()) {
                                message = "No live markers fall inside this recording. Check that the recording starts with the broadcast and covers the marked times."
                            } else {
                                val mergedCandidates = (clips + candidates).distinctBy { it.startMs to it.endMs }
                                    .sortedByDescending { it.score }
                                clips = mergedCandidates
                                selectedClips = (selectedClips + candidates).take(batchLimit).toSet()
                                activeSection = "Highlights"
                                message = if (hasRecordingStart) "Live markers automatically aligned to the local recording start. Review candidates before exporting." else "No linked recording start time was saved. Markers use broadcast-relative times; verify alignment manually before exporting."
                            }
                        }
                    },
                    enabled = video != null && duration > 0L && !analyzing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create clips from live markers") }
            }

            }
            if (activeSection == "Live") {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("RECORD A LIVESTREAM ON THIS PHONE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Capture a livestream that is playing in another app or browser, then use ClipForge to find its best moments.", color = Color(0xFFE0E4D9), fontSize = 12.sp, lineHeight = 17.sp)
                        Text("Android will ask for screen-sharing permission. Record only content you are authorised to capture. Protected content may block recording.", color = Muted, fontSize = 11.sp)
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(context, ScreenCaptureSetupActivity::class.java)
                                        .putExtra(ScreenCaptureService.EXTRA_LOCAL_ONLY, true)
                                        .putExtra(MainActivity.EXTRA_LIVE_SESSION_ID, activeLiveSessionId ?: -1L)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start local livestream recording") }
                    }
                }
            }
            YouTubeLivePanel(
                database = projectDb,
                initialVideoUrl = pendingYoutubeUrl,
                visible = activeSection == "Live",
                onMonitoringStarted = { liveMarkers = emptyList(); activeLiveSessionId = null },
                onActiveSessionChanged = { activeLiveSessionId = it },
                onMarker = { marker ->
                    liveMarkers = (liveMarkers + marker).distinctBy { it.startMs }.sortedBy { it.startMs }.takeLast(100)
                },
                onMarkersRestored = { restored ->
                    liveMarkers = restored.distinctBy { it.startMs }.sortedBy { it.startMs }.takeLast(100)
                }
            )
            if (activeSection == "Studio") {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("START A PROJECT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Text(if (video == null) "Import your long-form video" else if (linkStatus.startsWith("Download failed:")) "Previously selected video" else "Your video is ready", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Podcasts, gameplay, interviews and more. Choose a video stored on your phone.", color = Muted, fontSize = 12.sp)
                    Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Default.FolderOpen, null); Text(if (video == null) "  Import video" else "  Change video")
                    }
                    OutlinedButton(
                        onClick = { offlineImportPicker.launch(arrayOf("video/*")) },
                        enabled = !localImportBusy && !analyzing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (localImportBusy) "Copying offline video…" else "Copy video for reliable offline access")
                    }
                    if (localImportStatus.isNotBlank()) {
                        Text(localImportStatus, color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                        Text("  ON-DEVICE AI ENGINE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (ready) "READY" else "SETUP", color = if (ready) Color(0xFF75D6A2) else Muted, fontSize = 10.sp)
                    }
                    Text(WhisperModelManager.MODEL_NAME, fontWeight = FontWeight.SemiBold)
                    Text("Included in the APK · approx. 78 MB · no model download or sign-in", color = Muted, fontSize = 11.sp)
                    Box {
                        OutlinedButton(onClick = { languageMenuExpanded = true }, enabled = !analyzing) {
                            Text("Transcript language · " + when (transcriptLanguage) {
                                "en" -> "English"
                                "ml" -> "Malayalam"
                                "hi" -> "Hindi"
                                "ta" -> "Tamil"
                                "te" -> "Telugu"
                                "kn" -> "Kannada"
                                "bn" -> "Bengali"
                                "ur" -> "Urdu"
                                "ar" -> "Arabic"
                                else -> "Auto-detect"
                            })
                        }
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false }
                        ) {
                            listOf(
                                "auto" to "Auto-detect",
                                "en" to "English",
                                "ml" to "Malayalam",
                                "hi" to "Hindi",
                                "ta" to "Tamil",
                                "te" to "Telugu",
                                "kn" to "Kannada",
                                "bn" to "Bengali",
                                "ur" to "Urdu",
                                "ar" to "Arabic"
                            ).forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        transcriptLanguage = code
                                        languageMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (preparingModel) LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        scope.launch {
                            preparingModel = true
                            try {
                                WhisperModelManager.ensureModel(context) { done, total ->
                                    withContext(Dispatchers.Main) { progress = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) }
                                }
                                ready = true; modelMessage = "Verified. Ready for offline analysis."
                            } catch (e: Exception) { modelMessage = e.localizedMessage ?: "Bundled model setup failed." }
                            finally { preparingModel = false }
                        }
                    }, enabled = !ready && !preparingModel, modifier = Modifier.fillMaxWidth()) {
                        Text(if (preparingModel) "Preparing bundled model…" else if (ready) "AI model ready" else "Prepare bundled AI model")
                    }
                    Text(modelMessage, color = Muted, fontSize = 11.sp)
                }
            }

            if (video != null) {
                Text("TARGET SHORT LENGTH", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(15_000L to "15 sec", 30_000L to "30 sec", 45_000L to "45 sec", 60_000L to "60 sec").forEach { (lengthMs, label) ->
                        FilterChip(
                            selected = targetClipDurationMs == lengthMs,
                            onClick = { targetClipDurationMs = lengthMs },
                            label = { Text(label, fontSize = 10.sp) },
                            enabled = !analyzing
                        )
                    }
                }
                Text("AI searches for moments around this length and keeps speech boundaries inside the selected range.", color = Muted, fontSize = 11.sp)
                Text("MAXIMUM HIGHLIGHTS", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 20, 50, 100).forEach { count ->
                        FilterChip(
                            selected = maxHighlights == count,
                            onClick = { maxHighlights = count },
                            label = { Text("$count clips", fontSize = 10.sp) },
                            enabled = !analyzing
                        )
                    }
                }
                Text("For long recordings, choose up to 100 candidates. Actual results depend on available speech and distinct moments.", color = Muted, fontSize = 11.sp)
                if (exportAspectRatio < 0.8f) {
                    if (!YouTubeLinkClassifier.isYouTubeUrl(linkText)) {
                        VerticalLayoutChoices(verticalLayoutChoice, !analyzing && !importingLink) { verticalLayoutChoice = it }
                    }
                    if (splitSelected) {
                        if (facecamDetectionMessage.isNotBlank()) {
                            Text(facecamDetectionMessage, color = Muted, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                facecamCorner = null
                                facecamRegion = null
                                facecamScanRequest++
                            },
                            enabled = !analyzing && !facecamDetectionBusy && duration > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (facecamDetectionBusy) "Detecting facecam…" else "Detect facecam again") }
                        listOf("Top", "Bottom").forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("left", "right").forEach { side ->
                                    val corner = "$row $side"
                                    FilterChip(
                                        selected = facecamCorner == corner,
                                        onClick = {
                                            facecamCorner = corner
                                            val left = if (side == "left") 0f else 0.68f
                                            val top = if (row == "Top") 0f else 0.62f
                                            facecamRegion = StreamRegion(left, top, (left + 0.32f).coerceAtMost(1f), (top + 0.38f).coerceAtMost(1f))
                                            facecamDetectionMessage = "Manual facecam corner selected."
                                        },
                                        label = { Text(corner) },
                                        enabled = !analyzing && !facecamDetectionBusy
                                    )
                                }
                            }
                        }
                        splitRegion?.let { region ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { adjustFacecam = !adjustFacecam },
                                    enabled = !analyzing
                                ) { Text(if (adjustFacecam) "Done adjusting" else "Adjust region") }
                                OutlinedButton(
                                    onClick = {
                                        val start = editorStartMs.coerceIn(0L, duration - 1)
                                        previewingClip = ClipCandidate(start, minOf(duration, start + 5_000L), "Split layout sample", "", 0)
                                    },
                                    enabled = duration > 0L && !analyzing
                                ) { Text("Preview split") }
                            }
                            if (adjustFacecam) FacecamRegionControls(region) { facecamRegion = it }
                        }
                    }
                }

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
                            val analysis = VideoTranscriber.transcribeWithSignals(context, src, language = transcriptLanguage.takeUnless { it == "auto" }, onStatus = { status ->
                                message = status
                                val partMatch = Regex("part (\\d+)").find(status)
                                if (partMatch != null) {
                                    if (transcriptionStartedAt == 0L) transcriptionStartedAt = SystemClock.elapsedRealtime()
                                    currentPart = partMatch.groupValues[1].toIntOrNull() ?: currentPart
                                }
                            })
                            val transcript = analysis.segments
                            transcriptSegments = transcript
                            message = "Analyzing video motion and scene changes offline…"
                            val visualSignals = VisualUnderstandingEngine.analyze(context, src, duration)
                            message = "Tracking faces automatically for vertical framing…"
                            faceTrackingPath = FaceTrackingAnalyzer.analyze(context, src, 0L, duration, exportAspectRatio)
                            faceTrackingMessage = if (faceTrackingPath.isEmpty()) {
                                "No faces detected. Exports will use a centered crop."
                            } else {
                                "Face-aware framing ready · ${faceTrackingPath.size} tracked moments."
                            }
                            message = "Ranking speech, silence and scene changes…"
                            val found = withContext(Dispatchers.Default) {
                                rankVideoHighlights(transcript, analysis.audioEnergy, visualSignals, duration, targetClipDurationMs, maxHighlights)
                            }
                            val existingProjectId = activeProjectId
                            val persisted = withContext(Dispatchers.IO) {
                                val id = existingProjectId ?: projectDb.saveProject(
                                    title = src.lastPathSegment?.substringAfterLast('/')?.take(100)?.ifBlank { "ClipForge project" } ?: "ClipForge project",
                                    sourceUri = src.toString(),
                                    durationMs = duration,
                                    status = "completed"
                                )
                                projectDb.saveHighlights(id, found)
                                projectDb.saveTranscript(id, transcript)
                                id to projectDb.listProjects()
                            }
                            activeProjectId = persisted.first
                            savedProjects = persisted.second
                            clips = found
                            selectedClips = found.take(batchLimit).toSet()
                            if (found.isNotEmpty()) activeSection = "Highlights"
                            message = if (found.isEmpty()) "No clear highlights detected. Try a longer video with visible action."
                                else "Analysis complete · ${found.size} clips ranked"
                        } catch (e: Exception) { message = e.localizedMessage ?: "Analysis failed. Try again." }
                        finally { analyzing = false }
                    }
                }, enabled = ready && !analyzing && duration > 0L && (exportAspectRatio != 9f / 16f || (verticalLayoutChoice != null && (!splitSelected || splitRegion != null))), modifier = Modifier.fillMaxWidth().height(54.dp)) {
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

            }
            if (activeSection == "Highlights" && clips.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(28.dp))
                        Text("No clips yet", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(if (video == null) "Import a long video first. ClipForge will transcribe it locally and rank moments for you." else "Your source is ready. Run the AI analysis to find moments worth clipping.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        Button(onClick = { activeSection = "Studio" }, modifier = Modifier.fillMaxWidth()) { Text(if (video == null) "Go to import" else "Open clip studio") }
                    }
                }
            }
            if (activeSection == "Highlights" && transcriptSegments.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("TRANSCRIPT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${transcriptSegments.size} timestamped speech segments available", color = Muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "ClipForge transcript",
                                            TranscriptTextExport.format(transcriptSegments)
                                        )
                                    )
                                    exportMessage = if (clipboard != null) "Transcript copied to clipboard." else "Could not access the clipboard."
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Copy transcript")
                            }
                            Button(
                                onClick = { transcriptPicker.launch("ClipForge_transcript.txt") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Export TXT")
                            }
                        }
                    }
                }
            }
            if (activeSection == "Highlights" && clips.isNotEmpty()) {
                val reviewableClips = clips.filter { reviewed[it] != ClipReviewDecision.REJECTED }.take(batchLimit)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI Highlights", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("${clips.size} moments ranked · ${selectedClips.size}/$batchLimit selected", color = Muted, fontSize = 11.sp)
                        Text("Signal scores compare content cues; they do not predict views or virality.", color = Muted, fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = { selectedClips = if (selectedClips.containsAll(reviewableClips)) emptySet() else reviewableClips.toSet() },
                        enabled = !batchExporting
                    ) {
                        Text(if (selectedClips.containsAll(reviewableClips)) "Clear selection" else "Select top ${reviewableClips.size}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = burnCaptions, onCheckedChange = { burnCaptions = it }, enabled = !batchExporting)
                    Column(Modifier.weight(1f)) {
                        Text("Burn captions into exported MP4", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Uses your on-device Whisper transcript", color = Muted, fontSize = 10.sp)
                    }
                    var presetMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { presetMenu = true }, enabled = burnCaptions) { Text(CaptionPreset.valueOf(captionPreset).label) }
                        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                            CaptionPreset.entries.forEach { preset ->
                                DropdownMenuItem(text = { Text(preset.label) }, onClick = { captionPreset = preset.name; presetMenu = false })
                            }
                        }
                    }
                }
                Text("Export format", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("9:16 Shorts" to (9f / 16f), "16:9 YouTube" to (16f / 9f), "1:1 Square" to 1f).forEach { (label, ratio) ->
                        FilterChip(
                            selected = exportAspectLabel == label,
                            onClick = {
                                if (exportAspectRatio != ratio) {
                                    faceTrackingPath = emptyList()
                                    faceTrackingMessage = "Output ratio changed. Re-analyze faces before exporting for accurate framing."
                                }
                                exportAspectLabel = label
                                exportAspectRatio = ratio
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            enabled = !batchExporting
                        )
                    }
                }
                Button(onClick = { selectedClips = clips.filter { reviewed[it] == ClipReviewDecision.APPROVED }.take(batchLimit).toSet() },
                    enabled = !batchExporting && reviewed.values.any { it == ClipReviewDecision.APPROVED },
                    modifier = Modifier.fillMaxWidth()) { Text("Select approved clips only") }
                Button(onClick = { batchPicker.launch(null) }, enabled = selectedClips.isNotEmpty() && !batchExporting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (batchExporting) "Exporting selected clips…" else "Export ${selectedClips.size} selected clips")
                }
                if (batchExporting) {
                    val fraction = if (batchStatus.total > 0) batchStatus.completed.toFloat() / batchStatus.total else 0f
                    LinearProgressIndicator(progress = fraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    Text("Verified ${batchStatus.completed} of ${batchStatus.total} clips", color = Muted, fontSize = 11.sp)
                }
                if (batchStatus.retryAvailable) {
                    OutlinedButton(
                        onClick = { batchPicker.launch(null) },
                        enabled = !batchExporting && selectedClips.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry remaining clips") }
                }
                clips.forEachIndexed { index, clip ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = clip in selectedClips,
                                    onCheckedChange = { checked -> selectedClips = if (checked) selectedClips + clip else selectedClips - clip },
                                    enabled = !batchExporting && (clip in selectedClips || selectedClips.size < batchLimit)
                                )
                                Box(Modifier.size(32.dp).background(Accent.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", color = Accent, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(clip.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text("${time(clip.startMs)} – ${time(clip.endMs)}", color = Muted, fontSize = 11.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${clip.score}", color = Accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    Text("SIGNAL", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                                }
                            }
                            Text(clip.hook, color = Color(0xFFE0E4D9), fontSize = 11.sp, maxLines = 3)
                            if (clip.reason.isNotBlank()) Text(clip.reason, color = AccentSoft, fontSize = 10.sp, maxLines = 2)
                            val decision = reviewed[clip] ?: ClipReviewDecision.PENDING
                            Text("Review · ${decision.name.lowercase().replaceFirstChar { it.uppercase() }}", color = when (decision) {
                                ClipReviewDecision.APPROVED -> Color(0xFF6EE7B7)
                                ClipReviewDecision.REJECTED -> Color(0xFFFCA5A5)
                                else -> Muted
                            }, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = {
                                    reviewed = reviewed + (clip to ClipReviewDecision.APPROVED)
                                    activeProjectId?.let { projectDb.updateHighlightReview(it, clip.startMs, clip.endMs, "APPROVED") }
                                }, modifier = Modifier.weight(1f), enabled = !batchExporting) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    reviewed = reviewed + (clip to ClipReviewDecision.REJECTED)
                                    selectedClips = selectedClips - clip
                                    activeProjectId?.let { projectDb.updateHighlightReview(it, clip.startMs, clip.endMs, "REJECTED") }
                                }, modifier = Modifier.weight(1f), enabled = !batchExporting) { Text("Reject") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { previewingClip = clip }, modifier = Modifier.weight(1f), enabled = video != null) {
                                    Icon(Icons.Default.PlayArrow, null); Text("  Preview")
                                }
                                OutlinedButton(onClick = { editing = clip; editStart = clip.startMs; editEnd = clip.endMs }, modifier = Modifier.weight(1f)) { Text("Edit timing") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { saveCaptions(clip) }, modifier = Modifier.weight(1f)) { Text("Export SRT") }
                                Button(onClick = { save(clip) }, modifier = Modifier.weight(1f)) { Text("Export MP4") }
                            }
                        }
                    }
                }
            }
            previewingClip?.let { clip ->
                val source = video
                if (source != null) {
                    AlertDialog(
                        onDismissRequest = { previewingClip = null },
                        title = { Text(clip.title, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "${time(clip.startMs)} – ${time(clip.endMs)} · ${((clip.endMs - clip.startMs) / 1000L)} sec",
                                    color = Muted, fontSize = 11.sp
                                )
                                val cues = if (burnCaptions) {
                                    CaptionTrack.fromTranscript(transcriptSegments, clip.startMs, clip.endMs)
                                } else emptyList()
                                val words = if (burnCaptions && captionPreset == CaptionPreset.KARAOKE.name) {
                                    CaptionTrack.alignedWords(transcriptSegments, clip.startMs, clip.endMs)
                                } else emptyList()
                                when {
                                    splitSelected && splitRegion == null -> Text("Set the facecam region in Studio to preview this layout.")
                                    splitSelected || faceTrackingPath.isNotEmpty() || cues.isNotEmpty() -> {
                                        RenderedClipPreview(source, clip, exportAspectRatio, faceTrackingPath, splitRegion,
                                            cues, CaptionPreset.valueOf(captionPreset), words)
                                    }
                                    else -> {
                                        var clipPreviewError by remember(source, clip) { mutableStateOf<String?>(null) }
                                        VideoPreview(
                                            uri = source, onDuration = { }, onError = { clipPreviewError = it },
                                            retryKey = previewRetryKey, aspectRatio = exportAspectRatio,
                                            startAtMs = clip.startMs, endAtMs = clip.endMs
                                        )
                                        clipPreviewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                if (clip.hook.isNotBlank()) Text(clip.hook, color = Muted, fontSize = 11.sp, maxLines = 4)
                            }
                        },
                        confirmButton = { TextButton(onClick = { previewingClip = null }) { Text("Close") } }
                    )
                }
            }
            if (activeSection == "Highlights") editing?.let { clip ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("QUICK CLIP EDITOR", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(clip.title, fontWeight = FontWeight.Bold)
                        Text("Start · ${time(editStart)}", color = Muted, fontSize = 12.sp)
                        Slider(editStart.toFloat(), { editStart = it.toLong() }, valueRange = 0f..(editEnd - 1000).coerceAtLeast(0).toFloat())
                        Text("End · ${time(editEnd)}", color = Muted, fontSize = 12.sp)
                        Slider(editEnd.toFloat(), { editEnd = it.toLong() }, valueRange = (editStart + 1000).toFloat()..duration.coerceAtLeast(editStart + 1000).toFloat())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = null }, modifier = Modifier.weight(1f)) { Text("Close") }
                            Button(
                                onClick = {
                                    val trimmed = clip.copy(startMs = editStart, endMs = editEnd)
                                    activeProjectId?.let { projectDb.updateHighlightTrim(it, clip.startMs, clip.endMs, editStart, editEnd) }
                                    clips = clips.map { if (it == clip) trimmed else it }
                                    selectedClips = selectedClips.map { if (it == clip) trimmed else it }.toSet()
                                    reviewed = reviewed.entries.associate { (candidate, decision) ->
                                        (if (candidate == clip) trimmed else candidate) to decision
                                    }
                                    editing = null
                                },
                                enabled = editStart >= 0L && editEnd > editStart && editEnd <= duration,
                                modifier = Modifier.weight(1f)
                            ) { Text("Save trim") }
                        }
                    }
                }
            }
            val visibleExportMessage = batchStatus.message.ifBlank { exportMessage }
            if (visibleExportMessage.isNotBlank()) Text(visibleExportMessage, color = Muted, fontSize = 12.sp)
            if (activeSection == "Studio" && video == null) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(27.dp))
                        Text("Your clip studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Import a video to preview it, transcribe speech, rank standout moments, fine-tune clips and export MP4s.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PRIVATE · ON-DEVICE WORKFLOW", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                Text("v0.6.2", color = Muted, fontSize = 10.sp)
            }
            }
        }
    }
}
private fun formatElapsed(seconds: Long): String = "%d:%02d".format(seconds / 60L, seconds % 60L)

private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)

@Composable
private fun VerticalLayoutChoices(choice: String?, enabled: Boolean, onChange: (String) -> Unit) {
    Text("9:16 clip layout", fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = choice == "standard", onClick = { onChange("standard") },
            enabled = enabled, label = { Text("Standard") }
        )
        FilterChip(
            selected = choice == "split", onClick = { onChange("split") },
            enabled = enabled, label = { Text("50/50 · streamer on top, gameplay below") }
        )
    }
}

@Composable
private fun FacecamRegionControls(region: StreamRegion, onChange: (StreamRegion) -> Unit) {
    val minimumSize = 0.02f
    val width = region.width
    val height = region.height
    Text("Horizontal position")
    Slider(region.left, { x -> onChange(StreamRegion(x, region.top, (x + width).coerceAtMost(1f), region.bottom)) },
        valueRange = 0f..(1f - width), enabled = width < 1f)
    Text("Vertical position")
    Slider(region.top, { y -> onChange(StreamRegion(region.left, y, region.right, (y + height).coerceAtMost(1f))) },
        valueRange = 0f..(1f - height), enabled = height < 1f)
    Text("Width")
    Slider(width, { w -> onChange(region.copy(right = (region.left + w).coerceAtMost(1f))) },
        valueRange = minimumSize..(1f - region.left).coerceAtLeast(minimumSize))
    Text("Height")
    Slider(height, { h -> onChange(region.copy(bottom = (region.top + h).coerceAtMost(1f))) },
        valueRange = minimumSize..(1f - region.top).coerceAtLeast(minimumSize))
}

@Composable
private fun RenderedClipPreview(
    source: Uri,
    clip: ClipCandidate,
    aspect: Float,
    tracking: List<CropFocus>,
    region: StreamRegion?,
    captions: List<CaptionCue>,
    preset: CaptionPreset,
    words: List<TimedWord>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var preview by remember(source, clip, aspect, tracking, region, captions, preset, words, retry) { mutableStateOf<Uri?>(null) }
    var error by remember(source, clip, aspect, tracking, region, captions, preset, words, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, clip, aspect, tracking, region, captions, preset, words, retry) {
        var file: File? = null
        try {
            val output = File.createTempFile("clip-preview-", ".mp4", context.cacheDir)
            file = output
            val uri = Uri.fromFile(output)
            // Wait until the encoder no longer owns the file before deleting a dismissed preview.
            suspendCoroutine<Unit> { continuation ->
                ClipExporter.export(
                    context, source, uri, clip.startMs, clip.endMs,
                    aspectRatio = aspect, faceTrackingPath = tracking, splitFacecamRegion = region,
                    captionCues = captions, captionPreset = preset, captionWords = words,
                    onStatus = {},
                    onFinished = { success, message ->
                        if (success) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException(message))
                    }
                )
            }
            preview = uri
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.localizedMessage ?: "Could not render the clip preview."
        } finally {
            file?.delete()
        }
    }
    val uri = preview
    when {
        error != null -> {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { retry++ }) { Text("Retry preview") }
        }
        uri != null -> VideoPreview(uri, onDuration = {}, onError = { error = it }, retryKey = retry, aspectRatio = aspect)
        else -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Rendering clip preview…", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun VideoPreview(
    uri: Uri,
    onDuration: (Long) -> Unit,
    onError: (String) -> Unit,
    retryKey: Int,
    aspectRatio: Float = 9f / 16f,
    startAtMs: Long? = null,
    endAtMs: Long? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri, retryKey, startAtMs, endAtMs) {
        ExoPlayer.Builder(context).build().apply {
            val media = MediaItem.Builder().setUri(uri)
            if (startAtMs != null && endAtMs != null) {
                media.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startAtMs)
                        .setEndPositionMs(endAtMs)
                        .build()
                )
            }
            setMediaItem(media.build())
        }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var isSeeking by remember(player) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                    onDuration(durationMs)
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                onError(error.message ?: "Media3 could not open this file")
            }
        }
        player.addListener(listener)
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!isSeeking) positionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) durationMs = player.duration
            delay(300L)
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val previewMaxWidth = when {
            aspectRatio < 1f -> 150.dp
            aspectRatio == 1f -> 260.dp
            else -> 520.dp
        }
        AndroidView(
            factory = { PlayerView(it).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } },
            update = { it.player = player },
            modifier = Modifier.widthIn(max = previewMaxWidth).aspectRatio(aspectRatio)
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                Icon(Icons.Default.Replay10, contentDescription = "Rewind 10 seconds", tint = Color.White)
            }
            IconButton(onClick = {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                    tint = Color.White
                )
            }
            IconButton(onClick = {
                val end = durationMs.takeIf { it > 0L } ?: player.duration.coerceAtLeast(0L)
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
            }) {
                Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
            }
            Text("${time(positionMs)} / ${time(durationMs)}", color = Muted, fontSize = 11.sp)
        }
        Slider(
            value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
            onValueChange = { isSeeking = true; positionMs = it.toLong() },
            onValueChangeFinished = {
                player.seekTo(positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)))
                isSeeking = false
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L
        )
    }
}
