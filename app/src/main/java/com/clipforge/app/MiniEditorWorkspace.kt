package com.clipforge.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Compact, phone-first timeline editor. All edits are represented as source
 * time ranges and are passed to the existing Media3/FFmpeg export pipeline.
 */
@Composable
fun MiniEditorWorkspace(
    uri: Uri,
    sourceName: String,
    durationMs: Long,
    initialStartMs: Long,
    initialEndMs: Long,
    aspectRatio: Float,
    burnCaptions: Boolean,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onAspectRatioChange: (Float) -> Unit,
    onBurnCaptionsChange: (Boolean) -> Unit,
    onExport: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)) }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var playheadMs by remember(player) { mutableLongStateOf(initialStartMs) }
    var previewSpeed by remember(player) { mutableStateOf(1f) }
    var previewVolume by remember(player) { mutableStateOf(1f) }
    var loopSelection by rememberSaveable(uri) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) { isPlaying = isPlayingNow }
        }
        player.addListener(listener)
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, durationMs, previewSpeed, previewVolume) {
        player.setPlaybackParameters(PlaybackParameters(previewSpeed, 1f))
        player.volume = previewVolume
        while (true) {
            playheadMs = player.currentPosition.coerceIn(0L, durationMs)
            delay(250L)
        }
    }

    val start = initialStartMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
    val end = initialEndMs.coerceIn((start + 500L).coerceAtMost(durationMs), durationMs)
    val safeDuration = durationMs.coerceAtLeast(1L)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EDIT WORKSPACE", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(sourceName.ifBlank { "Untitled video" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(formatEditorTime(durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                        Icon(Icons.Default.Replay, contentDescription = "Back ten seconds")
                    }
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play")
                    }
                    IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs)) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward ten seconds")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatEditorTime(playheadMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Text(formatEditorTime(durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = playheadMs.toFloat().coerceIn(0f, safeDuration.toFloat()),
                    onValueChange = {
                        playheadMs = it.toLong()
                        player.seekTo(playheadMs)
                    },
                    valueRange = 0f..safeDuration.toFloat()
                )
            }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TIMELINE · TRIM", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                val timelineAccent = MaterialTheme.colorScheme.primary
                val timelineTrack = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                Canvas(Modifier.fillMaxWidth().height(42.dp)) {
                    val trackY = size.height / 2f
                    val left = (start.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * size.width
                    val right = (end.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * size.width
                    val playhead = (playheadMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = timelineTrack,
                        start = Offset(0f, trackY), end = Offset(size.width, trackY), strokeWidth = 9.dp.toPx()
                    )
                    drawLine(
                        color = timelineAccent.copy(alpha = 0.28f),
                        start = Offset(left, trackY), end = Offset(right, trackY), strokeWidth = 13.dp.toPx()
                    )
                    drawLine(
                        color = timelineAccent,
                        start = Offset(left, trackY), end = Offset(right, trackY), strokeWidth = 6.dp.toPx()
                    )
                    drawCircle(timelineAccent, radius = 7.dp.toPx(), center = Offset(left, trackY))
                    drawCircle(timelineAccent, radius = 7.dp.toPx(), center = Offset(right, trackY))
                    drawLine(
                        color = Color.White,
                        start = Offset(playhead, 3.dp.toPx()),
                        end = Offset(playhead, size.height - 3.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                Text("In point · ${formatEditorTime(start)}", fontWeight = FontWeight.Medium)
                Slider(
                    value = start.toFloat(),
                    onValueChange = { value ->
                        val next = value.toLong().coerceIn(0L, (end - 500L).coerceAtLeast(0L))
                        onStartChange(next)
                    },
                    valueRange = 0f..(durationMs - 500L).coerceAtLeast(1L).toFloat()
                )
                Text("Out point · ${formatEditorTime(end)}", fontWeight = FontWeight.Medium)
                Slider(
                    value = end.toFloat(),
                    onValueChange = { value ->
                        val next = value.toLong().coerceIn((start + 500L).coerceAtMost(durationMs), durationMs)
                        onEndChange(next)
                    },
                    valueRange = (start + 500L).coerceAtMost(durationMs).toFloat()..safeDuration.toFloat()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { onStartChange(playheadMs.coerceIn(0L, (end - 500L).coerceAtLeast(0L))) }) { Text("Mark in") }
                    OutlinedButton(onClick = { onEndChange(playheadMs.coerceIn((start + 500L).coerceAtMost(durationMs), durationMs)) }) { Text("Mark out") }
                }
                Text("Selected range · ${formatEditorTime((end - start).coerceAtLeast(0L))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Loop selected range", fontSize = 13.sp)
                    Switch(checked = loopSelection, onCheckedChange = { loopSelection = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onStartChange(0L); onEndChange(durationMs); player.seekTo(0L); loopSelection = false }) {
                        Text("Reset edit")
                    }
                }
                LaunchedEffect(player, loopSelection, start, end) {
                    if (loopSelection) {
                        while (true) {
                            if (player.currentPosition >= end) player.seekTo(start)
                            delay(100L)
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PREVIEW CONTROLS", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playback speed · %.2fx".format(previewSpeed), fontSize = 13.sp)
                    Text("Preview only", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(value = previewSpeed, onValueChange = { previewSpeed = it }, valueRange = 0.5f..2f, steps = 5)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Preview volume", fontSize = 13.sp)
                    Text("${(previewVolume * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(value = previewVolume, onValueChange = { previewVolume = it }, valueRange = 0f..1f)
            }
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CANVAS & CAPTIONS", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = aspectRatio < 1f, onClick = { onAspectRatioChange(9f / 16f) }, label = { Text("9:16") })
                    FilterChip(selected = aspectRatio == 1f, onClick = { onAspectRatioChange(1f) }, label = { Text("1:1") })
                    FilterChip(selected = aspectRatio > 1f, onClick = { onAspectRatioChange(16f / 9f) }, label = { Text("16:9") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = burnCaptions, onCheckedChange = onBurnCaptionsChange)
                    Column {
                        Text("Burn captions into export", fontWeight = FontWeight.Medium)
                        Text("Uses the available Whisper transcript and selected caption style.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
        }

        Button(
            onClick = { onExport(start, end) },
            enabled = end > start && end <= durationMs,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Export edited video", fontWeight = FontWeight.Bold)
        }
        Text("Edits are rendered through ClipForge's existing export pipeline.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
    }
}

private fun formatEditorTime(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    return "%d:%02d".format(safe / 60_000L, (safe / 1_000L) % 60L)
}
