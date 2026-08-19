package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Phase 34: tactile empty states.
 *
 * Vectors are drawn with Compose [Canvas] primitives (Path/circles/rounds) —
 * no image assets, no network. The illustration + copy are decided by the pure
 * JVM [EmptyStateResolver]; this file is only the presentation layer.
 */
private val CanvasSize = 112.dp

/** A 112 dp theme-aware vector drawing for the given motif. */
@Composable
fun EmptyStateIllustration(
    kind: IllustrationKind,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val secondary = scheme.secondary
    val tertiary = scheme.tertiary
    val muted = scheme.onSurfaceVariant.copy(alpha = 0.45f)

    Canvas(modifier = modifier.size(CanvasSize)) {
        when (kind) {
            IllustrationKind.NOTEBOOK -> drawIsometricNotebook(primary, secondary, tertiary, muted)
            IllustrationKind.GRAPH -> drawGraphNodes(primary, secondary, tertiary, muted)
            IllustrationKind.PEN -> drawPenNib(primary, secondary)
            IllustrationKind.SEARCH -> drawSearch(primary, muted)
            IllustrationKind.TRASH -> drawTrash(primary, muted)
            IllustrationKind.STACK -> drawBookStack(primary, secondary, muted)
            IllustrationKind.PUZZLE -> drawPuzzle(primary, secondary)
            IllustrationKind.HISTORY -> drawHistory(primary, secondary, muted)
        }
    }
}

/**
 * A complete, content-aware empty state: centered vector art, a title, a
 * contextual suggestion, and an optional primary action row.
 */
@Composable
fun TactileEmptyState(
    decision: EmptyStateDecision,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 200.dp)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyStateIllustration(decision.illustration)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = decision.title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = decision.suggestion,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}

// ---- vector drawings -------------------------------------------------------

