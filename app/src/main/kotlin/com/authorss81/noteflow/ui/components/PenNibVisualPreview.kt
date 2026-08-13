package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStrokeStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.PenPreset
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live rendered visual stroke and pen nib preview for ink tools and pen presets.
 */
@Composable
fun PenNibVisualPreview(
    tool: StrokeTool,
    color: Color,
    width: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(16f, h * 0.5f)
            cubicTo(w * 0.35f, h * 0.15f, w * 0.65f, h * 0.85f, w - 16f, h * 0.5f)
        }

        when (tool) {
            StrokeTool.DOTTED -> {
                val dashIntervals = floatArrayOf(width * 2f, width * 2.5f)
                drawPath(
                    path = path,
                    color = color,
                    style = DrawStrokeStyle(
                        width = width.coerceIn(2f, 10f),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(dashIntervals, 0f)
                    )
                )
            }
            StrokeTool.NEON -> {
                val effW = width.coerceIn(3f, 14f)
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.25f),
                    style = DrawStrokeStyle(width = effW * 2.8f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.85f),
                    style = DrawStrokeStyle(width = effW * 1.5f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = Color.White,
                    style = DrawStrokeStyle(width = effW * 0.6f, cap = StrokeCap.Round)
                )
            }
            StrokeTool.FINELINER -> {
                drawPath(
                    path = path,
                    color = color,
                    style = DrawStrokeStyle(width = width.coerceIn(1.2f, 4f), cap = StrokeCap.Square)
                )
            }
            StrokeTool.CHISEL_MARKER, StrokeTool.CALLIGRAPHIC -> {
                val effW = width.coerceIn(3f, 16f)
                val angle = Math.toRadians(35.0)
                val dx = (cos(angle) * effW * 0.8f).toFloat()
                val dy = (sin(angle) * effW * 0.8f).toFloat()
                val chiselPath = Path().apply {
                    moveTo(16f - dx, h * 0.5f - dy)
                    cubicTo(w * 0.35f - dx, h * 0.15f - dy, w * 0.65f - dx, h * 0.85f - dy, w - 16f - dx, h * 0.5f - dy)
                    lineTo(w - 16f + dx, h * 0.5f + dy)
                    cubicTo(w * 0.65f + dx, h * 0.85f + dy, w * 0.35f + dx, h * 0.15f + dy, 16f + dx, h * 0.5f + dy)
                    close()
                }
                drawPath(chiselPath, color.copy(alpha = 0.88f))
            }
            StrokeTool.LASER -> {
                val lColor = if (color == Color.Unspecified || color == Color.Transparent) Color(0xFFFF0044) else color
                val effW = width.coerceIn(4f, 14f)
                drawPath(
                    path = path,
                    color = lColor.copy(alpha = 0.35f),
                    style = DrawStrokeStyle(width = effW * 3.2f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = lColor.copy(alpha = 0.9f),
                    style = DrawStrokeStyle(width = effW * 1.5f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = path,
                    color = Color.White,
                    style = DrawStrokeStyle(width = effW * 0.5f, cap = StrokeCap.Round)
                )
                val endPt = Offset(w - 16f, h * 0.5f)
                drawCircle(lColor.copy(alpha = 0.4f), radius = effW * 2.5f, center = endPt)
                drawCircle(lColor, radius = effW * 1.3f, center = endPt)
                drawCircle(Color.White, radius = effW * 0.6f, center = endPt)
            }
            StrokeTool.HIGHLIGHTER -> {
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.45f),
                    style = DrawStrokeStyle(width = width.coerceIn(8f, 24f), cap = StrokeCap.Square)
                )
            }
            else -> {
                drawPath(
                    path = path,
                    color = color,
                    style = DrawStrokeStyle(width = width.coerceIn(1.5f, 16f), cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * Rich visual card for Pen Presets in sheet selectors.
 */
@Composable
fun PenPresetVisualCard(
    preset: PenPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = modifier.width(160.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(preset.color)
                        .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                )
                Text(
                    text = "${preset.width.toInt()}pt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Stroke sample preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            ) {
                PenNibVisualPreview(
                    tool = preset.tool,
                    color = preset.color,
                    width = preset.width,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
            }
        }
    }
}
