package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.plugins.FileTransferKind
import com.authorss81.noteflow.plugins.FileTransferOutcome
import com.authorss81.noteflow.plugins.FileTransferRequest
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendDiscoveryPolicy
import com.authorss81.noteflow.services.localsend.LocalSendGate
import com.authorss81.noteflow.services.localsend.LocalSendPairing
import com.authorss81.noteflow.services.localsend.LocalSendPairingCodes
import com.authorss81.noteflow.services.localsend.LocalSendPairingRequest
import com.authorss81.noteflow.services.localsend.LocalSendSender
import com.authorss81.noteflow.services.localsend.SettingsLocalSendPairedDeviceStore
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import com.authorss81.noteflow.utils.nestedScrollGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Send to nearby device (LocalSend)" — real, local-network-only file transfer.
 *
 * The user explicitly chooses WHAT to share (a note, the encrypted vault
 * backup, or a vault archive) and THEN taps a discovered device.
 *
 * B1-NET-02 (phase-41) pairing & consent model:
 *  - TLS only: a device that announces `protocol:"http"` (or no TLS
 *    fingerprint) is shown but can never be sent to — it is disabled with a
 *    reason. Nothing ever leaves over cleartext.
 *  - TOFU pairing: the FIRST send to a device shows its TLS fingerprint
 *    (formatted for out-of-band comparison) plus a short pairing code derived
 *    from it. The user must explicitly confirm the fingerprint matches what
 *    the receiving device shows ("Pair & Send"); the fingerprint is then
 *    persisted. An unknown/unpaired device is refused.
 *  - Per-send confirmation: EVERY send is preceded by an explicit confirm
 *    dialog. The receiver's `/prepare-upload` 200 is NOT treated as evidence a
 *    human accepted — a same-LAN fake receiver answers it immediately. Consent
 *    is the pairing + this explicit per-send confirmation only.
 */
internal enum class LocalSendPayload(val label: String) {
    NOTE_HTML("This note (HTML)"),
    VAULT_BACKUP("Encrypted vault backup (.nfb)"),
    OBSIDIAN_ZIP("Obsidian vault (ZIP)"),
    HTML_ZIP("HTML website (ZIP)")
}

/**
 * Review-fix (phase-173): when the compile-time FileTransfer plugin
 * (`plugins.filetransfer`, opt-in/off by default) is enabled, the send is routed
 * THROUGH the capability so the plugin route has a real production caller. The
 * plugin delegates to the exact same sender + pairing store this dialog uses, so
 * every consent gate (TOFU pairing, TLS-only, receiver's `/prepare-upload`
 * accept) is unchanged; when it is disabled this stays the direct LocalSend
 * send. The plugin's own progress numbers are surfaced through the same
 * sent/total callbacks as the direct path.
 */
