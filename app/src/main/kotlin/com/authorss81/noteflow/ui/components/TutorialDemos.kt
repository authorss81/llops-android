package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 125 — embedded interactive demos for the enhanced tutorial.
 *
 * These are CHEAP by design (low-end rule): fixed-size pads, plain `Canvas`
 * paths, no allocations per frame beyond the stroke buffers, no animations.
 * Each demo performs a REAL gesture the user does (draw / erase / add layer /
 * pick a colour / type) and fires its callback once on completion so the
 * tutorial's progress check can unlock the "Next" button.
 */

enum class PracticePadMode { DRAW, ERASE }

/** Small demo palette cycling through pleasant, theme-independent ink colours. */
private val DEMO_INKS = listOf(
    Color(0xFFE53935), // red
    Color(0xFF1E88E5), // blue
    Color(0xFF43A047), // green
    Color(0xFFFB8C00), // orange
    Color(0xFF8E24AA), // purple
    Color(0xFF00ACC1)  // teal
)

/**
 * A draw/erase practice pad. In DRAW mode each completed drag adds a stroke; in
 * ERASE mode the pad is seeded with a sample stroke and each drag paints
 * paper-colour tracks over the ink (a visual "eraser"). [onGestureDone] fires
 * after every completed draw drag, and — for erase — only after a swipe that
 * actually crossed a stroke (misses are recorded for feedback but never
 * complete the step). Paths are cached and rebuilt only when pad content
 * changes, not on every draw pass, so the demo stays allocation-cheap.
 */
