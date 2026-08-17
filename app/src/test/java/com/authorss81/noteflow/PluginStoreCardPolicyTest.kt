package com.authorss81.noteflow

import com.authorss81.noteflow.services.PluginStoreCardPolicy
import com.authorss81.noteflow.services.PluginStoreCardPolicy.Reveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-127 (plugin-store compact descriptions): the pure-JVM decision table
 * behind the Plugin Store's per-card summary/expand behaviour.
 *
 * These tests pin the SUMMARY TRUNCATION LOGIC and the COLLAPSE/EXPAND STATE
 * per card (the Compose rendering itself — `maxLines` + `Ellipsis` — is verified
 * by review; see `workspace/phase-127/REPORT.md`).
 */
class PluginStoreCardPolicyTest {

    private val shortDescription = "Extracts text on-device, offline, no API key."
    private val longDescription =
        "Reads the current note aloud with the platform text-to-speech engine — offline, " +
            "keyless, respects SilentToggle (a quiet mode that refuses to speak rather than " +
            "degrading silently). Engine availability is checked before any utterance is scheduled."

    @Test
    fun `collapsed is always the default state`() {
        // A brand-new dialog opens every card collapsed — short OR long description.
        listOf("", shortDescription, longDescription).forEach {
            assertEquals(Reveal.COLLAPSED, PluginStoreCardPolicy.defaultReveal())
        }
    }

    @Test
    fun `toggle flips a card between collapsed and expanded`() {
        assertEquals(Reveal.EXPANDED, PluginStoreCardPolicy.toggle(Reveal.COLLAPSED))
        assertEquals(Reveal.COLLAPSED, PluginStoreCardPolicy.toggle(Reveal.EXPANDED))
        // Round-trip lands back on collapsed.
        assertEquals(
            Reveal.COLLAPSED,
            PluginStoreCardPolicy.toggle(PluginStoreCardPolicy.toggle(Reveal.COLLAPSED))
        )
    }

    @Test
    fun `toggle label follows the reveal state`() {
        assertEquals("More", PluginStoreCardPolicy.toggleLabel(Reveal.COLLAPSED))
        assertEquals("Less", PluginStoreCardPolicy.toggleLabel(Reveal.EXPANDED))
    }

    @Test
    fun `long descriptions warrant the expand affordance`() {
        assertTrue(PluginStoreCardPolicy.needsExpandToggle(longDescription))
        // The three longest real catalog descriptions all collapse.
        assertTrue(PluginStoreCardPolicy.needsExpandToggle(
            "Reads the current note aloud with the platform text-to-speech engine — offline, " +
                "keyless, respects SilentToggle (a quiet mode that refuses to speak rather " +
                "than degrading silently)."
        ))
        assertTrue(PluginStoreCardPolicy.needsExpandToggle(
            "Type by voice. Speech recognition runs on-device whenever offline models exist; " +
                "activation is always a deliberate mic tap."
        ))
        assertTrue(PluginStoreCardPolicy.needsExpandToggle(
            "Captures the current canvas as an image note — optionally OCR's into a searchable " +
                "note via the existing on-device OCR plugin."
        ))
    }

    @Test
    fun `short descriptions never warrant a toggle`() {
        assertFalse(PluginStoreCardPolicy.needsExpandToggle(shortDescription))
        // One-liners that fit the collapsed two lines carry no affordance.
        listOf(
            "Rotates the ASCII letters of note text by 13 positions (ROT13 cipher).",
            "Converts note text to UPPERCASE, lowercase or Title Case.",
            "Exports any note to Markdown, HTML or PDF and shares it via the system share sheet."
        ).forEach { d ->
            assertFalse(PluginStoreCardPolicy.needsExpandToggle(d))
        }
    }

