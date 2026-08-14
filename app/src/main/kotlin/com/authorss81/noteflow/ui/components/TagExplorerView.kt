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
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.TagNode
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch

@Composable
fun TagExplorerView(
    viewModel: NoteflowViewModel,
    onSelectTagFilter: (tagPath: String?, matchingPageIds: Set<String>?) -> Unit,
    activeTagFilter: String?
) {
    var tagHierarchy by remember { mutableStateOf<List<TagNode>>(emptyList()) }
    var allActivePages by remember { mutableStateOf<List<NotePageEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            val pages = viewModel.repository.getAllActivePages()
            allActivePages = pages
            // B2-DOS-11: cached per unlock epoch + capped scan set; the LaunchEffect
            // teardown cancels the build when the panel closes.
            tagHierarchy = WikiLinkParser.buildTagHierarchy(pages)
            isLoading = false
        }
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
                Text(
                    "No #tags found in your notes.\nAdd #tag or #category/subtag in note text!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
