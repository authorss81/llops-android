package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
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
 * Phase 21: the Plugin Store (Phases 23/24 extend it).
 *
 * Lists EVERY known plugin from the bundled catalog (installed + optional
 * not-yet-downloaded definitions) with the correct per-plugin button and state:
 *
 * - **Not downloaded** → Download. Honest semantics: every bundled definition is
 *   compiled in the APK; Download installs the definition (activates it). For a
 *   REMOTE (downloadable) plugin the FIRST download needs explicit consent, then
 *   the signed artifact is fetched over HTTPS, verified (pinned certificate +
 *   SHA-256) and loaded (Phase 23).
 * - **Downloaded** → Delete (with confirmation) + Enable/Disable. Delete
 *   removes the plugin COMPLETELY (settings + downloaded assets wiped, gone
 *   from the registry); Disable is temporary (data kept, re-enableable).
 * - **Updates (Phase 24)** → "Check for updates" fetches the hosted version
 *   manifest (HTTPS, keyless, user-initiated); a downloaded remote plugin with
 *   a newer manifest version shows "Update available (vX → vY)" + an Update
 *   button that opens a per-update approval dialog ("Approve & install"). The
 *   approved update is re-downloaded, re-verified (pinned cert + SHA-256),
 *   smoke-tested and atomically swapped; any failure rolls back to the previous
 *   version. Bundled plugins are marked "managed by app update". There is NO
 *   auto-update toggle — every update is manual + approved.
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
    val pendingConsentId by viewModel.pendingConsentPluginId.collectAsState()
    // Phase 24: update rows + states.
    val updates by viewModel.storeUpdates.collectAsState()
    val updateBusy by viewModel.updateBusy.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    val pendingUpdateId by viewModel.pendingUpdatePluginId.collectAsState()
    val generalMessage by viewModel.storeGeneralMessage.collectAsState()
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
                    "Plugins marked \"bundled\" ship compiled in this app (install is instant and offline); " +
                        "plugins marked \"remote\" are downloadable, signature-verified plugins. " +
                        "\"Download\" makes a plugin available; \"Delete\" removes it completely (settings + " +
                        "downloaded assets wiped); \"Disable\" is temporary and keeps its data. Remote plugins " +
                        "can be updated — always manually, always approved, always verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.checkPluginUpdates() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Check for updates", style = MaterialTheme.typography.labelMedium)
                    }
                    if (updates.isNotEmpty()) {
                        Text(
                            "${updates.size} update(s) available",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                generalMessage?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.dismissStoreGeneralMessage() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (rows.isEmpty()) {
                        item {
                            TactileEmptyState(
                                decision = EmptyStateResolver.decide(EmptyStateKind.PLUGIN_STORE)
                            )
                        }
                    }
                    items(rows, key = { it.entry.pluginId }) { row ->
                                val entry = row.entry
                        val info = row.state
                        val isBusy = row.entry.pluginId in busy
                        val downloadProgress = progress[row.entry.pluginId]
                        val storeMessage = messages[row.entry.pluginId]
                        val localMessage = localMessages[row.entry.pluginId]
                        val updateInfo = updates[entry.pluginId]
                        val isUpdating = entry.pluginId in updateBusy
                        val updateProgressVal = updateProgress[entry.pluginId]

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
                                                append("  ·  ${entry.sourceLabel}")
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
                                    if (!entry.bundled) {
                                        Text(
                                            "Remote (downloadable) plugin — downloaded over HTTPS, verified " +
                                                "(pinned certificate + SHA-256) before any code runs, and OFF until you enable it.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.outline
                                        )
                                    }
                                    if (downloadProgress != null && isBusy) {
                                        LinearProgressIndicator(
                                            progress = { downloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            if (entry.bundled) {
                                                "Installing bundled definition…"
                                            } else {
                                                "Downloading + verifying…"
                                            },
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
                                    if (updateInfo != null) {
                                        Text(
                                            "Update available (v${entry.version} → v${updateInfo.newVersion})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else if (entry.bundled) {
                                        Text(
                                            "Managed by app update (updated with the app release).",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.outline
                                        )
                                    }
                                    if (isUpdating) {
                                        LinearProgressIndicator(
                                            progress = { (updateProgressVal ?: 0f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            "Downloading + verifying update…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.outline
                                        )
                                    }
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
                                        updateInfo?.let { update ->
                                            Button(
                                                onClick = { viewModel.requestPluginUpdate(entry.pluginId) },
                                                enabled = !isBusy && !isUpdating,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Update", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
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
                                                enabled = !isBusy && !isUpdating,
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

    // Phase 23: confirm before the FIRST download of a remote plugin. No bytes
    // are fetched until the user explicitly approves — this dialog is the
    // approval. It explains the signature-verification guarantees honestly.
    val consentId = pendingConsentId
    if (consentId != null) {
        val consentRow = rows.firstOrNull { it.entry.pluginId == consentId }
        val consentMessage = messages[consentId]
        AlertDialog(
            onDismissRequest = { viewModel.respondStoreConsent(grant = false) },
            icon = { Icon(Icons.Outlined.Download, contentDescription = null, tint = colorScheme.primary) },
            title = { Text("Download remote plugin?") },
            text = {
                Text(
                    consentMessage ?: consentRow?.entry?.let {
                        "Download \"${it.name}\"? It is downloaded over HTTPS, verified against a pinned " +
                            "certificate + SHA-256 before any code runs, and stays OFF until you enable it."
                    } ?: "Download this plugin?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.respondStoreConsent(grant = true) }) {
                    Text("Download", color = colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondStoreConsent(grant = false) }) { Text("Cancel") }
            }
        )
    }

    // Phase 24: per-update approval. An update NEVER applies silently — this
    // dialog is the approval (there is no auto-update toggle anywhere). It shows
    // current → new version, the update notes, the download size, and asks for
    // an explicit "Approve & install". Refusing/downgrading to an older version
    // is blocked before the dialog (the checker never offers one).
    val updateApprovalId = pendingUpdateId
    if (updateApprovalId != null) {
        val updateRow = rows.firstOrNull { it.entry.pluginId == updateApprovalId }
        val update = updates[updateApprovalId]
        AlertDialog(
            onDismissRequest = { viewModel.respondUpdateApproval(grant = false) },
            icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = colorScheme.primary) },
            title = { Text("Approve update?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        update?.let {
                            "\"${updateRow?.entry?.name ?: update.pluginId}\" will be updated from " +
                                "v${update.currentVersion} to v${update.newVersion}."
                        } ?: "Update this plugin?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    update?.updateNotes?.let { notes ->
                        Text(
                            "What changed: $notes",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    update?.installSizeBytes?.let { size ->
                        Text(
                            "Download size: ~${size / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "The new version is downloaded over HTTPS and re-verified against a pinned " +
                            "certificate + SHA-256 before it replaces the current version. If anything " +
                            "fails, the previous version stays active and can be rolled back to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.respondUpdateApproval(grant = true) }) {
                    Text("Approve & install", color = colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondUpdateApproval(grant = false) }) { Text("Cancel") }
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