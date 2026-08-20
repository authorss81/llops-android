package com.authorss81.noteflow.services

/**
 * Phase 183 — gallery display-title policy.
 *
 * Pure JVM: produces the STRING shown in compact gallery cards from the STORED
 * database title. It never mutates the stored title — the caller keeps the raw
 * value untouched for routing/export/wiki-link targets.
 *
 * Only ONE redundant trailing extension is stripped: `Note.md -> Note`,
 * `Note.md.md -> Note.md` (never a bare filename that IS an extension), and
 * the match is case-insensitive. All other names round-trip untouched.
 */
object GalleryTitleDisplayPolicy {

    private val REDUNDANT_EXTENSIONS = listOf(".md", ".markdown", ".txt")

    /**
     * @return a display-safe title with at most one redundant `.md`/`.markdown`/
     * `.txt` suffix removed. Never returns empty when the input had content —
     * a filename like `.md` (a bare extension) is kept as-is.
     */
    fun displayTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return rawTitle
        val trimmed = rawTitle.trim()
        if (trimmed.isBlank()) return trimmed
        val lower = trimmed.lowercase()
        for (suffix in REDUNDANT_EXTENSIONS) {
            if (lower.endsWith(suffix)) {
                val base = trimmed.dropLast(suffix.length)
                return if (base.isBlank()) trimmed else base
            }
        }
        return trimmed
    }
}