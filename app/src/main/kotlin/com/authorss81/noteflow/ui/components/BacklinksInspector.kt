package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.BacklinkMatch
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklinksInspectorBottomSheet(
    activePage: NotePageEntity,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var explicitLinks by remember { mutableStateOf<List<BacklinkMatch>>(emptyList()) }
    var unlinkedMentions by remember { mutableStateOf<List<BacklinkMatch>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val cleanTitle = remember(activePage.title) {
        activePage.title.replace(".md", "").replace(".txt", "").trim()
    }

    fun refreshBacklinks(forceRefresh: Boolean = false) {
        scope.launch {
            isLoading = true
            val allPages = viewModel.repository.getAllActivePages()
            // B2-DOS-11: findBacklinks caches per unlock epoch + caps the scanned
            // set; the rememberCoroutineScope teardown cancels the build when the
            // bottom sheet closes. forceRefresh=true bypasses the cache after an
            // in-place file edit (convert-to-[[WikiLink]]).
            val (linked, unlinked) = WikiLinkParser.findBacklinks(activePage, allPages, forceRefresh)
            explicitLinks = linked
            unlinkedMentions = unlinked
            isLoading = false
        }
    }

    LaunchedEffect(activePage) {
        refreshBacklinks()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Backlinks & Connections",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Text(
                text = "Connections pointing to \"$cleanTitle\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Linked References
                    item {
                        Text(
                            text = "Linked References (${explicitLinks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (explicitLinks.isEmpty()) {
                        item {
                            Text(
                                text = "No explicit [[WikiLinks]] point to this note yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(explicitLinks, key = { "explicit_${it.page.id}" }) { match ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onOpenPage(match.page)
                                    },
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Link,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = match.page.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = match.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Unlinked Mentions
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Unlinked Mentions (${unlinkedMentions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    if (unlinkedMentions.isEmpty()) {
                        item {
                            Text(
                                text = "No unlinked text mentions found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(unlinkedMentions, key = { "unlinked_${it.page.id}" }) { match ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        onOpenPage(match.page)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Note,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = match.page.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = match.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            // B1-DB-4 (phase-44): the body is edited in the
                                            // field-encrypted extractedText column, never by rewriting a
                                            // plaintext .md/.txt file. The resolved body may coalesce a
                                            // legacy plaintext source file if one still exists; the save
                                            // writes the encrypted column and deletes that file.
                                            val body = com.authorss81.noteflow.services.NoteBodyVaultPolicy.resolveBodyForDisplay(
                                                match.page.extractedText, match.page.sourceFilePath, match.page.sourceFileType
                                            )
                                            val newText = body.replace(cleanTitle, "[[$cleanTitle]]")
                                            if (newText != body) {
                                                viewModel.saveMarkdownNoteBody(match.page, newText)
                                                viewModel.showSnackbar("Converted to [[WikiLink]]!")
                                                // The repository write bumps the epoch, dropping the stale cached
                                                // full-text for that page before forcing a fresh scan.
                                                WikiLinkParser.invalidateTextCache(match.page.id)
                                                refreshBacklinks(forceRefresh = true)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.AddLink,
                                            contentDescription = "Convert to [[WikiLink]]",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
