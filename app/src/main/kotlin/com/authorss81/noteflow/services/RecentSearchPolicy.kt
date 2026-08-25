package com.authorss81.noteflow.services

/**
 * Phase 209: recent-search history policy — the pure-JVM ring/dedupe/cap
 * decision table behind HomeScreen's recent-search chips.
 *
 * Storage lives in SharedPreferences (`search_recent_<n>` ring keys, see
 * `SettingsManager.getRecentSearches` / `setRecentSearches`) — prefs only,
 * NEVER the DB schema. Queries are non-secret user-typed search strings;
 * persisting them is the feature (the same strings already sit in the search
 * field's edit history), and nothing here ever touches note content.
 */
object RecentSearchPolicy {

    /** Maximum persisted queries — the prompt's "last 8 non-blank queries". */
    const val CAP = 8

    /**
     * Record an EXECUTED query at the front of [current]. Blank queries are
     * ignored (never recorded); the query is trimmed; an existing entry that
     * differs only by case/whitespace moves to the front instead of
     * duplicating (most-recent-wins); the result is capped at [CAP].
     */
    fun record(current: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return sanitize(current)
        val rest = current.filter { !it.equals(trimmed, ignoreCase = true) }
        return (listOf(trimmed) + rest).take(CAP)
    }

    /**
     * Remove a chip-dismissed query (case-insensitive, trim-tolerant match).
     * Unknown queries leave the list unchanged.
     */
    fun dismiss(current: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return sanitize(current)
        return current.filter { !it.equals(trimmed, ignoreCase = true) }
    }

    /**
     * Normalize any raw/persisted list into displayable shape: trims each
     * entry, drops blanks, dedupes case-insensitively keeping the FIRST
     * (most-recent) occurrence, caps at [CAP]. Used on read-back so a
     * hand-edited or partially-corrupted pref store can never surface junk.
     */
    fun sanitize(raw: List<String?>): List<String> {
        val seenCaseFolded = HashSet<String>()
        val out = ArrayList<String>(CAP)
        for (value in raw) {
            if (out.size >= CAP) break
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) continue
            val folded = trimmed.lowercase()
            if (!seenCaseFolded.add(folded)) continue
            out.add(trimmed)
        }
        return out
    }
}
