package com.authorss81.noteflow.services.graph

/**
 * Command palette / quick-switcher logic — pure JVM, deterministic.
 *
 * Given (a) a decrypted in-memory corpus already loaded by `NoteRepository`
 * (title + extractedText + tags, never fresh decryption per keystroke) and (b)
 * the compile-time plugin-action catalog, this object produces:
 *
 *  1. [rank] — an ordered note-result list for a free-text query. Scoring is
 *     stable and tiered (title prefix > title contains > tag > body) with a
 *     deterministic tiebreak (updatedAt desc, then id asc) so tests can assert
 *     exact ordering.
 *  2. [matchesTagFilter] — AND/OR tag-filter combination used both by the
 *     palette's "filter by tag" chip row and by the knowledge-graph tag chips.
 *  3. [matchAction] — routes a query such as `web: matrix shadows` or `ocr`
 *     to a [PaletteActionDescriptor] + extracted argument. The descriptor
 *     carries the PluginManager capability key; execution stays in the
 *     ViewModel (tests here prove routing, not network).
 */
object CommandPaletteMath {

    /**
     * A searchable document from the cached decrypted corpus.
     *
     * R2-b2b5-FEA-05 (phase-152): the title/body/tags are lowercased EXACTLY
     * ONCE, here at index-build time (the defaults below run at construction),
     * so [rank] / [score] / [makeSnippet] never re-lowercase the whole corpus
     * per keystroke — a ~1500-page × 50 KB corpus cost ~75 MB of allocations on
     * every debounced keystroke before this fix.
     */
    data class PaletteDoc(
        val id: String,
        val title: String,
        val body: String,
        val tags: Set<String>,
        val updatedAt: Long,
        val lowerTitle: String = title.lowercase(),
        val lowerBody: String = body.lowercase(),
        val lowerTags: Set<String> = tags.mapTo(LinkedHashSet()) { it.lowercase() }
    )

    enum class MatchKind { TITLE_PREFIX, TITLE_CONTAINS, TAG_MATCH, BODY_CONTAINS }

    /** A ranked note match with a short preview snippet. */
    data class RankedNote(
        val doc: PaletteDoc,
        val score: Float,
        val snippet: String,
        internal val matchKind: MatchKind = MatchKind.TITLE_CONTAINS
    )

    /** A plugin quick action surfaced in the palette. */
    data class PaletteActionDescriptor(
        val id: String,
        val keyword: String,
        val label: String,
        val capabilityKey: String,
        /** When true, the query tail after the keyword is the action payload. */
        val needsArg: Boolean,
        val suffixHint: String = ""
    )

    /** The matched action + its extracted payload (may be blank). */
    data class ActionMatch(
        val action: PaletteActionDescriptor,
        val arg: String
    )

    /**
     * Compile-time action catalog (Phase 38 — the palette's plugin entry
     * points). Each item maps to one [PluginCapability] by key; the ViewModel
     * is the only place that fixes them to a PluginManager invocation. This set
     * must never add new permissions or network of its own.
     */
    val ACTION_CATALOG: List<PaletteActionDescriptor> = listOf(
        PaletteActionDescriptor("web-search", "web", "Web Search", "web_search", needsArg = true, suffixHint = "query"),
        PaletteActionDescriptor("ocr", "ocr", "OCR Image", "ocr", needsArg = false, suffixHint = "photo → text"),
        PaletteActionDescriptor("dictation", "dictate", "Dictation", "dictation", needsArg = false, suffixHint = "voice → text"),
        PaletteActionDescriptor("translate", "translate", "Translate", "translation", needsArg = true, suffixHint = "text"),
        PaletteActionDescriptor("read-aloud", "read", "Read Aloud", "read_aloud", needsArg = false, suffixHint = "speak note"),
        PaletteActionDescriptor("weather", "weather", "Weather", "weather", needsArg = false),
        PaletteActionDescriptor("units", "convert", "Unit Converter", "unit_conversion", needsArg = true, suffixHint = "2 km to mi"),
        PaletteActionDescriptor("dictionary", "define", "Dictionary", "dictionary", needsArg = true, suffixHint = "word"),
        PaletteActionDescriptor("transform", "transform", "Text Transform", "text_transform", needsArg = true, suffixHint = "text"),
        PaletteActionDescriptor("assistant", "ask", "Assistant", "assistant", needsArg = true, suffixHint = "question")
    )

    private const val KEYWORD_SEPARATORS = ":"

    /**
     * Stable tiered score for a [PaletteDoc] against [query]. Returns null for
     * no match. Title-prefix beats title-substring beats tag beats body — the
     * palette should never rank a passing mention above an exact title.
     */
    fun score(query: String, doc: PaletteDoc): Pair<Float, MatchKind>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val lq = q.lowercase()
        val lt = doc.lowerTitle

