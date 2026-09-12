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
 *  - [extractTags] delegates to the services extractor (same grammar + cap);
 *  - [findLinkedPageIdsForContent] matches with ONE precompiled regex over
 *    [Regex.escape]-d titles (no per-page regex compile, no syntax crash).
 *
 * Kept (not deleted) because `WikiLinkAndTagParserUnitTest` and external
 * callers use this `WikiLink(targetTitle, alias)` shape, which differs from the
 * services `WikiLink` (rawText + offsets). New code MUST import the services
 * parser directly.
 *
 * Review-fix: `extractTags` used to re-implement extraction with a DIFFERENT
 * grammar (mid-word matches, no lowercasing) — it now delegates to the services
 * extractor directly, so there is exactly one tag grammar.
 */
@Deprecated(
    "Use com.authorss81.noteflow.services.WikiLinkParser directly — " +
        "this facade only preserves the legacy WikiLink(targetTitle, alias) shape."
)
object WikiLinkParser {

    /** Hard bound for a single-text tag extraction (must equal the services cap). */
    const val MAX_TAGS_PER_TEXT = 20000

    /**
     * Review-fix: direct delegation — the pre-fix body re-implemented tags with
     * a different grammar (no whitespace anchor, no lowercasing), so the two
     * parsers disagreed on the same text. One grammar now (services).
     */
    fun extractTags(text: String): List<String> =
        ServicesWikiLinkParser.extractTags(text)

    fun extractWikiLinks(content: String): List<WikiLink> {
        if (content.isBlank()) return emptyList()
        return ServicesWikiLinkParser.extractWikiLinks(content).map { link ->
            WikiLink(
                targetTitle = link.targetTitle,
                alias = link.alias
            )
        }
    }

    /**
     * Review-fix notes:
     * - boundaries are `(?<!\w)` / `(?!\w)` lookarounds, NOT `\b`: a `\b` after
     *   a non-word char never matches, so escaped titles like `C++` silently
     *   never linked. Lookarounds treat word and non-word titles uniformly.
     * - first title wins on case-insensitive collision (deterministic input
     *   order; `associateBy` kept the LAST).
     * - the 2000-title cap mirrors the services `MAX_SCAN_PAGES` bound.
     */
    fun findLinkedPageIdsForContent(sourceContent: String, pagesToMatch: List<String>): List<String> {
        if (sourceContent.isBlank() || pagesToMatch.isEmpty()) return emptyList()
        val titles = pagesToMatch.distinct().filter { it.isNotBlank() }.take(2000)
        if (titles.isEmpty()) return emptyList()
        // ONE precompiled regex over escaped titles — no per-page compile, no
        // PatternSyntaxException on regex metacharacters (C++, [TODO], a|b).
        val alternation = titles.joinToString("|") { Regex.escape(it) }
        val wordBoundaryRegex = Regex("(?<!\\w)(?:$alternation)(?!\\w)", RegexOption.IGNORE_CASE)
        val byLower = HashMap<String, String>(titles.size * 2)
        for (t in titles) byLower.getOrPut(t.lowercase()) { t }
        return wordBoundaryRegex.findAll(sourceContent)
            .mapNotNull { byLower[it.value.lowercase()] }
            .distinct()
            .toList()
    }
}
