package com.authorss81.noteflow.services.graph

/**
 * Phase 154 — pure-JVM decision table for the Knowledge Graph's node peek /
 * quick-preview card.
 *
 * The preview is an additive layer above the existing
 * `onOpenPage(node.page)` flow: tapping a node selects it and the bottom card
 * shows the title, the tag chips and the first lines of the (decrypted while
 * authenticated) `extractedText`, with "Open" + "Copy wikilink" actions. All
 * of the *shape* of that card is decided here so it is unit-testable:
 *
 *  - [previewSnippet] — the bounded, first-3-lines body preview (a huge note
 *    body must never be rendered, or held in Compose state, unbounded);
 *  - [parseTags] — the capped tag-chip list (a 50-tag page must never lay out
 *    50 chips in a bottom card);
 *  - [wikilinkFor] — the exact `[[Title]]` text the copy action writes.
 *
 * Security contract: the text fed to [previewSnippet] is ALREADY decrypted by
 * the ViewModel only while the auth gate is up (see
 * `NoteflowViewModel.loadGraphNodePreview`); this policy never decrypts, never
 * holds plaintext, and refuses to fabricate content from ciphertext.
 */
object GraphPreviewPolicy {

    /** Max body lines shown in a preview card. */
    const val PREVIEW_MAX_LINES = 3

    /** Max characters across the previewed lines (whitespace-normalized). */
    const val PREVIEW_MAX_CHARS = 240

    /** Max tag chips rendered in a preview card. */
    const val PREVIEW_TAGS_MAX = 6

    /** Copied-wikilink snackbar copy. */
    const val COPY_WIKILINK_NOTICE = "Wikilink copied"

    /** Defensive fallback shown when the vault is locked or the read degrades. */
    const val PREVIEW_LOCKED_LABEL = "Preview unavailable — vault is locked"

    /**
     * First up-to-[PREVIEW_MAX_LINES] non-blank lines of [extractedText],
     * collapsed whitespace, capped at [PREVIEW_MAX_CHARS] with an ellipsis.
     * Blank/null input yields an empty preview (the card simply omits the body
     * line). Never interpolates ciphertext — the caller only passes decrypted
     * text (see the contract above).
     */
    fun previewSnippet(extractedText: String?): String {
        if (extractedText.isNullOrBlank()) return ""
        val lines = extractedText
            .replace("\r", "")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(PREVIEW_MAX_LINES)
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString(" ")
        if (joined.length <= PREVIEW_MAX_CHARS) return joined
        return joined.take(PREVIEW_MAX_CHARS).trimEnd() + "..."
    }

    /**
     * Parses the `pages.tags` CSV into the capped, ordered chip list. Malformed
     * entries (`#tag`, `tag `, `tag|alias`) are normalized: `#` prefixes are
     * stripped so chips render as the tag's readable name.
     */
    fun parseTags(tags: String): List<String> {
        if (tags.isBlank()) return emptyList()
        val out = ArrayList<String>(PREVIEW_TAGS_MAX)
        for (raw in tags.split(',')) {
            if (out.size >= PREVIEW_TAGS_MAX) break
            val normalized = raw.trim().removePrefix("#")
            if (normalized.isNotEmpty()) out.add(normalized)
        }
        return out
    }

    /** The exact wikilink text the "Copy wikilink" action writes. */
    fun wikilinkFor(title: String): String = "[[$title]]"

    /** Human-readable isolated-count line for the subgraph legend. */
    fun isolatedNotice(isolatedCount: Int): String =
        if (isolatedCount == 1) "1 isolated note" else "$isolatedCount isolated notes"
}