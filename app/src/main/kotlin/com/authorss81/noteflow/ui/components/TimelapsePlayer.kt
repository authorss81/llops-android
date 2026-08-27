package com.authorss81.noteflow.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.services.TimelapseExporter
import com.authorss81.noteflow.services.TimelapsePolicy
import com.authorss81.noteflow.utils.BitmapPool
import kotlinx.coroutines.delay

/**
 * Phase 224 — timelapse replay preview inside the export dialog.
 *
 * Plays the page's committed strokes back in timestamp order: the preview shows
 * the prefix `strokes.take(index)` exactly the way [TimelapseExporter] renders an
 * exported frame (same [TimelapseExporter.drawPrefix] rasterizer + a fit-to-view
 * transform), so what you see is what the MP4 contains. The scrub Slider runs
 * `0..strokes.size`; Play steps through time stamps (`stroke.timestampMs`, falling
 * back to `index*120ms` when null).
 *
 * [isExporting]/[exportProgress] are hoisted so the caller (EditorScreen) owns the
 * MediaCodec/SAF work and keeps the [LinearProgressIndicator] accurate across
 * recompositions; [onExport] triggers that work.
 */
@Composable
fun TimelapsePlayer(
    strokes: List<Stroke>,
    isExporting: Boolean,
    exportProgress: Float,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Capped, timestamp-ordered working set (same cap the exporter uses).
    val working = remember(strokes) { TimelapsePolicy.capped(strokes) }

    // 0..strokes.size — number of visible strokes at the current position.
    var sliderIdx by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    // Preview frame bitmap sized to the current layout.
    var previewSize by remember { mutableStateOf(IntSize(0, 0)) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(working, sliderIdx, previewSize.width, previewSize.height) {
        val w = previewSize.width
        val h = previewSize.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val visible = working.take(sliderIdx.coerceIn(0, working.size))
        val current = previewBitmap
        val bmp = if (current != null && current.width == w && current.height == h && !current.isRecycled) {
            current
        } else {
            current?.let { BitmapPool.release(it) }
            BitmapPool.acquire(w, h, Bitmap.Config.ARGB_8888).also { previewBitmap = it }
        }
        bmp.eraseColor(0xFFFBFBF7.toInt())
        val t = TimelapseExporter.fitTransform(TimelapseExporter.worldBounds(working), w, h)
        TimelapseExporter.drawPrefix(android.graphics.Canvas(bmp), visible, t)
    }

    // Playback clock — delay by each stroke's timestamp gap, capped/bounded.
    LaunchedEffect(isPlaying, working) {
        if (!isPlaying) return@LaunchedEffect
        val capped = working
        if (capped.isEmpty()) return@LaunchedEffect
        var next = sliderIdx.coerceIn(0, capped.size)
        while (isPlaying && next <= capped.size) {
            sliderIdx = next
            if (next >= capped.size) {
                isPlaying = false
                break
            }
            val gapMs = if (next == 0) {
                TimelapsePolicy.FALLBACK_STEP_MS
            } else {
                val cur = capped[next]?.timestampMs ?: (next * TimelapsePolicy.FALLBACK_STEP_MS).toLong()
                val prev = capped[next - 1]?.timestampMs ?: ((next - 1) * TimelapsePolicy.FALLBACK_STEP_MS).toLong()
                (cur - prev).coerceIn(16L, 2_000L)
            }
            delay(gapMs)
            next++
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Preview canvas — 16:10-ish, matching the 720p (1280×720) export aspect.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 320.dp)
                .clip(MaterialTheme.shapes.medium)
                .onSizeChanged { previewSize = it },
            contentAlignment = Alignment.Center
        ) {
            val bmp = previewBitmap
            if (bmp != null && bmp.width == previewSize.width) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Timelapse preview",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Text(
            text = if (isExporting) "Exporting MP4..." else "${sliderIdx} / ${working.size} strokes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Slider(
            value = sliderIdx.toFloat(),
            onValueChange = {
                sliderIdx = it.toInt().coerceIn(0, working.size)
                isPlaying = false
            },
            valueRange = 0f..working.size.toFloat(),
            steps = (working.size - 1).coerceAtLeast(0),
            enabled = working.isNotEmpty() && !isExporting,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        isPlaying = false
                    } else {
                        if (sliderIdx >= working.size) sliderIdx = 0
                        isPlaying = true
                    }
                },
                enabled = working.isNotEmpty() && !isExporting,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            FilledTonalButton(
                onClick = onExport,
                enabled = working.isNotEmpty() && !isExporting
            ) {
                Text("Export MP4")
            }
        }

        if (isExporting) {
            LinearProgressIndicator(
                progress = { exportProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
