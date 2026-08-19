package com.authorss81.noteflow.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.WbSunny
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.ui.components.VersionHistoryBottomSheet
import com.authorss81.noteflow.ui.components.WebSearchDialog
import com.authorss81.noteflow.ui.components.DictionaryDialog
import com.authorss81.noteflow.ui.components.WeatherDialog
import com.authorss81.noteflow.ui.components.UnitConverterDialog
import com.authorss81.noteflow.ui.components.OutlineGeneratorDialog
import com.authorss81.noteflow.ui.components.CitationFormatterDialog
import com.authorss81.noteflow.ui.components.secureDialogProperties
import com.authorss81.noteflow.ui.components.overflowMenuScrollModifier
import com.authorss81.noteflow.ui.components.overflowMenuScrollState
import kotlinx.coroutines.launch
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.HeadingScrollIndex
import com.authorss81.noteflow.services.NoteStatsFormatPolicy
import com.authorss81.noteflow.services.ReaderModePolicy
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.services.WikiSuggestionPolicy
import com.authorss81.noteflow.theme.LocalReduceMotion
import com.authorss81.noteflow.theme.serifBodyStyle
import com.authorss81.noteflow.ui.components.BacklinksInspectorBottomSheet
import com.authorss81.noteflow.ui.components.WikiLinkPickerDialog
import com.authorss81.noteflow.ui.components.markdown.HybridMarkdownEditor
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.io.File
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Child list accessor — 0.29+ removed Node.getChildren(). */
private fun Node.childrenList(): List<Node> {
    val list = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        list.add(child)
        child = child.next
    }
    return list
}

/** Plain-text content of a node (replaces removed TextContent.getChildText). */
private fun Node.collectLiteral(): String {
    val own = when (this) {
        is Text -> literal
        is Code -> literal
        is HtmlInline -> literal
        is HtmlBlock -> literal
        is FencedCodeBlock -> literal
        is IndentedCodeBlock -> literal
        is SoftLineBreak, is HardLineBreak -> "\n"
        else -> null
    }
    if (own != null) return own
    val sb = StringBuilder()
    var child = firstChild
    while (child != null) {
        sb.append(child.collectLiteral())
        child = child.next
    }
    return sb.toString()
}

enum class MarkdownViewMode {
    EDIT, SPLIT, PREVIEW
}

enum class SplitOrientation {
    AUTO, VERTICAL, HORIZONTAL
}

// Phase 158 (22.5): reader/focus layout signal for the markdown renderers. Avoids
// threading a boolean through every recursive RenderBlocks/RenderInline
// signature while keeping the decision local to this file.
private val LocalReaderMode = androidx.compose.runtime.compositionLocalOf { false }

// Phase 174 (outline quick-jump): layout-time heading measurement for the
// reader-mode rail. Holds the precomputed HeadingScrollIndex plus its
// node→position map, and turns each heading's on-screen root coordinates into a
// scroll-content offset (root top − viewport top + current scroll), independent
// of where the user has already scrolled. Visible only inside the reader branch.
private class HeadingMeasureScope(
    val index: HeadingScrollIndex,
    val nodePositions: Map<Node, Int>,
    val scrollState: ScrollState
) {
    @Volatile
    private var columnTopRoot = 0f
    private val coordinatesByPosition = java.util.concurrent.ConcurrentHashMap<Int, LayoutCoordinates>()

    /** The scroll viewport's root-coordinate top (updated once it settles). */
    fun onColumnPlaced(topRoot: Float) {
        columnTopRoot = topRoot
        recomputeAll()
    }

    /** A heading was (re)measured — remember its coordinates and re-register. */
    fun onHeadingPlaced(position: Int, coords: LayoutCoordinates) {
        coordinatesByPosition[position] = coords
        register(position)
    }

    private fun register(position: Int) {
        val coords = coordinatesByPosition[position] ?: return
        val raw = coords.boundsInRoot().top - columnTopRoot + scrollState.value
        index.register(position, raw.toInt().coerceAtLeast(0))
    }

    private fun recomputeAll() {
        coordinatesByPosition.keys.forEach { register(it) }
    }
}

private val LocalHeadingMeasure = androidx.compose.runtime.compositionLocalOf<HeadingMeasureScope?> { null }

/**
 * Phase 174 (Feature 2): the reader-mode heading index + its layout-time
 * measure scope, built together once per parsed document. Null (never built)
 * on the preview/split surfaces where the quick-jump rail isn't composed.
 */
private class ReaderHeadingModel(
    val index: HeadingScrollIndex,
    val measureScope: HeadingMeasureScope
)

/**
 * Phase 174 (Feature 2): DFS over the ALREADY-parsed markdown [Node] tree,
 * collecting every [Heading] in document order — the exact order [HeadingScrollIndex]
 * builds from, and the exact order RenderBlocks renders, so node⇄position
 * identity stays stable across recompositions (Node hash/equals are identity).
 */
private fun collectHeadingNodes(nodes: Iterable<Node>): List<Heading> {
    val out = mutableListOf<Heading>()
    fun walk(list: Iterable<Node>) {
        for (node in list) {
            when (node) {
                is Heading -> out.add(node)
                is BulletList, is OrderedList, is ListItem, is BlockQuote -> walk(node.childrenList())
                else -> Unit
            }
        }
    }
    walk(nodes)
    return out
}