@Composable
fun PracticePad(
    mode: PracticePadMode,
    onGestureDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val erases = remember { mutableStateListOf<List<Offset>>() }
    val paper = MaterialTheme.colorScheme.surfaceContainer

    // Bumped on every content change so the cached Path lists rebuild exactly once
    // per mutation, instead of allocating new Paths on every frame.
    var version by remember { mutableIntStateOf(0) }

    // The erase demo needs a sample stroke in the SAME pixel space as the pointer
    // coordinates, so it is seeded once the pad's measured size is known.
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(padSize, mode) {
        if (mode == PracticePadMode.ERASE && strokes.isEmpty() && padSize != IntSize.Zero) {
            strokes.add(sampleWave(padSize.width.toFloat(), padSize.height.toFloat()))
            version++
        }
    }

    val strokePaths = remember(version) {
        strokes.map { pts ->
            Path().apply {
                pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
        }
    }
    val erasePaths = remember(version) {
        erases.map { pts ->
            Path().apply {
                pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
        }
    }

    Surface(
        color = paper,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .height(190.dp)
                .onSizeChanged { padSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .pointerInput(mode) {
                        val buffer = mutableListOf<Offset>()
                        detectDragGestures(
                            onDragStart = { start ->
                                buffer.clear()
                                buffer.add(start)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                buffer.add(change.position)
                            },
                            onDragEnd = {
                                if (buffer.size >= 2) {
                                    if (mode == PracticePadMode.DRAW) {
                                        strokes.add(buffer.toList())
                                        version++
                                        onGestureDone()
                                    } else {
                                        erases.add(buffer.toList())
                                        version++
                                        if (overlapsAnyStroke(buffer, strokes)) onGestureDone()
                                    }
                                }
                            },
                            onDragCancel = { buffer.clear() }
                        )
                    }
            ) {
                strokePaths.forEachIndexed { i, path ->
                    drawPath(
                        path = path,
                        color = DEMO_INKS[i % DEMO_INKS.size],
                        style = Stroke(width = 7f, cap = StrokeCap.Round)
                    )
                }
                erasePaths.forEach { path ->
                    drawPath(
                        path = path,
                        color = paper,
                        style = Stroke(width = 18f, cap = StrokeCap.Round)
                    )
                }
            }

            when (mode) {
                PracticePadMode.DRAW -> if (strokes.isEmpty()) {
                    Text(
                        text = "Drag here to draw a stroke",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp)
                    )
                } else {
                    Text(
                        text = "${strokes.size} stroke${if (strokes.size == 1) "" else "s"} drawn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }

                PracticePadMode.ERASE -> if (erases.isEmpty()) {
                    Text(
                        text = "Drag across the sample stroke to erase a part of it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp)
                    )
                } else {
                    val erasedCount = erases.count { it.size >= 2 }
                    Text(
                        text = "$erasedCount erase swipe${if (erasedCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * True when any point of the erase swipe lies within [ERASE_OVERLAP_RADIUS] of
 * any point of a drawn stroke — a cheap "did the eraser actually touch ink"
 * check in the shared pixel space of the pad.
 */
private fun overlapsAnyStroke(swipe: List<Offset>, strokes: List<List<Offset>>): Boolean {
    if (swipe.isEmpty() || strokes.isEmpty()) return false
    val radiusSq = ERASE_OVERLAP_RADIUS * ERASE_OVERLAP_RADIUS
    for (stroke in strokes) {
        for (s in stroke) {
            for (e in swipe) {
                val dx = e.x - s.x
                val dy = e.y - s.y
                if (dx * dx + dy * dy <= radiusSq) return true
            }
        }
    }
    return false
}

/** Erase tool half-width (18f stroke) + a little slack. */
private const val ERASE_OVERLAP_RADIUS = 18f

/** A gentle S-curve across the pad, generated in local pixel coordinates. */
private fun sampleWave(width: Float, height: Float): List<Offset> {
    val n = 48
    val points = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val x = width * (0.12f + 0.76f * t)
        val y = height * (0.3f + 0.4f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat())))
        points.add(Offset(x, y))
    }
    return points
}

/** Layer-stack mini demo: tap "+ Add Layer" to stack a new layer. */
@Composable
fun LayerDemoPanel(
    onLayerAdded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layers = remember { mutableStateListOf("Background", "Ink", "Text") }
    val selected = remember { mutableIntStateOf(0) }
    var addNotified by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Layers",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    onClick = {
                        layers.add("Layer ${layers.size + 1}")
                        selected.intValue = layers.size - 1
                        if (!addNotified) {
                            addNotified = true
                            onLayerAdded()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Add layer",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Add Layer",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            layers.forEachIndexed { index, name ->
                val isSelected = index == selected.intValue
                Surface(
                    onClick = { selected.intValue = index },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            if (isSelected) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/** Colour-mode + swatch mini demo: picking any mode or swatch completes. */
@Composable
fun ColourModeDemo(
    onModeSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf("Solid", "Rainbow", "Gradient", "Shimmer")
    val swatches = listOf(
        Color(0xFFE53935),
        Color(0xFF1E88E5),
        Color(0xFF43A047),
        Color(0xFFFB8C00),
        Color(0xFF8E24AA),
        Color(0xFF00ACC1)
    )
    var selectedMode by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(Color(0xFF3F51B5)) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            modes.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = {
                        selectedMode = mode
                        onModeSelected()
                    },
                    label = {
                        Text(mode, fontSize = 12.sp)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                swatches.forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            .clickable {
                                preview = swatch
                                onModeSelected()
                            }
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        CircleShape
                    )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedMode != null) {
            Text(
                text = when (selectedMode) {
                    "Rainbow" -> "Rainbow selected — each stroke sweeps the whole spectrum."
                    "Gradient" -> "Gradient selected — the stroke fades between two colours."
                    "Shimmer" -> "Shimmer selected — a lustrous sheen along the stroke."
                    else -> "Solid selected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Markdown mini editor: typing a real "# heading" (>= 3 chars) completes the check. */
@Composable
fun MarkdownTypeDemo(
    onTyped: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var notified by remember { mutableStateOf(false) }
    val isHeading = text.startsWith("#")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    if (new.startsWith("#") && new.length >= 3 && !notified) {
                        notified = true
                        onTyped()
                    }
                },
                placeholder = { Text("# My First Note") },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (text.isBlank()) {
                        Text(
                            "Type some markdown above — e.g. a # heading.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            text = text.removePrefix("# ").ifBlank { text },
                            style = if (isHeading) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}