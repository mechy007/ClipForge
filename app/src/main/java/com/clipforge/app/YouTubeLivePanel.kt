package com.clipforge.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun YouTubeLivePanel(
    onMonitoringStarted: () -> Unit,
    onMarker: (LiveChatHighlightMarker) -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = remember { YouTubeLiveApiClient() }
    var videoInput by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Monitor chat activity for your own authorised live content.") }
    var sessionTitle by remember { mutableStateOf("") }
    var markers by remember { mutableStateOf<List<LiveChatHighlightMarker>>(emptyList()) }
    var monitorJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(monitorJob) {
        onDispose { monitorJob?.cancel() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171720)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("YOUTUBE LIVE", color = Color(0xFF9B83FF), fontWeight = FontWeight.Bold)
            Text("Chat-driven highlight markers", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = videoInput,
                onValueChange = { videoInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube Live URL or video ID") },
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube Data API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                "Use a restricted key from your Google Cloud project. The key stays in memory for this screen and is not saved by ClipForge. Select your own original recording in Video Studio; its timeline should begin with the broadcast.",
                color = Color(0xFF9A9AA9),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    if (monitorJob?.isActive == true) {
                        monitorJob?.cancel()
                        monitorJob = null
                        status = "Monitoring stopped. Existing markers are kept on this screen."
                    } else {
                        onMonitoringStarted()
                        markers = emptyList()
                        status = "Connecting to YouTube Live…"
                        monitorJob = scope.launch {
                            try {
                                val session = api.resolveLiveSession(videoInput, apiKey)
                                sessionTitle = session.title
                                val monitorStartedAtMs = System.currentTimeMillis()
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
                                    val elapsedMs = System.currentTimeMillis() - session.actualStartTimeMs
                                    val newMarkers = detector.completeBucketsThrough(elapsedMs)
                                    if (newMarkers.isNotEmpty()) {
                                        markers = (markers + newMarkers).takeLast(100)
                                        newMarkers.forEach(onMarker)
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
                                monitorJob = null
                            }
                        }
                    }
                },
                enabled = monitorJob?.isActive == true || (videoInput.isNotBlank() && apiKey.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (monitorJob?.isActive == true) "Stop monitoring" else "Connect and monitor")
            }
            Text(status, color = Color(0xFFB7B7C4), style = MaterialTheme.typography.bodySmall)
            if (sessionTitle.isNotBlank()) {
                Text(sessionTitle, fontWeight = FontWeight.SemiBold)
            }
            markers.takeLast(8).forEach { marker ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${marker.startMs / 60_000L}:${((marker.startMs % 60_000L) / 1_000L).toString().padStart(2, '0')} · ${marker.messageCount} messages",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Spike", color = Color(0xFF75D6A2), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
