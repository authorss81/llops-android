package com.authorss81.noteflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Floating Zoom Controller widget for document and canvas inspection.
 * Provides quick Zoom Out, Zoom In, Zoom Level readout, Fit-to-Width, and Preset menu.
 */
@Composable
fun FloatingZoomWidget(
    zoomScale: Float,
    onZoomChange: (Float) -> Unit,
    onFitWidth: () -> Unit,
    onFitPage: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    var showPresetMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.testTag("floating_zoom_widget"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            if (!isExpanded) {
                IconButton(
                    onClick = { isExpanded = true },
                    modifier = Modifier.size(36.dp).testTag("zoom_expand_btn")
                ) {
                    Icon(
                        Icons.Outlined.ZoomIn,
                        contentDescription = "Expand Zoom Controls",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // Zoom Out Button
                IconButton(
                    onClick = {
                        val next = (zoomScale / 1.25f).coerceIn(0.25f, 5.0f)
                        onZoomChange(next)
                    },
                    modifier = Modifier.size(36.dp).testTag("zoom_out_btn")
                ) {
                    Icon(
                        Icons.Outlined.Remove,
                        contentDescription = "Zoom Out",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Zoom Readout / Preset Menu Trigger
                Box {
                    val percentText = "${(zoomScale * 100).roundToInt()}%"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showPresetMenu = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("zoom_percent_btn")
                    ) {
                        Text(
                            text = percentText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    DropdownMenu(
                        expanded = showPresetMenu,
                        onDismissRequest = { showPresetMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Fit to Width") },
                            leadingIcon = { Icon(Icons.Outlined.FitScreen, contentDescription = null) },
                            onClick = {
                                onFitWidth()
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Fit to Page") },
                            leadingIcon = { Icon(Icons.Outlined.AspectRatio, contentDescription = null) },
                            onClick = {
                                onFitPage()
                                showPresetMenu = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("50%") },
                            onClick = {
                                onZoomChange(0.5f)
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("100% (Original)") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = {
                                onResetZoom()
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("150%") },
                            onClick = {
                                onZoomChange(1.5f)
                                showPresetMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("200%") },
                            onClick = {
                                onZoomChange(2.0f)
                                showPresetMenu = false
                            }
                        )
                    }
                }

                // Zoom In Button
                IconButton(
                    onClick = {
                        val next = (zoomScale * 1.25f).coerceIn(0.25f, 5.0f)
                        onZoomChange(next)
                    },
                    modifier = Modifier.size(36.dp).testTag("zoom_in_btn")
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "Zoom In",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Quick Fit-to-Width Button
                IconButton(
                    onClick = onFitWidth,
                    modifier = Modifier.size(36.dp).testTag("zoom_fit_width_btn")
                ) {
                    Icon(
                        Icons.Outlined.FitScreen,
                        contentDescription = "Fit Page Width",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
