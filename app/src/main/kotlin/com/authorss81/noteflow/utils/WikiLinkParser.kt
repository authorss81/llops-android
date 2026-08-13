package com.authorss81.noteflow.utils

data class WikiLink(
    val targetTitle: String,
    val alias: String?
)

object WikiLinkParser {
    
    fun extractTags(text: String): List<String> {
        val tagPattern = Regex("#([\\p{L}\\p{N}_\\p{So}\\p{Sk}]+)")
        return tagPattern.findAll(text)
            .map { it.groupValues[1] }
            .toList()
    }
    
    fun extractWikiLinks(content: String): List<WikiLink> {
        val wikiLinkPattern = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]")
        return wikiLinkPattern.findAll(content)
            .map { match ->
                WikiLink(
                    targetTitle = match.groupValues[1].trim(),
                    alias = match.groupValues[2].takeIf { it.isNotEmpty() }?.trim()
                )
            }
            .toList()
    }
    
    fun findLinkedPageIdsForContent(sourceContent: String, pagesToMatch: List<String>): List<String> {
        return pagesToMatch.filter { page ->
            Regex("\\b$page\\b", RegexOption.IGNORE_CASE).containsMatchIn(sourceContent)
        }
    }
}
