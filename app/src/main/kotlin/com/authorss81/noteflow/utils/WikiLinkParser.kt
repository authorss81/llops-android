package com.authorss81.noteflow.utils

import com.authorss81.noteflow.services.WikiLinkParser as ServicesWikiLinkParser

data class WikiLink(
    val targetTitle: String,
    val alias: String?
)

/**
 * Phase 259: legacy facade over the hardened
 * [com.authorss81.noteflow.services.WikiLinkParser].
 *
 * The pre-259 shadow re-implemented extraction WITHOUT the services hardening
 * (per-page/total edge caps, epoch cache, Default-dispatcher + cancellation) and
 * `findLinkedPageIdsForContent` built `Regex("\\b$page\\b")` per page WITHOUT
 * escaping — a title like `C++`/`[TODO]` threw `PatternSyntaxException` (crash)
 * and attacker-controlled titles were a ReDoS vector. Every entry below now
 * delegates to (or mirrors the bounds of) the services parser:
 *  - [extractWikiLinks] maps the capped, fence-aware services scan;
 *  - [extractTags] is bounded at construction (never an unbounded materialize);
 *  - [findLinkedPageIdsForContent] matches with ONE precompiled regex over
 *    [Regex.escape]-d titles (no per-page regex compile, no syntax crash).
 *
 * Kept (not deleted) because `WikiLinkAndTagParserUnitTest` and external
 * callers use this `WikiLink(targetTitle, alias)` shape, which differs from the
 * services `WikiLink` (rawText + offsets). New code MUST import the services
 * parser directly.
 */
@Deprecated(
    "Use com.authorss81.noteflow.services.WikiLinkParser directly — " +
        "this facade only preserves the legacy WikiLink(targetTitle, alias) shape."
)
object WikiLinkParser {

    /** Hard bound for a single-text tag extraction (mirrors the services cap). */
    const val MAX_TAGS_PER_TEXT = 20000

    private val tagPattern = Regex("#([\\p{L}\\p{N}_\\p{So}\\p{Sk}]+)")

    fun extractTags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val fences = ServicesWikiLinkParser.fenceRanges(text)
        val out = ArrayList<String>(16)
        val seen = HashSet<String>(64)
        for (match in tagPattern.findAll(text)) {
            if (seen.size >= MAX_TAGS_PER_TEXT) break
            val start = match.range.first
            if (fences.isNotEmpty() && fences.any { start >= it.start && start <= it.endInclusive }) continue
            if (seen.add(match.groupValues[1])) {
                out.add(match.groupValues[1])
            }
        }
        return out
    }

    fun extractWikiLinks(content: String): List<WikiLink> {
        if (content.isBlank()) return emptyList()
        return ServicesWikiLinkParser.extractWikiLinks(content).map { link ->
            WikiLink(
                targetTitle = link.targetTitle,
                alias = link.alias
            )
        }
    }

    fun findLinkedPageIdsForContent(sourceContent: String, pagesToMatch: List<String>): List<String> {
        if (sourceContent.isBlank() || pagesToMatch.isEmpty()) return emptyList()
        val titles = pagesToMatch.distinct().filter { it.isNotBlank() }.take(2000)
        if (titles.isEmpty()) return emptyList()
        // ONE precompiled regex over escaped titles — no per-page compile, no
        // PatternSyntaxException on regex metacharacters (C++, [TODO], a|b).
        val alternation = titles.joinToString("|") { Regex.escape(it) }
        val wordBoundaryRegex = Regex("\\b(?:$alternation)\\b", RegexOption.IGNORE_CASE)
        val byLower = titles.associateBy { it.lowercase() }
        return wordBoundaryRegex.findAll(sourceContent)
            .mapNotNull { byLower[it.value.lowercase()] }
            .distinct()
            .toList()
    }
}
