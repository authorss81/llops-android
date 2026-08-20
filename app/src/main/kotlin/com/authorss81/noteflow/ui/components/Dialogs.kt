package com.authorss81.noteflow.ui.components

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.BiometricAuthHelper
import com.authorss81.noteflow.services.BiometricKeyBindingPolicy
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.PasswordStrengthPolicy
import com.authorss81.noteflow.services.UpdateInfo
import com.authorss81.noteflow.services.UpdateService
import com.authorss81.noteflow.services.UpdateSourceTrust
import com.authorss81.noteflow.services.UpdateTrustPolicy
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppUpdateDialog(
    onDismiss: () -> Unit,
    onSnackbar: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val currentVersionName = remember { UpdateService.getCurrentVersionName(context) }
    val currentVersionCode = remember { UpdateService.getCurrentVersionCode(context) }

    var isChecking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showUntrustedConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Phase 190: scan app-private storage ONCE on open so a staged APK (from the
    // picker, or an APK shared into the app via MainActivity) is offered
    // immediately. Only runs when the user has not already picked a file this
    // session — a picker refusal's honest copy is never clobbered by the scan.
    LaunchedEffect(Unit) {
        if (updateInfo == null) {
            isChecking = true
            val found = withContext(Dispatchers.IO) {
                UpdateService.checkForDownloadedUpdates(context)
            }
            updateInfo = found
            isChecking = false
            statusMessage = if (found.hasUpdate) {
                "Found a local APK in the app's private storage."
            } else {
                "No newer APK found in the app's private storage (public Downloads are never scanned)."
            }
        }
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    // Phase 190: STREAM the selected APK into app-private cacheDir —
                    // never the old in-heap `readUriBytes` + `writeBytes` (a
                    // 100+ MB APK was 2-3x in heap at once and OOM'd low-RAM
                    // devices right as the user tried to update). The staged file
                    // is a direct cacheDir child, so "Scan App Storage" finds it.
                    val destFile = withContext(Dispatchers.IO) {
                        ImportExportService.stageApkUriToFile(context, uri)
                    }
                    if (destFile != null) {
                        val inspected = withContext(Dispatchers.IO) {
                            UpdateService.inspectApkFile(context, destFile)
                        }
                        if (inspected != null) {
                            updateInfo = inspected
                            // Phase 190: a refusal (different package / signature)
                            // surfaces its HONEST releaseNotes copy instead of the
                            // misleading "equal to or older" line.
                            statusMessage = if (inspected.hasUpdate) {
                                "Selected local APK is newer than the installed app (${inspected.newVersionName})."
                            } else {
                                inspected.releaseNotes
                                    ?: "Selected APK version (${inspected.newVersionName}) is equal to or older than current version ($currentVersionName)."
                            }
                        } else {
                            statusMessage = "Could not parse valid Android APK from selected file."
                        }
                    } else {
                        statusMessage = "Failed to read APK file."
                    }
                } catch (e: Exception) {
                    statusMessage = "Could not read the selected APK file."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("App Version & Update")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("InkFlow", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Current Version: v$currentVersionName (Build $currentVersionCode)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (updateInfo != null && updateInfo!!.hasUpdate) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "🚀 New Version Available!",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Version: v${updateInfo!!.newVersionName} (Build ${updateInfo!!.newVersionCode})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            updateInfo!!.releaseNotes?.let { notes ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                statusMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateInfo?.hasUpdate == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Text("Check for Updates", style = MaterialTheme.typography.labelLarge)

                Button(
                    onClick = {
                        isChecking = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                UpdateService.checkForDownloadedUpdates(context)
                            }
                            updateInfo = found
                            isChecking = false
                            statusMessage = if (found.hasUpdate) {
                                "Found a local APK in the app's private storage."
                            } else {
                                "No newer APK found in the app's private storage (public Downloads are never scanned)."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isChecking) "Checking..." else "Scan App Storage for APK")
                }

                OutlinedButton(
                    onClick = {
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Local APK File...")
                }
            }
        },
        confirmButton = {
            if (updateInfo != null && updateInfo!!.hasUpdate && updateInfo!!.apkFile != null) {
                Button(
                    onClick = {
                        // B1-PLAT-7: the strong confirmation gate applies ONLY to
                        // UNTRUSTED files. An OFFICIAL (channel-verified) file installs
                        // directly — nothing in this app produces one today.
                        if (updateInfo!!.trust == UpdateSourceTrust.UNTRUSTED_LOCAL) {
                            showUntrustedConfirm = true
                        } else {
                            val success = UpdateService.installApk(
                                context,
                                updateInfo!!.apkFile!!,
                                updateInfo!!.trust,
                                userConfirmedUntrusted = false
                            )
                            if (!success) {
                                onSnackbar("Could not launch package installer.", false)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Outlined.GetApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Install Update")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (updateInfo != null && updateInfo!!.hasUpdate) {
                TextButton(onClick = onDismiss) {
                    Text("Later")
                }
            }
        }
    )

    if (showUntrustedConfirm && updateInfo != null && updateInfo!!.apkFile != null) {
        val confirmed = updateInfo!!
        AlertDialog(
            onDismissRequest = { showUntrustedConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(UpdateTrustPolicy.confirmationTitle())
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        UpdateTrustPolicy.confirmationMessage(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "File: ${confirmed.apkFile!!.name} — v${confirmed.newVersionName} (${confirmed.newVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUntrustedConfirm = false
                        val success = UpdateService.installApk(
                            context,
                            confirmed.apkFile!!,
                            confirmed.trust,
                            // the confirmation gate is only reachable for UNTRUSTED files,
                            // so by showing this dialog the user has already confirmed.
                            userConfirmedUntrusted = true
                        )
                        if (!success) {
                            onSnackbar("Could not launch package installer.", false)
                        }
                    }
                ) {
                    Text("Install anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUntrustedConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PromptNameDialog(
    title: String,
    initialValue: String = "",
    submitLabel: String = "Create",
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSubmit(text.trim())
                    }
                }
            ) {
                Text(submitLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SecuritySettingsDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val hasPass by viewModel.hasMasterPassword.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val biometricRefusalMessage by viewModel.biometricRefusalMessage.collectAsState()
    val autoLockTimeoutSeconds by viewModel.autoLockTimeoutSeconds.collectAsState()
    var showPasswordInput by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security & Encryption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasPass) {
                    Text(
                        "Encrypt your notes with a master password — encryption runs locally on your device. Note titles, text and stroke text are encrypted; imported files, journal and voice notes are stored as plain files.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (showPasswordInput) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Master Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordConfirm,
                            onValueChange = { passwordConfirm = it },
                            label = { Text("Confirm Master Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Button(
                            onClick = { showPasswordInput = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enable Master Password")
                        }
                    }
                } else {
                    Text(
                        "Encryption at Rest is Active. Note titles, text and stroke text are encrypted with your master password. Imported files, journal and voice notes are stored as plain files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    var showPasswordPrompt by remember { mutableStateOf(false) }
                    var tempChecked by remember { mutableStateOf(false) }
                    var verifyPassword by remember { mutableStateOf("") }
                    var verifyError by remember { mutableStateOf<String?>(null) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Biometric Unlock")
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { checked ->
                                tempChecked = checked
                                verifyPassword = ""
                                verifyError = null
                                // B1-CRYPTO-07 (phase-65): on API 26-29 the platform
                                // cannot create a key bound to a strong biometric, so
                                // enabling is refused up-front (no pointless password
                                // prompt) with a clear non-alarming message.
                                if (checked && !BiometricAuthHelper.canCreateStrongBiometricBoundKey()) {
                                    verifyError = BiometricKeyBindingPolicy.refuseEnableMessage(Build.VERSION.SDK_INT)
                                } else {
                                    showPasswordPrompt = true
                                }
                            }
                        )
                    }

                    if (showPasswordPrompt) {
                        AlertDialog(
                            onDismissRequest = { showPasswordPrompt = false },
                            title = { Text(if (tempChecked) "Enable Biometric Unlock" else "Disable Biometric Unlock") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Please enter your Master Password to confirm this action.")
                                    OutlinedTextField(
                                        value = verifyPassword,
                                        onValueChange = { verifyPassword = it; verifyError = null },
                                        label = { Text("Master Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        isError = verifyError != null,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    verifyError?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            if (viewModel.setBiometricEnabled(tempChecked, verifyPassword)) {
                                                showPasswordPrompt = false
                                            } else {
                                                verifyError =
                                                    biometricRefusalMessage ?: "Incorrect Master Password"
                                            }
                                        }
                                    }
                                ) {
                                    Text("Confirm")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPasswordPrompt = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    var showChangePasswordDialog by remember { mutableStateOf(false) }

                    // 22.1: auto-lock after foreground inactivity.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Lock After Inactivity")
                        val timeoutOptions = listOf(0 to "Off", 60 to "1 min", 300 to "5 min", 900 to "15 min")
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(timeoutOptions.first { it.first == autoLockTimeoutSeconds }.second)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                scrollState = overflowMenuScrollState(),
                                modifier = overflowMenuScrollModifier()
                            ) {
                                timeoutOptions.forEach { (seconds, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.setAutoLockTimeoutSeconds(seconds)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showChangePasswordDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Master Password")
                    }

                    if (showChangePasswordDialog) {
                        var currentPass by remember { mutableStateOf("") }
                        var newPass by remember { mutableStateOf("") }
                        var newPassConfirm by remember { mutableStateOf("") }
                        var changeError by remember { mutableStateOf<String?>(null) }

                        AlertDialog(
                            onDismissRequest = { showChangePasswordDialog = false },
                            title = { Text("Rotate Master Key") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Re-encrypt DEK key container with a new master password.")
                                    OutlinedTextField(
                                        value = currentPass,
                                        onValueChange = { currentPass = it; changeError = null },
                                        label = { Text("Current Master Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = newPass,
                                        onValueChange = { newPass = it; changeError = null },
                                        label = { Text("New Master Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = newPassConfirm,
                                        onValueChange = { newPassConfirm = it; changeError = null },
                                        label = { Text("Confirm New Password") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    changeError?.let {
                                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        // B2-CRYPTO-07 (phase-113) + B1-CRYPTO-04
                                        // (phase-63) + B1-PLAT-8 (phase-90): the
                                        // NEW password must clear the strength
                                        // policy — ≥ 10 NFKC-normalized
                                        // graphemes, no sequential/keyboard/
                                        // common-word/prefix-suffix patterns,
                                        // class diversity for short passwords.
                                        // The verdict carries the exact
                                        // non-alarming reason. The old password
                                        // is only verified by the ViewModel,
                                        // never strength-gated, so a
                                        // pre-existing weaker vault keeps
                                        // rotating.
                                        val verdict = PasswordStrengthPolicy.evaluate(newPass)
                                        if (!verdict.accepted) {
                                            changeError = verdict.message
                                        } else if (newPass != newPassConfirm) {
                                            changeError = "New passwords do not match"
                                        } else {
                                            scope.launch {
                                                if (viewModel.changeMasterPassword(currentPass, newPass)) {
                                                    showChangePasswordDialog = false
                                                } else {
                                                    changeError = "Incorrect current password"
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("Update Password")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showChangePasswordDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.lock()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lock Vault Now")
                    }
                }
            }
        },
        confirmButton = {
            if (!hasPass && showPasswordInput) {
                Button(
                    onClick = {
                        // B2-CRYPTO-07 (phase-113) + B1-CRYPTO-04 (phase-63)
                        // + B1-PLAT-8 (phase-90): a NEW master password must
                        // clear the strength policy (≥ 10 NFKC-normalized
                        // graphemes, no sequential/keyboard/common-word/
                        // prefix-suffix patterns, class diversity for short
                        // passwords) so an offline attacker with a copied vault
                        // cannot brute-force the wrapped DEK — offline cracking
                        // is only mitigated by password entropy, never by the
                        // on-device lockout. The verdict message is the exact
                        // non-alarming reason.
                        val verdict = PasswordStrengthPolicy.evaluate(password)
                        if (!verdict.accepted) {
                            errorMessage = verdict.message
                        } else if (password != passwordConfirm) {
                            errorMessage = "Passwords do not match"
                        } else {
                            scope.launch {
                                if (viewModel.setMasterPassword(password)) {
                                    onDismiss()
                                } else {
                                    errorMessage = "Failed to set master password"
                                }
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (!hasPass && showPasswordInput) {
                TextButton(onClick = { showPasswordInput = false }) {
                    Text("Back")
                }
            }
        }
    )
}
