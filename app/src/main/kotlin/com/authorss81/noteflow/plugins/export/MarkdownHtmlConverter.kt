package com.authorss81.noteflow.plugins.export

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Pure-JVM Markdown → HTML / plain-text conversion built on the app's existing
 * CommonMark parser (the same one MarkdownPreviewScreen uses for rendering).
 *
 * `toHtml` renders into fragment HTML (no `<html>`/`<head>` wrapper); the
 * [ExportPayloadAssembler] wraps it into a full document. `toPlainText` reduces
 * the document to a readable plain-text body used for the PDF export path.
 *
 * Everything here is unit-testable on the JVM with no Android classes.
 */
object MarkdownHtmlConverter {

    private val parser: Parser by lazy {
        Parser.builder().extensions(listOf(TablesExtension.create())).build()
    }
    private val htmlRenderer: HtmlRenderer by lazy {
        // The GFM Tables extension registers both a block parser AND an HTML
        // node renderer (TableHtmlNodeRenderer) — register it here too or the
        // <table> markup wouldn't be emitted.
        HtmlRenderer.builder().extensions(listOf(TablesExtension.create())).build()
    }

    /** Parse [markdown] and render it to an HTML fragment ("" for blank input). */
    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        return htmlRenderer.render(parser.parse(markdown))
    }

    /**
     * Reduce [markdown] to plain text with paragraph breaks between blocks,
     * code blocks kept verbatim, and inline syntax stripped. Blank input returns
     * "". Deterministic and pure.
     */
    fun toPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val document = parser.parse(markdown)
        val out = StringBuilder()
        collectBlock(document, out, depth = 0)
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun collectBlock(node: Node, out: StringBuilder, depth: Int) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                is SoftLineBreak -> out.append(' ')
                is HardLineBreak -> out.append('\n')
                is HtmlInline -> Unit // strip raw inline HTML
                is HtmlBlock -> Unit // strip raw block HTML
                is FencedCodeBlock -> {
                    out.append('\n').append(child.literal.trimEnd()).append('\n')
                }
                is IndentedCodeBlock -> {
                    out.append('\n').append(child.literal.trimEnd()).append('\n')
                }
                else -> {
                    // Block element (paragraph, heading, list, quote…): recurse
                    // into its children, then separate blocks with a blank line.
                    collectBlock(child, out, depth + 1)
                    out.append("\n\n")
                }
            }
            child = child.next
        }
    }

    /**
     * Escape a plain string for safe embedding in HTML text/title content.
     * Used by [ExportPayloadAssembler] when a note has no markdown but a plain
     * text body. Pure and unit-tested.
     */
    fun escapeHtml(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}