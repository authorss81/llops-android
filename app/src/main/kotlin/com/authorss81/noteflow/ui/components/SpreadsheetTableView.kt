package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SpreadsheetTableView(
    pages: List<NotePageEntity>,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val horizontalScrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScrollState)
        ) {
            // Table Header Row
            Row(
                modifier = Modifier
                    .background(scheme.surfaceVariant)
                    .border(0.5.dp, scheme.outline.copy(alpha = 0.3f))
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TableCell(text = "Title", width = 200.dp, isHeader = true)
                TableCell(text = "Status", width = 120.dp, isHeader = true)
                TableCell(text = "Tags", width = 160.dp, isHeader = true)
                TableCell(text = "Type", width = 90.dp, isHeader = true)
                TableCell(text = "Updated", width = 120.dp, isHeader = true)
                TableCell(text = "Pinned", width = 80.dp, isHeader = true)
            }

            // Table Data Rows
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(pages, key = { it.id }) { page ->
                    SpreadsheetTableRow(
                        page = page,
                        viewModel = viewModel,
                        onOpenPage = onOpenPage
                    )
                }
            }
        }
    }
}

@Composable
private fun SpreadsheetTableRow(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var showStatusMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .border(0.5.dp, scheme.outline.copy(alpha = 0.2f))
            .clickable { onOpenPage(page) }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title Cell
        Box(modifier = Modifier.width(200.dp)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Status Cell (Editable)
        Box(modifier = Modifier.width(120.dp)) {
            val statusTag = remember(page.tags) {
                val tags = page.tags.split(",").map { it.trim().lowercase() }
                when {
                    tags.contains("done") -> "Done"
                    tags.contains("in-progress") -> "In Progress"
                    tags.contains("review") -> "Review"
                    else -> "To Do"
                }
            }

            Surface(
                onClick = { showStatusMenu = true },
                shape = RoundedCornerShape(4.dp),
                color = when (statusTag) {
                    "Done" -> Color(0xFF43A047).copy(alpha = 0.2f)
                    "In Progress" -> Color(0xFFFB8C00).copy(alpha = 0.2f)
                    "Review" -> Color(0xFF8E24AA).copy(alpha = 0.2f)
                    else -> scheme.surfaceVariant
                }
            ) {
                Text(
                    text = statusTag,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = scheme.onSurface
                )
            }

            DropdownMenu(
                expanded = showStatusMenu,
                onDismissRequest = { showStatusMenu = false },
                scrollState = overflowMenuScrollState(),
                modifier = overflowMenuScrollModifier()
            ) {
                listOf("To Do" to "todo", "In Progress" to "in-progress", "Review" to "review", "Done" to "done").forEach { (label, tag) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            showStatusMenu = false
                            val cleanTags = page.tags.split(",").map { it.trim() }.filter { t ->
                                val l = t.lowercase().removePrefix("#")
                                l != "todo" && l != "in-progress" && l != "review" && l != "done"
                            }
                            viewModel.updatePageTags(page.id, (cleanTags + tag).joinToString(","))
                        }
                    )
                }
            }
        }

        // Tags Cell
        Box(modifier = Modifier.width(160.dp)) {
            Text(
                text = if (page.tags.isNotBlank()) page.tags else "-",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Type Cell
        Box(modifier = Modifier.width(90.dp)) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = scheme.surfaceVariant
            ) {
                Text(
                    text = page.sourceFileType?.uppercase() ?: "INK/NOTE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Updated Date Cell
        Box(modifier = Modifier.width(120.dp)) {
            Text(
                text = dateFormat.format(Date(page.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = scheme.outline
            )
        }

        // Pinned Cell
        Box(modifier = Modifier.width(80.dp), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = { viewModel.togglePinPage(page.id, page.pinned) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = "Pin Note",
                    tint = if (page.pinned) scheme.primary else scheme.outline.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, isHeader: Boolean) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            style = if (isHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            color = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}
