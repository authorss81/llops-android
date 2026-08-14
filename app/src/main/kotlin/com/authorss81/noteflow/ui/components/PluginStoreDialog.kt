package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel

/**
 * Phase 21: the Plugin Store.
 *
 * Lists EVERY known plugin from the bundled catalog (installed + optional
 * not-yet-downloaded definitions) with the correct per-plugin button and state:
 *
 * - **Not downloaded** → Download. Honest semantics: every definition is
 *   bundled in the APK (compile-time rule); Download installs the definition
 *   (activates it), it never fetches an APK and never needs the network.
 * - **Downloaded** → Delete (with confirmation) + Enable/Disable. Delete
 *   removes the plugin COMPLETELY (settings + downloaded assets wiped, gone
 *   from the registry); Disable is temporary (data kept, re-enableable).
 *
 * Reachable from HomeScreen's ⋮ menu → "Plugin Store" — functional, not dead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginStoreDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val rows by viewModel.storeRows.collectAsState()
    val busy by viewModel.storeBusy.collectAsState()
    val progress by viewModel.storeProgress.collectAsState()
    val messages by viewModel.storeMessages.collectAsState()
    // pluginId pending a destructive-delete confirmation.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    // pluginId → inline message (e.g. an enable refusal with its reason).
    var localMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val colorScheme = MaterialTheme.colorScheme
    val statusColor: (PluginLifecycleState?) -> Color = { state ->
        when (state) {
            PluginLifecycleState.AVAILABLE, PluginLifecycleState.ENABLED -> colorScheme.primary
            PluginLifecycleState.UNAVAILABLE, PluginLifecycleState.REJECTED -> colorScheme.error
            PluginLifecycleState.DISABLED, PluginLifecycleState.REGISTERED -> colorScheme.onSurfaceVariant
            else -> colorScheme.onSurfaceVariant
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Storefront, contentDescription = null, tint = colorScheme.primary) },
        title = { Text("Plugin Store") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "All plugin definitions ship bundled in this app (compile-time rule — no APK downloading, " +
                        "fully offline). \"Download\" installs a definition and makes the plugin available; " +
                        "\"Delete\" removes a plugin completely (settings + downloaded assets wiped); " +
                        "\"Disable\" is temporary and keeps its data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows, key = { it.entry.pluginId }) { row ->
                        val entry = row.entry
                        val info = row.state
                        val isBusy = row.entry.pluginId in busy
                        val downloadProgress = progress[row.entry.pluginId]
                        val storeMessage = messages[row.entry.pluginId]
                        val localMessage = localMessages[row.entry.pluginId]

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Extension,
                                        contentDescription = null,
                                        tint = colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (entry.name.isNotBlank()) entry.name else entry.pluginId,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            entry.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            buildString {
                                                append("v${entry.version}")
                                                append("  ·  ${entry.category}")
                                                append("  ·  " + entry.capabilities.joinToString(", ") { it.label })
                                                if (entry.permissions.isNotEmpty()) {
                                                    append("  ·  " + entry.permissions.joinToString(", ") { it.label })
                                                }
                                                entry.installSizeBytes?.let {
                                                    append("  ·  ~${it / (1024 * 1024)} MB model on device")
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.outline
                                        )
                                    }
                                }

                                if (!row.installed) {
                                    Text(
                                        "Not downloaded",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                    if (downloadProgress != null && isBusy) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "Installing bundled definition…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.outline
                                        )
                                    }
                                } else {
                                    Text(
                                        "${statusLabel(info?.state)}" +
                                            (info?.reason?.let { " — $it" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = statusColor(info?.state)
                                    )
                                }

                                if (localMessage != null) {
                                    Text(
                                        localMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.error
                                    )
                                }
                                if (storeMessage != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            storeMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { viewModel.clearStoreMessage(entry.pluginId) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!row.installed) {
                                        Button(
                                            onClick = { viewModel.storeDownload(entry.pluginId) },
                                            enabled = !isBusy,
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Download", style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        // Rejected plugins cannot be enabled; show Delete only.
                                        val state = info?.state
                                        if (state != PluginLifecycleState.REJECTED) {
                                            val wantOn = state == PluginLifecycleState.REGISTERED ||
                                                state == PluginLifecycleState.DISABLED
                                            TextButton(
                                                onClick = {
                                                    localMessages = localMessages - entry.pluginId
                                                    val result = viewModel.setPluginEnabled(entry.pluginId, wantOn)
                                                    if (result is PluginEnableResult.Refused) {
                                                        localMessages = localMessages + (entry.pluginId to result.reason)
                                                    }
                                                },
                                                enabled = !isBusy,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    if (wantOn) "Enable" else "Disable",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { pendingDeleteId = entry.pluginId },
                                            enabled = !isBusy,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = colorScheme.error
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Delete", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                if (row.plugin != null && row.installed) {
                                    Text(
                                        "Delete removes it completely; a re-download starts fresh (off).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.outline
                                    )
                                }
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

    val deleteId = pendingDeleteId
    if (deleteId != null) {
        val row = rows.firstOrNull { it.entry.pluginId == deleteId }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete plugin?") },
            text = {
                Text(
                    "Delete \"${row?.entry?.name ?: deleteId}\"? This REMOVES the plugin completely: " +
                        "its settings are wiped and any downloaded models/assets are deleted. " +
                        "You can re-download it later from the store. (Disable instead if you just want it off.)"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    viewModel.storeDelete(deleteId)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            }
        )
    }
}

private fun statusLabel(state: PluginLifecycleState?): String = when (state) {
    PluginLifecycleState.AVAILABLE -> "Active"
    PluginLifecycleState.ENABLED -> "Enabled — verifying"
    PluginLifecycleState.UNAVAILABLE -> "Unavailable"
    PluginLifecycleState.DISABLED -> "Disabled"
    PluginLifecycleState.REGISTERED -> "Available — off"
    PluginLifecycleState.REJECTED -> "Rejected"
    else -> "Not downloaded"
}