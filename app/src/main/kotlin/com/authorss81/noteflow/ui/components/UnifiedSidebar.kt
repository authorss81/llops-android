package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.NotebookEntity
import com.authorss81.noteflow.data.model.SectionEntity

@Composable
fun UnifiedSidebar(
    notebooks: List<NotebookEntity>,
    allSections: List<SectionEntity>,
    allActivePages: List<NotePageEntity>,
    selectedNotebook: NotebookEntity?,
    selectedSection: SectionEntity?,
    onSelectNotebook: (NotebookEntity) -> Unit,
    onSelectSection: (SectionEntity) -> Unit,
    onSelectPage: (NotePageEntity) -> Unit,
    onAddNotebook: () -> Unit,
    onAddSection: (NotebookEntity) -> Unit,
    onAddPage: (SectionEntity) -> Unit,
    onRenameNotebook: (NotebookEntity) -> Unit,
    onDeleteNotebook: (NotebookEntity) -> Unit,
    onRenameSection: (SectionEntity) -> Unit,
    onDeleteSection: (SectionEntity) -> Unit,
    onRenamePage: (NotePageEntity) -> Unit,
    onDeletePage: (NotePageEntity) -> Unit,
    onTogglePinPage: (NotePageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep track of which notebooks and sections are expanded
    val expandedNotebooks = remember { mutableStateMapOf<String, Boolean>() }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    // Auto-expand current notebook and section on first load or when changed
    LaunchedEffect(selectedNotebook) {
        selectedNotebook?.let {
            if (!expandedNotebooks.containsKey(it.id)) {
                expandedNotebooks[it.id] = true
            }
        }
    }
    LaunchedEffect(selectedSection) {
        selectedSection?.let {
            if (!expandedSections.containsKey(it.id)) {
                expandedSections[it.id] = true
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxHeight()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FolderCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "InkFlow Notebooks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onAddNotebook,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.CreateNewFolder,
                        contentDescription = "New Notebook",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Quick Access Header
                item {
                    Text(
                        text = "QUICK NOTES",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Starred / Pinned Pages shortcut
                val pinnedPages = allActivePages.filter { it.pinned }
                if (pinnedPages.isNotEmpty()) {
                    items(pinnedPages, key = { "pinned-${it.id}" }) { page ->
                        SidebarPageRow(
                            page = page,
                            indentation = 16.dp,
                            isPinnedSection = true,
                            onSelectPage = onSelectPage,
                            onRenamePage = onRenamePage,
                            onDeletePage = onDeletePage,
                            onTogglePinPage = onTogglePinPage
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                } else {
                    item {
                        Text(
                            text = "No pinned pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                }

                item {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                    )
                    Text(
                        text = "ALL NOTEBOOKS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (notebooks.isEmpty()) {
                    item {
                        Text(
                            text = "No notebooks created",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }

                // Render the notebooks tree hierarchy
                notebooks.forEach { notebook ->
                    val isNotebookExpanded = expandedNotebooks[notebook.id] == true
                    val isSelectedNotebook = selectedNotebook?.id == notebook.id

                    item(key = "nb-${notebook.id}") {
                        SidebarNotebookRow(
                            notebook = notebook,
                            isExpanded = isNotebookExpanded,
                            isSelected = isSelectedNotebook,
                            onToggleExpand = {
                                expandedNotebooks[notebook.id] = !isNotebookExpanded
                                onSelectNotebook(notebook)
                            },
                            onAddSection = { onAddSection(notebook) },
                            onRename = { onRenameNotebook(notebook) },
                            onDelete = { onDeleteNotebook(notebook) }
                        )
                    }

                    if (isNotebookExpanded) {
                        val notebookSections = allSections.filter { it.notebookId == notebook.id }

                        if (notebookSections.isEmpty()) {
                            item(key = "empty-sec-${notebook.id}") {
                                Text(
                                    text = "No sections",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 36.dp, top = 4.dp, bottom = 4.dp)
                                )
                            }
                        }

                        notebookSections.forEach { section ->
                            val isSectionExpanded = expandedSections[section.id] == true
                            val isSelectedSection = selectedSection?.id == section.id

                            item(key = "sec-${section.id}") {
                                SidebarSectionRow(
                                    section = section,
                                    isExpanded = isSectionExpanded,
                                    isSelected = isSelectedSection,
                                    onToggleExpand = {
                                        expandedSections[section.id] = !isSectionExpanded
                                        onSelectSection(section)
                                    },
                                    onAddPage = { onAddPage(section) },
                                    onRename = { onRenameSection(section) },
                                    onDelete = { onDeleteSection(section) }
                                )
                            }

                            if (isSectionExpanded) {
                                val sectionPages = allActivePages.filter { it.sectionId == section.id }

                                if (sectionPages.isEmpty()) {
                                    item(key = "empty-page-${section.id}") {
                                        Text(
                                            text = "Empty section",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 52.dp, top = 4.dp, bottom = 4.dp)
                                        )
                                    }
                                } else {
                                    items(sectionPages, key = { "page-${it.id}" }) { page ->
                                        SidebarPageRow(
                                            page = page,
                                            indentation = 44.dp,
                                            isPinnedSection = false,
                                            onSelectPage = onSelectPage,
                                            onRenamePage = onRenamePage,
                                            onDeletePage = onDeletePage,
                                            onTogglePinPage = onTogglePinPage
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNotebookRow(
    notebook: NotebookEntity,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onAddSection: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable { onToggleExpand() }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = notebook.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        // Add section button
        IconButton(
            onClick = { onAddSection() },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add Section",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        // Dropdown options
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Notebook options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                scrollState = overflowMenuScrollState(),
                modifier = overflowMenuScrollModifier()
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun SidebarSectionRow(
    section: SectionEntity,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onAddPage: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable { onToggleExpand() }
            .padding(vertical = 5.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
        )

        // Add page button
        IconButton(
            onClick = { onAddPage() },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Outlined.NoteAdd,
                contentDescription = "New Page",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        // Options dropdown
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Section options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                scrollState = overflowMenuScrollState(),
                modifier = overflowMenuScrollModifier()
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun SidebarPageRow(
    page: NotePageEntity,
    indentation: androidx.compose.ui.unit.Dp,
    isPinnedSection: Boolean,
    onSelectPage: (NotePageEntity) -> Unit,
    onRenamePage: (NotePageEntity) -> Unit,
    onDeletePage: (NotePageEntity) -> Unit,
    onTogglePinPage: (NotePageEntity) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentation, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelectPage(page) }
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (page.pinned) Icons.Outlined.Star else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (page.pinned) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (page.title.isBlank()) "Untitled" else page.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )

        // Dropdown options
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Page options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                scrollState = overflowMenuScrollState(),
                modifier = overflowMenuScrollModifier()
            ) {
                DropdownMenuItem(
                    text = { Text(if (page.pinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(if (page.pinned) Icons.Outlined.StarOutline else Icons.Outlined.Star, contentDescription = null) },
                    onClick = { menuExpanded = false; onTogglePinPage(page) }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onRenamePage(page) }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDeletePage(page) }
                )
            }
        }
    }
}
