package com.authorss81.noteflow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.TagNode
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel

@Composable
fun TagExplorerView(
    viewModel: NoteflowViewModel,
    onSelectTagFilter: (tagPath: String?, matchingPageIds: Set<String>?) -> Unit,
    activeTagFilter: String?
) {
    var tagHierarchy by remember { mutableStateOf<List<TagNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Phase 164: the vault is scoped to the CURRENTLY selected notebook; keying
    // the LaunchedEffect on (notebookId, notebook tags) re-runs the scoped build
    // on every notebook switch / notebook-tag edit, so notebook A's tags never
    // leak into notebook B's vault. Because the build runs directly in the
    // effect's coroutine (not a remembered scope), a switch mid-build cancels the
    // stale notebook's build before it can overwrite the vault (B2-DOS-11).
    val selectedNotebook by viewModel.selectedNotebook.collectAsState()
    val notebookId = selectedNotebook?.id

    LaunchedEffect(notebookId, selectedNotebook?.tags) {
        isLoading = true
        if (notebookId == null) {
            tagHierarchy = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        // R2-b2b1-UI-01 (phase-134): guarded VM read (armed-empty on a lock
        // race) + auth re-check before assigning decrypted tags into state.
        // Phase 164: scoped aggregation — only this notebook's pages' tags +
        // this notebook's own tag list; no other notebook can contribute.
        // Phase 164 review (finding 5): the captured notebookId (the effect KEY)
        // is passed down, so the read can never race over to the new notebook.
        val hierarchy = viewModel.loadScopedTagHierarchy(notebookId, ImportExportService.getImportsDir(context))
        if (viewModel.authenticated.value) {
            tagHierarchy = hierarchy
        } else {
            // Phase 164 review (finding 6): a lock that raced the guarded read must
            // never leave the previous notebook's (decrypted) tag list in state.
            tagHierarchy = emptyList()
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Hierarchical Tag Vault", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (activeTagFilter != null) {
                TextButton(onClick = { onSelectTagFilter(null, null) }) {
                    Text("Clear Filter")
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (tagHierarchy.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TactileEmptyState(
                    decision = EmptyStateResolver.decide(EmptyStateKind.TAG_VAULT)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tagHierarchy, key = { it.fullTagPath }) { rootTag ->
                    TagTreeNodeItem(
                        node = rootTag,
                        level = 0,
                        activeTagFilter = activeTagFilter,
                        onSelectTag = onSelectTagFilter
                    )
                }
            }
        }
    }
}

@Composable
private fun TagTreeNodeItem(
    node: TagNode,
    level: Int,
    activeTagFilter: String?,
    onSelectTag: (tagPath: String, matchingPageIds: Set<String>) -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }
    val isSelected = activeTagFilter == node.fullTagPath

    val indentPadding = (level * 16).dp

    Column(modifier = Modifier.fillMaxWidth().padding(start = indentPadding)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onSelectTag(node.fullTagPath, node.matchingPageIds)
                },
            shape = RoundedCornerShape(8.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isSelected) 4.dp else 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (node.children.isNotEmpty()) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                            contentDescription = "Expand"
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(24.dp))
                }

                Icon(
                    Icons.Outlined.Tag,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Badge(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text("${node.noteCount}")
                }
            }
        }

        if (node.children.isNotEmpty()) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = com.authorss81.noteflow.theme.MotionSystem.enter(fadeIn()),
                exit = com.authorss81.noteflow.theme.MotionSystem.exit(fadeOut())
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (child in node.children) {
                        TagTreeNodeItem(
                            node = child,
                            level = level + 1,
                            activeTagFilter = activeTagFilter,
                            onSelectTag = onSelectTag
                        )
                    }
                }
            }
        }
    }
}
