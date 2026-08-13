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
import com.authorss81.noteflow.services.WebDavSyncService
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSyncDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var serverUrl by remember { mutableStateOf("https://cloud.example.com/remote.php/dav/files/user/") }
    var username by remember { mutableStateOf("") }
    var passwordOrToken by remember { mutableStateOf("") }
    var remoteFolderName by remember { mutableStateOf("Noteflow_Vault") }

    var syncStatus by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val syncService = remember { WebDavSyncService(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = scheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("WebDAV / Nextcloud E2EE Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Sync your zero-knowledge encrypted vault directly to your personal WebDAV or Nextcloud server. Your data stays 100% under your control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server WebDAV URL") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

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

                syncStatus?.let { msg ->
                    Surface(
                        color = scheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                            color = scheme.onPrimaryContainer
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
                        syncStatus = "Connecting to Nextcloud / WebDAV server..."
                        scope.launch {
                            val cfg = WebDavSyncService.SyncConfig(serverUrl, username, passwordOrToken, remoteFolderName)
                            // Export current vault to temp backup zip
                            val backupZip = File(context.cacheDir, "webdav_sync_export.zip")
                            viewModel.exportEncryptedBackupToZip(backupZip) { success ->
                                if (success) {
                                    scope.launch {
                                        val res = syncService.uploadEncryptedVault(cfg, backupZip)
                                        isLoading = false
                                        syncStatus = res.message
                                    }
                                } else {
                                    isLoading = false
                                    syncStatus = "Failed to package local vault for WebDAV upload."
                                }
                            }
                        }
                    },
                    enabled = !isLoading && username.isNotBlank() && passwordOrToken.isNotBlank()
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload Vault")
                }

                OutlinedButton(
                    onClick = {
                        isLoading = true
                        syncStatus = "Fetching remote vault from Nextcloud / WebDAV..."
                        scope.launch {
                            val cfg = WebDavSyncService.SyncConfig(serverUrl, username, passwordOrToken, remoteFolderName)
                            val targetZip = File(context.cacheDir, "webdav_download_import.zip")
                            val res = syncService.downloadLatestEncryptedVault(cfg, targetZip)
                            if (res.success) {
                                viewModel.restoreEncryptedBackupFromZip(targetZip) { restored ->
                                    isLoading = false
                                    syncStatus = if (restored) "Vault successfully restored from WebDAV server!" else "Failed to restore downloaded vault."
                                }
                            } else {
                                isLoading = false
                                syncStatus = res.message
                            }
                        }
                    },
                    enabled = !isLoading && username.isNotBlank() && passwordOrToken.isNotBlank()
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Vault")
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
