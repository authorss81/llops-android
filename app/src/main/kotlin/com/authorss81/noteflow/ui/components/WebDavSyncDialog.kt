package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.authorss81.noteflow.services.BiometricAuthHelper
import com.authorss81.noteflow.services.WebDavCredentialLoadResult
import com.authorss81.noteflow.services.WebDavCredentialStore
import com.authorss81.noteflow.services.WebDavFailurePolicy
import com.authorss81.noteflow.services.WebDavSyncService
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSyncDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit,
    onRestoreSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    val credentialStore = remember { WebDavCredentialStore(context) }

    var serverUrl by remember { mutableStateOf("https://cloud.example.com/remote.php/dav/files/user/") }
    var username by remember { mutableStateOf("") }
    var passwordOrToken by remember { mutableStateOf("") }
    var remoteFolderName by remember { mutableStateOf("Noteflow_Vault") }
    var rememberMe by remember { mutableStateOf(false) }
    var allowInsecureHttp by remember { mutableStateOf(false) }

    var syncStatus by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    // R2-B1C-02 (phase-145): a non-alarming notice when the remembered
    // credentials exist but the auth-bound keystore key's biometric window has
    // expired. They are PRESERVED (never silently deleted) and a biometric
    // re-auth decrypts them again.
    var authRequiredNotice by remember { mutableStateOf<String?>(null) }

    // B1-NET-08: "remembered credentials" opt in to a biometric auth gate when
    // the user has enabled global biometric unlock (and a strong biometric is
    // actually enrolled). A plain (no recent biometric) thief or in-process
    // plugin can no longer decrypt the stored WebDAV password in that mode.
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()

    // R2-b2b1-UI-03 (phase-135): disable the restore trigger while a restore is
    // in flight on ANY path — the local/WebDAV/recovery paths share one gate.
    val isRestoring by viewModel.isRestoring.collectAsState()

    val syncService = remember { WebDavSyncService(context) }

    // R2-B1C-02 (phase-145): apply decrypted remembered credentials to the form.
    fun applyRememberedCredentials(creds: WebDavCredentialStore.StoredCredentials?) {
        if (creds == null) return
        serverUrl = creds.serverUrl
        username = creds.username
        passwordOrToken = creds.passwordOrToken
        rememberMe = true
        authRequiredNotice = null
    }

    // R2-B1C-02 (phase-145): BiometricPrompt.CryptoObject re-auth for a stored
    // auth-bound blob whose window expired — the SecurityService/DEK pattern
    // (getDecryptionCipher → CryptoObject → decryptWithCipher) applied to the
    // WebDAV credential key. When the window was already open the prompt binds
    // the decrypt to the biometric result; when closed, the plain prompt's
    // success refreshes the keystore window and the load is re-run.
    fun unlockRememberedWithBiometrics(activity: FragmentActivity?) {
        if (activity == null) return
        val cryptoObject = credentialStore.prepareReauthCipher()
            ?.let { BiometricPrompt.CryptoObject(it) }
        BiometricAuthHelper.promptBiometricAuth(
            activity = activity,
            title = "Unlock remembered WebDAV credentials",
            subtitle = "Use your fingerprint or face to decrypt the saved password",
            cryptoObject = cryptoObject,
            onSuccess = { result ->
                val creds = if (cryptoObject != null) {
                    val cipher = result.cryptoObject?.cipher
                    if (cipher != null) credentialStore.decryptWithReauthCipher(cipher) else null
                } else {
                    // The window had closed; the plain prompt refreshed it.
                    (credentialStore.loadDetailed() as? WebDavCredentialLoadResult.Credentials)?.value
                }
                if (creds != null) {
                    applyRememberedCredentials(creds)
                } else {
                    authRequiredNotice =
                        "Biometric unlock did not recover your saved credentials. " +
                            "Re-enter the password below (your stored copy is still there)."
                }
            },
            onError = {
                authRequiredNotice =
                    "Biometric unlock cancelled — your remembered credentials are still " +
                        "saved and can be unlocked again later."
            }
        )
    }

    // Pre-fill from the encrypted credential store (AndroidKeyStore-backed),
    // never from plaintext disk.
    LaunchedEffect(Unit) {
        when (val result = credentialStore.loadDetailed()) {
            is WebDavCredentialLoadResult.Credentials ->
                applyRememberedCredentials(result.value)
            WebDavCredentialLoadResult.AuthRequired -> {
                // R2-B1C-02: the auth window expired — the stored credentials were
                // NOT deleted. Keep remember-me checked and offer the biometric
                // re-auth (or let the user re-enter; the stored copy survives).
                rememberMe = true
                authRequiredNotice =
                    "Your remembered credentials are locked — the biometric unlock " +
                        "window expired. Unlock them below, or re-enter the password " +
                        "(the saved copy is preserved)."
            }
            WebDavCredentialLoadResult.None, WebDavCredentialLoadResult.Corrupt -> Unit
        }
    }

    val usesInsecureHttp = remember(serverUrl) {
        serverUrl.trim().lowercase().startsWith("http://")
    }

    fun rememberCredentialsIfRequested() {
        if (rememberMe) {
            val authBound = biometricEnabled && BiometricAuthHelper.isBiometricAvailable(context)
            val saved = credentialStore.save(serverUrl, username, passwordOrToken, authBound)
            if (!saved) {
                // B1-NET-08: never silently keep the previous credentials while
                // the UI believes the new ones were persisted.
                statusIsError = false
                syncStatus = "Sync succeeded, but your credentials could not be saved " +
                    "securely (keystore/biometric unavailable). Re-enter them next time."
            }
        } else {
            credentialStore.clear()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = scheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("WebDAV / Nextcloud Backup Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Backs up your vault as encrypted archive files to YOUR OWN WebDAV or Nextcloud server. " +
                        "Backups are encrypted and sent over HTTPS. Your server operator can read the backup files, " +
                        "but not your note content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server WebDAV URL (https:// required)") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (usesInsecureHttp) {
                    Surface(
                        color = scheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Checkbox(
                                checked = allowInsecureHttp,
                                onCheckedChange = { allowInsecureHttp = it }
                            )
                            Column {
                                Text(
                                    "HTTP is insecure — your backups could be read in transit.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onErrorContainer
                                )
                                Text(
                                    "Allow HTTP only for a LOCAL-NETWORK server (e.g. localhost, 192.168.x.x).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = passwordOrToken,
                        onValueChange = { passwordOrToken = it },
                        label = { Text("Password / App Token") },
                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text(
                        "Remember credentials (stored encrypted)", style = MaterialTheme.typography.bodySmall
                    )
                }

                // R2-B1C-02 (phase-145): auth-bound remembered credentials whose
                // biometric window expired are PRESERVED and re-unlockable. Show a
                // non-alarming notice + a BiometricPrompt.CryptoObject re-auth.
                authRequiredNotice?.let { notice ->
                    Surface(
                        color = scheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    val activity = context as? FragmentActivity
                                    if (activity != null &&
                                        BiometricAuthHelper.isBiometricAvailable(context)
                                    ) {
                                        unlockRememberedWithBiometrics(activity)
                                    } else {
                                        authRequiredNotice =
                                            "Biometrics are unavailable right now — re-enter the " +
                                                "password below (your saved copy is preserved)."
                                    }
                                }
                            ) {
                                Text("Unlock with biometrics")
                            }
                        }
                    }
                }

                syncStatus?.let { msg ->
                    Surface(
                        color = if (statusIsError) scheme.errorContainer else scheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                            color = if (statusIsError) scheme.onErrorContainer else scheme.onPrimaryContainer
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }

                // Phase 166: the primary actions live in the BODY as full-width
                // buttons instead of two side-by-side confirm buttons — the AlertDialog
                // buttons row is a compact right-aligned row, and "Upload Backup" +
                // "Download & Restore" together overflow the dialog on 360dp devices.
                // A stack of full-width buttons can never clip on any supported width.
                Button(
                    onClick = {
                        isLoading = true
                        statusIsError = false
                        syncStatus = "Packaging encrypted backup and connecting to WebDAV server..."
                        scope.launch {
                            val cfg = WebDavSyncService.SyncConfig(
                                serverUrl, username, passwordOrToken, remoteFolderName, allowInsecureHttp
                            )
                            val backupZip = File(context.cacheDir, "webdav_sync_export.nfb")
                            viewModel.exportEncryptedBackupToZip(backupZip) { success ->
                                if (success) {
                                    scope.launch {
                                        val res = syncService.uploadEncryptedVault(cfg, backupZip)
                                        isLoading = false
                                        syncStatus = WebDavFailurePolicy.scrubForDisplay(res.message)
                                        statusIsError = !res.success
                                        if (res.success) rememberCredentialsIfRequested()
                                    }
                                } else {
                                    isLoading = false
                                    statusIsError = true
                                    syncStatus = "Failed to package your encrypted backup for WebDAV upload."
                                }
                            }
                        }
                    },
                    enabled = !isLoading && username.isNotBlank() && passwordOrToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload Backup")
                }

                OutlinedButton(
                    onClick = {
                        isLoading = true
                        statusIsError = false
                        syncStatus = "Downloading encrypted backup from WebDAV server..."
                        scope.launch {
                            val cfg = WebDavSyncService.SyncConfig(
                                serverUrl, username, passwordOrToken, remoteFolderName, allowInsecureHttp
                            )
                            val targetZip = File(context.cacheDir, "webdav_download_import.nfb")
                            val res = syncService.downloadLatestEncryptedVault(cfg, targetZip)
                            if (res.success) {
                                rememberCredentialsIfRequested()
                                viewModel.restoreEncryptedBackupFromZip(targetZip) { restored, failureMessage ->
                                    isLoading = false
                                    if (restored) {
                                        statusIsError = false
                                        syncStatus = "Vault restored from your WebDAV backup. The app will restart."
                                        onRestoreSuccess()
                                    } else {
                                        statusIsError = true
                                        // B2-LOG-05 (phase-94): the rendered status
                                        // surface is scrubbed as defense-in-depth —
                                        // no URL-derived text can reach the dialog.
                                        syncStatus = WebDavFailurePolicy.scrubForDisplay(
                                            failureMessage
                                                ?: "Failed to restore the downloaded backup — your vault on disk is unchanged. Restart the app to reopen your vault."
                                        )
                                    }
                                }
                            } else {
                                isLoading = false
                                syncStatus = WebDavFailurePolicy.scrubForDisplay(res.message)
                                statusIsError = !res.success
                            }
                        }
                    },
                    enabled = !isLoading && !isRestoring && username.isNotBlank() && passwordOrToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download & Restore")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}