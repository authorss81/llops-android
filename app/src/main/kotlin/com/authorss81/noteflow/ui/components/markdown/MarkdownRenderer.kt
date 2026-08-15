package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.CalloutType
import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownInlineMath
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.theme.serifBodyStyle
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
import java.io.File

/**
 * Phase 37 — the SINGLE markdown renderer shared by the preview AND the hybrid
 * editor. Extracted from `MarkdownPreviewScreen` so "rendered output equals the
 * preview engine's output" holds by construction: both code paths parse with the
 * same CommonMark parser and style the same node types.
 *
 * New in Phase 37 on top of the old preview renderer:
 *  - typed callout cards (`NOTE` / `WARNING` / `TIP` / `IMPORTANT` / `QUOTE`)
 *    with matching icons/colors (old code used a stub [Icons.Outlined.Edit]);
 *  - interactive checkboxes for `- [ ]` / `- [x]` list items ([AnimatedCheckmark],
 *    toggling wired through [onToggleCheckbox] with document-order indexes);
 *  - inline math highlighting for `$...$` / `$$...$$` runs outside code spans.
 */

internal val markdownRendererParser by lazy {
    Parser.builder().extensions(listOf(TablesExtension.create())).build()
}

/** Child list accessor — CommonMark 0.29+ removed Node.getChildren(). */
internal fun Node.childrenList(): List<Node> {
    val list = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        list.add(child)
        child = child.next
    }
    return list
}

