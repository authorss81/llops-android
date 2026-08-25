package com.authorss81.noteflow.services

import java.util.regex.Pattern

object HtmlToMarkdownConverter {

    fun convertHtmlToMarkdown(htmlContent: String): Pair<String, String> {
        if (htmlContent.isBlank()) return Pair("Untitled", "")

        val cleanHtml = htmlContent
            .replace(Regex("(?s)<script.*?>.*?</script>"), "")
            .replace(Regex("(?s)<style.*?>.*?</style>"), "")
            .replace(Regex("(?s)<!--.*?-->"), "")

        // Extract title
        val titleMatcher = Pattern.compile("(?i)<title>(.*?)</title>").matcher(cleanHtml)
        var title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "" else ""
        if (title.isBlank()) {
            val h1Matcher = Pattern.compile("(?i)<h1.*?>(.*?)</h1>").matcher(cleanHtml)
            if (h1Matcher.find()) {
                title = stripTags(h1Matcher.group(1) ?: "").trim()
            }
        }
        if (title.isBlank()) {
            title = "Imported Note"
        }

        // Extract body or use cleanHtml
        val bodyMatcher = Pattern.compile("(?is)<body.*?>(.*?)</body>").matcher(cleanHtml)
        val bodyHtml = if (bodyMatcher.find()) bodyMatcher.group(1) ?: cleanHtml else cleanHtml

        val sb = StringBuilder()

        // Process line by line or tag blocks
        var processed = bodyHtml

        // 1. Headings
        for (i in 1..6) {
            val hashes = "#".repeat(i)
            processed = processed.replace(Regex("(?is)<h$i.*?>(.*?)</h$i>")) { matchResult ->
                "\n\n$hashes ${stripTags(matchResult.groupValues[1]).trim()}\n\n"
            }
        }

        // 2. Preformatted / Code blocks
        processed = processed.replace(Regex("(?is)<pre.*?>\\s*<code.*?>(.*?)</code>\\s*</pre>")) { matchResult ->
            val code = decodeEntities(stripTags(matchResult.groupValues[1])).trim()
            "\n\n```\n$code\n```\n\n"
        }
        processed = processed.replace(Regex("(?is)<pre.*?>(.*?)</pre>")) { matchResult ->
            val code = decodeEntities(stripTags(matchResult.groupValues[1])).trim()
            "\n\n```\n$code\n```\n\n"
        }

        // 3. Blockquotes
        processed = processed.replace(Regex("(?is)<blockquote.*?>(.*?)</blockquote>")) { matchResult ->
            // Phase 212 fix: <br> inside a quote becomes a line break BEFORE the
            // "> " prefix pass — the pre-fix stripTags silently glued the quote's
            // lines together ("firstsecond").
            val content = stripTags(matchResult.groupValues[1].replace(Regex("(?i)<br\\s*/?>"), "\n")).trim()
            val lines = content.lines().joinToString("\n") { "> $it" }
            "\n\n$lines\n\n"
        }

        // 4. Tables
        processed = processed.replace(Regex("(?is)<table.*?>(.*?)</table>")) { matchResult ->
            parseHtmlTable(matchResult.groupValues[1])
        }

        // 5. Lists: <ul>, <ol>, <li>
        processed = processed.replace(Regex("(?is)<li.*?>(.*?)</li>")) { matchResult ->
            val text = stripTags(matchResult.groupValues[1]).trim()
            "\n- $text"
        }
        processed = processed.replace(Regex("(?is)</?[uo]l.*?>"), "\n")

        // 6. Paragraphs and breaks
        processed = processed.replace(Regex("(?i)<br\\s*/?>"), "\n")
        processed = processed.replace(Regex("(?is)<p.*?>(.*?)</p>")) { matchResult ->
            "\n\n${matchResult.groupValues[1].trim()}\n\n"
        }
        processed = processed.replace(Regex("(?i)<hr\\s*/?>"), "\n\n---\n\n")

        // 7. Inline formatting: bold, italic, code, links, images
        // Images: <img src="src" alt="alt"/>
        processed = processed.replace(Regex("(?is)<img\\s+[^>]*src=[\"']([^\"']+)[\"'][^>]*alt=[\"']([^\"']*)[\"'][^>]*>")) { matchResult ->
            val src = matchResult.groupValues[1]
            val alt = matchResult.groupValues[2].ifBlank { "Image" }
            " ![$alt]($src) "
        }
        processed = processed.replace(Regex("(?is)<img\\s+[^>]*src=[\"']([^\"']+)[\"'][^>]*>")) { matchResult ->
            val src = matchResult.groupValues[1]
            " ![$src]($src) "
        }

        // Links: <a href="url">text</a> -> [text](url) or [[target]] if internal link
        processed = processed.replace(Regex("(?is)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")) { matchResult ->
            val href = matchResult.groupValues[1]
            val linkText = stripTags(matchResult.groupValues[2]).trim()
            if (href.endsWith(".html", ignoreCase = true) || href.endsWith(".htm", ignoreCase = true) || !href.contains("://")) {
                val targetPage = href.substringAfterLast('/').substringBeforeLast('.')
                // Phase 212 fix: the pre-fix format string carried an escaped
                // quote before the closing "]]" so every imported internal
                // link emitted a corrupted target ("[[page\"]]"). Pinned by
                // HtmlToMarkdownConverterTest.
                " [[$targetPage${if (linkText.isNotBlank() && !linkText.equals(targetPage, ignoreCase = true)) "|$linkText" else ""}]] "
            } else {
                " [$linkText]($href) "
            }
        }

        // Bold & Italic & Inline Code
        processed = processed.replace(Regex("(?is)<(?:b|strong).*?>(.*?)</(?:b|strong)>")) { matchResult -> " **${stripTags(matchResult.groupValues[1]).trim()}** " }
        processed = processed.replace(Regex("(?is)<(?:i|em).*?>(.*?)</(?:i|em)>")) { matchResult -> " *${stripTags(matchResult.groupValues[1]).trim()}* " }
        processed = processed.replace(Regex("(?is)<code.*?>(.*?)</code>")) { matchResult -> " `${decodeEntities(stripTags(matchResult.groupValues[1]).trim())}` " }

        // Strip remaining HTML tags
        var markdown = stripTags(processed)
        markdown = decodeEntities(markdown)

        // Clean up excessive blank lines
        markdown = markdown.replace(Regex("\n{3,}"), "\n\n").trim()

        return Pair(title, markdown)
    }

    private fun parseHtmlTable(tableHtml: String): String {
        val rows = mutableListOf<List<String>>()
        val trMatcher = Pattern.compile("(?is)<tr.*?>(.*?)</tr>").matcher(tableHtml)
        while (trMatcher.find()) {
            val rowContent = trMatcher.group(1) ?: ""
            val cells = mutableListOf<String>()
            val cellMatcher = Pattern.compile("(?is)<t[dh].*?>(.*?)</t[dh]>").matcher(rowContent)
            while (cellMatcher.find()) {
                cells.add(stripTags(cellMatcher.group(1) ?: "").trim())
            }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (rows.isEmpty()) return ""

        val sb = StringBuilder("\n\n")
        val header = rows.first()
        sb.append("| ").append(header.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(header.joinToString(" | ") { "---" }).append(" |\n")

        for (i in 1 until rows.size) {
            sb.append("| ").append(rows[i].joinToString(" | ")).append(" |\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun stripTags(input: String): String {
        return input.replace(Regex("<[^>]*>"), "")
    }

    private fun decodeEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }
}
