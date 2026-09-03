package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.WikiSuggestionPolicy
import com.authorss81.noteflow.ui.components.WikiLinkSuggestionPopup

/**
 * Whole-page Markdown Editor allowing users to write continuous markdown text
 * without segmenting into individual block cards.
 */
@Composable
fun WholeMarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    serif: Boolean = false,
    wikiLinkTitles: List<String> = emptyList(),
    onWikiLinkQueryEngaged: () -> Unit = {}
) {
    var queryBounds by remember { mutableStateOf<WikiSuggestionPolicy.QueryBounds?>(null) }
    LaunchedEffect(value, wikiLinkTitles) {
        val bounds = WikiSuggestionPolicy.locateQuery(value)
        if (bounds != null && wikiLinkTitles.isNotEmpty()) {
            queryBounds = bounds
        } else if (bounds != null && wikiLinkTitles.isEmpty()) {
            queryBounds = bounds
            onWikiLinkQueryEngaged()
        } else {
            queryBounds = null
        }
    }
    val currentBounds = queryBounds
    val query = currentBounds?.let { value.substring(it.queryStart + 2, it.queryEnd) } ?: ""
    val suggestions = remember(query, wikiLinkTitles, currentBounds) {
        if (currentBounds != null && wikiLinkTitles.isNotEmpty()) {
            WikiSuggestionPolicy.suggest(wikiLinkTitles, query)
        } else {
            emptyList()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .testTag("markdownBody"),
            placeholder = {
                Text(
                    "Start writing Markdown note (headings #, **bold**, *italic*, lists, [[wikilinks]])...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primaryColor,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions()
        )

        if (currentBounds != null && suggestions.isNotEmpty()) {
            WikiLinkSuggestionPopup(
                suggestions = suggestions,
                onSelect = { title ->
                    val snippet = WikiSuggestionPolicy.wikilinkSnippet(title)
                    val replaced = value.substring(0, currentBounds.queryStart) +
                        snippet + value.substring(currentBounds.queryEnd)
                    onValueChange(replaced)
                    queryBounds = null
                },
                onDismiss = { queryBounds = null },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 56.dp)
            )
        }
    }
}
