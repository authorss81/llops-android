package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.DetectedLanguage
import com.authorss81.noteflow.plugins.LanguageDetectionOutcome
import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionCore
import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 Language Detection pure-JVM tests over Lingua: English + German
 * detection on real (≥20 char) snippets, the too-short gate, auto-tag merging
 * that honours user overrides, and plugin wiring.
 */
class LanguageDetectionTest {

    private val englishParagraph = """
        The quick brown fox jumps over the lazy dog. This sentence is long
        enough to be recognized as English by a language detector, since it
        contains many common English function words and grammatical patterns.
    """.trimIndent()

    private val germanParagraph = """
        Der schnelle braune Fuchs springt über den faulen Hund. Dieser Satz ist
        lang genug, um von einem Spracherkenner als Deutsch erkannt zu werden,
        weil er viele typische deutsche Wörter und Satzstrukturen enthält.
    """.trimIndent()

    // ---- detection ---------------------------------------------------------

    @Test
    fun `detects English on a real paragraph`() {
        val outcome = LanguageDetectionCore.detectLanguage(englishParagraph)
        assertTrue("expected Success, got $outcome", outcome is LanguageDetectionOutcome.Success)
        val lang = (outcome as LanguageDetectionOutcome.Success).language
        assertEquals("en", lang.isoCode)
        assertTrue(lang.confidence > 0.0)
        assertTrue(lang.displayName.isNotBlank())
    }

    @Test
    fun `detects German on a real paragraph`() {
        val outcome = LanguageDetectionCore.detectLanguage(germanParagraph)
        assertTrue("expected Success, got $outcome", outcome is LanguageDetectionOutcome.Success)
        assertEquals("de", (outcome as LanguageDetectionOutcome.Success).language.isoCode)
    }

    @Test
    fun `too-short text returns NoMatch with a user-facing reason`() {
        val outcome = LanguageDetectionCore.detectLanguage("Hi.")
        assertTrue(outcome is LanguageDetectionOutcome.NoMatch)
        assertTrue((outcome as LanguageDetectionOutcome.NoMatch).message.contains("short"))
    }

    @Test
    fun `blank text returns NoMatch`() {
        assertTrue(LanguageDetectionCore.detectLanguage("   ") is LanguageDetectionOutcome.NoMatch)
    }

    // ---- auto-tagging ------------------------------------------------------

    @Test
    fun `autoTagLanguage appends lang tag when none exists`() {
        val merged = LanguageDetectionCore.autoTagLanguage(englishParagraph, "work,inbox")
        assertTrue("merged=$merged", merged.contains("lang:en"))
        assertTrue(merged.contains("work"))
        assertTrue(merged.contains("inbox"))
    }

    @Test
    fun `autoTagLanguage keeps a user language override untouched`() {
        val merged = LanguageDetectionCore.autoTagLanguage(englishParagraph, "lang:de,notes")
        assertEquals("lang:de,notes", merged)
    }

    @Test
    fun `autoTagLanguage honours language prefix override too`() {
        val merged = LanguageDetectionCore.autoTagLanguage(englishParagraph, "language:fr")
        assertEquals("language:fr", merged)
    }

    @Test
    fun `autoTagLanguage returns tags unchanged when text is too short`() {
        assertEquals("todo", LanguageDetectionCore.autoTagLanguage("Hi.", "todo"))
    }

    @Test
    fun `autoTagLanguage does not duplicate the tag`() {
        val once = LanguageDetectionCore.autoTagLanguage(englishParagraph, "")
        val twice = LanguageDetectionCore.autoTagLanguage(englishParagraph, once)
        assertEquals(once, twice)
    }

    // ---- tag recognition ---------------------------------------------------

    @Test
    fun `isLanguageTag matches lang and language prefixes case-insensitively`() {
        assertTrue(LanguageDetectionCore.isLanguageTag("lang:en"))
        assertTrue(LanguageDetectionCore.isLanguageTag("LANG:DE"))
        assertTrue(LanguageDetectionCore.isLanguageTag("language:fr"))
        assertFalse(LanguageDetectionCore.isLanguageTag("work"))
        assertFalse(LanguageDetectionCore.isLanguageTag(""))
    }

    // ---- plugin wiring -----------------------------------------------------

    @Test
    fun `engine delegates to the pure core and declares the capability`() {
        val engine = LanguageDetectionEngine()
        assertTrue(com.authorss81.noteflow.plugins.PluginCapability.LanguageDetection in engine.capabilities)
        assertTrue(engine.isLanguageTag("lang:en"))
        assertEquals(
            "lang:de",
            engine.autoTagLanguage(englishParagraph, "lang:de")
        )
    }
}