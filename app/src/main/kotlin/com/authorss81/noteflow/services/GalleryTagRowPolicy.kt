package com.authorss81.noteflow.services

/**
 * Phase 188 — gallery card tag-row policy.
 *
 * Pure-JVM decision table for the multi-tag chip row on a gallery card. The
 * ~140dp card text column cannot legibly render more than [MAX_VISIBLE_TAGS]
 * chips at 1.3–1.5x font scale, so the row is capped at two chips plus a "+N"
 * badge and stays SINGLE-LINE [TAG_ROW_MAX_LINES] (see
 * [GalleryCardLayoutPolicy]) so the update timestamp directly below always stays
 * visible. All parsing / capping / label decisions live here so `GalleryView`
 * renders no inline tag math a reviewer could de-sync.
 */
object GalleryTagRowPolicy {

    /** Maximum chips rendered on one card; any more collapse into the "+N" badge. */
    const val MAX_VISIBLE_TAGS = 2

    /**
     * Parses the raw comma-joined `page.tags` field: split on ',', trim, strip
     * one leading '#', drop empty tokens.
     */
    fun parseTags(raw: String): List<String> =
        raw.split(",")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }

    /** The chips to render for an already-parsed tag list: at most [MAX_VISIBLE_TAGS]. */
    fun visibleChips(tags: List<String>): List<String> = tags.take(MAX_VISIBLE_TAGS)

    /** Convenience [visibleChips] directly from the raw field. */
    fun visibleChips(raw: String): List<String> = visibleChips(parseTags(raw))

    /** Number of further tags hidden behind the "+N" badge (never negative). */
    fun hiddenChipCount(tags: List<String>): Int =
        (tags.size - MAX_VISIBLE_TAGS).coerceAtLeast(0)

    /** Convenience [hiddenChipCount] directly from the raw field. */
    fun hiddenChipCount(raw: String): Int = hiddenChipCount(parseTags(raw))

    /** Badge label for [hidden] hidden tags, or null when nothing is hidden. */
    fun hiddenBadgeText(hidden: Int): String? = if (hidden > 0) "+$hidden" else null

    /** Single-tag chip label (a leading '#', matching the tag store's convention). */
    fun chipText(tag: String): String = "#$tag"
}
