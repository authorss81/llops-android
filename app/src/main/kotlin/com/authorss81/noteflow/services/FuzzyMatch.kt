package com.authorss81.noteflow.services

/**
 * Phase 209: shared typo-tolerant matcher — ONE subsequence implementation used
 * by BOTH scorers ([VaultSearchPolicy.pageMatchTier] for vault search and
 * [com.authorss81.noteflow.services.graph.CommandPaletteMath.score] for the
 * command palette), so the two surfaces can never drift apart.
 *
 * The match is a case-insensitive IN-ORDER SUBSEQUENCE test with a
 * length-aware gap penalty (the matched "density"): a query whose characters
 * all appear in order in the text but not contiguously still counts as a hit,
 * ranked strictly BELOW any exact-substring tier by both scorers.
 *
 * Density = query length / (query length + skipped characters). Contiguous
 * substrings score 1.0; every skipped character before or between matches
 * lowers it. Both scorers reject hits below [MIN_DENSITY], so a short query
 * cannot match everything ("nte" matches "notebook" but NOT "alternative"),
 * and tighter matches rank above looser ones inside the fuzzy tier.
 *
 * Pure JVM — no platform calls, deterministic, allocation-light (one lowercase
 * copy per input on the convenience path; the palette hot path feeds the
 * already-lowered per-index fields via [subsequenceDensityPreLowered]).
 */
object FuzzyMatch {

    /**
     * Queries shorter than this NEVER fuzzy-match. A single character is an
     * in-order subsequence of nearly every string with density 1.0 — without
     * this gate one-letter queries would fuzz-flood both search surfaces.
     */
    const val MIN_QUERY_LENGTH = 2

    /**
     * Minimum matched density for a subsequence hit to count. 0.45 means at
     * most ~1 skipped character per ~1.8 matched characters — loose enough for
     * real typos ("ntebook" → "notebook" = 0.70), tight enough to reject noise
     * ("nte" → "alternative" = 0.27).
     */
    const val MIN_DENSITY = 0.45f

    /**
     * Case-insensitive in-order subsequence test with a gap penalty. Returns
     * the matched DENSITY in (0..1] (higher = tighter), or null when [query]
     * is not a qualifying subsequence of [text]: shorter than
     * [MIN_QUERY_LENGTH], not a subsequence at all, too sparse (density below
     * [MIN_DENSITY]), or when either side is empty.
     *
     * Convenience path — lowers both inputs. Scorers holding pre-lowered text
     * MUST use [subsequenceDensityPreLowered] instead so the corpus is never
     * re-lowercased per keystroke (phase-152 discipline).
     */
    fun subsequenceDensity(query: String, text: String): Float? =
        subsequenceDensityPreLowered(query.lowercase(), text.lowercase())

    /**
     * Pre-lowered variant of [subsequenceDensity] — both inputs must already be
     * lowercase (the callers guarantee it; nothing here re-lowercases). Same
     * contract and result.
     */
    fun subsequenceDensityPreLowered(lowerQuery: String, lowerText: String): Float? {
        val q = lowerQuery
        if (q.length < MIN_QUERY_LENGTH) return null
        val t = lowerText
        if (t.isEmpty()) return null
        var ti = 0
        var skipped = 0
        for (qc in q) {
            var found = false
            while (ti < t.length) {
                val tc = t[ti]
                ti++
                if (tc == qc) {
                    found = true
                    break
                }
                skipped++
            }
            if (!found) return null
        }
        val density = q.length.toFloat() / (q.length + skipped).toFloat()
        return if (density >= MIN_DENSITY) density else null
    }

    /** Boolean form of [subsequenceDensity] for call sites that need no score. */
    fun isFuzzyMatch(query: String, text: String): Boolean =
        subsequenceDensity(query, text) != null
}
