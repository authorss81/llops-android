package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendSender
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Send to nearby device (LocalSend)" — real, local-network-only file transfer.
 *
 * The user explicitly chooses WHAT to share (a note, the encrypted vault
 * backup, or a vault archive) and THEN taps a discovered device. The receiving
 * device must human-accept the transfer (the device's own confirm dialog) —
 * this app never auto-accepts and never receives.
 */
internal enum class LocalSendPayload(val label: String) {
    NOTE_HTML("This note (HTML)"),
    VAULT_BACKUP("Encrypted vault backup (.nfb)"),
    OBSIDIAN_ZIP("Obsidian vault (ZIP)"),
    HTML_ZIP("HTML website (ZIP)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendSendDialog(
    viewModel: NoteflowViewModel,
    pages: List<NotePageEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sender = remember { LocalSendSender() }

    var payloadType by remember { mutableStateOf(LocalSendPayload.NOTE_HTML) }
    var selectedPage by remember { mutableStateOf(pages.firstOrNull()) }

    var discoveredDevices by remember { mutableStateOf<List<LocalSendDevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveryNote by remember { mutableStateOf<String?>(null) }

    var phase by remember { mutableStateOf("Idle") } // Idle | Preparing | Requesting | Sending | Done/Failed
    var statusText by remember { mutableStateOf<String?>(null) }
    var progressSent by remember { mutableStateOf(0L) }
    var progressTotal by remember { mutableStateOf(0L) }
    var activeDevice by remember { mutableStateOf<LocalSendDevice?>(null) }

    fun discover() {
        scope.launch {
            isDiscovering = true
            discoveryNote = "Listening on the local network…"
            discoveredDevices = emptyList()
            val devices = sender.discoverDevices(discoveryTimeoutMs = 3_000L, includeLegacyHttpScan = true)
            discoveredDevices = devices
            discoveryNote = when {
                devices.isEmpty() ->
                    "No LocalSend devices found. Make sure the target device has LocalSend open and " +
                        "both devices are on the same Wi-Fi. Some networks block discovery — try again."
                else -> "Found ${devices.size} device${if (devices.size == 1) "" else "s"}."
            }
            isDiscovering = false
        }
    }

    suspend fun buildPayloadFile(): File? = withContext(Dispatchers.IO) {
        when (payloadType) {
            LocalSendPayload.NOTE_HTML -> selectedPage?.let {
                ImportExportService.exportNoteToHtml(context, it, viewModel.repository)
            }
            LocalSendPayload.VAULT_BACKUP ->
                ImportExportService.exportBackup(context, viewModel.repository.encryptionKey, backupPassword = null)
            LocalSendPayload.OBSIDIAN_ZIP ->
                ImportExportService.exportObsidianVaultZip(context, "SmoothNotes_Vault", pages, viewModel.repository)
            LocalSendPayload.HTML_ZIP ->
                ImportExportService.exportVaultToHtmlZip(context, "SmoothNotes_Site", pages, viewModel.repository)
        }
    }

    fun startSend(device: LocalSendDevice) {
        scope.launch {
            activeDevice = device
            phase = "Preparing"
            statusText = "Preparing the file to send…"
            val file = buildPayloadFile()
            if (file == null) {
                phase = "Failed"
                statusText = "Could not create the export file."
                return@launch
            }
            progressSent = 0L
            progressTotal = file.length()
            phase = "Requesting"
            statusText = "Waiting for ${device.alias} to accept the transfer…"
            val result = sender.sendFile(device, file) { sent, total ->
                if (phase == "Requesting") {
                    phase = "Sending"
                    statusText = "Sending ${file.name} to ${device.alias}…"
                }
                progressSent = sent
                progressTotal = total
            }
            activeDevice = null
            if (result.success) {
                phase = "Done"
                statusText = result.description
            } else {
                phase = "Failed"
                statusText = result.description
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            sender.cancelActiveTransfer()
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NearMe, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send to Nearby Device", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Sends the file directly to another phone/computer on the same Wi-Fi using the " +
                        "LocalSend protocol — no internet, no account. The receiving device must accept first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Payload selection
                var payloadMenuOpen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = payloadMenuOpen,
                    onExpandedChange = { payloadMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = payloadType.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("What to send") },
                        leadingIcon = { Icon(Icons.Outlined.DevicesOther, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = payloadMenuOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = payloadMenuOpen, onDismissRequest = { payloadMenuOpen = false }) {
                        LocalSendPayload.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    payloadType = option
                                    payloadMenuOpen = false
                                }
                            )
                        }
                    }
                }
                if (payloadType == LocalSendPayload.NOTE_HTML) {
                    var noteMenuOpen by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = noteMenuOpen,
                        onExpandedChange = { noteMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = selectedPage?.title ?: "—",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Note") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = noteMenuOpen) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = noteMenuOpen, onDismissRequest = { noteMenuOpen = false }) {
                            pages.forEach { page ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            page.title.ifBlank { "Untitled" },
                                            maxLines = 1
                                        )
                                    },
                                    onClick = {
                                        selectedPage = page
                                        noteMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Device discovery
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { discover() }, enabled = !isDiscovering && phase == "Idle") {
                        if (isDiscovering) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isDiscovering) "Searching…" else "Find nearby devices")
                    }
                    if (discoveredDevices.isNotEmpty() && phase == "Idle") {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { discover() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }
                discoveryNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Device list
                if (discoveredDevices.isNotEmpty()) {
                    Text("Devices", style = MaterialTheme.typography.labelMedium)
                    discoveredDevices.forEach { device ->
                        val isActive = activeDevice == device
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = phase == "Idle") { startSend(device) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.DevicesOther, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.alias, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${device.deviceModel ?: "Unknown device"} · ${device.protocol.uppercase()}"
                                            + if (device.fingerprint != null) "" else " · no TLS cert",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isActive) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }

                // Transfer progress
                when (phase) {
                    "Preparing", "Requesting" -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    "Sending" -> {
                        val fraction = if (progressTotal > 0) {
                            (progressSent.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Sending ${progressSent / 1024} of ${progressTotal / 1024} KB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> {}
                }
                statusText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (phase == "Failed") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                "Done" -> TextButton(onClick = onDismiss) { Text("Done") }
                "Preparing", "Requesting", "Sending" -> {
                    TextButton(onClick = { sender.cancelActiveTransfer() }) { Text("Cancel") }
                }
                "Failed" -> TextButton(onClick = {
                    phase = "Idle"
                    statusText = null
                }) { Text("OK") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (phase == "Idle") {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}