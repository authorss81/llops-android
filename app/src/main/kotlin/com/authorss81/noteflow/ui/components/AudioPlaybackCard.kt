package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.Stroke
import java.util.Locale

@Composable
fun AudioPlaybackCard(
    modifier: Modifier = Modifier,
    embed: CanvasMediaEmbed,
    syncedStrokesCount: Int = 0,
    isPlaying: Boolean,
    currentPositionMsProvider: () -> Long,
    playbackSpeed: Float = 1.0f,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleCollapse: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val currentPositionMs = currentPositionMsProvider()
    val durationMs = if (embed.durationMs > 0) embed.durationMs else 1000L
    val progress = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    if (embed.isCollapsed) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
            shape = RoundedCornerShape(24.dp),
            modifier = modifier
                .wrapContentSize()
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable { onToggleCollapse?.invoke() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
                        contentDescription = "Voice Note (Collapsed)",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "Voice Note",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatMs(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (onToggleCollapse != null) {
                    IconButton(
                        onClick = onToggleCollapse,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = "Expand Voice Note",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        return
    }


    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Audio Icon, Title, Synced Strokes Badge, Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.GraphicEq,
                            contentDescription = "Voice Memo",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Time-Synced Voice Note",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (syncedStrokesCount > 0) {
                            Text(
                                text = "$syncedStrokesCount stroke(s) synced",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (onToggleCollapse != null) {
                        IconButton(
                            onClick = onToggleCollapse,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ExpandLess,
                                contentDescription = "Collapse Voice Note",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Delete Voice Note",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Waveform Visualizer & Scrub Bar (real recorded amplitudes only)
            val amplitudes = embed.waveformAmplitudes

            val activeBarColor = MaterialTheme.colorScheme.primary
            val inactiveBarColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .pointerInput(embed.id, durationMs) {
                        detectTapGestures { offset ->
                            val tapRatio = (offset.x / size.width).coerceIn(0f, 1f)
                            val targetMs = (tapRatio * durationMs).toLong()
                            onSeekTo(targetMs)
                        }
                    }
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                if (amplitudes.isEmpty()) {
                    // No waveform data — draw an honest flat baseline instead of a fake pattern
                    drawLine(
                        color = activeBarColor.copy(alpha = 0.4f),
                        start = Offset(0f, canvasHeight / 2f),
                        end = Offset(canvasWidth, canvasHeight / 2f),
                        strokeWidth = 2f
                    )
                    return@Canvas
                }

                val totalBars = amplitudes.size
                val barWidth = (canvasWidth / totalBars).coerceAtLeast(3f)
                val gap = 2f

                for (i in 0 until totalBars) {
                    val barRatio = i.toFloat() / totalBars.toFloat()
                    val isPlayed = barRatio <= progress
                    val amp = amplitudes[i].coerceIn(0.1f, 1.0f)
                    val barHeight = canvasHeight * amp * 0.85f

                    val x = i * barWidth
                    val y = (canvasHeight - barHeight) / 2f

                    drawRoundRect(
                        color = if (isPlayed) activeBarColor else inactiveBarColor,
                        topLeft = Offset(x, y),
                        size = Size((barWidth - gap).coerceAtLeast(1f), barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Controls & Timing Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Play/Pause & Skip Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }

                    IconButton(
                        onClick = { onSeekTo((currentPositionMs - 5000L).coerceAtLeast(0L)) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Replay5, contentDescription = "Rewind 5s", modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = { onSeekTo((currentPositionMs + 5000L).coerceAtMost(durationMs)) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Forward5, contentDescription = "Forward 5s", modifier = Modifier.size(18.dp))
                    }
                }

                // Speed Pill & Duration Readout
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Playback Speed Selector
                    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val nextSpeed = speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.size]

                    AssistChip(
                        onClick = { onSpeedChange(nextSpeed) },
                        label = {
                            Text(
                                text = String.format(Locale.US, "%.2fx", playbackSpeed).replace(".00", ""),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(48.dp)
                    )

                    Text(
                        text = "${formatMs(currentPositionMs)} / ${formatMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }
    }
}


private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
