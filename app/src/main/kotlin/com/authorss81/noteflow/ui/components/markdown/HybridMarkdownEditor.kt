package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.MarkdownBlock
import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockType
import com.authorss81.noteflow.theme.serifBodyStyle
import java.io.File

/**
 * Phase 37 — the hybrid block editor. Live-inline slice:
 *
 *  - content is split into source-faithful [MarkdownBlock]s by the pure-JVM
 *    [MarkdownBlockTokenizer];
 *  - every non-edited block renders through the SAME renderer as the preview
 *    ([MarkdownRenderBlocks]), so live formatting (headings/bold/italic/code/
 *    lists/links/math/callouts/checkbox cards) is byte-for-byte the preview's;
 *  - tapping any rendered block's edit affordance (or a heading) swaps that block
 *    to a raw multi-line editor; Done / edit-another-block collapses it back to
 *    rendered;
 *  - while typing, [onValueChange] fires with the exact reconstructed source, so
 *    sibling blocks, the preview and back-navigation save all stay fresh;
 *  - external replaces (slash commands, plugins, version restore) reset the block
 *    layout via a dirty-guard.
 *
 * Phase 174 (wiki-link autocomplete): when [wikiLinkTitles] is non-empty, the
 * raw editor watches for an unterminated `[[` in progress and shows a suggestion
 * popup over the field. Selecting one page calls [onWikiLinkQueryEngaged] (once
 * titles actually load) and replaces the `[[…` region with `[[title|display]]`.
 * An empty/absent title list (vault locked, or no cached corpus yet) shows no
 * popup at all — fails cleanly, never crashes.
 *
 * A full WYSIWYG cursor-within-rich-text model is documented as deferred in
 * REPORT.md — this is the honest first slice.
 */
@Composable
fun HybridMarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false,
    // Phase 174 — candidate note titles for [[ wiki-link autocomplete (empty while
    // locked / not yet loaded). Titles only; never body text.
    wikiLinkTitles: List<String> = emptyList(),
    onWikiLinkQueryEngaged: () -> Unit = {}
) {
    // The whole document is held as ONE one-pass-tokenized [MarkdownDocument]:
    // blocks, checkbox candidates and the candidates-by-block index are computed
    // together, so a keystroke never runs two full passes (R2-b2b5-FEA-03).
    var doc by remember { mutableStateOf(MarkdownBlockTokenizer.tokenize(value)) }
    var editingBlock by remember { mutableStateOf(-1) }
    var editingText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }

    // External change (plugin replace, session, version restore) → adopt it and
    // collapse any open raw editor. Our own lived-through edits set `dirty` so
    // the echo does not reset state.
    LaunchedEffect(value) {
        if (!dirty && value != doc.content) {
            doc = MarkdownBlockTokenizer.tokenize(value)
            editingBlock = -1
            editingText = ""
        } else if (dirty) {
            dirty = false
        }
    }

    fun emitBlockEdit(blockIndex: Int, newRaw: String) {
        dirty = true
        doc = MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newRaw)
        onValueChange(doc.content)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 2.dp)
            .padding(horizontal = 4.dp)
    ) {
        if (doc.blocks.isEmpty()) {
            Text(
                text = "Empty note — type or use / Commands to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        doc.blocks.forEachIndexed { index, block ->
            if (index == editingBlock) {
                RawBlockEditor(
                    label = blockLabel(block),
                    value = editingText,
                    onValueChange = { newRaw ->
                        editingText = newRaw
                        emitBlockEdit(index, newRaw)
                    },
                    onDone = {
                        editingBlock = -1
                        editingText = ""
                    },
                    primaryColor = primaryColor,
                    wikiLinkTitles = wikiLinkTitles,
                    onWikiLinkQueryEngaged = onWikiLinkQueryEngaged
                )
            } else {
                RenderedBlockRow(
                    block = block,
                    source = doc.blockSource(block),
                    primaryColor = primaryColor,
                    baseDir = baseDir,
                    onOpenWikiLink = onOpenWikiLink,
                    serif = serif,
                    cursorOrder = doc.candidatesByBlock[index] ?: emptyList(),
                    onToggleCheckbox = { candidateIndex ->
                        dirty = true
                        doc = MarkdownBlockTokenizer.toggleCheckbox(doc, candidateIndex)
                        onValueChange(doc.content)
                    },
                    onEdit = { editingBlock = index; editingText = doc.blockSource(block) }
                )
            }
        }
    }
}