private const val PLUGIN_ID_FILE_TRANSFER = "plugins.filetransfer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSendSendDialog(
    viewModel: NoteflowViewModel,
    pages: List<NotePageEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairedDevices = remember { SettingsLocalSendPairedDeviceStore(SettingsManager(context.applicationContext)) }
    val sender = remember { LocalSendSender(pairedDevices) }

    var payloadType by remember { mutableStateOf(LocalSendPayload.NOTE_HTML) }
    var selectedPage by remember { mutableStateOf(pages.firstOrNull()) }

    var discoveredDevices by remember { mutableStateOf<List<LocalSendDevice>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveryNote by remember { mutableStateOf<String?>(null) }

    // B1-NET-06 (phase-85): the /24 HTTP register sweep is OFF unless the user
    // explicitly opts in for it (per-search checkbox, never persisted). Discovery
    // only ever runs on the explicit "Find nearby devices" action below.
    var legacyHttpScanOptIn by remember {
        mutableStateOf(LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT)
    }

    var phase by remember { mutableStateOf("Idle") } // Idle | Preparing | Requesting | Sending | Done/Failed
    var statusText by remember { mutableStateOf<String?>(null) }
    var progressSent by remember { mutableStateOf(0L) }
    var progressTotal by remember { mutableStateOf(0L) }
    var activeDevice by remember { mutableStateOf<LocalSendDevice?>(null) }

    // ---- B1-NET-02 pairing / per-send confirmation sub-views ----
    var pairingDevice by remember { mutableStateOf<LocalSendDevice?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var confirmDevice by remember { mutableStateOf<LocalSendDevice?>(null) }
    // Out-of-band verification input: either a code entered from the receiving
    // device, or an explicit acknowledgement that the displayed fingerprint was
    // compared against the receiver's own identity screen. Pairing refuses to
    // persist unless exactly one of the two is supplied (no silent self-match).
    var pairingCodeInput by remember { mutableStateOf("") }
    var comparedFingerprintChecked by remember { mutableStateOf(false) }
    // Bumped after every successful pair so the device list re-evaluates gates.
    var pairedRevision by remember { mutableIntStateOf(0) }
    val pairingRequest = pairingDevice?.let { LocalSendPairing.startPairing(it) }

    val deviceGates = remember(discoveredDevices, pairedRevision) {
        discoveredDevices.map { it to LocalSendPairing.gate(it, pairedDevices) }
    }

    fun discover() {
        scope.launch {
            isDiscovering = true
            discoveryNote = "Listening on the local network…"
            discoveredDevices = emptyList()
            val devices = sender.discoverDevices(
                discoveryTimeoutMs = 3_000L,
                includeLegacyHttpScan = legacyHttpScanOptIn
            )
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
                // R2-B1D-03 (phase-137): exportBackup is the single disciplined
                // DB-file producer — passing the repository makes it checkpoint
                // the WAL + re-stamp the HMAC + verified-copy the DB snapshot
                // BEFORE building the archive, exactly like the HomeScreen/WebDAV
                // producers (previously this path shipped a WAL-stale archive).
                ImportExportService.exportBackup(
                    context,
                    viewModel.repository.encryptionKey,
                    backupPassword = null,
                    repository = viewModel.repository
                )
            LocalSendPayload.OBSIDIAN_ZIP ->
                ImportExportService.exportObsidianVaultZip(context, "SmoothNotes_Vault", pages, viewModel.repository)
            LocalSendPayload.HTML_ZIP ->
                ImportExportService.exportVaultToHtmlZip(context, "SmoothNotes_Site", pages, viewModel.repository)
        }
    }

    fun startSend(device: LocalSendDevice) {
        when (val gate = LocalSendPairing.gate(device, pairedDevices)) {
            is LocalSendGate.Allowed -> {
                // Paired device → explicit per-send confirmation (Not consent via 200).
                confirmDevice = device
                statusText = null
            }
            is LocalSendGate.Denied -> {
                if (LocalSendPairing.startPairing(device) != null) {
                    pairingDevice = device
                    pairingError = null
                } else {
                    phase = "Failed"
                    statusText = gate.reason
                }
            }
        }
    }

    fun confirmPairing() {
        val device = pairingDevice ?: return
        val request = pairingRequest ?: return
        val entered = pairingCodeInput.trim()
        val ok = when {
            entered.isNotBlank() -> {
                // The user typed a code from the receiving device: it must equal
                // the code derived from the announced fingerprint (constant-time
                // compare). A mismatch here refuses pairing — no silent self-match.
                LocalSendPairing.pair(pairedDevices, request, entered)
            }
            comparedFingerprintChecked -> {
                // No code available (most receivers display only their TLS
                // fingerprint): pairing is authorized by the user's affirmative
                // "fingerprints match" acknowledgement after out-of-band comparison.
                LocalSendPairing.pair(pairedDevices, request, request.code)
            }
            else -> {
                pairingError = "Compare the TLS fingerprint below with the receiving device's " +
                    "identity screen, or enter its verification code, then confirm."
                return
            }
        }
        if (ok) {
            confirmDevice = device
            pairingDevice = null
            pairingError = null
            pairingCodeInput = ""
            comparedFingerprintChecked = false
            pairedRevision++
        } else {
            pairingError = "The verification code does not match this device. Check the fingerprint " +
                "or code on the receiving device and try again."
        }
    }

    fun dismissPairing() {
        pairingDevice = null
        pairingError = null
        pairingCodeInput = ""
        comparedFingerprintChecked = false
    }

    /**
     * Send [file] to [device] through the FileTransfer capability (plugin route).
     * Maps every typed [FileTransferOutcome] and manager result into the same
     * [LocalSendSender.SendResult] the direct path produces, so the UI states
     * (phase / statusText / progress) are identical. Consent is unchanged: the
     * plugin delegates to the same `LocalSendSender` + pairing store.
     */
    suspend fun sendViaPlugin(
        device: LocalSendDevice,
        file: File,
        totalBytes: Long
    ): LocalSendSender.SendResult {
        val kind = when (payloadType) {
            LocalSendPayload.NOTE_HTML -> FileTransferKind.NOTE_HTML
            LocalSendPayload.VAULT_BACKUP -> FileTransferKind.VAULT_BACKUP
            LocalSendPayload.OBSIDIAN_ZIP -> FileTransferKind.OBSIDIAN_ZIP
            LocalSendPayload.HTML_ZIP -> FileTransferKind.HTML_ZIP
        }
        val result = viewModel.sendFileWithPlugin(
            FileTransferRequest(kind, file, device)
        ) { sent, total ->
            if (phase == "Requesting") {
                phase = "Sending"
                statusText = "Sending ${file.name} to ${device.alias}…"
            }
            progressSent = sent
            progressTotal = if (total > 0L) total else totalBytes
        }
        return when (result) {
            is PluginResult.Success -> when (val outcome = result.value) {
                is FileTransferOutcome.Sent -> LocalSendSender.SendResult(true, outcome.description, outcome.bytesSent)
                is FileTransferOutcome.Rejected -> LocalSendSender.SendResult(false, outcome.message, 0L)
                is FileTransferOutcome.Error -> LocalSendSender.SendResult(false, outcome.message, 0L)
            }
            is PluginResult.Failure -> LocalSendSender.SendResult(false, result.message, 0L)
            is PluginResult.Unavailable -> LocalSendSender.SendResult(false, result.message, 0L)
        }
    }

    fun doSend(device: LocalSendDevice) {
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
            // Review-fix (phase-173): the plugin route gets a REAL production
            // caller when opted in; otherwise this is the unchanged direct send.
            val result = if (viewModel.pluginRegistry.isEnabled(PLUGIN_ID_FILE_TRANSFER)) {
                sendViaPlugin(device, file, file.length())
            } else {
                sender.sendFile(device, file) { sent, total ->
                    if (phase == "Requesting") {
                        phase = "Sending"
                        statusText = "Sending ${file.name} to ${device.alias}…"
                    }
                    progressSent = sent
                    progressTotal = total
                }
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

    // ------------------------------------------------------------------
    // Sub-view builders (local @Composable functions)
    // ------------------------------------------------------------------

    @Composable
    fun MainBody() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScrollGuard()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val didPair = pairingDevice == null && confirmDevice == null
            if (didPair) {
                Text(
                    text = "Sends the file directly to another phone/computer on the same Wi-Fi using the " +
                        "LocalSend protocol — no internet, no account.\n\n" +
                        "Security: only devices you pair once (you verify their TLS fingerprint) can receive " +
                        "files, and only over HTTPS. The receiving device must accept on its screen — but " +
                        "that alone is never enough: pairing + your confirmation is what authorizes a send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                ExposedDropdownMenu(
                    expanded = payloadMenuOpen,
                    onDismissRequest = { payloadMenuOpen = false },
                    scrollState = overflowMenuScrollState(),
                    modifier = overflowMenuScrollModifier()
                ) {
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
                    ExposedDropdownMenu(
                        expanded = noteMenuOpen,
                        onDismissRequest = { noteMenuOpen = false },
                        scrollState = overflowMenuScrollState(),
                        modifier = overflowMenuScrollModifier()
                    ) {
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

            // B1-NET-06 (phase-85): the directed /24 HTTP sweep is an EXPLICIT
            // per-search opt-in, off by default — a plain search only ever issues
            // the UDP announce/listen, never 254 HTTP POSTs to the subnet.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = legacyHttpScanOptIn,
                    onCheckedChange = { legacyHttpScanOptIn = it },
                    enabled = !isDiscovering && phase == "Idle"
                )
                Text("Also check every address on this Wi-Fi (slower; helps on networks that block discovery broadcasts)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Device list
            if (deviceGates.isNotEmpty()) {
                Text("Devices", style = MaterialTheme.typography.labelMedium)
                deviceGates.forEach { (device, gate) ->
                    val sendable = gate is LocalSendGate.Allowed
                    val statusNote = when (gate) {
                        is LocalSendGate.Allowed -> "Paired · HTTPS"
                        is LocalSendGate.Denied -> when {
                            !device.protocol.equals("https", ignoreCase = true) ->
                                "No secure (HTTPS) connection — cannot send"
                            device.fingerprint.isNullOrBlank() ->
                                "No TLS certificate — cannot send"
                            else -> "Not paired — tap to verify & pair"
                        }
                    }
                    val isActive = activeDevice == device
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            isActive -> MaterialTheme.colorScheme.secondaryContainer
                            sendable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // All devices are tappable: a tap routes a sendable device to the per-send
                            // confirmation, an unpaired HTTPS device into the pairing sub-view, and an
                            // http/no-fingerprint device to an explicit "cannot send" message via startSend.
                            .clickable(enabled = phase == "Idle") { startSend(device) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (sendable) Icons.Outlined.VerifiedUser else Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = if (sendable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.alias, fontWeight = FontWeight.Medium)
                                Text(
                                    "${device.deviceModel ?: "Unknown device"} · ${device.protocol.uppercase()}" +
                                        if (device.fingerprint != null) " · TLS cert ✓" else " · no TLS cert",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    statusNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sendable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
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
    }

    @Composable
    fun PairingBody(
        device: LocalSendDevice,
        request: LocalSendPairingRequest?,
        error: String?,
        codeInput: String,
        onCodeChange: (String) -> Unit,
        acknowledged: Boolean,
        onAcknowledgeChange: (Boolean) -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Pair with ${device.alias}?",
                fontWeight = FontWeight.Bold
            )
            Text(
                "This is the FIRST send to this device. Only pair with a device you control. " +
                    "On the receiving device, open its LocalSend identity/info screen and check that the " +
                    "TLS fingerprint below is the same. After pairing, files are only ever sent to this " +
                    "exact certificate over HTTPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            request?.let { req ->
                Text(
                    LocalSendPairingCodes.formattedFingerprint(req.fingerprint).ifBlank { "—" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { onCodeChange(it.trim().take(64)) },
                    label = { Text("Verification code from the receiving device") },
                    supportingText = {
                        Text("If the receiving device shows a 6-digit code, enter it here. " +
                            "It is verified against this device (mismatch refuses pairing).")
                    },
                    singleLine = true,
                    isError = codeInput.isNotBlank() && error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = onAcknowledgeChange
                    )
                    Text(
                        "I compared the TLS fingerprint on this screen with the receiving device and they match",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Short code derived from this fingerprint: ${req.code}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    @Composable
    fun ConfirmSendBody(
        device: LocalSendDevice,
        payloadLabel: String
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Send ${payloadLabel.lowercase()} to ${device.alias}?",
                fontWeight = FontWeight.Bold
            )
            Text(
                buildString {
                    append("Address: ").append(device.address).append(':').append(device.port).append('\n')
                    append("Connection: verified TLS, pinned to your paired certificate.\n")
                    append("The receiving device must still accept the transfer on its screen. ")
                    append("Files cannot be recalled after sending.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            val pairingTarget = pairingDevice
            val confirmTarget = confirmDevice
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    pairingTarget != null && phase == "Idle" -> {
                        PairingBody(
                            device = pairingTarget,
                            request = pairingRequest,
                            error = pairingError,
                            codeInput = pairingCodeInput,
                            onCodeChange = { pairingCodeInput = it },
                            acknowledged = comparedFingerprintChecked,
                            onAcknowledgeChange = { comparedFingerprintChecked = it }
                        )
                    }
                    confirmTarget != null && phase == "Idle" -> {
                        ConfirmSendBody(confirmTarget, payloadType.label)
                    }
                    else -> {
                        MainBody()
                    }
                }
            }
        },
        confirmButton = {
            val pairTarget = pairingDevice
            val sendTarget = confirmDevice
            when {
                phase == "Done" -> TextButton(onClick = onDismiss) { Text("Done") }
                phase in listOf("Preparing", "Requesting", "Sending") ->
                    TextButton(onClick = { sender.cancelActiveTransfer() }) { Text("Cancel") }
                phase == "Failed" -> TextButton(onClick = {
                    phase = "Idle"
                    statusText = null
                }) { Text("OK") }
                pairTarget != null && phase == "Idle" ->
                    TextButton(onClick = { confirmPairing() }) { Text("Pair & Send") }
                sendTarget != null && phase == "Idle" ->
                    TextButton(onClick = { doSend(sendTarget) }) { Text("Send") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (phase == "Idle") {
                when {
                    pairingDevice != null ->
                        TextButton(onClick = { dismissPairing() }) { Text("Cancel") }
                    confirmDevice != null ->
                        TextButton(onClick = { confirmDevice = null }) { Text("Back") }
                    else -> TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    )
}