/**
 * Phase 174 (Feature 2): the anchored, collapsible outline rail shown on the
 * reader/focus surface. Collapsed by default; expands to a scrollable heading
 * list. Tapping a heading scrolls the preview via the precomputed
 * [HeadingScrollIndex] offset — instant when reduce-motion is on, animated
 * otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderOutlineRail(
    index: HeadingScrollIndex,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    var collapsed by rememberSaveable { mutableStateOf(true) }
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = modifier.padding(end = 2.dp, top = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.width(168.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { collapsed = !collapsed }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ListAlt,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (collapsed) "Outline (${index.size})" else "Outline",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    if (collapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                    contentDescription = if (collapsed) "Expand outline" else "Collapse outline",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (!collapsed) {
                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    index.labels().forEach { label ->
                        val level = index.labels().indexOf(label)
                        val indent = if (level > 0) {
                            (index.levelAt(level) - 1).coerceIn(0, 3) * 8
                        } else 0
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val offset = index.offsetForLabel(label)
                                        if (offset != null) {
                                            if (reduceMotion) {
                                                scrollState.scrollTo(offset)
                                            } else {
                                                scrollState.animateScrollTo(offset)
                                            }
                                        }
                                    }
                                }
                                .padding(start = (indent + 10).dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownPreviewScreen(
    page: NotePageEntity,
    initialContent: String,
    viewModel: NoteflowViewModel,
    // Phase 158 (22.5): reader/focus mode. The screen starts in reader mode
    // when it was opened FROM a share-sheet capture (one-shot request — the
    // parent consumes it via onConsumeReaderMode so a later unlock never
    // re-applies it); the user can toggle reader mode on/off any time after.
    initialReaderMode: Boolean = false,
    onConsumeReaderMode: () -> Unit = {},
    // Phase 158 review-fix: when a share-sheet clip is appended to this note,
    // MainActivity pushes the resulting body here so the open screen shows it
    // immediately — without this, the editor kept the stale pre-append snapshot
    // and the next save would write it back, silently dropping the appended
    // text. One-shot: consumed the moment it is applied.
    externalBodyUpdate: String? = null,
    onConsumeExternalBodyUpdate: () -> Unit = {},
    onBack: () -> Unit,
    onOpenWikiLink: (String) -> Unit,
    onOpenPage: (NotePageEntity) -> Unit,
    onSaveContent: (String) -> Unit
) {
    var viewMode by remember { mutableStateOf(MarkdownViewMode.SPLIT) }
    var splitOrientation by remember { mutableStateOf(SplitOrientation.AUTO) }
    // Phase 158 (22.5): focus/reading mode. Read-only by construction — the
    // hybrid editor is never composed while reader mode is active, so a
    // long-press can never open an edit surface. rememberSaveable so a rotation
    // keeps the user's reader/editor choice (review-fix).
    var readerMode by rememberSaveable(page.id) { mutableStateOf(initialReaderMode) }
    LaunchedEffect(page.id) {
        if (initialReaderMode) onConsumeReaderMode()
    }
    // Phase 34: long-form reading toggle — editorial serif for the body only;
    // UI chrome stays sans. Persisted per device via SettingsManager.
    var serifReadingMode by remember(page.id) {
        mutableStateOf(viewModel.settings.serifReadingEnabled)
    }
    var contentText by remember { mutableStateOf(initialContent) }

    // B1-DB-4 (phase-44): a text page no longer has a source file to anchor
    // relative markdown images (its body lives in the encrypted column). Fall
    // back to the imports directory — the same single folder imported
    // attachments were always written to — so `![alt](img.png)` /
    // `![[img.png]]` keep resolving for imported HTML/Obsidian vaults.
    val baseDir = page.sourceFilePath?.let { File(it).parentFile }
        ?: (if (page.sourceFileType == "text") {
            runCatching { ImportExportService.getImportsDir(LocalContext.current) }.getOrNull()
        } else null)

    // 22.9: never silently discard edits — flush content before navigating back.
    // Dedupe: only write when the content actually changed, and never twice for the
    // same snapshot (BackHandler and onDispose both funnel through flushSave, so a
    // back press results in exactly one write, and an unchanged screen writes zero).
    var savedContent by remember(page.id) { mutableStateOf(initialContent) }
    fun flushSave() {
        if (savedContent != contentText) {
            savedContent = contentText
            onSaveContent(contentText)
        }
    }

    // Phase 158 review-fix: apply an externally-computed body (a share-clip
    // append) so the open screen never shows — and never flushes back — the
    // stale pre-append snapshot. Aligning savedContent prevents a redundant
    // rewrite of the just-appended body.
    LaunchedEffect(page.id, externalBodyUpdate) {
        val body = externalBodyUpdate ?: return@LaunchedEffect
        contentText = body
        savedContent = body
        onConsumeExternalBodyUpdate()
    }

    androidx.activity.compose.BackHandler {
        flushSave()
        onBack()
    }

    // 22.9: also flush when the editor leaves composition for any other reason
    // (nav elsewhere, split-pane layout changes, page switch).
    DisposableEffect(Unit) {
        onDispose { flushSave() }
    }
    var splitRatio by remember { mutableFloatStateOf(0.5f) }
    var showBacklinks by remember { mutableStateOf(false) }
    var showSmartAssistant by remember { mutableStateOf(false) }
    var showSlashCommands by remember { mutableStateOf(false) }
    var showVersionHistory by remember { mutableStateOf(false) }
    var showPluginMenu by remember { mutableStateOf(false) }
    var showWebSearch by remember { mutableStateOf(false) }
    var showTextTools by remember { mutableStateOf(false) }
    var showLanguageDetection by remember { mutableStateOf(false) }
    // Phase 16 — keyless on-device plugins.
    var showDictation by remember { mutableStateOf(false) }
    var showReadAloud by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(false) }
    var pendingTransformPlugin by remember { mutableStateOf<NoteflowPlugin?>(null) }
    val transformScope = rememberCoroutineScope()
    // Phase 26 — lightweight compile-time plugins.
    var showDictionary by remember { mutableStateOf(false) }
    var showWeather by remember { mutableStateOf(false) }
    var showUnitConverter by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showCitation by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val isTopBottomSplit = when (splitOrientation) {
        SplitOrientation.VERTICAL -> true
        SplitOrientation.HORIZONTAL -> false
        SplitOrientation.AUTO -> isPortrait
    }

    // ---- Phase 174 (Feature 1): note-stats footer --------------------------
    // Debounced off the document length; recomputes only when the length changed
    // materially (NoteStatsFormatPolicy.shouldRecomputeStats) — never a
    // full re-tokenize per keystroke. The pure-JVM analyzer (TextToolsAnalyzer)
    // is O(n) on a single pass, so a debounced sample is cheap.
    val reduceMotion = LocalReduceMotion.current
    val statsLocale = java.util.Locale.getDefault()
    var statsText by remember { mutableStateOf<String?>(null) }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(page.id) {
        // Review-fix (Finding 3): clear the footer instantly on a note switch so
        // the PREVIOUS note's "N words · ~M min read" never flashes while the new
        // document's debounced sample is still warming up.
        statsText = null
        var lastRecomputedLength = -1
        var skippedSamplesSinceRecompute = 0
        snapshotFlow { contentText }
            .debounce(NoteStatsFormatPolicy.STATS_DEBOUNCE_MILLIS)
            .collect { text ->
                skippedSamplesSinceRecompute++
                // Review-fix (Finding 7): staleness is bounded BOTH by literal
                // drift (< MIN_MATERIAL_LENGTH_DELTA since the last recompute —
                // the design intent, punctuation-only edits don't re-tokenize) AND
                // by real time (a cap of 12 consecutive sub-threshold samples, i.e.
                // ~3s, forces convergence). The old code only ever compared to the
                // last recomputed length, so a slow typist landing <8 net chars
                // per sample could lag forever; now the footer always converges.
                val driftExceeded = NoteStatsFormatPolicy.shouldRecomputeStats(lastRecomputedLength, text.length)
                val staleForTooLong = skippedSamplesSinceRecompute >= 12
                if (lastRecomputedLength >= 0 && !driftExceeded && !staleForTooLong) return@collect
                // Review-fix (Finding 8): count words/chars/reading-time against the
                // markdown VISIBLE TEXT (the same parse the preview renders, its
                // literal concatenation strips `#`/`-`/`|`/`**` markers), so a
                // heading-only or table-heavy note isn't inflated by syntax tokens.
                lastRecomputedLength = text.length
                skippedSamplesSinceRecompute = 0
                val visibleText = markdownParser.parse(text).collectLiteral()
                val analysis = com.authorss81.noteflow.plugins.texttools.TextToolsAnalyzer.analyze(visibleText)
                statsText = NoteStatsFormatPolicy.statsLabel(
                    wordCount = analysis.wordCount,
                    readingTimeSeconds = analysis.readingTimeSeconds,
                    characterCount = analysis.characterCount,
                    locale = statsLocale
                )
            }
    }

    // ---- Phase 174 (Feature 3): wiki-link suggestions ----------------------
    // Candidate titles come from the single cached bounded search corpus (no new
    // DB reads per keystroke). Loaded lazily the first time a `[[` or the
    // slash-menu picker engages, cleared on lock (fail closed: no suggestions).
    val authenticated by viewModel.authenticated.collectAsState()
    var wikiTitles by remember { mutableStateOf<List<String>?>(null) }
    var loadingWikiTitles by remember { mutableStateOf(false) }
    val wikiScope = rememberCoroutineScope()
    fun ensureWikiLinkTitles() {
        if (wikiTitles != null || loadingWikiTitles || !authenticated) return
        loadingWikiTitles = true
        wikiScope.launch {
            val loaded = viewModel.cachedWikiLinkTitles()
            wikiTitles = loaded
            loadingWikiTitles = false
        }
    }
    LaunchedEffect(authenticated) {
        if (!authenticated) {
            wikiTitles = null
            loadingWikiTitles = false
        }
    }
    // Review-fix (Finding 4): the candidate-title snapshot is invalidated after
    // every REAL save so notes created/mutated later in the same unlocked session
    // show up in the next `[[`/picker engagement (the corpus cache itself is
    // epoch-based; this just drops the stale copy lazily). Reload happens only on
    // the next engagement — never per keystroke. `wikiTitles == null` (never
    // loaded) and `== emptyList` (genuinely loaded-empty) are both respected.
    LaunchedEffect(savedContent) {
        if (savedContent != initialContent) {
            wikiTitles = null
            loadingWikiTitles = false
        }
    }
    val lockedWikiTitles = if (authenticated) (wikiTitles ?: emptyList()) else emptyList()
    var showWikiLinkPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        // Phase 158 (22.5): instant reader/focus toggle — no
                        // transition animation (respects reduce-motion). Selecting it
                        // strips the editing chrome and renders a read-only, capped
                        // reading column; the hybrid editor is never composed in this
                        // mode so long-press can never open an edit surface.
                        FilterChip(
                            selected = readerMode,
                            onClick = { readerMode = !readerMode },
                            label = { Text(stringResource(com.authorss81.noteflow.R.string.reader_toggle_label), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showVersionHistory = true }) {
                        Icon(Icons.Outlined.History, contentDescription = "Version History", tint = primaryColor)
                    }
                    // Phase 158 (22.5): reader/focus mode strips the EDITING chrome
                    // (save, smart-assistant, plugins). History + backlinks stay —
                    // browsing either is read-only; a version RESTORE is disabled
                    // while reader mode is active (review-fix), so no write action
                    // is reachable from the reading surface.
                    if (!readerMode) {
                        IconButton(onClick = { showSmartAssistant = true }) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "On-Device Smart Assistant", tint = primaryColor)
                        }
                    }
                    IconButton(onClick = { showBacklinks = true }) {
                        Icon(Icons.Outlined.Hub, contentDescription = "Backlinks & Knowledge Connections", tint = primaryColor)
                    }
                    if (!readerMode) {
                        IconButton(
                            onClick = {
                                flushSave()
                                viewModel.createNoteVersion(page.id, page.title, contentText, "Manual save in Live Editor")
                            }
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save Content")
                        }
                    }
                    // Phase 166: the Serif chip moved out of the app-bar actions
                    // into the full-width sub-bar beneath the app bar (see below),
                    // where the editor/preview view mode + split orientation chips
                    // also live. The app bar no longer crowds on 360dp screens.
                    // Phase 158 (22.5): the plugin menu is editing chrome (text
                    // transforms insert/rewrite content) — hidden in reader mode.
                    if (!readerMode) {
                        Box {
                        IconButton(onClick = { showPluginMenu = true }) {
                            Icon(Icons.Outlined.Extension, contentDescription = "Plugins", tint = primaryColor)
                        }
                        DropdownMenu(
                            expanded = showPluginMenu,
                            onDismissRequest = { showPluginMenu = false },
                            scrollState = overflowMenuScrollState(),
                            modifier = overflowMenuScrollModifier()
                        ) {
                            val transformPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.TextTransform)
                            if (transformPlugins.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No text-transform plugins installed") },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else {
                                transformPlugins.forEach { plugin ->
                                    // Only runnable plugins are selectable; disabled or
                                    // device-unavailable ones are grayed out with a hint.
                                    // Uses the ViewModel's freshly-derived lifecycle state
                                    // (never a raw, unguarded availability() call).
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (runnable) "Run ${plugin.name}" else "${plugin.name} (off)")
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            pendingTransformPlugin = plugin
                                        }
                                    )
                                }
                            }
                            // Phase 12: real web search — the Web Search plugin
                            // (DuckDuckGo) opens the search dialog, which inserts
                            // a [title](url) link into the note.
                            val webSearchPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.WebSearch)
                            if (webSearchPlugins.isNotEmpty()) {
                                HorizontalDivider()
                                webSearchPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (runnable) "Search the web…" else "Web Search (off)")
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showWebSearch = true
                                        }
                                    )
                                }
                            }
                            // Phase 15 (Text Tools): structural stats + note diff in a dialog.
                            val textToolsPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.TextTools)
                            if (textToolsPlugins.isNotEmpty()) {
                                HorizontalDivider()
                                textToolsPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (runnable) "Text Tools: analyze & diff…" else "Text Tools (off)")
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Functions, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showTextTools = true
                                        }
                                    )
                                }
                            }
                            // Phase 15 (Language Detection): detect + auto-tag `lang:<iso>`.
                            val langPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.LanguageDetection)
                            if (langPlugins.isNotEmpty()) {
                                HorizontalDivider()
                                langPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (runnable) "Detect language / auto-tag…" else "Language Detection (off)")
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showLanguageDetection = true
                                        }
                                    )
                                }
                            }
                            // Phase 16 (Dictation/Read-Aloud/Translation): keyless
                            // on-device plugins, all strictly user-initiated.
                            val dictPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.Dictation)
                            if (dictPlugins.isNotEmpty()) {
                                HorizontalDivider()
                                dictPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(if (runnable) "Dictate into this note…" else "Dictation (off)") },
                                        leadingIcon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            flushSave()
                                            showDictation = true
                                        }
                                    )
                                }
                            }
                            val readAloudPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.ReadAloud)
                            if (readAloudPlugins.isNotEmpty()) {
                                readAloudPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(if (runnable) "Read this note aloud…" else "Read Aloud (off)") },
                                        leadingIcon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showReadAloud = true
                                        }
                                    )
                                }
                            }
                            val translationPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.Translation)
                            if (translationPlugins.isNotEmpty()) {
                                translationPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(if (runnable) "Translate this note…" else "Translation (off)") },
                                        leadingIcon = { Icon(Icons.Outlined.Translate, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showTranslation = true
                                        }
                                    )
                                }
                            }
                            // Phase 26 — lightweight compile-time plugins (dictionary,
                            // weather, unit converter, outline & checklist, citation).
                            val dictionaryPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.Dictionary)
                            if (dictionaryPlugins.isNotEmpty()) {
                                HorizontalDivider()
                                dictionaryPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(viewModel.pluginMenuLabel(plugin.id, "Look up a word…")) },
                                        leadingIcon = { Icon(Icons.Outlined.Book, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showDictionary = true
                                        }
                                    )
                                }
                            }
                            val weatherPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.Weather)
                            if (weatherPlugins.isNotEmpty()) {
                                weatherPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(viewModel.pluginMenuLabel(plugin.id, "Weather snapshot…")) },
                                        leadingIcon = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            flushSave()
                                            showWeather = true
                                        }
                                    )
                                }
                            }
                            val unitConverterPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.UnitConversion)
                            if (unitConverterPlugins.isNotEmpty()) {
                                unitConverterPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(viewModel.pluginMenuLabel(plugin.id, "Unit Converter…")) },
                                        leadingIcon = { Icon(Icons.Outlined.Calculate, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showUnitConverter = true
                                        }
                                    )
                                }
                            }
                            val outlinePlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.OutlineGenerator)
                            if (outlinePlugins.isNotEmpty()) {
                                outlinePlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(viewModel.pluginMenuLabel(plugin.id, "Outline / checklist…")) },
                                        leadingIcon = { Icon(Icons.Outlined.ListAlt, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showOutline = true
                                        }
                                    )
                                }
                            }
                            val citationPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.CitationFormatter)
                            if (citationPlugins.isNotEmpty()) {
                                citationPlugins.forEach { plugin ->
                                    val runnable = viewModel.isPluginUsable(plugin.id)
                                    DropdownMenuItem(
                                        text = { Text(viewModel.pluginMenuLabel(plugin.id, "Cite a URL…")) },
                                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                                        enabled = runnable,
                                        onClick = {
                                            showPluginMenu = false
                                            showCitation = true
                                        }
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .imePadding()
            ) {
                // Phase 166: the view-mode, split-orientation and serif chips moved
                // out of the top app bar so the title / action icons no longer crowd
                // on 360dp screens. They now live in this full-width sub-bar, which
                // scrolls horizontally if a future chip ever gets wider than the
                // screen — the app bar itself can never clip again.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Phase 166 review-fix: the Serif chip moved out of the app bar
                    // but keeps its phase-158 gate — reachable in reader/preview/split
                    // modes, hidden in the plain editor, exactly as before.
                    if (readerMode || viewMode != MarkdownViewMode.EDIT) {
                        FilterChip(
                            selected = serifReadingMode,
                            onClick = {
                                serifReadingMode = !serifReadingMode
                                viewModel.settings.serifReadingEnabled = serifReadingMode
                            },
                            label = { Text("Serif", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Book,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (!readerMode) {
                        FilterChip(
                            selected = viewMode == MarkdownViewMode.SPLIT,
                            onClick = {
                                viewMode = when (viewMode) {
                                    MarkdownViewMode.EDIT -> MarkdownViewMode.SPLIT
                                    MarkdownViewMode.SPLIT -> MarkdownViewMode.PREVIEW
                                    MarkdownViewMode.PREVIEW -> MarkdownViewMode.EDIT
                                }
                            },
                            label = { Text(viewMode.name, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                        if (viewMode == MarkdownViewMode.SPLIT) {
                            FilterChip(
                                selected = splitOrientation != SplitOrientation.AUTO,
                                onClick = {
                                    splitOrientation = when (splitOrientation) {
                                        SplitOrientation.AUTO -> SplitOrientation.VERTICAL
                                        SplitOrientation.VERTICAL -> SplitOrientation.HORIZONTAL
                                        SplitOrientation.HORIZONTAL -> SplitOrientation.AUTO
                                    }
                                },
                                label = {
                                    Text(
                                        when (splitOrientation) {
                                            SplitOrientation.AUTO -> if (isPortrait) "Auto (Top/Bottom)" else "Auto (Left/Right)"
                                            SplitOrientation.VERTICAL -> "Top/Bottom"
                                            SplitOrientation.HORIZONTAL -> "Left/Right"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (isTopBottomSplit) Icons.Outlined.TableRows else Icons.Outlined.ViewColumn,
                                        contentDescription = "Toggle Split Orientation",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (readerMode) {
                        // Phase 158 (22.5): reader/focus mode is read-only by construction —
                        // the hybrid editor is never composed here, so long-press can never
                        // open an edit surface. Instant swap, no transition animation
                        // (reduce-motion honored by adding no motion).
                        MarkdownRenderedContent(
                            content = contentText,
                            primaryColor = primaryColor,
                            baseDir = baseDir,
                            onOpenWikiLink = onOpenWikiLink,
                            serif = serifReadingMode,
                            readerMode = true
                        )
                    } else when (viewMode) {
                        MarkdownViewMode.EDIT -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(
                                        onClick = { showSlashCommands = true },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("/ Slash Commands")
                                    }
                                }
        
                                HybridMarkdownEditor(
                                    value = contentText,
                                    onValueChange = { contentText = it },
                                    modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                                    primaryColor = primaryColor,
                                    baseDir = baseDir,
                                    onOpenWikiLink = onOpenWikiLink,
                                    serif = serifReadingMode,
                                    wikiLinkTitles = lockedWikiTitles,
                                    onWikiLinkQueryEngaged = ::ensureWikiLinkTitles
                                )
                            }
                        }
        
                        MarkdownViewMode.PREVIEW -> {
                            MarkdownRenderedContent(
                                content = contentText,
                                primaryColor = primaryColor,
                                baseDir = baseDir,
                                onOpenWikiLink = onOpenWikiLink,
                                serif = serifReadingMode,
                                readerMode = readerMode
                            )
                        }
        
                        MarkdownViewMode.SPLIT -> {
                            if (isTopBottomSplit) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(splitRatio).fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Markdown Editor", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            Button(
                                                onClick = { showSlashCommands = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("/ Commands", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        HybridMarkdownEditor(
                                            value = contentText,
                                            onValueChange = { contentText = it },
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            primaryColor = primaryColor,
                                            baseDir = baseDir,
                                            onOpenWikiLink = onOpenWikiLink,
                                            serif = serifReadingMode,
                                            wikiLinkTitles = lockedWikiTitles,
                                            onWikiLinkQueryEngaged = ::ensureWikiLinkTitles
                                        )
                                    }
        
                                    HorizontalDivider()
        
                                    Column(modifier = Modifier.weight(1f - splitRatio).fillMaxWidth()) {
                                        Text("Live Preview", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
                                        Surface(
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Box(modifier = Modifier.padding(8.dp)) {
                                                MarkdownRenderedContent(
                                                    content = contentText,
                                                    primaryColor = primaryColor,
                                                    baseDir = baseDir,
                                                    onOpenWikiLink = onOpenWikiLink,
                                                    serif = serifReadingMode
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(splitRatio).fillMaxHeight()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Markdown Editor", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                            Button(
                                                onClick = { showSlashCommands = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("/ Commands", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        HybridMarkdownEditor(
                                            value = contentText,
                                            onValueChange = { contentText = it },
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            primaryColor = primaryColor,
                                            baseDir = baseDir,
                                            onOpenWikiLink = onOpenWikiLink,
                                            serif = serifReadingMode,
                                            wikiLinkTitles = lockedWikiTitles,
                                            onWikiLinkQueryEngaged = ::ensureWikiLinkTitles
                                        )
                                    }
        
                                    VerticalDivider()
        
                                    Column(modifier = Modifier.weight(1f - splitRatio).fillMaxHeight()) {
                                        Text("Live Preview", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                                        Surface(
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Box(modifier = Modifier.padding(8.dp)) {
                                                MarkdownRenderedContent(
                                                    content = contentText,
                                                    primaryColor = primaryColor,
                                                    baseDir = baseDir,
                                                    onOpenWikiLink = onOpenWikiLink,
                                                    serif = serifReadingMode
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Phase 174 (Feature 1): unobtrusive note-stats footer. Shown in
                // every mode (edit/split/preview/reader); hidden for blank notes
                // and under reduce-motion (non-essential chrome — the directive
                // lists it as a "hidden on reduced-motion" affordance). Static
                // text, no motion added.
                if (!reduceMotion && statsText != null) {
                    Text(
                        text = statsText!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            if (showSlashCommands) {
                com.authorss81.noteflow.ui.components.SlashCommandMenuPopup(
                    onSelectCommand = { cmd ->
                        contentText += "\n" + cmd.snippet
                    },
                    onDismiss = { showSlashCommands = false },
                    // Phase 174: slash-menu entry into the same wiki-link
                    // suggestion flow (picker dialog instead of a static snippet).
                    onInsertWikiLink = {
                        showSlashCommands = false
                        ensureWikiLinkTitles()
                        showWikiLinkPicker = true
                    }
                )
            }

            if (showVersionHistory) {
                VersionHistoryBottomSheet(
                    page = page,
                    viewModel = viewModel,
                    // Phase 158 review-fix: version RESTORE writes the note body,
                    // so the sheet is read-only in reader/focus mode — a restore
                    // must leave reader mode first. Browsing history stays.
                    readOnly = readerMode,
                    onRestoreVersion = { restoredVer ->
                        contentText = restoredVer.extractedText ?: ""
                        flushSave()
                    },
                    onDismiss = { showVersionHistory = false }
                )
            }


            if (showSmartAssistant) {
                com.authorss81.noteflow.ui.components.OnDeviceSmartAssistantBottomSheet(
                    page = page,
                    content = contentText,
                    viewModel = viewModel,
                    context = LocalContext.current,
                    onApplyTags = { tags ->
                        val existing = page.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val updated = (existing + tags).distinct().joinToString(",")
                        viewModel.updatePageTags(page.id, updated)
                    },
                    onDismiss = { showSmartAssistant = false }
                )
            }

            if (showBacklinks) {
                BacklinksInspectorBottomSheet(
                    activePage = page,
                    viewModel = viewModel,
                    onOpenPage = onOpenPage,
                    onDismiss = { showBacklinks = false }
                )
            }

            if (showWebSearch) {
                WebSearchDialog(
                    viewModel = viewModel,
                    onInsertLink = { link ->
                        // Append the [title](url) link to the note (never overwrite).
                        contentText = if (contentText.isBlank()) {
                            link
                        } else {
                            contentText.trimEnd() + "\n\n$link\n"
                        }
                        flushSave()
                        viewModel.showSnackbar("Web result inserted into note")
                    },
                    onDismiss = { showWebSearch = false }
                )
            }

            pendingTransformPlugin?.let { plugin ->
                AlertDialog(
                    onDismissRequest = { pendingTransformPlugin = null },
                    // R2-b2b1-UI-02 (phase-140): confirm dialog over an open
                    // decrypted note — dialog window carries its own FLAG_SECURE.
                    properties = secureDialogProperties(),
                    title = { Text("Run ${plugin.name}?") },
                    text = {
                        Text(
                            "This replaces the current note text. A snapshot of the " +
                                "current text is saved to Version History so you can " +
                                "restore it afterwards."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val original = contentText
                            pendingTransformPlugin = null
                            // Run the transform off the main thread: the manager
                            // dispatches the plugin work to a background dispatcher,
                            // so a slow/hung plugin can never block the UI.
                            transformScope.launch {
                                when (val result = viewModel.transformNoteText(original)) {
                                    is PluginResult.Success -> {
                                        if (result.value != original) {
                                            viewModel.createNoteVersion(
                                                page.id,
                                                page.title,
                                                original,
                                                "Before running ${plugin.name}"
                                            )
                                            contentText = result.value
                                            flushSave()
                                        } else {
                                            viewModel.showSnackbar("${plugin.name} produced no change", isLong = false)
                                        }
                                    }
                                    is PluginResult.Failure ->
                                        viewModel.showSnackbar(result.message, isLong = true)
                                    is PluginResult.Unavailable ->
                                        viewModel.showSnackbar(result.message, isLong = true)
                                }
                            }
                        }) { Text("Apply") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingTransformPlugin = null }) { Text("Cancel") }
                    }
                )
            }

            if (showTextTools) {
                TextToolsDialog(
                    viewModel = viewModel,
                    text = contentText,
                    onDismiss = { showTextTools = false }
                )
            }

            if (showLanguageDetection) {
                LanguageDetectionDialog(
                    viewModel = viewModel,
                    text = contentText,
                    existingTags = page.tags,
                    onTagsChanged = { newTags ->
                        contentText = contentText
                        viewModel.updatePageTags(page.id, newTags)
                        viewModel.showSnackbar("Language tag updated")
                    },
                    onDismiss = { showLanguageDetection = false }
                )
            }

            if (showDictation) {
                com.authorss81.noteflow.ui.components.DictationDialog(
                    viewModel = viewModel,
                    context = LocalContext.current,
                    initialText = contentText,
                    onTextChanged = { newText ->
                        contentText = newText
                        flushSave()
                    },
                    onDismiss = { showDictation = false }
                )
            }

            if (showReadAloud) {
                com.authorss81.noteflow.ui.components.ReadAloudDialog(
                    viewModel = viewModel,
                    context = LocalContext.current,
                    text = contentText,
                    onDismiss = { showReadAloud = false }
                )
            }

            if (showTranslation) {
                com.authorss81.noteflow.ui.components.TranslationDialog(
                    viewModel = viewModel,
                    context = LocalContext.current,
                    text = contentText,
                    onReplace = { translated ->
                        val original = contentText
                        if (original != translated) {
                            viewModel.createNoteVersion(
                                page.id,
                                page.title,
                                original,
                                "Before on-device translation"
                            )
                        }
                        contentText = translated
                        flushSave()
                        viewModel.showSnackbar("Note replaced with translation")
                    },
                    onDismiss = { showTranslation = false }
                )
            }

            // Phase 26 — lightweight compile-time plugin dialogs.
            if (showDictionary) {
                DictionaryDialog(
                    viewModel = viewModel,
                    onInsert = { text ->
                        contentText = if (contentText.isBlank()) text else contentText.trimEnd() + "\n\n$text\n"
                        flushSave()
                        viewModel.showSnackbar("Definition inserted into note")
                    },
                    onDismiss = { showDictionary = false }
                )
            }

            if (showWeather) {
                val weatherPluginId = viewModel.pluginRegistry
                    .pluginsForCapability(PluginCapability.Weather)
                    .firstOrNull()?.id
                if (weatherPluginId != null) {
                    WeatherDialog(
                        viewModel = viewModel,
                        pluginId = weatherPluginId,
                        onInsert = { text ->
                            contentText = if (contentText.isBlank()) text else contentText.trimEnd() + "\n\n$text\n"
                            flushSave()
                            viewModel.showSnackbar("Weather snapshot inserted into note")
                        },
                        onDismiss = { showWeather = false }
                    )
                }
            }

            if (showUnitConverter) {
                UnitConverterDialog(
                    viewModel = viewModel,
                    onInsert = { text ->
                        contentText = if (contentText.isBlank()) text else contentText.trimEnd() + "\n\n$text\n"
                        flushSave()
                        viewModel.showSnackbar("Conversion inserted into note")
                    },
                    onDismiss = { showUnitConverter = false }
                )
            }

            if (showOutline) {
                OutlineGeneratorDialog(
                    viewModel = viewModel,
                    sourceText = contentText,
                    onInsert = { text ->
                        contentText = contentText.trimEnd() + "\n\n$text"
                        flushSave()
                        viewModel.showSnackbar("Outline inserted into note")
                    },
                    onDismiss = { showOutline = false }
                )
            }

            if (showCitation) {
                CitationFormatterDialog(
                    viewModel = viewModel,
                    onInsert = { link ->
                        contentText = if (contentText.isBlank()) link else contentText.trimEnd() + "\n\n$link\n"
                        flushSave()
                        viewModel.showSnackbar("Citation inserted into note")
                    },
                    onDismiss = { showCitation = false }
                )
            }

            if (showWikiLinkPicker) {
                WikiLinkPickerDialog(
                    titleTitles = lockedWikiTitles,
                    onSelect = { title ->
                        val snippet = WikiSuggestionPolicy.wikilinkSnippet(title)
                        contentText = if (contentText.isBlank()) {
                            snippet
                        } else {
                            contentText.trimEnd() + "\n\n$snippet\n"
                        }
                        showWikiLinkPicker = false
                        flushSave()
                        viewModel.showSnackbar("Wiki-link inserted into note")
                    },
                    onDismiss = { showWikiLinkPicker = false }
                )
            }
        }
    }
}

private val markdownParser by lazy {
    Parser.builder().extensions(listOf(TablesExtension.create())).build()
}

@Composable
private fun MarkdownRenderedContent(
    content: String,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false,
    // Phase 158 (22.5): reader/focus layout. ALSO provided via LocalReaderMode so
    // the body/heading renderers can widen their leading without threading the
    // flag through every recursive-style signature in this file.
    readerMode: Boolean = false
) {
    val document = remember(content) { markdownParser.parse(content) }
    val scroll = rememberScrollState()

    // ---- Phase 174 (Feature 2): precomputed heading index for quick-jump. The
    // heading refs come from the ALREADY-parsed CommonMark document (never a
    // re-parse); the composable registers each heading's measured content offset
    // during layout, and the rail maps labels back to those offsets.
    // Review-fix (Finding 5): built ONLY for the reader surface where the rail
    // is shown — the preview/split-preview paths never compose the rail, so they
    // skip the heading-index + measure-scope construction entirely.
    val readerHeadingModel = if (readerMode) {
        val headingNodes = remember(content) { collectHeadingNodes(document.childrenList()) }
        remember(headingNodes) {
            val index = HeadingScrollIndex().build(headingNodes.map { it.collectLiteral().trim() to it.level })
            val positions = HashMap<Node, Int>().also { map ->
                headingNodes.forEachIndexed { i, heading -> map[heading] = i }
            }
            ReaderHeadingModel(index, HeadingMeasureScope(index, positions, scroll))
        }
    } else remember { null }

    if (readerMode) {
        // Reader/focus layout: centered, capped to an article measure, widened
        // leading. Read-only by construction — no editor is ever composed inside.
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            CompositionLocalProvider(
                LocalReaderMode provides true,
                LocalHeadingMeasure provides readerHeadingModel?.measureScope
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .widthIn(max = ReaderModePolicy.MAX_COLUMN_WIDTH_DP.dp)
                        .padding(vertical = 4.dp)
                        .onGloballyPositioned { coords -> readerHeadingModel?.measureScope?.onColumnPlaced(coords.boundsInRoot().top) }
                ) {
                    RenderBlocks(
                        children = document.childrenList(),
                        primaryColor = primaryColor,
                        baseDir = baseDir,
                        onOpenWikiLink = onOpenWikiLink,
                        serif = serif
                    )
                }
            }
            // Anchored, collapsible outline rail on the reading surface.
            val outlineIndex = readerHeadingModel?.index
            if (outlineIndex != null && !outlineIndex.isEmpty) {
                ReaderOutlineRail(
                    index = outlineIndex,
                    scrollState = scroll,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 4.dp)
        ) {
            RenderBlocks(
                children = document.childrenList(),
                primaryColor = primaryColor,
                baseDir = baseDir,
                onOpenWikiLink = onOpenWikiLink,
                serif = serif
            )
        }
    }
}

@Composable
private fun RenderBlocks(
    children: Iterable<Node>,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    for (node in children) {
        when (node) {
            is Heading -> {
                val style = when (node.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }
                // Phase 158 (22.5): reader mode widens leading proportionally to the
                // style's OWN already-scaled line height (never an absolute sp
                // override), so system font-scale accessibility is preserved and the
                // reader leading is always wider than the default.
                val baseStyle = serifBodyStyle(style, serif)
                val readerStyle = if (LocalReaderMode.current) {
                    baseStyle.copy(lineHeight = ReaderModePolicy.readerLineHeightSp(baseStyle.fontSize.value, baseStyle.lineHeight.value).sp)
                } else {
                    baseStyle
                }
                // Phase 174 (Feature 2): register this heading's measured offset
                // into the precomputed index so the outline rail can jump here.
                val measure = LocalHeadingMeasure.current
                val headingPosition = measure?.nodePositions?.get(node)
                Text(
                    text = node.collectLiteral(),
                    style = readerStyle.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (node.level <= 3) scheme.primary else scheme.onBackground
                    ),
                    modifier = if (measure != null && headingPosition != null) {
                        Modifier.onGloballyPositioned { coords ->
                            measure.onHeadingPlaced(headingPosition, coords)
                        }
                    } else {
                        Modifier
                    }
                )
            }
            is Paragraph -> MarkdownParagraph(node, primaryColor, onOpenWikiLink, baseDir, serif)
            is FencedCodeBlock, is IndentedCodeBlock -> {
                val codeText = when (node) {
                    is FencedCodeBlock -> node.literal
                    else -> (node as IndentedCodeBlock).literal
                }
                Surface(
                    color = scheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = codeText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            is BulletList -> RenderBlocks(node.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif)
            is OrderedList -> {
                val startNumber = node.startNumber
                val children = node.childrenList()
                for ((index, child) in children.withIndex()) {
                    ListItemView(
                        item = child as? ListItem ?: continue,
                        marker = "${startNumber + index}.",
                        primaryColor = primaryColor,
                        baseDir = baseDir,
                        onOpenWikiLink = onOpenWikiLink,
                        serif = serif
                    )
                }
            }
            is ListItem -> ListItemView(node, "•", primaryColor, baseDir, onOpenWikiLink, serif)
            is BlockQuote -> {
                val quoteText = node.collectLiteral().trim()
                if (quoteText.startsWith("[!")) {
                    // Render Callout Banner
                    val calloutType = quoteText.substringAfter("[").substringBefore("]").uppercase()
                    val calloutBody = quoteText.substringAfter("]").trim()
                    val (bannerColor, bannerTitle, bannerIcon) = when {
                        calloutType.contains("WARNING") || calloutType.contains("CAUTION") ->
                            Triple(Color(0xFFE53935), "WARNING", androidx.compose.material.icons.Icons.Outlined.Edit)
                        calloutType.contains("TIP") ->
                            Triple(Color(0xFF43A047), "TIP", androidx.compose.material.icons.Icons.Outlined.Edit)
                        calloutType.contains("IMPORTANT") ->
                            Triple(Color(0xFF8E24AA), "IMPORTANT", androidx.compose.material.icons.Icons.Outlined.Edit)
                        else ->
                            Triple(primaryColor, "NOTE", androidx.compose.material.icons.Icons.Outlined.Edit)
                    }

                    Surface(
                        color = bannerColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, bannerColor.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = bannerTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = bannerColor
                                )
                            }
                            if (calloutBody.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = calloutBody,
                                    style = serifBodyStyle(MaterialTheme.typography.bodyMedium, serif),
                                    color = scheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(primaryColor.copy(alpha = 0.5f))
                        )
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            RenderBlocks(node.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif)
                        }
                    }
                }
            }
            is TableBlock -> MarkdownTable(node, serif)
            is ThematicBreak -> HorizontalDivider(color = scheme.outline)
            is HtmlBlock -> {
                val html = node.literal ?: ""
                if (html.contains("<details>", ignoreCase = true)) {
                    var expanded by remember { mutableStateOf(false) }
                    val summaryText = html.substringAfter("<summary>", "Toggle Details").substringBefore("</summary>").trim()
                    val detailsText = html.substringAfter("</summary>", "").substringBefore("</details>").trim()

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "▶ $summaryText",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.primary
                                )
                                Text(if (expanded) "Collapse" else "Expand", style = MaterialTheme.typography.labelSmall)
                            }
                            if (expanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = detailsText, style = serifBodyStyle(MaterialTheme.typography.bodyMedium, serif))
                            }
                        }
                    }
                } else {
                    Text(
                        text = html,
                        style = MaterialTheme.typography.bodySmall.copy(color = scheme.onSurfaceVariant)
                    )
                }
            }
            else -> Text(
                text = node.collectLiteral(),
                style = serifBodyStyle(MaterialTheme.typography.bodyLarge, serif).copy(color = scheme.onBackground)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun ListItemView(
    item: ListItem,
    marker: String,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            style = serifBodyStyle(MaterialTheme.typography.bodyLarge, serif).copy(
                color = primaryColor,
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier.padding(end = 8.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            RenderBlocks(item.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif)
        }
    }
}

@Composable
private fun MarkdownTable(node: TableBlock, serif: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        for (child in node.childrenList()) {
            when (child) {
                is TableHead -> {
                    for (rowNode in child.childrenList()) {
                        val row = rowNode as? TableRow ?: continue
                        Row(modifier = Modifier.background(scheme.surfaceVariant)) {
                            for (cell in row.childrenList()) {
                                val tableCell = cell as? TableCell ?: continue
                                TableCellView(tableCell, isHeader = true, serif = serif)
                            }
                        }
                    }
                    HorizontalDivider(color = scheme.primary)
                }
                is TableBody -> {
                    for (rowNode in child.childrenList()) {
                        val row = rowNode as? TableRow ?: continue
                        Row {
                            for (cell in row.childrenList()) {
                                val tableCell = cell as? TableCell ?: continue
                                TableCellView(tableCell, isHeader = false, serif = serif)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableCellView(cell: TableCell, isHeader: Boolean, serif: Boolean = false) {
    Text(
        text = cell.collectLiteral(),
        style = if (isHeader) {
            serifBodyStyle(MaterialTheme.typography.bodyMedium, serif).copy(fontWeight = FontWeight.Bold)
        } else {
            serifBodyStyle(MaterialTheme.typography.bodyMedium, serif)
        },
        modifier = Modifier
            .weight(1f)
            .padding(6.dp)
    )
}

@Composable
private fun MarkdownParagraph(
    paragraph: Paragraph,
    primaryColor: Color,
    onOpenWikiLink: (String) -> Unit,
    baseDir: File?,
    serif: Boolean = false
) {
    val context = LocalContext.current
    val text = paragraph.collectLiteral()

    if (text.trim().startsWith("$$") && text.trim().endsWith("$$") && text.trim().length >= 4) {
        val mathContent = text.trim().removePrefix("$$").removeSuffix("$$").trim()
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "LaTeX Math Expression",
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryColor,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = mathContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    // 21.6 & 24.3: images are rendered as inline composables below the paragraph text.
    val images = remember(paragraph) { paragraph.childrenList().filterIsInstance<Image>() }
    val embeddedObsidianImages = remember(text) {
        val regex = Regex("!\\[\\[([^\\]]+)\\]\\]")
        regex.findAll(text).map { match ->
            match.groupValues[1].trim()
        }.filter { target ->
            val ext = target.substringAfterLast('.', "").lowercase()
            ext in listOf("png", "jpg", "jpeg", "webp", "gif", "svg")
        }.toList()
    }
    val annotated = remember(text, primaryColor) {
        val links = WikiLinkParser.extractWikiLinks(text)
        buildAnnotatedString {
            var currentIndex = 0
            for (link in links) {
                if (link.startIndex > currentIndex) {
                    appendInlineFragment(text.substring(currentIndex, link.startIndex), primaryColor, this)
                }
                pushStringAnnotation(tag = "WIKILINK", annotation = link.targetTitle)
                withStyle(
                    style = SpanStyle(
                        color = primaryColor,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(link.alias ?: link.targetTitle)
                }
                pop()
                currentIndex = link.endIndex
            }
            if (currentIndex < text.length) {
                appendInlineFragment(text.substring(currentIndex), primaryColor, this)
            }
        }
    }
    Column {
        // Phase 158 (22.5): reader mode widens leading proportionally to the
        // already-scaled body line height (see ReaderModePolicy).
        val baseBodyStyle = serifBodyStyle(MaterialTheme.typography.bodyLarge, serif)
        val bodyStyle = if (LocalReaderMode.current) {
            baseBodyStyle.copy(lineHeight = ReaderModePolicy.readerLineHeightSp(baseBodyStyle.fontSize.value, baseBodyStyle.lineHeight.value).sp)
        } else {
            baseBodyStyle
        }
        ClickableText(
            text = annotated,
            style = bodyStyle.copy(color = MaterialTheme.colorScheme.onBackground),
            onClick = { offset ->
                val wikiLink = annotated.getStringAnnotations(tag = "WIKILINK", start = offset, end = offset)
                    .firstOrNull()
                if (wikiLink != null) {
                    onOpenWikiLink(wikiLink.item)
                    return@ClickableText
                }
                annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()
                    ?.let { urlAnnotation ->
                        val destination = urlAnnotation.item
                        if (destination.startsWith("http://") || destination.startsWith("https://")) {
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(destination)))
                            } catch (e: Exception) {
                                // No browser available — ignore.
                            }
                        }
                    }
            }
        )
        images.forEach { image ->
            Spacer(modifier = Modifier.height(6.dp))
            com.authorss81.noteflow.ui.components.MarkdownInlineImage(
                destination = image.destination,
                alt = (image.firstChild as? Text)?.literal,
                baseDir = baseDir
            )
        }
        embeddedObsidianImages.forEach { imageName ->
            Spacer(modifier = Modifier.height(6.dp))
            com.authorss81.noteflow.ui.components.MarkdownInlineImage(
                destination = imageName,
                alt = imageName,
                baseDir = baseDir
            )
        }
    }
}

private fun appendInlineFragment(
    fragment: String,
    primaryColor: Color,
    builder: AnnotatedString.Builder
) {
    if (fragment.isEmpty()) return
    val doc = markdownParser.parse(fragment)
    for (child in doc.childrenList()) {
        renderInline(child, primaryColor, builder)
    }
}

private fun renderInline(node: Node, primaryColor: Color, builder: AnnotatedString.Builder) {
    when (node) {
        is Text -> builder.append(node.literal)
        is Emphasis -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.childrenList().forEach { renderInline(it, primaryColor, this) }
        }
        is StrongEmphasis -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.childrenList().forEach { renderInline(it, primaryColor, this) }
        }
        is Code -> builder.withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = primaryColor,
                background = Color(0x22000000)
            )
        ) { append(node.literal) }
        is Link -> {
            builder.pushStringAnnotation(tag = "URL", annotation = node.destination ?: "")
            builder.withStyle(
                SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                node.childrenList().forEach { renderInline(it, primaryColor, this) }
            }
            builder.pop()
        }
        is Image -> builder.append((node.firstChild as? Text)?.literal ?: "[image]")
        is SoftLineBreak -> builder.append("\n")
        is HardLineBreak -> builder.append("\n")
        is HtmlInline -> builder.append(node.literal)
        else -> {
            val children = node.childrenList()
            if (children.iterator().hasNext()) {
                children.forEach { renderInline(it, primaryColor, builder) }
            } else {
                builder.append(node.collectLiteral())
            }
        }
    }
}

/**
 * Phase 15 (Text Tools): dialog showing structural statistics of the current
 * note text. Runs the pure-JVM analyzer through the plugin manager; failures
 * surface the plugin's message instead of throwing.
 */
