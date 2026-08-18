package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.NoteVersionEntity
import com.authorss81.noteflow.services.NoteVersionRetentionPolicy
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistoryBottomSheet(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onRestoreVersion: (NoteVersionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // R2-b2b4-DOS-01 (phase-149): the sheet only ever holds the VISIBLE window.
    // [loadedVersions] starts with the first bounded batch and extends lazily as
    // the list scrolls toward the end — never a whole oversized history in heap.
    val loadedVersions = remember { mutableStateListOf<NoteVersionEntity>() }
    var selectedVersion by remember { mutableStateOf<NoteVersionEntity?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(page.id) {
        loadedVersions.clear()
        selectedVersion = null
        endReached = false
        isLoading = true
        // R2-b2b1-UI-01 (phase-134): getNoteVersions is guard-armed in the VM
        // (empty history + notice on a lock race) and only applied while the
        // auth gate is still up.
        val loaded = viewModel.getNoteVersions(page.id)
        if (viewModel.authenticated.value) {
            loadedVersions.addAll(loaded)
            selectedVersion = loadedVersions.firstOrNull()
            endReached = loaded.size < NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE
        }
        isLoading = false
    }

    // R2-b2b4-DOS-01 (phase-149): near-end sentinel — the next bounded window is
    // fetched only when the user actually scrolls to it, so only the visible
    // rows are decrypted/materialized at any moment.
    LaunchedEffect(listState, loadedVersions.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val threshold = (loadedVersions.size - 5).coerceAtLeast(0)
        if (!isLoading && !endReached && lastVisible >= threshold) {
            isLoading = true
            val start = loadedVersions.size
            val more = viewModel.getNoteVersionsPaged(
                page.id,
                NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE,
                start
            )
            if (viewModel.authenticated.value) {
                loadedVersions.addAll(more)
                endReached = more.size < NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE
            }
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = scheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Version History (Revision Snapshots)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            if (isLoading && loadedVersions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (loadedVersions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No prior revision snapshots recorded yet.\nSnapshots are auto-created when saving notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left list: Version snapshots
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(loadedVersions) { version ->
                            val isSelected = version.id == selectedVersion?.id
                            val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(version.timestampMs))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedVersion = version },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) scheme.primaryContainer else scheme.surfaceVariant,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary) else null
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface
                                    )
                                    Text(
                                        text = version.versionNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Right pane: Selected snapshot content preview
                    Surface(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            val activeVer = selectedVersion
                            if (activeVer != null) {
                                Text(
                                    text = activeVer.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = activeVer.extractedText?.ifBlank { "[Empty text content]" } ?: "[Empty text content]",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    color = scheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onRestoreVersion(activeVer)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restore Version")
                                }
                            } else {
                                Text("Select a version snapshot to preview", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