@Composable
private fun RenderedBlockRow(
    block: MarkdownBlock,
    source: String,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean,
    cursorOrder: List<Int>,
    onToggleCheckbox: (Int) -> Unit,
    onEdit: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val parsed = remember(source) { markdownRendererParser.parse(source) }
        val cursor = remember(source) { MarkdownCheckboxCursor(cursorOrder) }
        MarkdownRenderBlocks(
            children = parsed.childrenList(),
            primaryColor = primaryColor,
            baseDir = baseDir,
            onOpenWikiLink = onOpenWikiLink,
            serif = serif,
            cursor = cursor,
            onToggleCheckbox = onToggleCheckbox
        )
        // Tap-to-edit affordance: explicit icon for interactive blocks, whole-row
        // tap for non-interactive ones (headings, rules, code, math).
        if (block.type == MarkdownBlockType.HEADING || block.type == MarkdownBlockType.THEMATIC_BREAK) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onEdit)
            )
        }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit ${blockLabel(block)} raw syntax",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun RawBlockEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    primaryColor: Color,
    // Phase 174 — wiki-link autocomplete. Empty titles (locked vault / nothing
    // cached yet) simply show no popup.
    wikiLinkTitles: List<String> = emptyList(),
    onWikiLinkQueryEngaged: () -> Unit = {}
) {
    // ---- Phase 174: detect an in-progress `[[` and drive the suggestion popup.
    var queryBounds by remember { mutableStateOf<com.authorss81.noteflow.services.WikiSuggestionPolicy.QueryBounds?>(null) }
    LaunchedEffect(value, wikiLinkTitles) {
        val bounds = com.authorss81.noteflow.services.WikiSuggestionPolicy.locateQuery(value)
        if (bounds != null && wikiLinkTitles.isNotEmpty()) {
            queryBounds = bounds
            // Titles present — no load needed, but the hook stays harmless.
        } else if (bounds != null && wikiLinkTitles.isEmpty()) {
            // `[[` typed but no cached titles yet: ask the host to load the corpus
            // once. Popup stays hidden until titles arrive.
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
            com.authorss81.noteflow.services.WikiSuggestionPolicy.suggest(wikiLinkTitles, query)
        } else {
            emptyList()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Editing $label — raw syntax",
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryColor,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDone, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Done", style = MaterialTheme.typography.labelSmall)
                }
            }
            Box {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    minLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    trailingIcon = {
                        // Esc-like affordance: clear the edit session without writing.
                        IconButton(onClick = onDone) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close editor", modifier = Modifier.size(16.dp))
                        }
                    }
                )
                // Phase 174 — anchored suggestion popup over the field. Selecting a
                // title replaces the whole `[[…` region (never a mid-keystroke jump).
                if (currentBounds != null && suggestions.isNotEmpty()) {
                    com.authorss81.noteflow.ui.components.WikiLinkSuggestionPopup(
                        suggestions = suggestions,
                        onSelect = { title ->
                            val snippet = com.authorss81.noteflow.services.WikiSuggestionPolicy.wikilinkSnippet(title)
                            val replaced = value.substring(0, currentBounds.queryStart) +
                                snippet + value.substring(currentBounds.queryEnd)
                            onValueChange(replaced)
                            queryBounds = null
                        },
                        onDismiss = { queryBounds = null },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = 72.dp)
                    )
                }
            }
        }
    }
}

private fun blockLabel(block: MarkdownBlock): String = when (block.type) {
    MarkdownBlockType.HEADING -> "heading"
    MarkdownBlockType.PARAGRAPH -> "paragraph"
    MarkdownBlockType.CODE_FENCE -> "code block"
    MarkdownBlockType.MATH_BLOCK -> "math block"
    MarkdownBlockType.BLOCKQUOTE -> "quote"
    MarkdownBlockType.CALLOUT -> "callout"
    MarkdownBlockType.BULLET_LIST -> "bulleted list"
    MarkdownBlockType.ORDERED_LIST -> "numbered list"
    MarkdownBlockType.TABLE -> "table"
    MarkdownBlockType.THEMATIC_BREAK -> "divider"
    MarkdownBlockType.HTML_BLOCK -> "HTML block"
}