/** Plain-text content of a node (replaces removed TextContent.getChildText). */
internal fun Node.collectLiteral(): String {
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

/**
 * Document-order cursor over [MarkdownBlockTokenizer.checkboxCandidates] global
 * indexes. The hybrid editor hands each rendered block a cursor seeded with that
 * block's candidates; the renderer pops one per checkbox it draws, so toggling
 * always writes back to the correct source line.
 */
internal class MarkdownCheckboxCursor(
    val order: List<Int>,
    var pos: Int = 0
) {
    fun nextOrNull(): Int? = if (pos < order.size) order[pos++] else null
}

private val checkboxPrefixRe = Regex("""^\[([ xX])\]\s""")

@Composable
fun MarkdownDocument(
    content: String,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false,
    cursor: MarkdownCheckboxCursor? = null,
    onToggleCheckbox: ((Int) -> Unit)? = null
) {
    val document = remember(content) { markdownRendererParser.parse(content) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        MarkdownRenderBlocks(
            children = document.childrenList(),
            primaryColor = primaryColor,
            baseDir = baseDir,
            onOpenWikiLink = onOpenWikiLink,
            serif = serif,
            cursor = cursor,
            onToggleCheckbox = onToggleCheckbox
        )
    }
}

@Composable
internal fun MarkdownRenderBlocks(
    children: Iterable<Node>,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false,
    cursor: MarkdownCheckboxCursor? = null,
    onToggleCheckbox: ((Int) -> Unit)? = null
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
            is BulletList -> MarkdownRenderBlocks(
                node.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif, cursor, onToggleCheckbox
            )
            is OrderedList -> {
                val startNumber = node.startNumber
                val children = node.childrenList()
                for ((index, child) in children.withIndex()) {
                    MarkdownListItemView(
                        item = child as? ListItem ?: continue,
                        marker = "${startNumber + index}.",
                        primaryColor = primaryColor,
                        baseDir = baseDir,
                        onOpenWikiLink = onOpenWikiLink,
                        serif = serif,
                        cursor = cursor,
                        onToggleCheckbox = onToggleCheckbox
                    )
                }
            }
            is ListItem -> MarkdownListItemView(
                node, "•", primaryColor, baseDir, onOpenWikiLink, serif, cursor, onToggleCheckbox
            )
            is BlockQuote -> {
                val quoteText = node.collectLiteral().trim()
                val callout = MarkdownBlockTokenizer.calloutOf(quoteText)
                if (callout != null) {
                    MarkdownCalloutCard(callout.type, callout.body, primaryColor, serif)
                } else {
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(primaryColor.copy(alpha = 0.5f))
                        )
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            MarkdownRenderBlocks(node.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif, cursor, onToggleCheckbox)
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
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
private fun MarkdownCalloutCard(
    type: CalloutType,
    body: String,
    primaryColor: Color,
    serif: Boolean
) {
    val (calloutColor, calloutTitle, calloutIcon): Triple<Color, String, ImageVector> = when (type) {
        CalloutType.WARNING -> Triple(Color(0xFFE53935), "WARNING", Icons.Outlined.Warning)
        CalloutType.TIP -> Triple(Color(0xFF43A047), "TIP", Icons.Outlined.Lightbulb)
        CalloutType.IMPORTANT -> Triple(Color(0xFF8E24AA), "IMPORTANT", Icons.Outlined.ErrorOutline)
        CalloutType.QUOTE -> Triple(Color(0xFF546E7A), "QUOTE", Icons.Outlined.FormatQuote)
        CalloutType.NOTE -> Triple(primaryColor, "NOTE", Icons.Outlined.Info)
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = calloutColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = BorderStroke(1.dp, calloutColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = calloutIcon,
                    contentDescription = null,
                    tint = calloutColor,
                    modifier = Modifier.width(18.dp).height(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = calloutTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = calloutColor
                )
            }
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    style = serifBodyStyle(MaterialTheme.typography.bodyMedium, serif),
                    color = scheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MarkdownListItemView(
    item: ListItem,
    marker: String,
    primaryColor: Color,
    baseDir: File?,
    onOpenWikiLink: (String) -> Unit,
    serif: Boolean = false,
    cursor: MarkdownCheckboxCursor? = null,
    onToggleCheckbox: ((Int) -> Unit)? = null
) {
    // Checkbox detection: CommonMark strips the bullet marker, so a `- [ ] task`
    // item's first paragraph literal is "[ ] task".
    val firstParagraph = item.firstChild as? Paragraph
    val firstText = firstParagraph?.collectLiteral()?.trimStart()
    val checkboxMatch = firstText?.let { checkboxPrefixRe.find(it) }
    if (checkboxMatch != null) {
        val restText = firstText!!.substring(checkboxMatch.range.last)
        val checked = checkboxMatch.groupValues[1].trim() in setOf("x", "X")
        val candidateIndex = cursor?.nextOrNull()
        Row(modifier = Modifier.fillMaxWidth()) {
            AnimatedCheckmark(
                checked = checked,
                enabled = onToggleCheckbox != null,
                checkedColor = primaryColor,
                onToggle = if (onToggleCheckbox != null && candidateIndex != null) {
                    { onToggleCheckbox(candidateIndex) }
                } else {
                    null
                },
                modifier = Modifier.padding(end = 8.dp).padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                val annotated = remember(restText, primaryColor) {
                    buildMarkdownAnnotatedString(restText, primaryColor)
                }
                val context = LocalContext.current
                ClickableText(
                    text = annotated,
                    style = serifBodyStyle(MaterialTheme.typography.bodyLarge, serif)
                        .copy(color = MaterialTheme.colorScheme.onBackground),
                    onClick = { offset ->
                        val wiki = annotated.getStringAnnotations(tag = "WIKILINK", start = offset, end = offset)
                            .firstOrNull()
                        if (wiki != null) {
                            onOpenWikiLink(wiki.item)
                            return@ClickableText
                        }
                        annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()
                            ?.let { url ->
                                if (url.item.startsWith("http://") || url.item.startsWith("https://")) {
                                    try {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url.item)
                                            )
                                        )
                                    } catch (e: Exception) {
                                        // No browser available — ignore.
                                    }
                                }
                            }
                    }
                )
                // Any sibling nodes of the checkbox paragraph (rare: nested lists).
                if (firstParagraph.next != null) {
                    MarkdownRenderBlocks(
                        item.childrenList().drop(1),
                        primaryColor,
                        baseDir,
                        onOpenWikiLink,
                        serif,
                        cursor,
                        onToggleCheckbox
                    )
                }
            }
        }
        return
    }

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
            MarkdownRenderBlocks(item.childrenList(), primaryColor, baseDir, onOpenWikiLink, serif, cursor, onToggleCheckbox)
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
                                MarkdownTableCellView(tableCell, isHeader = true, serif = serif)
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
                                MarkdownTableCellView(tableCell, isHeader = false, serif = serif)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MarkdownTableCellView(
    cell: TableCell,
    isHeader: Boolean,
    serif: Boolean = false
) {
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
                Text(
                    text = "Inline highlighting is on; full LaTeX typesetting is deferred.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
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
        buildMarkdownAnnotatedString(text, primaryColor)
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
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(destination)
                                    )
                                )
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

/**
 * Build the inline-styled annotated text for a paragraph (or a checkbox item
 * remainder): WikiLinks + bold/italic/code/links + inline math highlighting.
 * Math runs are located on the normalized literal and rendered outside any code
 * span; highlighting never alters the underlying characters.
 */
internal fun buildMarkdownAnnotatedString(text: String, primaryColor: Color): AnnotatedString {
    val links = WikiLinkParser.extractWikiLinks(text)
    val codeRanges = MarkdownInlineMath.findCodeRanges(text)
    val mathRuns = MarkdownInlineMath.findMathRuns(text, codeRanges)
    return buildAnnotatedString {
        var currentIndex = 0
        for (link in links) {
            if (link.startIndex > currentIndex) {
                appendInlineFragment(
                    text.substring(currentIndex, link.startIndex),
                    primaryColor,
                    this,
                    mathRuns,
                    currentIndex
                )
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
            appendInlineFragment(
                text.substring(currentIndex),
                primaryColor,
                this,
                mathRuns,
                currentIndex
            )
        }
    }
}

private class InlineOffsetTracker(var offset: Int)

private fun appendInlineFragment(
    fragment: String,
    primaryColor: Color,
    builder: AnnotatedString.Builder,
    mathRuns: List<com.authorss81.noteflow.services.MathRun>,
    absoluteStart: Int
) {
    if (fragment.isEmpty()) return
    val doc = markdownRendererParser.parse(fragment)
    val track = InlineOffsetTracker(absoluteStart)
    for (child in doc.childrenList()) {
        renderInlineBlock(child, primaryColor, builder, mathRuns, track)
    }
}

private fun renderInlineBlock(
    node: Node,
    primaryColor: Color,
    builder: AnnotatedString.Builder,
    mathRuns: List<com.authorss81.noteflow.services.MathRun>,
    track: InlineOffsetTracker
) {
    when (node) {
        is Text -> {
            val literal = node.literal
            builder.appendWithMath(literal, track.offset, mathRuns, primaryColor)
            track.offset += literal.length
        }
        is Emphasis -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.childrenList().forEach { renderInlineBlock(it, primaryColor, this, mathRuns, track) }
        }
        is StrongEmphasis -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.childrenList().forEach { renderInlineBlock(it, primaryColor, this, mathRuns, track) }
        }
        is Code -> builder.withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = primaryColor,
                background = Color(0x22000000)
            )
        ) {
            append(node.literal)
            track.offset += node.literal.length
        }
        is Link -> {
            builder.pushStringAnnotation(tag = "URL", annotation = node.destination ?: "")
            builder.withStyle(
                SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                node.childrenList().forEach { renderInlineBlock(it, primaryColor, this, mathRuns, track) }
            }
            builder.pop()
        }
        is Image -> {
            val alt = (node.firstChild as? Text)?.literal ?: "[image]"
            builder.append(alt)
            track.offset += alt.length
        }
        is SoftLineBreak -> {
            builder.append("\n")
            track.offset++
        }
        is HardLineBreak -> {
            builder.append("\n")
            track.offset++
        }
        is HtmlInline -> {
            builder.append(node.literal)
            track.offset += node.literal.length
        }
        else -> {
            val children = node.childrenList()
            if (children.iterator().hasNext()) {
                children.forEach { renderInlineBlock(it, primaryColor, builder, mathRuns, track) }
            } else {
                val literal = node.collectLiteral()
                builder.append(literal)
                track.offset += literal.length
            }
        }
    }
}

private fun AnnotatedString.Builder.appendWithMath(
    literal: String,
    absoluteStart: Int,
    mathRuns: List<com.authorss81.noteflow.services.MathRun>,
    primaryColor: Color
) {
    if (mathRuns.isEmpty()) {
        append(literal)
        return
    }
    var cursor = 0
    for (run in mathRuns) {
        if (run.endIndex < absoluteStart) continue
        val runLocalStart = run.startIndex - absoluteStart
        if (runLocalStart >= literal.length) break
        val localStart = runLocalStart.coerceAtLeast(0)
        val localEnd = (run.endIndex - absoluteStart + 1).coerceAtMost(literal.length)
        if (localEnd <= localStart) continue
        if (localStart > cursor) append(literal.substring(cursor, localStart))
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                color = primaryColor,
                background = primaryColor.copy(alpha = 0.12f)
            )
        ) {
            append(literal.substring(localStart, localEnd))
        }
        cursor = localEnd
    }
    if (cursor < literal.length) append(literal.substring(cursor))
}