private fun DrawScope.drawIsometricNotebook(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    // Isometric page (parallelogram), rotated notebook look.
    val page = Path().apply {
        moveTo(w * 0.28f, h * 0.22f)
        lineTo(w * 0.68f, h * 0.34f)
        lineTo(w * 0.68f, h * 0.76f)
        lineTo(w * 0.28f, h * 0.64f)
        close()
    }
    drawPath(page, color = primary.copy(alpha = 0.85f))

    // Page depth (thick spine).
    val spine = Path().apply {
        moveTo(w * 0.28f, h * 0.64f)
        lineTo(w * 0.28f, h * 0.72f)
        lineTo(w * 0.68f, h * 0.84f)
        lineTo(w * 0.68f, h * 0.76f)
        close()
    }
    drawPath(spine, color = primary.copy(alpha = 0.45f))

    // Ruled lines on the page.
    for (i in 1..3) {
        val y = h * (0.36f + i * 0.10f)
        val xStart = w * (0.32f)
        val len = w * 0.30f
        drawLine(
            color = secondary.copy(alpha = 0.7f),
            start = Offset(xStart, y),
            end = Offset(xStart + len, y + 0.05f * h),
            strokeWidth = 2f
        )
    }

    // Spiral binding.
    for (i in 0..4) {
        val x = w * (0.35f + i * 0.06f)
        drawCircle(
            color = muted,
            radius = 2.2f,
            center = Offset(x, h * 0.32f),
            style = Stroke(width = 1.4f)
        )
    }

// Pen resting on the notebook.
    drawLine(
        color = tertiary,
        start = Offset(w * 0.52f, h * 0.86f),
        end = Offset(w * 0.76f, h * 0.42f),
        strokeWidth = 4f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawGraphNodes(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    val nodes = listOf(
        Triple(0.30f, 0.34f, 0.055f),
        Triple(0.58f, 0.24f, 0.040f),
        Triple(0.66f, 0.60f, 0.060f),
        Triple(0.38f, 0.68f, 0.040f),
        Triple(0.72f, 0.42f, 0.028f)
    )
    // Edges.
    val edges = listOf(
        0 to 1, 1 to 2, 0 to 3, 2 to 4, 3 to 2, 1 to 4
    )
    for ((a, b) in edges) {
        val na = nodes[a]
        val nb = nodes[b]
        drawLine(
            color = primary.copy(alpha = 0.35f),
            start = Offset(w * na.first, h * na.second),
            end = Offset(w * nb.first, h * nb.second),
            strokeWidth = 2f
        )
    }
    // Nodes.
    val palette = listOf(primary, secondary, tertiary, tertiary.copy(alpha = 0.8f), secondary.copy(alpha = 0.8f))
    nodes.forEachIndexed { index, (fx, fy, fr) ->
        drawCircle(
            color = palette[index % palette.size],
            radius = w * fr,
            center = Offset(w * fx, h * fy)
        )
    }
    // Hub highlight.
    drawCircle(
        color = Color.White.copy(alpha = 0.6f),
        radius = w * nodes[0].third * 0.32f,
        center = Offset(w * 0.30f, h * 0.32f)
    )
    drawCircle(
        color = muted,
        radius = 2.4f,
        center = Offset(w * 0.30f, h * 0.34f)
    )
}

private fun DrawScope.drawPenNib(
    primary: Color,
    secondary: Color
) {
    val w = size.width
    val h = size.height
    // Nib body (rounded triangle, italic angle).
    val nib = Path().apply {
        moveTo(w * 0.50f, h * 0.16f)
        cubicTo(w * 0.60f, h * 0.30f, w * 0.62f, h * 0.48f, w * 0.56f, h * 0.70f)
        lineTo(w * 0.50f, h * 0.86f)
        lineTo(w * 0.44f, h * 0.70f)
        cubicTo(w * 0.38f, h * 0.48f, w * 0.40f, h * 0.30f, w * 0.50f, h * 0.16f)
        close()
    }
    drawPath(nib, color = primary.copy(alpha = 0.9f))

    // Slit up the nib.
    drawLine(
        color = Color.White.copy(alpha = 0.75f),
        start = Offset(w * 0.50f, h * 0.84f),
        end = Offset(w * 0.50f, h * 0.34f),
        strokeWidth = 1.6f
    )
    // Ink breath.
    drawLine(
        color = secondary,
        start = Offset(w * 0.50f, h * 0.90f),
        end = Offset(w * 0.50f, h * 0.96f),
        strokeWidth = 3.5f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawSearch(
    primary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.42f, h * 0.42f)
    val radius = w * 0.22f
    drawCircle(
        color = primary.copy(alpha = 0.9f),
        radius = radius,
        center = center,
        style = Stroke(width = 7f)
    )
    drawLine(
        color = primary.copy(alpha = 0.9f),
        start = Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
        end = Offset(w * 0.72f, h * 0.74f),
        strokeWidth = 7f,
        cap = StrokeCap.Round
    )
    // Dots suggesting filtered-out items.
    drawCircle(color = muted.copy(alpha = 0.6f), radius = 3f, center = Offset(w * 0.62f, h * 0.28f))
    drawCircle(color = muted.copy(alpha = 0.45f), radius = 2.4f, center = Offset(w * 0.74f, h * 0.40f))
    drawCircle(color = muted.copy(alpha = 0.35f), radius = 2f, center = Offset(w * 0.58f, h * 0.62f))
}

private fun DrawScope.drawTrash(
    primary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    drawRoundRect(
        color = primary.copy(alpha = 0.9f),
        topLeft = Offset(w * 0.26f, h * 0.26f),
        size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.10f),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawRoundRect(
        color = primary.copy(alpha = 0.55f),
        topLeft = Offset(w * 0.30f, h * 0.34f),
        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.40f),
        cornerRadius = CornerRadius(5f, 5f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(w * 0.35f, h * 0.38f),
        size = androidx.compose.ui.geometry.Size(w * 0.30f, h * 0.16f),
        cornerRadius = CornerRadius(3f, 3f)
    )
    // Handle.
    drawRoundRect(
        color = muted,
        topLeft = Offset(w * 0.43f, h * 0.21f),
        size = androidx.compose.ui.geometry.Size(w * 0.14f, h * 0.06f),
        cornerRadius = CornerRadius(2f, 2f),
        style = Stroke(width = 3f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(w * 0.35f, h * 0.42f),
        end = Offset(w * 0.35f, h * 0.66f),
        strokeWidth = 2.2f
    )
}

private fun DrawScope.drawBookStack(
    primary: Color,
    secondary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    val widths = listOf(0.62f, 0.50f, 0.40f)
    val colors = listOf(
        primary.copy(alpha = 0.5f),
        secondary.copy(alpha = 0.55f),
        muted.copy(alpha = 0.5f)
    )
    widths.forEachIndexed { index, frac ->
        val y = h * (0.22f + index * 0.20f)
        val x0 = w * ((1f - frac) / 2f)
        drawRoundRect(
            color = colors[index],
            topLeft = Offset(w * x0, y),
            size = androidx.compose.ui.geometry.Size(w * frac, h * 0.16f),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }
    drawLine(
        color = primary,
        start = Offset(w * 0.38f, h * 0.28f),
        end = Offset(w * 0.62f, h * 0.28f),
        strokeWidth = 2.6f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawPuzzle(
    primary: Color,
    secondary: Color
) {
    val w = size.width
    val h = size.height
    val base = primary.copy(alpha = 0.85f)
    val accent = secondary.copy(alpha = 0.85f)
    // Two interlocking puzzle tiles (approximated with rounded squares + knob).
    val tileTopLeft = Offset(w * 0.24f, h * 0.26f)
    val tileSize = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.48f)
    drawRoundRect(
        color = base,
        topLeft = tileTopLeft,
        size = tileSize,
        cornerRadius = CornerRadius(10f, 10f)
    )
    // Right knob.
    drawCircle(
        color = base,
        radius = w * 0.09f,
        center = Offset(w * 0.74f, h * 0.50f)
    )
    drawRoundRect(
        color = accent,
        topLeft = Offset(w * 0.34f, h * 0.48f),
        size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.26f),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // Top knob notch (puzzle hole).
    drawCircle(
        color = Color.White.copy(alpha = 0.2f),
        radius = w * 0.05f,
        center = Offset(w * 0.50f, h * 0.30f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.35f),
        start = Offset(w * 0.32f, h * 0.34f),
        end = Offset(w * 0.66f, h * 0.34f),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawHistory(
    primary: Color,
    secondary: Color,
    muted: Color
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.50f
    val cy = h * 0.52f
    val r = w * 0.30f
    // Clock face ring.
    drawCircle(
        color = primary.copy(alpha = 0.85f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = 6f)
    )
    // Restore arc (circular arrow sweeping back toward the clock).
    drawArc(
        color = secondary,
        startAngle = 60f,
        sweepAngle = 160f,
        useCenter = false,
        topLeft = Offset(cx - r * 1.18f, cy - r * 1.18f),
        size = androidx.compose.ui.geometry.Size(r * 2.36f, r * 2.36f),
        style = Stroke(width = 5f, cap = StrokeCap.Round)
    )
    // Arrow head.
    val head = Path().apply {
        moveTo(cx + r * 1.18f, cy - r * 0.78f)
        lineTo(cx + r * 1.28f, cy - r * 0.96f)
        lineTo(cx + r * 0.98f, cy - r * 0.86f)
        close()
    }
    drawPath(head, color = secondary)
    // Hour + minute hands.
    drawLine(
        color = muted,
        start = Offset(cx, cy),
        end = Offset(cx, cy - r * 0.55f),
        strokeWidth = 3.4f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = muted,
        start = Offset(cx, cy),
        end = Offset(cx + r * 0.40f, cy + r * 0.28f),
        strokeWidth = 3.4f,
        cap = StrokeCap.Round
    )
    drawCircle(color = primary, radius = 2.6f, center = Offset(cx, cy))
}
