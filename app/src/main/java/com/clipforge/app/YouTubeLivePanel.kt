package com.clipforge.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

@Composable
fun YouTubeLivePanel(
    database: ProjectDatabase,
    visible: Boolean = true,
    initialVideoUrl: String = "",
    onMonitoringStarted: () -> Unit,
    onActiveSessionChanged: (Long?) -> Unit,
    onMarker: (LiveChatHighlightMarker) -> Unit,
    onMarkersRestored: (List<LiveChatHighlightMarker>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = remember { YouTubeLiveApiClient() }
    var discoveryQuery by remember { mutableStateOf("GTA 6") }
    var discoveredStreams by remember { mutableStateOf<List<DiscoveredLiveStream>>(emptyList()) }
    var discoveryStatus by remember { mutableStateOf("Search active YouTube livestreams by game or topic.") }
    var discoveryBusy by remember { mutableStateOf(false) }
    var videoInput by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val restoredSession = remember { database.listLiveSessions().firstOrNull() }
    val restoredMarkers = remember(restoredSession?.id) {
        restoredSession?.let { saved ->
            database.listLiveMarkers(saved.id).map { row ->
                LiveChatHighlightMarker(row.startMs, row.messageCount, row.baselineMedian, row.reason)
            }
        }.orEmpty()
    }
    var activeSessionId by remember { mutableStateOf<Long?>(restoredSession?.id) }
    var status by remember { mutableStateOf(restoredSession?.let { "Restored " + restoredMarkers.size + " saved marker(s) from your last live session." } ?: "Monitor public chat activity for a livestream you are authorised to repurpose.") }
    var sessionTitle by remember { mutableStateOf(restoredSession?.title.orEmpty()) }
    var markers by remember { mutableStateOf(restoredMarkers) }
    var monitorJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        if (restoredMarkers.isNotEmpty()) onMarkersRestored(restoredMarkers)
    }

    LaunchedEffect(initialVideoUrl) {
        if (initialVideoUrl.isNotBlank() && monitorJob?.isActive != true) {
            videoInput = initialVideoUrl
            status = YouTubeLinkClassifier.importGuidance()
        }
    }

    DisposableEffect(monitorJob) {
        val activeJob = monitorJob
        onDispose { activeJob?.cancel() }
    }

    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (visible) Modifier else Modifier.height(0.dp).clipToBounds().alpha(0f)
        ),
        colors = CardDefaults.cardColors(containerColor = ClipForgePalette.Surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("LIVE CHAT SIGNALS", color = ClipForgePalette.Accent, fontWeight = FontWeight.Bold)
            Text("Public chat activity markers", style = MaterialTheme.typography.titleMedium)
            Text(
                "This tool reads public live-chat activity and creates timestamps. It does not download or record YouTube video playback. Import an authorised recording to turn approved moments into clips.",
                color = ClipForgePalette.Muted,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = videoInput,
                onValueChange = { videoInput = it },
                enabled = monitorJob?.isActive != true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Public YouTube Live URL or video ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                enabled = monitorJob?.isActive != true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube Data API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                "Use a restricted key from your Google Cloud project. The key stays in memory for this screen and is not saved by ClipForge. Markers use broadcast-relative timestamps; compare them with your imported recording and verify the offset before exporting. Only process footage you own or are authorised to use.",
                color = ClipForgePalette.Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Text("STREAM SCOUT", color = ClipForgePalette.Accent, fontWeight = FontWeight.Bold)
            Text(
                "Find currently live YouTube streams. GTA 6 is prefilled; change the search for another game or topic.",
                color = ClipForgePalette.Muted,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = discoveryQuery,
                onValueChange = { discoveryQuery = it },
                enabled = !discoveryBusy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Game or topic") },
                singleLine = true
            )
            Button(
                onClick = {
                    scope.launch {
                        discoveryBusy = true
                        discoveryStatus = "Searching live streams…"
                        try {
                            discoveredStreams = YouTubeStreamDiscoveryClient(apiKey).searchLiveStreams(discoveryQuery)
                            discoveryStatus = if (discoveredStreams.isEmpty()) {
                                "No active streams found for this search."
                            } else {
                                "Found " + discoveredStreams.size + " active stream(s). Select one to monitor its public chat."
                            }
                        } catch (error: Exception) {
                            discoveryStatus = error.localizedMessage ?: "Stream discovery failed."
                        } finally {
                            discoveryBusy = false
                        }
                    }
                },
                enabled = !discoveryBusy && discoveryQuery.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (discoveryBusy) "Searching…" else "Find live streams")
            }
            Text(discoveryStatus, color = ClipForgePalette.Muted, style = MaterialTheme.typography.bodySmall)
            discoveredStreams.take(8).forEach { stream ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stream.title, color = ClipForgePalette.Text, fontWeight = FontWeight.SemiBold)
                        val viewers = stream.viewerCount?.let { it.toString() + " viewers" } ?: "Viewer count unavailable"
                        Text(stream.channelName + " · " + viewers, color = ClipForgePalette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            videoInput = stream.watchUrl
                            status = "Selected " + stream.channelName + ". Connect and monitor its public chat below."
                        }
                    ) { Text("Use") }
                }
            }
            Text(
                "YouTube search is quota-limited. Refresh only when needed. Stream discovery finds public live listings; it does not capture or download playback.",
                color = ClipForgePalette.Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    if (monitorJob?.isActive == true) {
                        monitorJob?.cancel()
                        monitorJob = null
                        onActiveSessionChanged(null)
                        status = "Monitoring stopped. Existing markers are kept on this screen."
                    } else {
                        onMonitoringStarted()
                        sessionTitle = ""
                        markers = emptyList()
                        status = "Connecting to YouTube Live…"
                        monitorJob = scope.launch {
                            val ownJob = coroutineContext[Job]
                            try {
                                val session = api.resolveLiveSession(videoInput, apiKey)
                                sessionTitle = session.title
                                val monitorStartedAtMs = System.currentTimeMillis()
                                activeSessionId = database.saveLiveSession(session, monitorStartedAtMs)
                                onActiveSessionChanged(activeSessionId)
                                val startOffsetMs = (monitorStartedAtMs - session.actualStartTimeMs).coerceAtLeast(0L)
                                val detector = LiveChatSpikeDetector(monitorStartOffsetMs = startOffsetMs)
                                val seenIds = LinkedHashSet<String>()
                                var pageToken: String? = null
                                status = "Monitoring chat: ${session.title}"
                                while (isActive) {
                                    val page = api.getChatPage(session, apiKey, pageToken)
                                    page.messages.forEach { chatMessage ->
                                        if (chatMessage.publishedAtMs >= monitorStartedAtMs &&
                                            seenIds.add(chatMessage.id)
                                        ) {
                                            detector.addMessage(chatMessage.publishedAtMs - session.actualStartTimeMs)
                                            if (seenIds.size > 5_000) {
                                                val oldest = seenIds.iterator()
                                                if (oldest.hasNext()) {
                                                    oldest.next()
                                                    oldest.remove()
                                                }
                                            }
                                        }
                                    }
                                    // Keep a grace window for messages that arrive in a later API poll.
                                    // Late messages older than the finalized watermark are counted by the detector.
                                    val elapsedMs = System.currentTimeMillis() - session.actualStartTimeMs
                                    val latenessWindowMs = maxOf(15_000L, page.pollingIntervalMs * 3L)
                                    val newMarkers = detector.completeBucketsThrough(elapsedMs - latenessWindowMs)
                                    if (newMarkers.isNotEmpty()) {
                                        markers = (markers + newMarkers).takeLast(100)
                                        newMarkers.forEach { marker ->
                                            activeSessionId?.let { database.saveLiveMarker(it, marker) }
                                            onMarker(marker)
                                        }
                                        status = "Found ${markers.size} live highlight marker(s)."
                                    }
                                    pageToken = page.nextPageToken
                                    if (page.offline) {
                                        status = "The YouTube live chat has ended."
                                        break
                                    }
                                    delay(page.pollingIntervalMs)
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                status = error.localizedMessage ?: "Could not monitor this live chat."
                            } finally {
                                if (monitorJob === ownJob) {
                                    monitorJob = null
                                    onActiveSessionChanged(null)
                                }
                            }
                        }
                    }
                },
                enabled = monitorJob?.isActive == true || (videoInput.isNotBlank() && apiKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (monitorJob?.isActive == true) "Stop monitoring" else "Connect and monitor")
            }
            Text(status, color = ClipForgePalette.Muted, style = MaterialTheme.typography.bodySmall)
            if (sessionTitle.isNotBlank()) {
                Text(sessionTitle, fontWeight = FontWeight.SemiBold)
            }
            markers.takeLast(8).forEach { marker ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${marker.startMs / 60_000L}:${((marker.startMs % 60_000L) / 1_000L).toString().padStart(2, '0')} · ${marker.messageCount} messages",
                        color = ClipForgePalette.Text,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Spike", color = ClipForgePalette.AccentSoft, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
