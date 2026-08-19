package com.authorss81.noteflow.services

/**
 * Phase 174 — wiki-link autocomplete decision table.
 *
 * Pure JVM: ranks candidate note titles for a `[[` ink, dedupes, caps and
 * renders the insert snippet, plus locates the unterminated `[[…` query region
 * a keystroke just typed. The editor/picker feeds the cached vault titles
 * through here so the popup can never show duplicates, > [MAX_SUGGESTIONS]
 * rows, or titles that would corrupt the wikilink syntax.
 */
object WikiSuggestionPolicy {

    /** Maximum suggestions shown for any query. */
    const val MAX_SUGGESTIONS = 6

    private val extensionRegex = Regex("(?i)\\.(md|txt)$")

    /** Strip a `.md`/`.txt` suffix — display + insert target stay clean. */
    fun normalizeTitle(title: String): String =
        extensionRegex.replace(title.trim(), "")

    /**
     * A wikilink `[[…]]` target containing any of `[`, `]` or `|` would break
     * the syntax / alias parsing — such titles are never offered.
     */
    fun breaksWikilinkSyntax(title: String): Boolean =
        title.contains('[') || title.contains(']') || title.contains('|')

    /**
     * Candidate ranking for a `[[‹query›` ink. Visible prefix matches rank
     * first, then substring matches; both case-insensitive. Dedup is
     * case-insensitive with first-seen order winning; the result is capped at
     * [MAX_SUGGESTIONS] and stays in a stable order. Titles matching
     * [excludedTitles] (e.g. the current note's own title) are never offered.
     * A blank query returns the first [MAX_SUGGESTIONS] titled notes.
     */
    fun suggest(
        candidateTitles: Collection<String>,
        query: String,
        excludedTitles: Collection<String> = emptyList()
    ): List<String> {
        val normalizedCandidates = candidateTitles
            .asSequence()
            .map(::normalizeTitle)
            .filter { it.isNotEmpty() && !breaksWikilinkSyntax(it) }
            .filterNot { excluded ->
                excludedTitles.any { it.trim().equals(excluded, ignoreCase = true) }
            }
            .toList()

        val q = query.trim()
        if (q.isBlank()) {
            return takeCappedDistinct(normalizedCandidates)
        }
        val lowerQuery = q.lowercase()

        val result = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        fun addIfUnique(title: String) {
            if (seen.add(title.lowercase())) result.add(title)
        }
        for (title in normalizedCandidates) {
            if (title.lowercase().startsWith(lowerQuery)) addIfUnique(title)
        }
        for (title in normalizedCandidates) {
            if (result.size >= MAX_SUGGESTIONS) break
            if (title.lowercase().contains(lowerQuery)) addIfUnique(title)
        }
        return result.take(MAX_SUGGESTIONS)
    }

    private fun takeCappedDistinct(titles: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = LinkedHashSet<String>()
        for (title in titles.take(MAX_SUGGESTIONS)) {
            if (seen.add(title.lowercase())) result.add(title)
        }
        return result
    }

    /**
     * The insert snippet for a chosen title. `[[Title]]` when the title already
     * has no `.md`/`.txt` suffix; when it does, the RAW title stays the link
     * target and the stripped name is the alias display text (`[[Title.md|Title]]`,
     * the canonical wiki-link alias form) — either parses back to the same target.
     */
    fun wikilinkSnippet(title: String): String {
        val raw = title.trim()
        val clean = normalizeTitle(raw)
        return if (clean == raw) "[[$clean]]" else "[[$raw|$clean]]"
    }

    /** Bounds of the current unterminated `[[…` query, or null when none. */
    data class QueryBounds(val queryStart: Int, val queryEnd: Int)

    /**
     * Locate the LAST `[[` run that has no closing `]]` after it — the region a
     * keystroke is extending. [queryEnd] is the text end, so parents replace
     * `value.substring(queryStart, queryEnd)` with the inserted wikilink.
     *
     * Phase 174 review-fix: an open `[[` whose region spans a line break is NOT
     * treated as a live query. The suggestion popup replaces the whole
     * `[[…end-of-block` region on select, and multi-line blocks (paragraph/code
     * raw source) legitimately hold text after the `[[` that the user is NOT
     * typing as query ink — bounding to the current line keeps the popup out of
     * unrelated block content (no deletion of text the user didn't intend as a
     * title).
     */
    fun locateQuery(text: String): QueryBounds? {
        if (text.isBlank()) return null
        val lastOpen = text.lastIndexOf("[[")
        if (lastOpen < 0) return null
        if (text.indexOf("]]", lastOpen + 2) >= 0) return null
        if (text.indexOf('\n', lastOpen) >= 0) return null
        return QueryBounds(lastOpen, text.length)
    }
}