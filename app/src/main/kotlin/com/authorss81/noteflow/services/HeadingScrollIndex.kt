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
* Duplicate heading texts are disambiguated with a stable, unique occurrence
 * suffix ("Notes" then "Notes (2)") so both the rail labels AND the offset
 * lookups address the exact heading the user tapped. Unique labels are
 * GUARANTEED: a heading whose own text already ends in " (N)" (e.g. a literal
 * "Goals (2)") never collides with a generated suffix — the later duplicate is
 * pushed to the next free occurrence ("Goals (3)").
 */
class HeadingScrollIndex {

    /** A heading's identity: visible text, level and final occurrence counter. */
    data class Heading(
        val text: String,
        val level: Int,
        val occurrence: Int,
        val label: String
    )

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
     * next layout pass). Duplicate labels are disambiguated to be globally
     * unique using the occurrence counter; any collision with a source heading
     * that already ends in " (N)" bumps the counter until a free label is found.
     */
    fun build(rawHeadings: List<Pair<String, Int>>): HeadingScrollIndex {
        headings.clear()
        offsets.clear()
        val occurrences = HashMap<String, Int>()
        val usedLabels = HashSet<String>()
        for ((text, level) in rawHeadings) {
            if (text.isBlank()) continue
            val next = (occurrences[text] ?: 0) + 1
            occurrences[text] = next
            var occurrence = next
            var label = if (occurrence <= 1) text else "$text ($occurrence)"
            while (usedLabels.contains(label)) {
                occurrence += 1
                label = "$text ($occurrence)"
            }
            usedLabels.add(label)
            headings.add(Heading(text = text, level = level, occurrence = occurrence, label = label))
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