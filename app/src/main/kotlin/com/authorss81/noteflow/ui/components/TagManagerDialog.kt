package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch

/**
 * Tag Manager Dialog for viewing, creating, renaming, and deleting tags across the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagManagerDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var newTagInput by remember { mutableStateOf("") }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }

    fun refreshTags() {
        scope.launch {
            isLoading = true
            val nbs = viewModel.repository.getAllNotebooks()
            val pgs = viewModel.repository.getAllActivePages()
            val tagSet = mutableSetOf<String>()
            nbs.forEach { nb ->
                nb.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tagSet.add(it) }
            }
            pgs.forEach { pg ->
                pg.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tagSet.add(it) }
            }
            allTags = tagSet.sorted()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshTags()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Tag Manager", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Categorize notebooks and quick notes with tags.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Add new tag input row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it },
                        placeholder = { Text("Enter tag name...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val tag = newTagInput.trim().replace("#", "")
                            if (tag.isNotEmpty()) {
                                if (!allTags.contains(tag)) {
                                    allTags = (allTags + tag).sorted()
                                }
                                newTagInput = ""
                            }
                        },
                        enabled = newTagInput.isNotBlank()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add Tag")
                    }
                }

                HorizontalDivider()

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (allTags.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No tags created yet. Add one above!", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(allTags, key = { it }) { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Outlined.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(tag, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                editingTag = tag
                                                renameInput = tag
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "Rename Tag", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    viewModel.deleteTag(tag)
                                                    refreshTags()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "Delete Tag", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )

    // Rename Dialog sub-modal
    editingTag?.let { targetTag ->
        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text("Rename Tag '#$targetTag'") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New tag name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = renameInput.trim().replace("#", "")
                        if (newName.isNotEmpty() && newName != targetTag) {
                            scope.launch {
                                viewModel.renameTag(targetTag, newName)
                                refreshTags()
                                editingTag = null
                            }
                        } else {
                            editingTag = null
                        }
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTag = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Individual Tag Editor Dialog for attaching/detaching tags to a specific Notebook or Note Page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    itemTitle: String,
    currentTagsString: String,
    onDismiss: () -> Unit,
    onSaveTags: (String) -> Unit
) {
    var tagList by remember {
        mutableStateOf(currentTagsString.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
    var tagInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Tags for '$itemTitle'") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        placeholder = { Text("Add tag...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val clean = tagInput.trim().replace("#", "")
                            if (clean.isNotEmpty() && !tagList.contains(clean)) {
                                tagList = tagList + clean
                                tagInput = ""
                            }
                        },
                        enabled = tagInput.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }

                Text("Assigned Tags:", style = MaterialTheme.typography.labelMedium)

                if (tagList.isEmpty()) {
                    Text("No tags assigned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tagList.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { tagList = tagList.filterNot { it == tag } },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Remove Tag",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveTags(tagList.joinToString(","))
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