    @Test
    fun `boundary length of exactly the budget stays collapsed without a toggle`() {
        val boundary = "x".repeat(PluginStoreCardPolicy.MAX_SUMMARY_CHARS)
        assertFalse(PluginStoreCardPolicy.needsExpandToggle(boundary))
        assertEquals(boundary, PluginStoreCardPolicy.collapsedSummary(boundary))

        val overByOne = "x".repeat(PluginStoreCardPolicy.MAX_SUMMARY_CHARS + 1)
        assertTrue(PluginStoreCardPolicy.needsExpandToggle(overByOne))
        assertEquals(
            PluginStoreCardPolicy.MAX_SUMMARY_CHARS + 1, // prefix + ellipsis
            PluginStoreCardPolicy.collapsedSummary(overByOne).length
        )
    }

    @Test
    fun `collapsed summary is identity for short text`() {
        assertEquals("", PluginStoreCardPolicy.collapsedSummary(""))
        assertEquals(" ", PluginStoreCardPolicy.collapsedSummary(" "))
        assertEquals(shortDescription, PluginStoreCardPolicy.collapsedSummary(shortDescription))
    }

    @Test
    fun `collapsed summary truncates long text to a bounded ellipsis-terminated prefix`() {
        val summary = PluginStoreCardPolicy.collapsedSummary(longDescription)

        assertTrue(summary.length <= PluginStoreCardPolicy.MAX_SUMMARY_CHARS + 1)
        assertTrue(longDescription.startsWith(summary.removeSuffix("…")))
assertTrue(summary.endsWith("…"))
        assertNotEquals(longDescription, summary)
        // The truncation cut never leaves trailing whitespace before the ellipsis.
        assertFalse(summary.removeSuffix("…").endsWith(" "))
    }

    @Test
    fun `every real catalog description collapses to at most the two-line budget`() {
        // Guard against a future catalog edit accidentally blowing up a card:
        // no description may produce a collapsed summary longer than the budget.
        realCatalogDescriptions().forEach { d ->
            assertTrue(
                "catalog description exceeded summary budget: ${d.take(40)}…",
                PluginStoreCardPolicy.collapsedSummary(d).length <=
                    PluginStoreCardPolicy.MAX_SUMMARY_CHARS + 1
            )
        }
    }

    private fun realCatalogDescriptions(): List<String> = listOf(
        "Type by voice. Speech recognition runs on-device whenever offline models exist; " +
            "activation is always a deliberate mic tap.",
        "Reads the current note aloud with the platform text-to-speech engine — offline, " +
            "keyless, respects SilentToggle (a quiet mode that refuses to speak rather " +
            "than degrading silently).",
        "Captures the current canvas as an image note — optionally OCR's into a searchable " +
            "note via the existing on-device OCR plugin.",
        "Looks up a word's definition via the keyless dictionaryapi.dev API, with a bundled " +
            "offline fallback.",
        "Formats a pasted URL into a clean Markdown [title](url) link, fetching the page " +
            "title over HTTPS.",
        "Converts \"2 km to mi\" inline — length, mass, temperature and basic currency " +
            "(offline, no deps).",
        "Searches the web via the keyless DuckDuckGo Instant Answer API and inserts " +
            "[title](url) links.",
        "Extracts text from images on-device with ML Kit — offline, no API key.",
        "Word/character/paragraph counts, reading time, Flesch-Kincaid readability and a " +
            "note diff.",
        "Generates a structured markdown outline or checkbox checklist from the selected text.",
        "Detects a note's language (Lingua, offline) and auto-tags it as lang:<iso> on save.",
        "Receives shared text, images and files from other apps and stores them in an " +
            "encrypted note.",
        "Exports any note to Markdown, HTML or PDF and shares it via the system share sheet.",
        "Captures any web page as a clean Markdown note (offline extraction via jsoup).",
        "Converts a freehand ink stroke into a clean line, rectangle, ellipse or arrow on demand.",
        "Rotates the ASCII letters of note text by 13 positions (ROT13 cipher).",
        "Converts note text to UPPERCASE, lowercase or Title Case.",
        "Inserts a dated weather snapshot (keyless Open-Meteo, no GPS) into the note.",
        "Translates note text with ML Kit on-device — keyless, offline after a one-time " +
            "model download."
    )
}