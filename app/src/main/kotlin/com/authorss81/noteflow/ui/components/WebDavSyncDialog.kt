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
import com.authorss81.noteflow.services.WebDavCredentialStore
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

    val syncService = remember { WebDavSyncService(context) }

    // Pre-fill from the encrypted credential store (AndroidKeyStore-backed),
    // never from plaintext disk.
    LaunchedEffect(Unit) {
        credentialStore.load()?.let { creds ->
            serverUrl = creds.serverUrl
            username = creds.username
            passwordOrToken = creds.passwordOrToken
            rememberMe = true
        }
    }

    val usesInsecureHttp = remember(serverUrl) {
        serverUrl.trim().lowercase().startsWith("http://")
    }

    fun rememberCredentialsIfRequested() {
        if (rememberMe) {
            credentialStore.save(serverUrl, username, passwordOrToken)
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
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        syncStatus = res.message
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
                    enabled = !isLoading && username.isNotBlank() && passwordOrToken.isNotBlank()
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
                                viewModel.restoreEncryptedBackupFromZip(targetZip) { restored ->
                                    isLoading = false
                                    if (restored) {
                                        statusIsError = false
                                        syncStatus = "Vault restored from your WebDAV backup. The app will restart."
                                        onRestoreSuccess()
                                    } else {
                                        statusIsError = true
                                        syncStatus = "Failed to restore the downloaded backup — your vault on disk is unchanged. Restart the app to reopen your vault."
                                    }
                                }
                            } else {
                                isLoading = false
                                syncStatus = res.message
                                statusIsError = !res.success
                            }
                        }
                    },
                    enabled = !isLoading && username.isNotBlank() && passwordOrToken.isNotBlank()
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download & Restore")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}