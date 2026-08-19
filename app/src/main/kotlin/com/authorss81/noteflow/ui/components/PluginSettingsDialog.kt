package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.PluginDiagnostics
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.services.PluginDiagnosticsRowPolicy
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * Phase 10/11: Settings → Plugins. Lists every compile-time-installed plugin
 * with its derived lifecycle state, reason, version, last invocation outcome and
 * a "Test now" diagnostics action. Enabling is refused with an inline reason when
 * the registry's requirements (dependency / conflict / manifest) are unmet; the
 * refusal is never silent and never a crash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val enabledIds by viewModel.pluginEnabledIds.collectAsState()
    val states by viewModel.pluginStates.collectAsState()
    val diagnostics by viewModel.pluginDiagnosticsEntries.collectAsState()
    // Inline per-plugin messages (e.g. an enable refusal with its reason).
    var localMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val colorScheme = MaterialTheme.colorScheme
    val stateColor: (PluginLifecycleState) -> Color = { state ->
        when (state) {
            PluginLifecycleState.AVAILABLE -> colorScheme.primary
            PluginLifecycleState.ENABLED -> colorScheme.primary
            PluginLifecycleState.UNAVAILABLE -> colorScheme.error
            PluginLifecycleState.DISABLED -> colorScheme.onSurfaceVariant
            PluginLifecycleState.REGISTERED -> colorScheme.onSurfaceVariant
            PluginLifecycleState.REJECTED -> colorScheme.error
        }
    }
    val stateLabel: (PluginLifecycleState) -> String = { state ->
        when (state) {
            PluginLifecycleState.AVAILABLE -> "Active"
            PluginLifecycleState.ENABLED -> "Enabled — verifying"
            PluginLifecycleState.UNAVAILABLE -> "Unavailable"
            PluginLifecycleState.DISABLED -> "Disabled"
            PluginLifecycleState.REGISTERED -> "Available — off"
            PluginLifecycleState.REJECTED -> "Rejected"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Plugins")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Plugins are optional capabilities. Enable the ones you want — they are off by default. " +
                        "Each plugin shows its current state; use Test now to run its self-check.",
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                viewModel.pluginRegistry.allPlugins.forEach { plugin ->
                    val info: PluginStateInfo? = states[plugin.id]
                    val entry: PluginDiagnostics.Entry? = diagnostics.firstOrNull { it.plugin.id == plugin.id }
                    val localMessage = localMessages[plugin.id]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                // A unavailable-but-enabled plugin can still be
                                // toggled OFF so the user isn't stuck with it.
                                enabled = info?.state != PluginLifecycleState.UNAVAILABLE ||
                                    enabledIds[plugin.id] == true,
                                onCheckedChange = { wantOn ->
                                    localMessages = localMessages - plugin.id
                                    val result = viewModel.setPluginEnabled(plugin.id, wantOn)
                                    if (result is PluginEnableResult.Refused) {
                                        localMessages = localMessages + (plugin.id to result.reason)
                                    }
                                }
                            )
                        }
                        val state = info?.state
                        Text(
                            "${stateLabel(state ?: PluginLifecycleState.REGISTERED)}" +
                                // Phase 157 (phase-148 rule): the state reason can
                                // be plugin-influenceable (availability gate) —
                                // scrubbed before it may reach the row.
                                (PluginDiagnosticsRowPolicy.scrub(info?.reason)?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = stateColor(state ?: PluginLifecycleState.REGISTERED)
                        )
                        // Phase 157 feature 3: the compact diagnostics footer —
                        // served capabilities, opt-in and lifecycle, all fixed
                        // labels from the tested policy table.
                        Text(
                            PluginDiagnosticsRowPolicy.footer(
                                capabilities = plugin.capabilities,
                                enabled = enabledIds[plugin.id] == true,
                                state = state
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                        if (localMessage != null) {
                            Text(
                                localMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        PluginDiagnosticsRowPolicy.lastInvocationLine(entry?.lastInvocation)?.let { last ->
                            Text(
                                last,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    localMessages = localMessages - plugin.id
                                    viewModel.testPlugin(plugin.id)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Text("Test now", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
