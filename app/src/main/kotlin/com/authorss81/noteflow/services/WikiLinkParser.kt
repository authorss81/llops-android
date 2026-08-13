package com.authorss81.noteflow.services

import android.content.Context
import com.authorss81.noteflow.data.model.NotePageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class WikiLink(
    val rawText: String,
    val targetTitle: String,
    val alias: String?,
    val startIndex: Int,
    val endIndex: Int
)

data class TagNode(
    val name: String,
    val fullTagPath: String,
    val noteCount: Int,
    val children: List<TagNode> = emptyList(),
    val matchingPageIds: Set<String> = emptySet()
)

data class BacklinkMatch(
    val page: NotePageEntity,
    val snippet: String,
    val isExplicitWikiLink: Boolean
)

object WikiLinkParser {

    private val wikiLinkRegex = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]")
    private val tagRegex = Regex("(?:^|\\s)#([^\\s#\\[\\]{}()|.,!?:;\"]+)")

    fun extractWikiLinks(text: String): List<WikiLink> {
        if (text.isBlank()) return emptyList()
        return wikiLinkRegex.findAll(text).map { match ->
            val rawText = match.value
            val targetTitle = match.groupValues[1].trim()
            val alias = match.groupValues[2].takeIf { it.isNotBlank() }?.trim()
            WikiLink(
                rawText = rawText,
                targetTitle = targetTitle,
                alias = alias,
                startIndex = match.range.first,
                endIndex = match.range.last + 1
            )
        }.toList()
    }

    fun extractTags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return tagRegex.findAll(text).map { match ->
            match.groupValues[1].lowercase().trim('/')
        }.distinct().toList()
    }

    suspend fun getFullTextForPage(context: Context, page: NotePageEntity): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.append(page.title).append("\n")
            page.extractedText?.let { sb.append(it).append("\n") }
            page.sourceFilePath?.let { path ->
                val f = File(path)
                val isTextFile = page.sourceFileType == "text" || path.endsWith(".md") || path.endsWith(".txt")
                if (isTextFile && f.exists() && f.canRead()) {
                    try {
                        sb.append(f.readText())
                    } catch (e: Exception) {
                        // Safe read fallback
                    }
                }
            }
            sb.toString()
        }

    suspend fun findBacklinks(
        targetPage: NotePageEntity,
        allPages: List<NotePageEntity>,
        context: Context
    ): Pair<List<BacklinkMatch>, List<BacklinkMatch>> {
        val targetTitle = targetPage.title.replace(".md", "").replace(".txt", "").trim()
        if (targetTitle.isBlank()) return Pair(emptyList(), emptyList())

        val explicitLinks = mutableListOf<BacklinkMatch>()
        val unlinkedMentions = mutableListOf<BacklinkMatch>()
        val wordBoundaryRegex = Regex("(?i)\\b${Regex.escape(targetTitle)}\\b")

        for (page in allPages) {
            if (page.id == targetPage.id) continue
            val fullText = getFullTextForPage(context, page)
            val wikiLinks = extractWikiLinks(fullText)

            val matchedWikiLink = wikiLinks.find {
                it.targetTitle.equals(targetTitle, ignoreCase = true) ||
                it.targetTitle.equals(targetPage.title, ignoreCase = true)
            }

            if (matchedWikiLink != null) {
                val snippet = createSnippet(fullText, matchedWikiLink.startIndex, matchedWikiLink.endIndex)
                explicitLinks.add(BacklinkMatch(page, snippet, isExplicitWikiLink = true))
            } else {
                val match = wordBoundaryRegex.find(fullText)
                if (match != null) {
                    val snippet = createSnippet(fullText, match.range.first, match.range.last + 1)
                    unlinkedMentions.add(BacklinkMatch(page, snippet, isExplicitWikiLink = false))
                }
            }
        }

        return Pair(explicitLinks, unlinkedMentions)
    }

    private fun createSnippet(text: String, start: Int, end: Int, padding: Int = 40): String {
        val cleanText = text.replace("\n", " ")
        val snippetStart = (start - padding).coerceAtLeast(0)
        val snippetEnd = (end + padding).coerceAtMost(cleanText.length)
        var snippet = cleanText.substring(snippetStart, snippetEnd).trim()
        if (snippetStart > 0) snippet = "...$snippet"
        if (snippetEnd < cleanText.length) snippet = "$snippet..."
        return snippet
    }

    suspend fun buildTagHierarchy(
        allPages: List<NotePageEntity>,
        context: Context
    ): List<TagNode> {
        val tagToPagesMap = mutableMapOf<String, MutableSet<String>>()

        for (page in allPages) {
            val fullText = getFullTextForPage(context, page)
            val tags = extractTags(fullText)
            for (tag in tags) {
                tagToPagesMap.getOrPut(tag) { mutableSetOf() }.add(page.id)
            }
        }

        if (tagToPagesMap.isEmpty()) return emptyList()

        // Build hierarchical tree from tags with '/'
        val rootNodes = mutableMapOf<String, MutableTagNodeBuilder>()

        for ((fullTag, pageIds) in tagToPagesMap) {
            val parts = fullTag.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue

            var currentMap = rootNodes
            var currentPath = ""

            for (i in parts.indices) {
                val part = parts[i]
                currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

                val node = currentMap.getOrPut(part) {
                    MutableTagNodeBuilder(name = part, fullTagPath = currentPath)
                }
                node.matchingPageIds.addAll(pageIds)
                currentMap = node.children
            }
        }

        return rootNodes.values.map { it.toTagNode() }.sortedBy { it.name }
    }

    private class MutableTagNodeBuilder(
        val name: String,
        val fullTagPath: String,
        val matchingPageIds: MutableSet<String> = mutableSetOf(),
        val children: MutableMap<String, MutableTagNodeBuilder> = mutableMapOf()
    ) {
        fun toTagNode(): TagNode {
            val childNodes = children.values.map { it.toTagNode() }.sortedBy { it.name }
            return TagNode(
                name = name,
                fullTagPath = fullTagPath,
                noteCount = matchingPageIds.size,
                children = childNodes,
                matchingPageIds = matchingPageIds
            )
        }
    }
}
