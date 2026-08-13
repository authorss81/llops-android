package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.PluginStatus
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel

/**
 * Phase 10: Settings → Plugins. Lists every compile-time-installed plugin with
 * its availability status and an opt-in toggle. A plugin that fails
 * [isAvailable] shows "Unavailable" and cannot be enabled; otherwise the toggle
 * reflects the persisted enable state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val enabledIds by viewModel.pluginEnabledIds.collectAsState()
    val plugins = viewModel.pluginRegistry.allPlugins

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Plugins")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Plugins are optional capabilities. Enable the ones you want — they are off by default.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                plugins.forEach { plugin ->
                    val status = viewModel.pluginRegistry.statusOf(plugin, context)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    plugin.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "v${plugin.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = enabledIds[plugin.id] == true,
                                enabled = status != PluginStatus.UNAVAILABLE,
                                onCheckedChange = { viewModel.setPluginEnabled(plugin.id, it) }
                            )
                        }
                        Text(
                            when (status) {
                                PluginStatus.ENABLED -> "Enabled"
                                PluginStatus.DISABLED -> "Available — off"
                                PluginStatus.UNAVAILABLE -> "Unavailable on this device"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (status) {
                                PluginStatus.ENABLED -> MaterialTheme.colorScheme.primary
                                PluginStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                                PluginStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}