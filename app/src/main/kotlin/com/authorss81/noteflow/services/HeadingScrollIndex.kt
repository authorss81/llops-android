package com.authorss81.noteflow.services

/**
 * Phase 174 — precomputed heading index for reader-mode quick-jump.
 *
 * Pure JVM decision table + mutable index for the anchored outline rail. It is
 * built ONCE from the already-parsed markdown document (never a re-parse), then
 * the composable registers the measured content-offset (px) of every heading as
 * it is laid out. Tapping a heading's label in the rail looks up the precomputed
 * offset here and scrolls the preview's scroll state to it.
 *
 * Duplicate heading texts are disambiguated with a stable, occurrence-based
 * suffix ("Notes" then "Notes (2)") so both the rail labels AND the offset
 * lookups address the exact heading the user tapped.
 */
class HeadingScrollIndex {

    /** A heading's identity: visible text, level and 1-based occurrence. */
    data class Heading(
        val text: String,
        val level: Int,
        val occurrence: Int
    ) {
        /** Stable unique label; duplicates carry a suffix ("Notes (2)"). */
        val label: String = if (occurrence <= 1) text else "$text (${occurrence})"
    }

    private val headings = mutableListOf<Heading>()
    private val offsets = mutableMapOf<Int, Int>()

    val isEmpty: Boolean get() = headings.isEmpty()
    val size: Int get() = headings.size

    /** The heading at document position [position], or null. */
    fun headingAt(position: Int): Heading? = headings.getOrNull(position)

    /** Unique labels in document order (what the rail renders). */
    fun labels(): List<String> = headings.map { it.label }

    /** Heading level at [position] (defaults to 1 for a missing entry). */
    fun levelAt(position: Int): Int = headings.getOrNull(position)?.level ?: 1

    /**
     * Build the index from document-order `(text, level)` pairs, skipping blank
     * texts and clearing any earlier state (offsets are re-registered by the
     * next layout pass).
     */
    fun build(rawHeadings: List<Pair<String, Int>>): HeadingScrollIndex {
        headings.clear()
        offsets.clear()
        val occurrences = HashMap<String, Int>()
        for ((text, level) in rawHeadings) {
            if (text.isBlank()) continue
            val next = (occurrences[text] ?: 0) + 1
            occurrences[text] = next
            headings.add(Heading(text = text, level = level, occurrence = next))
        }
        return this
    }

    /** Record the measured content offset (px) for document position [position]. */
    fun register(position: Int, contentOffsetPx: Int) {
        if (position in headings.indices) {
            offsets[position] = contentOffsetPx.coerceAtLeast(0)
        }
    }

    /** Precomputed content offset px to scroll to, or null when not yet measured. */
    fun offsetForPosition(position: Int): Int? = offsets[position]

    /** Translate a rail label back to its scroll offset, or null when unknown. */
    fun offsetForLabel(label: String): Int? {
        val position = headings.indexOfFirst { it.label == label }
        if (position < 0) return null
        return offsets[position]
    }

    /** Drop measured offsets (the composable calls this before a re-layout). */
    fun clearOffsets() {
        offsets.clear()
    }
}