        if (lt == lq || lt.startsWith(lq)) {
            return 100f - minF(doc.title.length / 50f, 10f) to MatchKind.TITLE_PREFIX
        }
        if (lt.contains(lq)) return 85f to MatchKind.TITLE_CONTAINS
        val tagHit = doc.lowerTags.firstOrNull { it.contains(lq) }
        if (tagHit != null) return 65f to MatchKind.TAG_MATCH
        if (doc.lowerBody.contains(lq)) return 40f to MatchKind.BODY_CONTAINS
        return null
    }

    private fun minF(a: Float, b: Float): Float = if (a < b) a else b

    private fun makeSnippet(body: String, lowerBody: String, lq: String): String {
        val idx = lowerBody.indexOf(lq)
        if (idx < 0 || body.isBlank()) return body.take(60)
        val start = (idx - 30).coerceAtLeast(0)
        val end = (idx + 90).coerceAtMost(body.length)
        var snippet = body.substring(start, end).replace('\n', ' ').trim()
        if (start > 0) snippet = "…$snippet"
        if (end < body.length) snippet = "$snippet…"
        return snippet
    }

    /**
     * Rank [docs] against [query], applying [selectedTags] as a filter first.
     * Deterministic: score desc, then updatedAt desc, then id asc.
     */
    fun rank(
        query: String,
        docs: List<PaletteDoc>,
        selectedTags: Set<String> = emptySet(),
        requireAllTags: Boolean = true,
        maxResults: Int = 12
    ): List<RankedNote> {
        val q = query.trim()
        // R2-b2b5-FEA-05: selected tags are lowercased once per rank() call and
        // the docs' per-index lowercase is already in `lowerTags` — neither the
        // corpus nor the selection is re-lowercased per doc.
        val lowerSelected = selectedTags.mapTo(HashSet(selectedTags.size)) { it.lowercase() }
        val filtered = if (selectedTags.isEmpty()) docs else
            docs.filter { matchesTagFilter(it.lowerTags, lowerSelected, requireAllTags) }
        return filtered
            .mapNotNull { doc ->
                if (q.isEmpty()) {
                    // Blank query + a tag filter → recency order within the filter.
                    if (selectedTags.isEmpty()) return@mapNotNull null
                    return@mapNotNull RankedNote(
                        doc = doc,
                        score = doc.updatedAt.toFloat(),
                        snippet = ""
                    )
                }
                val score = score(q, doc) ?: return@mapNotNull null
                RankedNote(
                    doc = doc,
                    score = score.first,
                    snippet = makeSnippet(doc.body, doc.lowerBody, q.lowercase()),
                    matchKind = score.second
                )
            }
            .sortedWith(
                compareByDescending<RankedNote> { it.score }
                    .thenByDescending { it.doc.updatedAt }
                    .thenBy { it.doc.id }
            )
            .take(maxResults)
    }

    /**
     * Tag-filter combination: [requireAllTags]=true requires EVERY selected tag
     * (AND); false requires ANY (OR). Underscores/hyphens in tags are compared
     * case-insensitively.
     */
    fun matchesTagFilter(
        docTags: Set<String>,
        selectedTags: Set<String>,
        requireAllTags: Boolean
    ): Boolean {
        if (selectedTags.isEmpty()) return true
        val lt = docTags.map { it.lowercase() }.toSet()
        val selected = selectedTags.map { it.lowercase() }.toSet()
        return if (requireAllTags) selected.all { it in lt }
        else selected.any { it in lt }
    }

    /**
     * Route a query to a catalog action. Supported forms (case-insensitive):
     *  - "keyword"            (e.g. `ocr`, `weather`)
     *  - "keyword: arg"       (e.g. `translate: hello`, `convert: 2 km to mi`)
     *  - "keyword arg"        (e.g. `web how to center in css`)
     * Longer keywords win when two catalog entries share a prefix (e.g.
     * `read` vs none). Returns null when no keyword matches.
     */
    fun matchAction(query: String, catalog: List<PaletteActionDescriptor> = ACTION_CATALOG): ActionMatch? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        val lowered = trimmed.lowercase()
        val candidates = catalog.sortedByDescending { it.keyword.length }
        for (action in candidates) {
            val kw = action.keyword.lowercase()
            if (lowered == kw) return ActionMatch(action, "")
            if (lowered.startsWith("$kw:") || lowered.startsWith("$kw ")) {
                val arg = trimmed.substring(kw.length).trim()
                val cleaned = arg.removePrefix(":").trim()
                return ActionMatch(action, cleaned)
            }
            // keyword may also be separated by a tab
            if (lowered.startsWith("$kw\t")) {
                return ActionMatch(action, trimmed.substring(kw.length).trim())
            }
        }
        return null
    }
}