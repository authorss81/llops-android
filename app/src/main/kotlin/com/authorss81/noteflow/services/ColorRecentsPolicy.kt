package com.authorss81.noteflow.services

/**
 * Phase 172 — persistent recently-used colors + favorites decision table.
 * Pure JVM (no android/Compose imports) so the caps, dedupe, ordering and the
 * shared-preferences wire format are unit-testable.
 *
 * Two bounded lists are persisted in `SettingsManager`:
 *  - [MAX_RECENT_COLORS] recent colors — most-recent-first, deduped on every
 *    `recordRecent`. Recorded on every explicit color pick (swatch taps in the
 *    color picker + eyedropper samples) so a cold-restart session keeps the
 *    user's last-used inks, replacing the old volatile in-memory derivation.
 *  - [MAX_FAVORITE_COLORS] favorites — a small curated set, most-recent-first,
 *    toggled by the star control in the color picker.
 *
 * Persistence uses a compact comma-joined decimal-ARGB wire format; decode is
 * fail-closed (unknown tokens are skipped, tones are deduped and capped) so a
 * hand-edited/torn pref value can never yield an oversized or malformed list.
 */
object ColorRecentsPolicy {

    const val MAX_RECENT_COLORS = 16

    const val MAX_FAVORITE_COLORS = 12

    private const val SEPARATOR = ","

    /**
     * Record a pick: dedupe (later picks win position), move to front,
     * cap to [MAX_RECENT_COLORS]. The earlier in-memory `take(16)` equivalent.
     */
    fun recordRecent(recent: List<Int>, colorArgb: Int): List<Int> {
        val out = ArrayList<Int>(recent.size + 1)
        out.add(colorArgb)
        for (existing in recent) {
            if (existing != colorArgb) out.add(existing)
        }
        return out.take(MAX_RECENT_COLORS)
    }

    /** True iff [colorArgb] is currently a favorite. */
    fun isFavorite(favorites: List<Int>, colorArgb: Int): Boolean = colorArgb in favorites

    /**
     * Toggle a color's favorite state: present → removed, absent → added at the
     * front and capped to [MAX_FAVORITE_COLORS]. Returns the NEW list (never the
     * caller's mutable copy).
     */
    fun toggleFavorite(favorites: List<Int>, colorArgb: Int): List<Int> {
        val out = ArrayList<Int>(favorites)
        return if (isFavorite(out, colorArgb)) {
            out.remove(colorArgb)
            out
        } else {
            out.add(0, colorArgb)
            out.take(MAX_FAVORITE_COLORS)
        }
    }

    /** Deterministic compact wire format (comma-joined decimal ARGB ints). */
    fun encodeColors(colors: List<Int>): String = colors.joinToString(SEPARATOR)

    /**
     * Fail-closed decode: blank/torn input → empty; unknown tokens skipped;
     * duplicate/cap overflow normalized away so the in-memory list is always
     * ≤ the corresponding cap.
     */
    fun decodeColors(encoded: String?): List<Int> {
        if (encoded.isNullOrBlank()) return emptyList()
        val out = ArrayList<Int>()
        for (token in encoded.split(SEPARATOR)) {
            val value = token.toIntOrNull() ?: continue
            if (value !in out) out.add(value)
        }
        return out
    }

    /** Enforce the recent cap + dedupe on any externally-built list. */
    fun sanitizeRecent(recent: List<Int>): List<Int> =
        recent.distinct().take(MAX_RECENT_COLORS)

    /** Enforce the favorites cap + dedupe on any externally-built list. */
    fun sanitizeFavorites(favorites: List<Int>): List<Int> =
        favorites.distinct().take(MAX_FAVORITE_COLORS)
}