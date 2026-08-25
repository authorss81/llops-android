package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.NotePageEntity

/**
 * B2-DOS-02 (phase-78): single decision table for BOUNDED vault search.
 *
 * Pre-fix, `NoteRepository.loadSearchCorpus` deliberately stopped caching the
 * decrypted corpus once the active page count exceeded `searchCorpusMaxPages`
 * (1500) — so for a vault of 5k+ pages every non-blank keystroke re-ran a
 * FULL-VAULT AES-GCM decrypt of every title/body plus an O(n) substring scan,
 * saturating a 2-core device for seconds per keypress, with no LIMIT on the
 * underlying reads and no cancellation between concurrent in-flight searches.
 *
 * This policy owns the fix's bounds:
 *  - a keystroke search NEVER decrypts more than [SEARCH_CORPUS_CAP] rows: the
 *    decrypted window is cached once per epoch and re-used across keystrokes;
 *  - a vault larger than the cap is reported via [exceedsCorpusCap] so the UI
 *    can surface an EMPTY, NON-ALARMING, explicitly user-approved "refine" path
 *    (search all pages) instead of silently degrading search coverage or
 *    silently re-decrypting the whole vault per keystroke;
 *  - the explicit deep scan only ever decrypts [DEEP_SCAN_BATCH_SIZE] rows per
 *    batch (bounded memory, bounded per-step work, cancelled by the next
 *    keystroke via the ViewModel's shared search Job).
 *
 * Pure JVM — API 26+ floor with no platform calls and no fallback needed.
 */
object VaultSearchPolicy {

    /** Match ranking tiers — EXACT always beats FUZZY (see [pageMatchTier]). */
    enum class SearchMatchTier { EXACT, FUZZY }

    /** Row budget for the cached, per-keystroke decrypted search window. */
    const val SEARCH_CORPUS_CAP = 1500

    /** Batch size for the explicit deep-scan (refine) pass. */
    const val DEEP_SCAN_BATCH_SIZE = 1500

    /** A query with no searchable content is never worth a scan. */
    fun isBlankQuery(query: String?): Boolean = query.isNullOrBlank()

    /** Whether a vault of [totalActivePages] must bound its cached search window. */
    fun exceedsCorpusCap(totalActivePages: Int): Boolean = totalActivePages > SEARCH_CORPUS_CAP

    /**
     * Size of the cached decrypted search window for a vault of [totalActivePages].
     * Always bounded by [SEARCH_CORPUS_CAP]; identical to the vault size below it.
     */
    fun cachedWindowSize(totalActivePages: Int): Int =
        if (exceedsCorpusCap(totalActivePages)) SEARCH_CORPUS_CAP else totalActivePages

    /**
     * Non-alarming copy for the refine affordance — a positive hint that the
     * recent-page window is narrowed, never an error, and explicitly opted into
     * by the user before the (one-time, cancellable) full scan runs.
     */
    fun refineNoticeMessage(totalActivePages: Int): String =
        if (totalActivePages <= SEARCH_CORPUS_CAP) {
            ""
        } else {
            "Search covers the $SEARCH_CORPUS_CAP most recent pages of $totalActivePages."
        }

    /**
     * Does [page] match [query]? EXACT title/body substring first
     * (case-insensitive — the pre-phase-209 semantics), then the Phase 209
     * typo-tolerant tier: a case-insensitive in-order subsequence match via the
     * SHARED [FuzzyMatch] (same function the command palette scores with).
     * True for both tiers; callers that need the ranking split use
     * [pageMatchTier]. [query] must already be trimmed and non-blank (see
     * [isBlankQuery]).
     */
    fun pageMatches(page: NotePageEntity, query: String): Boolean =
        pageMatchTier(page, query) != null

    /**
     * Tiered match for ranking. EXACT (title or body substring,
     * case-insensitive) always outranks FUZZY (in-order subsequence with a
     * length-aware gap penalty — see [FuzzyMatch]); null = no match. The fuzzy
     * tier runs ONLY after both exact probes fail, so a keystroke search pays
     * the extra scan just once per non-matching page.
     */
    fun pageMatchTier(page: NotePageEntity, query: String): SearchMatchTier? {
        if (page.title.contains(query, ignoreCase = true)) return SearchMatchTier.EXACT
        if (page.extractedText?.contains(query, ignoreCase = true) == true) {
            return SearchMatchTier.EXACT
        }
        val body = page.extractedText
        val fuzzyTitle = FuzzyMatch.subsequenceDensity(query, page.title)
        if (fuzzyTitle != null) return SearchMatchTier.FUZZY
        if (body != null && FuzzyMatch.subsequenceDensity(query, body) != null) {
            return SearchMatchTier.FUZZY
        }
        return null
    }

    /**
     * Phase 209 ordering: EXACT-tier results lead, FUZZY typo hits follow;
     * STABLE within each tier so the corpus's recency order is preserved for
     * equal ranks. Applied by `NoteRepository.searchPages` / `deepSearchPages`
     * to their matched list.
     *
     * Phase 209 REVIEW-FIX (finding 3): the tier is computed ONCE per page
     * (decorate-sort-undecorate) instead of inside the sort selector, which a
     * comparator-driven `sortedBy` re-invokes O(n log n) times — each such call
     * re-ran up to two full-text fuzzy scans per page.
     */
    fun exactFirst(pages: List<NotePageEntity>, query: String): List<NotePageEntity> =
        pages.asSequence()
            .map { page -> page to pageMatchTier(page, query) }
            .sortedBy { if (it.second == SearchMatchTier.EXACT) 0 else 1 }
            .map { it.first }
            .toList()
}