package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Phase 174 — wiki-link suggestion surface used by BOTH entry points:
 *  - the hybrid editor's raw-block autocomplete over `[[` (anchored popup), and
 *  - the slash-menu "Insert wiki-link" flow (full picker dialog).
 *
 * Titles come pre-ranked/deduped/capped from [WikiSuggestionPolicy]; this file
 * only renders the candidate list. Selecting a title replaces the `[[…` query
 * with the canonical `[[title|display]]` wikilink.
 */

/** Anchored mini-popup — overlays an editor field while typing `[[`. */
@Composable
fun WikiLinkSuggestionPopup(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .heightIn(max = 240.dp),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "Link to a note",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(suggestions, key = { it }) { title ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(title) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Link, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Full picker dialog — opened from the slash menu's "Insert wiki-link" entry. */
@Composable
fun WikiLinkPickerDialog(
    titleTitles: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val suggestions = remember(query, titleTitles) {
        com.authorss81.noteflow.services.WikiSuggestionPolicy.suggest(titleTitles, query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // R2-b2b1-UI-02 (phase-140): dialog over an open decrypted note — carry
        // FLAG_SECURE itself in release builds.
        properties = secureDialogProperties(),
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text("Insert Wiki Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Search existing note titles to insert as [[Title|display]]. " +
                        "No suggestions whenever the vault is locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter notes…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isEmpty()) {
                    Text(
                        "No matching notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Box(modifier = Modifier.heightIn(max = 280.dp)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(suggestions, key = { it }) { title ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(title) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}