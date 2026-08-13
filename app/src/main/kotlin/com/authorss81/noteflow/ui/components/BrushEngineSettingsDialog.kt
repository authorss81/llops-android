package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.LibMyPaintJni
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.utils.HardwareProfiler
import com.authorss81.noteflow.utils.RenderingEngineTier

@Composable
fun BrushEngineSettingsDialog(
    settings: SettingsManager,
    onDismiss: () -> Unit,
    onEngineChanged: (RenderingEngineTier) -> Unit
) {
    val context = LocalContext.current
    val hardwareProfile = remember { HardwareProfiler.profile(context) }
    
    var selectedTier by remember {
        mutableStateOf(HardwareProfiler.getActiveEngine(context, settings))
    }
    
    var pendingTierForWarning by remember { mutableStateOf<RenderingEngineTier?>(null) }

    val recommended = hardwareProfile.recommendedEngine

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Rendering Engine Settings",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Rendering Engine & Hardware Tier",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Hardware Profile: ${hardwareProfile.detectedDeviceTier.name} (${String.format("%.1f", hardwareProfile.totalRamGb)} GB RAM • ${hardwareProfile.availableCores} Cores)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Recommendation Header
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Recommended",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Recommended for Your Device",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = recommended.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Text(
                    text = "Select Engine Architecture:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Engine Option Cards
                RenderingEngineTier.entries.forEach { tierOption ->
                    val isSelected = selectedTier == tierOption
                    val isRecommended = recommended == tierOption
                    val isSupported = hardwareProfile.apiLevel >= tierOption.minimumApi

                    val borderColor = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isRecommended -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }

                    val containerColor = when {
                        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isSupported) {
                                // Check if user is selecting an engine HIGHER than recommended tier
                                val isExceeding = tierOption.ordinal > recommended.ordinal
                                if (isExceeding && tierOption != selectedTier) {
                                    pendingTierForWarning = tierOption
                                } else {
                                    selectedTier = tierOption
                                    settings.renderingEngineOverride = tierOption.id
                                    onEngineChanged(tierOption)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor,
                        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                enabled = isSupported
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = tierOption.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )

                                    if (isRecommended) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 2.dp)
                                        ) {
                                            Text(
                                                text = "RECOMMENDED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    if (tierOption == RenderingEngineTier.LIBMYPAINT_NATIVE) {
                                        val isNativeReady = LibMyPaintJni.isNativeLibraryLoaded
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isNativeReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = if (isNativeReady) "C++ NDK LOADED" else "JNI BRIDGE READY",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isNativeReady) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = tierOption.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )

                                if (!isSupported) {
                                    Text(
                                        text = "Requires Android API ${tierOption.minimumApi}+ (Device API: ${hardwareProfile.apiLevel})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reset to Recommended Button
                if (settings.renderingEngineOverride != null) {
                    TextButton(
                        onClick = {
                            settings.renderingEngineOverride = null
                            selectedTier = recommended
                            onEngineChanged(recommended)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset Recommended")
                    }
                }

                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    )

    // Performance & Battery Hardware Warning Dialog (Phase 35.2)
    pendingTierForWarning?.let { targetTier ->
        AlertDialog(
            onDismissRequest = { pendingTierForWarning = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Hardware Performance Warning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Warning: The selected rendering engine (${targetTier.displayName}) exceeds your recommended hardware profile (${hardwareProfile.detectedDeviceTier.name} - ${recommended.displayName}).\n\n" +
                            "Switching from the recommended tier may cause frame drops, thermal throttling, elevated RAM pressure, and significantly increased battery consumption.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedTier = targetTier
                        settings.renderingEngineOverride = targetTier.id
                        onEngineChanged(targetTier)
                        pendingTierForWarning = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Apply Engine Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingTierForWarning = null }) {
                    Text("Keep Recommended")
                }
            }
        )
    }
}
