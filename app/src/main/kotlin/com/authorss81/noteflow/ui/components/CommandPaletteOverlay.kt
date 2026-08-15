package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.authorss81.noteflow.services.graph.CommandPaletteMath
import com.authorss81.noteflow.theme.LocalReduceMotion
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 38 — the global Command Palette HUD.
 *
 * A global quick-switcher over the whole app: rapid note search over the cached
 * decrypted corpus (never a fresh per-keystroke decrypt), tag filtering, and
 * plugin quick actions (web search, OCR, dictation, translate, read-aloud,
 * weather, units, dictionary, transform, assistant) via PluginManager. Zero
 * network of its own, zero new permissions, no background scanning.
 *
 * Keyboard-aware: ArrowUp / ArrowDown move the highlight, Enter invokes. On
 * touch, tap a row. Debounced input runs on the ViewModel's IO path so a
 * keystroke never blocks the UI thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteOverlay(
    viewModel: NoteflowViewModel,
    onOpenNote: (noteId: String, noteTitle: String) -> Unit,
    onClose: () -> Unit
) {
    val reduceMotion = LocalReduceMotion.current

    var query by remember { mutableStateOf("") }
    var filterTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requireAllTags by remember { mutableStateOf(true) }
    var results by remember {
        mutableStateOf<NoteflowViewModel.CommandPaletteSearchResult>(
            NoteflowViewModel.CommandPaletteSearchResult(emptyList(), emptyList())
        )
    }
    var matchedAction by remember { mutableStateOf<CommandPaletteMath.ActionMatch?>(null) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var highlightedIndex by remember { mutableStateOf(0) }
    var selectedTagsVisible by remember { mutableStateOf(false) }

    val searchJob = remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Invoke a highlighted/tapped item: notes open, plugin actions run through
    // PluginManager and surface a one-line banner inside the overlay.
    val invokeItem: (CommandPaletteItem) -> Unit = remember { { item ->
        scope.launch {
            when (item) {
                is CommandPaletteItem.NoteItem -> onOpenNote(item.rank.doc.id, item.rank.doc.title)
                is CommandPaletteItem.ActionItem -> {
                    val result = viewModel.runPaletteAction(item.match, item.match.arg, null)
                    actionFeedback = when (result) {
                        is NoteflowViewModel.PaletteActionResult.Text -> result.text
                        is NoteflowViewModel.PaletteActionResult.Error -> result.message
                    }
                }
            }
        }
    } }

    // Debounced search: 250ms after the last keystroke, run against the cached
    // corpus + tag filter on the ViewModel's IO path.
    LaunchedEffect(query, filterTags, requireAllTags) {
        searchJob.value?.cancel()
        val job = launch {
            delay(250)
            if (query.isBlank() && filterTags.isEmpty()) {
                results = viewModel.commandPaletteSearch("")
            } else {
                results = viewModel.commandPaletteSearch(query, filterTags, requireAllTags)
            }
        }
        searchJob.value = job
        // Action routing is synchronous/pure — refresh immediately, not debounced.
        matchedAction = CommandPaletteMath.matchAction(query)
        highlightedIndex = 0
        actionFeedback = null
    }

    LaunchedEffect(Unit) {
        // Initial recency list appears instantly (no keystroke needed).
        results = viewModel.commandPaletteSearch("")
        focusRequester.requestFocus()
    }

    val combined: List<CommandPaletteItem> = remember(results, matchedAction) {
        buildList {
            if (matchedAction != null) add(CommandPaletteItem.ActionItem(matchedAction!!))
            results.notes.forEach { add(CommandPaletteItem.NoteItem(it)) }
        }
    }
    val highlighted = if (combined.isEmpty()) -1 else highlightedIndex.coerceIn(0, combined.size - 1)

    // Hardware-keyboard navigation.
    val onKey = { event: androidx.compose.ui.input.key.KeyEvent ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> {
                    if (combined.isNotEmpty()) {
                        highlightedIndex = (highlightedIndex + 1).coerceAtMost(combined.size - 1)
                        scope.launch { listState.animateScrollToItem(highlightedIndex) }
                    }
                    true
                }
                Key.DirectionUp -> {
                    if (combined.isNotEmpty()) {
                        highlightedIndex = (highlightedIndex - 1).coerceAtLeast(0)
                        scope.launch { listState.animateScrollToItem(highlightedIndex) }
                    }
                    true
                }
                Key.Enter -> {
                    val idx = highlightedIndex
                    if (idx >= 0 && idx < combined.size) invokeItem(combined[idx])
                    true
                }
                Key.Escape -> {
                    onClose()
                    true
                }
                else -> false
            }
        } else false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 12.dp, vertical = 48.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Command Palette",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "⌘ ↑/↓ · Enter · two-finger swipe down to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent(onKey),
                    placeholder = { Text("Search notes, #tags, or run an action — e.g.  web: css grid") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true
                )

                // Tag filter chips (selected filters) + suggested tags.
                if (filterTags.isNotEmpty() || results.tagSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (filterTags.isNotEmpty()) {
                            Text(
                                if (requireAllTags) "all of:" else "any of:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { requireAllTags = !requireAllTags }) {
                                Text(
                                    if (requireAllTags) "AND" else "OR",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        TextButton(onClick = {
                            filterTags = emptySet()
                            selectedTagsVisible = false
                        }) {
                            Text("Clear tags", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        items(filterTags.toList()) { tag ->
                            InputChip(
                                selected = true,
                                onClick = { filterTags = filterTags - tag },
                                label = { Text("#$tag") }
                            )
                        }
                        items(results.tagSuggestions) { suggestion ->
                            FilterChip(
                                selected = false,
                                onClick = { filterTags = filterTags + suggestion.tag },
                                label = { Text("#${suggestion.tag} (${suggestion.count})") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Action feedback banner (result of running a plugin action).
                actionFeedback?.let { fb ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            fb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (combined.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (query.isBlank()) "No notes yet." else "No matches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(combined) { index, item ->
                            PaletteRow(
                                item = item,
                                highlighted = index == highlighted,
                                onClick = { invokeItem(item) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: #tag filters combine with your query · actions run installed plugins only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Combined palette list entry: either a ranked note or a matched action. */
private sealed interface CommandPaletteItem {
    data class NoteItem(val rank: CommandPaletteMath.RankedNote) : CommandPaletteItem
    data class ActionItem(val match: CommandPaletteMath.ActionMatch) : CommandPaletteItem
}

@Composable
private fun PaletteRow(
    item: CommandPaletteItem,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (item) {
            is CommandPaletteItem.NoteItem -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.rank.doc.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.rank.snippet.isNotBlank()) {
                        Text(
                            item.rank.snippet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.rank.doc.tags.isNotEmpty()) {
                        Text(
                            item.rank.doc.tags.joinToString("  ") { "#$it" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            is CommandPaletteItem.ActionItem -> {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.match.action.label, style = MaterialTheme.typography.bodyLarge)
                    val hint = if (item.match.arg.isNotBlank()) {
                        "\"${item.match.arg}\""
                    } else if (item.match.action.needsArg) {
                        "type ${item.match.action.keyword}: <${item.match.action.suffixHint}>"
                    } else {
                        item.match.action.suffixHint
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