@Composable
private fun TextToolsDialog(
    viewModel: NoteflowViewModel,
    text: String,
    onDismiss: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var wordCount by remember { mutableStateOf(0) }
    var charCount by remember { mutableStateOf(0) }
    var paraCount by remember { mutableStateOf(0) }
    var sentCount by remember { mutableStateOf(0) }
    var readSecs by remember { mutableStateOf(0) }
    var fkGrade by remember { mutableStateOf(0.0) }
    var fkLabel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        when (val result = viewModel.analyzeNoteText(text)) {
            is PluginResult.Success -> {
                val a = result.value
                wordCount = a.wordCount
                charCount = a.characterCount
                paraCount = a.paragraphCount
                sentCount = a.sentenceCount
                readSecs = a.readingTimeSeconds
                fkGrade = a.fleschKincaid
                fkLabel = a.fleschKincaidLabel
            }
            is PluginResult.Failure -> error = result.message
            is PluginResult.Unavailable -> error = result.message
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // R2-b2b1-UI-02 (phase-140): shows decrypted note statistics/text over an
        // open note — the dialog window must carry FLAG_SECURE itself in release.
        properties = secureDialogProperties(),
        title = { Text("Text Tools") },
        text = {
            when {
                loading -> Text("Analyzing…")
                error != null -> Text(error ?: "Text Tools unavailable.")
                else -> Column {
                    StatRow("Words", wordCount.toString())
                    StatRow("Characters", charCount.toString())
                    StatRow("Paragraphs", paraCount.toString())
                    StatRow("Sentences", sentCount.toString())
                    StatRow("Reading time", "${readSecs / 60}:${(readSecs % 60).toString().padStart(2, '0')} min")
                    StatRow("Flesch-Kincaid", "$fkGrade ($fkLabel)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Phase 15 (Language Detection): detect the note's language and, on user
 * confirmation, merge a `lang:<iso>` tag into the note's tags (respecting an
 * existing `lang:*`/`language:*` override, which [LanguageDetectionPlugin]
 * never overwrites).
 */
@Composable
private fun LanguageDetectionDialog(
    viewModel: NoteflowViewModel,
    text: String,
    existingTags: String,
    onTagsChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detection by remember {
        mutableStateOf<com.authorss81.noteflow.plugins.DetectedLanguage?>(null)
    }

    LaunchedEffect(Unit) {
        when (val result = viewModel.detectNoteLanguage(text)) {
            is PluginResult.Success ->
                when (val d = result.value) {
                    is com.authorss81.noteflow.plugins.LanguageDetectionOutcome.Success ->
                        detection = d.language
                    is com.authorss81.noteflow.plugins.LanguageDetectionOutcome.NoMatch ->
                        error = d.message
                    is com.authorss81.noteflow.plugins.LanguageDetectionOutcome.Error ->
                        error = d.message
                }
            is PluginResult.Failure -> error = result.message
            is PluginResult.Unavailable -> error = result.message
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // R2-b2b1-UI-02 (phase-140): shows a sample of the note's text — the
        // dialog window must carry FLAG_SECURE itself in release.
        properties = secureDialogProperties(),
        title = { Text("Language Detection") },
        text = {
            when {
                loading -> Text("Detecting…")
                error != null -> Text(error ?: "Detection unavailable.")
                detection != null -> {
                    val lang = detection!!
                    Column {
                        Text("Detected language: ${lang.displayName} (${lang.isoCode})")
                        Text(
                            "Confidence: ${(lang.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (existingTags.split(",").any { it.trim().lowercase().startsWith("lang:") }) {
                                "A language tag already exists — it will be left untouched."
                            } else {
                                "Tags will gain: lang:${lang.isoCode} (only on Apply)."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = detection != null,
                onClick = {
                    val lang = detection ?: return@TextButton
                    scope.launch {
                        when (val merged = viewModel.autoTagNoteLanguage(text, existingTags)) {
                            is PluginResult.Success -> onTagsChanged(merged.value)
                            is PluginResult.Failure -> viewModel.showSnackbar(merged.message, isLong = true)
                            is PluginResult.Unavailable -> viewModel.showSnackbar(merged.message, isLong = true)
                        }
                    }
                    onDismiss()
                }
            ) { Text("Apply tag") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
