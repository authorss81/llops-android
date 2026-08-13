package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.WebSearchOutcome
import com.authorss81.noteflow.plugins.WebSearchResult
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Search state inside [WebSearchDialog]. */
private sealed interface SearchStage {
    data object Idle : SearchStage
    data object Loading : SearchStage
    data class Results(val results: List<WebSearchResult>) : SearchStage
    data class Error(val message: String) : SearchStage
}

/**
 * Phase 12: "Search the web" — runs a real, keyless web search (DuckDuckGo
 * Instant Answer via the Web Search plugin) and inserts a `[title](url)` link
 * into the note on tap. An explicit offline error is shown when the plugin can't
 * reach the network — never a silent failure.
 *
 * Pure UI over `viewModel.searchWeb`; all search logic lives in the plugin.
 */
@Composable
fun WebSearchDialog(
    viewModel: NoteflowViewModel,
    onInsertLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<SearchStage>(SearchStage.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun search() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            stage = SearchStage.Error("Enter a search query first.")
            return
        }
        job?.cancel()
        stage = SearchStage.Loading
        job = scope.launch {
            val result = viewModel.searchWeb(trimmed)
            if (coroutineContext.isActive) {
                stage = when (result) {
                    is PluginResult.Success -> when (val outcome = result.value) {
                        is WebSearchOutcome.Success ->
                            if (outcome.results.isEmpty()) {
                                SearchStage.Error("No results found for \"$trimmed\".")
                            } else {
                                SearchStage.Results(outcome.results)
                            }
                        is WebSearchOutcome.Error -> SearchStage.Error(outcome.message)
                    }
                    is PluginResult.Failure -> SearchStage.Error(result.message)
                    is PluginResult.Unavailable -> SearchStage.Error(result.message)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    AlertDialog(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        },
        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        title = { Text("Search the web") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Search query") },
                        singleLine = true
                    )
                    IconButton(onClick = ::search, enabled = stage !is SearchStage.Loading) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                }

                when (val s = stage) {
                    SearchStage.Idle -> Text(
                        "Search results from DuckDuckGo are pulled into this note as [title](url) links. " +
                            "Tap a result to insert it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SearchStage.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    }
                    is SearchStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    is SearchStage.Results -> SearchResultsList(s.results) { title, url ->
                        val link = "[$title]($url)"
                        onInsertLink(link)
                        // Dismiss after insert — mirrors OcrResultDialog so a
                        // single tap both inserts and closes (no orphaned dialog).
                        job?.cancel()
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                SearchStage.Loading -> TextButton(onClick = {
                    job?.cancel()
                    stage = SearchStage.Idle
                }) { Text("Cancel") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun SearchResultsList(
    results: List<WebSearchResult>,
    onInsert: (title: String, url: String) -> Unit
) {
    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        results.forEachIndexed { index, result ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInsert(result.title, result.url) }
                    .padding(vertical = 8.dp, horizontal = 8.dp)
            ) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                result.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    result.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index < results.lastIndex) HorizontalDivider()
        }
    }
}