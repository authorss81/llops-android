package com.authorss81.noteflow.plugins.webcapture

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

/**
 * Pure-JVM (jsoup) extraction of readable content from arbitrary HTML into
 * Markdown. No network access — feed it a captured document string.
 *
 * Strategy:
 *  - strip chrome (scripts, styles, nav, headers/footers, ads).
 *  - prefer a well-known content container; fall back to the whole body.
 *  - convert the container's structure to Markdown (headings, paragraphs,
 *    lists, blockquotes, code, links, images, tables kept as HTML).
 */
object WebToMarkdownExtractor {

    private val contentSelectors = listOf(
        "article", "main", ".article", ".post", ".post-content", ".entry-content",
        ".article-body", ".story-body", "[role=main]"
    )

    /** @return the extracted [Extract]. [Extract.markdown] is blank when no readable content was found. */
    fun extract(html: String, baseUri: String? = null): Extract {
        val doc = Jsoup.parse(html, baseUri ?: "")
        stripChrome(doc)
        val container = pickContainer(doc) ?: return Extract(title = title(doc).orEmpty(), markdown = "")
        val title = title(doc)?.takeIf { it.isNotBlank() } ?: firstHeading(container) ?: ""
        val markdown = convertContainer(container)
        return Extract(
            title = title.trim(),
            markdown = markdown.trim(),
            url = baseUri
        )
    }

    data class Extract(val title: String, val markdown: String, val url: String? = null)

    private fun stripChrome(doc: org.jsoup.nodes.Document) {
        doc.select(
            "script, style, noscript, template, iframe, svg, canvas, form, button, " +
                "nav, header, footer, aside, [class~=(?i)(^|\\s)(ad|ads|advert|banner|promo|social|share|related|recommended)(\\s|$)]"
        ).remove()
        doc.select("[onclick], [onload], [onerror]").removeAttr("onclick").removeAttr("onload").removeAttr("onerror")
    }

    private fun pickContainer(doc: org.jsoup.nodes.Document): Element? {
        for (selector in contentSelectors) {
            val el = doc.selectFirst(selector) ?: continue
            if (el.text().length > 40) return el
        }
        return doc.body()?.takeIf { it.text().trim().isNotEmpty() }
    }

    private fun title(doc: org.jsoup.nodes.Document): String? =
        doc.title().trim().ifEmpty { null }

    private fun firstHeading(container: Element): String? =
        container.select("h1, h2").firstOrNull()?.text()?.trim()?.ifEmpty { null }

    private fun convertContainer(container: Element): String {
        val sb = StringBuilder()
        convert(container, sb, 0)
        return collapseBreaks(sb.toString())
    }

    private fun convert(el: Element, sb: StringBuilder, depth: Int) {
        for (child in el.children()) {
            when (val tag = child.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val text = inline(child).trim()
                    if (text.isNotEmpty()) {
                        sb.append("#".repeat(tag[1] - '0')).append(' ').append(text).append("\n\n")
                    }
                }
                "p", "div" -> {
                    val text = inline(child).trim()
                    if (text.isNotEmpty()) sb.append(text).append("\n\n")
                }
                "ul", "ol" -> appendList(child, sb, depth)
                "blockquote" -> {
                    val text = inline(child).trim()
                    if (text.isNotEmpty()) {
                        sb.append(text.lineSequence().joinToString("\n") { "> ${it.trim()}" }).append("\n\n")
                    }
                }
                "pre" -> {
                    val code = child.text()
                    if (code.isNotBlank()) {
                        sb.append("```\n").append(code).append("\n```\n\n")
                    }
                }
                "img" -> {
                    val alt = child.attr("alt").trim()
                    val src = child.attr("abs:src").ifEmpty { child.attr("src") }
                    if (src.isNotBlank()) {
                        sb.append("![").append(alt).append("](").append(src).append(")\n\n")
                    }
                }
                "hr" -> sb.append("---\n\n")
                "br" -> sb.append("\n")
                else -> {
                    // Unknown/non-structural element (article, section, span,
                    // custom tags, and on the jsoup-1.23.1 fixed line control-
                    // char "script<ESC>") — emit its DIRECT text as inert content,
                    // then recurse into its children. Direct text is captured
                    // here so it is never silently dropped (B2-DEPS-01: the CVE
                    // payload's content must survive as plain text, never be
                    // re-serialized as active markup); containers carry no direct
                    // text, so nothing is duplicated.
                    for (node in child.childNodes()) {
                        if (node is TextNode) {
                            val direct = node.text().trim()
                            if (direct.isNotEmpty()) sb.append(direct).append("\n\n")
                        }
                    }
                    convert(child, sb, depth)
                }
            }
        }
    }

    private fun appendList(list: Element, sb: StringBuilder, depth: Int) {
        val ordered = list.tagName() == "ol"
        var index = 1
        for (li in list.children()) {
            if (li.tagName() != "li") continue
            val prefix = if (ordered) "${index++}. " else "- "
            val nested = li.children().firstOrNull { it.tagName() == "ul" || it.tagName() == "ol" }
            val text = buildList {
                for (node in li.childNodes()) {
                    if (node is Element && (node.tagName() == "ul" || node.tagName() == "ol")) continue
                    if (node is TextNode) { add(node.text()); continue }
                    if (node is Element) add(inline(node))
                }
            }.joinToString("").trim()
            sb.append("  ".repeat(depth)).append(prefix).append(text).append("\n")
            if (nested != null) appendList(nested, sb, depth + 1)
        }
        sb.append("\n")
    }

    private fun inline(el: Element): String {
        val sb = StringBuilder()
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> when (node.tagName()) {
                    "a" -> {
                        val text = inline(node).trim()
                        val href = node.attr("abs:href").ifEmpty { node.attr("href") }
                        if (text.isNotEmpty()) {
                            sb.append("[").append(text).append("](").append(href).append(")")
                        }
                    }
                    "code" -> sb.append("`").append(node.text()).append("`")
                    "strong", "b" -> wrapInline(sb, "**", node)
                    "em", "i" -> wrapInline(sb, "*", node)
                    "br" -> sb.append("\n")
                    "img" -> {
                        val alt = node.attr("alt").trim()
                        val src = node.attr("abs:src").ifEmpty { node.attr("src") }
                        sb.append("![").append(alt).append("](").append(src).append(")")
                    }
                    "sub" -> wrapInline(sb, "~", node)
                    "sup" -> wrapInline(sb, "^", node)
                    else -> sb.append(inline(node))
                }
                else -> Unit
            }
        }
        return sb.toString()
    }

    private fun wrapInline(sb: StringBuilder, marker: String, el: Element) {
        val text = inline(el).trim()
        if (text.isNotEmpty()) sb.append(marker).append(text).append(marker)
    }

    private fun collapseBreaks(raw: String): String {
        val sb = StringBuilder(raw.length)
        var blankStreak = 0
        for (line in raw.lines()) {
            if (line.isBlank()) blankStreak++ else blankStreak = 0
            if (blankStreak > 1) continue
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }
}