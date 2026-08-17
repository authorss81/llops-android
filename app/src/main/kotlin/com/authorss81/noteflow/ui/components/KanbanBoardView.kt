package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class KanbanColumn(
    val id: String,
    val title: String,
    val tag: String,
    val color: Color
)

@Composable
fun KanbanBoardView(
    pages: List<NotePageEntity>,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val columns = remember {
        listOf(
            KanbanColumn("todo", "To Do", "todo", Color(0xFFE53935)),
            KanbanColumn("in_progress", "In Progress", "in-progress", Color(0xFFFB8C00)),
            KanbanColumn("review", "Review", "review", Color(0xFF8E24AA)),
            KanbanColumn("done", "Done", "done", Color(0xFF43A047))
        )
    }

    // Categorize pages into columns according to tags (#todo, #in-progress, etc.)
    val categorizedPages = remember(pages) {
        val map = mutableMapOf<String, MutableList<NotePageEntity>>()
        columns.forEach { map[it.id] = mutableListOf() }
        val unassigned = mutableListOf<NotePageEntity>()

        for (page in pages) {
            val pageTags = page.tags.split(",").map { it.trim().lowercase() }
            var assigned = false
            for (col in columns) {
                if (pageTags.contains(col.tag) || pageTags.contains("#${col.tag}")) {
                    map[col.id]?.add(page)
                    assigned = true
                    break
                }
            }
            if (!assigned) {
                // Default unassigned pages go to "To Do"
                map["todo"]?.add(page)
            }
        }
        map
    }

    LazyRow(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(columns) { column ->
            val columnPages = categorizedPages[column.id] ?: emptyList()
            KanbanColumnView(
                column = column,
                pages = columnPages,
                allColumns = columns,
                onOpenPage = onOpenPage,
                onAddCard = {
                    viewModel.addPage("New ${column.title} Task", tags = column.tag, onCreated = onOpenPage)
                },
                onMoveCard = { page, targetColumn ->
                    val oldTags = page.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val cleanOldTags = oldTags.filterNot { tag ->
                        val lower = tag.lowercase().removePrefix("#")
                        columns.any { it.tag == lower }
                    }
                    val newTagList = (cleanOldTags + targetColumn.tag).distinct()
                    viewModel.updatePageTags(page.id, newTagList.joinToString(","))
                }
            )
        }
    }
}

@Composable
private fun KanbanColumnView(
    column: KanbanColumn,
    pages: List<NotePageEntity>,
    allColumns: List<KanbanColumn>,
    onOpenPage: (NotePageEntity) -> Unit,
    onAddCard: () -> Unit,
    onMoveCard: (NotePageEntity, KanbanColumn) -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(column.color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = column.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceVariant
                    ) {
                        Text(
                            text = pages.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(onClick = onAddCard, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add Task to ${column.title}")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cards list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pages, key = { it.id }) { page ->
                    KanbanCardItem(
                        page = page,
                        column = column,
                        allColumns = allColumns,
                        onOpenPage = onOpenPage,
                        onMoveCard = onMoveCard
                    )
                }
            }
        }
    }
}

@Composable
private fun KanbanCardItem(
    page: NotePageEntity,
    column: KanbanColumn,
    allColumns: List<KanbanColumn>,
    onOpenPage: (NotePageEntity) -> Unit,
    onMoveCard: (NotePageEntity, KanbanColumn) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var showMoveMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPage(page) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box {
                    IconButton(
                        onClick = { showMoveMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Move Card Options", modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = showMoveMenu,
                        onDismissRequest = { showMoveMenu = false },
                        scrollState = overflowMenuScrollState(),
                        modifier = overflowMenuScrollModifier()
                    ) {
                        Text(
                            text = "Move to Column:",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = scheme.outline
                        )
                        allColumns.forEach { targetCol ->
                            if (targetCol.id != column.id) {
                                DropdownMenuItem(
                                    text = { Text(targetCol.title) },
                                    onClick = {
                                        showMoveMenu = false
                                        onMoveCard(page, targetCol)
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(targetCol.color)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (!page.extractedText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = page.extractedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
                Text(
                    text = dateFormat.format(Date(page.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = scheme.outline
                )

                if (page.tags.isNotBlank()) {
                    val tagList = page.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (tagList.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = column.color.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "#${tagList.first().removePrefix("#")}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = column.color,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
