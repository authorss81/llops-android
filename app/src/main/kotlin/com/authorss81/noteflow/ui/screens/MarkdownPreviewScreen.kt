package com.authorss81.noteflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.theme.serifBodyStyle
import com.authorss81.noteflow.ui.components.BacklinksInspectorBottomSheet
import com.authorss81.noteflow.ui.components.markdown.HybridMarkdownEditor
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.io.File
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownPreviewScreen(
    page: NotePageEntity,
    initialContent: String,
    viewModel: NoteflowViewModel,
    onBack: () -> Unit,
    onOpenWikiLink: (String) -> Unit,
    onOpenPage: (NotePageEntity) -> Unit,
    onSaveContent: (String) -> Unit
) {
    var viewMode by remember { mutableStateOf(MarkdownViewMode.SPLIT) }
    var splitOrientation by remember { mutableStateOf(SplitOrientation.AUTO) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
                    IconButton(onClick = { showSmartAssistant = true }) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "On-Device Smart Assistant", tint = primaryColor)
                    }
                    IconButton(onClick = { showBacklinks = true }) {
                        Icon(Icons.Outlined.Hub, contentDescription = "Backlinks & Knowledge Connections", tint = primaryColor)
                    }
                    IconButton(
                        onClick = {
                            flushSave()
                            viewModel.createNoteVersion(page.id, page.title, contentText, "Manual save in Live Editor")
                        }
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = "Save Content")
                    }
                    if (viewMode != MarkdownViewMode.EDIT) {
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
                    Box {
                        IconButton(onClick = { showPluginMenu = true }) {
                            Icon(Icons.Outlined.Extension, contentDescription = "Plugins", tint = primaryColor)
                        }
                        DropdownMenu(expanded = showPluginMenu, onDismissRequest = { showPluginMenu = false }) {
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
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .imePadding()
        ) {
            when (viewMode) {
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
                            serif = serifReadingMode
                        )
                    }
                }

                MarkdownViewMode.PREVIEW -> {
                    MarkdownRenderedContent(
                        content = contentText,
                        primaryColor = primaryColor,
                        baseDir = baseDir,
                        onOpenWikiLink = onOpenWikiLink,
                        serif = serifReadingMode
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
                                    serif = serifReadingMode
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
                                    serif = serifReadingMode
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

            if (showSlashCommands) {
                com.authorss81.noteflow.ui.components.SlashCommandMenuPopup(
                    onSelectCommand = { cmd ->
                        contentText += "\n" + cmd.snippet
                    },
                    onDismiss = { showSlashCommands = false }
                )
            }

            if (showVersionHistory) {
                VersionHistoryBottomSheet(
                    page = page,
                    viewModel = viewModel,
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
    serif: Boolean = false
) {
    val document = remember(content) { markdownParser.parse(content) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                Text(
                    text = node.collectLiteral(),
                    style = serifBodyStyle(style, serif).copy(
                        fontWeight = FontWeight.Bold,
                        color = if (node.level <= 3) scheme.primary else scheme.onBackground
                    )
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
        ClickableText(
            text = annotated,
            style = serifBodyStyle(MaterialTheme.typography.bodyLarge, serif).copy(color = MaterialTheme.colorScheme.onBackground),
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
