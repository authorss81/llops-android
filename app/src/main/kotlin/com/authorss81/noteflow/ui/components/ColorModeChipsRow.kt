package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.StrokeColorMode

/**
 * Phase 122: the colour-MODE chip row (Solid / Rainbow / Gradient / Shimmer)
 * shared by the colour picker and the width/quick picker. Selecting a
 * multi-color mode keeps the current base color; gradient additionally shows a
 * second-color mini-row below. Reused by both bottom sheets so the rainbow mode
 * is reachable from quick pickup without opening the full colour picker.
 */
@Composable
fun ColorModeChipsRow(
    currentColorMode: StrokeColorMode,
    currentColor: Color,
    currentGradientToColor: Color,
    onColorModeChange: (StrokeColorMode, Color, Color?) -> Unit,
    onGradientToColorSelect: (Color) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StrokeColorMode.entries.forEach { mode ->
            val selected = currentColorMode == mode
            FilterChip(
                selected = selected,
                onClick = {
                    // Phase 122 (review fix): the chip is intentionally idempotent —
                    // re-tapping the active mode is a NO-OP instead of silently
                    // reverting to SOLID, so an accidental tap can no longer reset a
                    // now-persisted mode across sessions.
                    if (!selected) {
                        onColorModeChange(mode, currentColor, currentGradientToColor)
                    }
                },
                label = {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            mode == StrokeColorMode.RAINBOW -> Color(0xFFE91E5A)
                            mode == StrokeColorMode.GRADIENT -> Color(0xFF2F80ED)
                            mode == StrokeColorMode.SHIMMER -> Color(0xFF9C27B0)
                            else -> LocalContentColor.current
                        }
                    )
                },
                leadingIcon = if (mode.isMultiColor) {
                    {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    when (mode) {
                                        StrokeColorMode.RAINBOW -> Brush.sweepGradient(
                                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                                        )
                                        StrokeColorMode.GRADIENT -> Brush.linearGradient(listOf(currentColor, currentGradientToColor))
                                        StrokeColorMode.SHIMMER -> Brush.linearGradient(listOf(Color.White, currentColor, Color.White))
                                        else -> SolidColor(currentColor)
                                    }
                                )
                        )
                    }
                } else null
            )
        }
    }

    if (currentColorMode == StrokeColorMode.GRADIENT) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Gradient end",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            val gradientCandidates = remember(currentColor) {
                listOf(currentColor) +
                    com.authorss81.noteflow.services.PaletteCatalog.curated
                    .map { Color(it.argb) }
                    .filter { it != currentColor }
                    .distinctBy { it.toArgb() }
                    .take(10)
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(gradientCandidates, key = { it.toArgb() }) { candidate ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(candidate)
                            .border(
                                width = if (candidate.toArgb() == currentGradientToColor.toArgb()) 3.dp else 1.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                            .clickable { onGradientToColorSelect(candidate) }
                    )
                }
            }
        }
    }
}
