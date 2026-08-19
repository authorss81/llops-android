package com.authorss81.noteflow

import com.authorss81.noteflow.services.NoteStatsFormatPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Phase 174 (Feature 1) — note-stats footer formatting decision table.
 */
class Phase174NoteStatsFormatPolicyTest {

    @Test
    fun `reading time ceils seconds to minutes with a one-minute floor`() {
        assertEquals(0, NoteStatsFormatPolicy.readingTimeMinutes(0))
        assertEquals(1, NoteStatsFormatPolicy.readingTimeMinutes(1))
        assertEquals(1, NoteStatsFormatPolicy.readingTimeMinutes(59))
        assertEquals(1, NoteStatsFormatPolicy.readingTimeMinutes(60))
        assertEquals(2, NoteStatsFormatPolicy.readingTimeMinutes(61))
        assertEquals(2, NoteStatsFormatPolicy.readingTimeMinutes(120))
        assertEquals(3, NoteStatsFormatPolicy.readingTimeMinutes(121))
        assertEquals(4, NoteStatsFormatPolicy.readingTimeMinutes(181))
    }

    @Test
    fun `reading time label is empty for zero minutes`() {
        assertEquals("", NoteStatsFormatPolicy.readingTimeLabel(0))
        assertEquals("~1 min read", NoteStatsFormatPolicy.readingTimeLabel(1))
        assertEquals("~6 min read", NoteStatsFormatPolicy.readingTimeLabel(359))
        assertEquals("~6 min read", NoteStatsFormatPolicy.readingTimeLabel(360))
        assertEquals("~7 min read", NoteStatsFormatPolicy.readingTimeLabel(361))
    }

    @Test
    fun `counts are formatted locale-safely`() {
        assertEquals("1", NoteStatsFormatPolicy.formatCount(1, Locale.US))
        assertEquals("1,234", NoteStatsFormatPolicy.formatCount(1234, Locale.US))
        // de-DE groups with a dot — the label must render the locale's own format.
        assertEquals("1.234", NoteStatsFormatPolicy.formatCount(1234, Locale.GERMANY))
    }

    @Test
    fun `blank-note detection`() {
        assertTrue(NoteStatsFormatPolicy.isBlankNote(0, 0))
        assertFalse(NoteStatsFormatPolicy.isBlankNote(0, 5))
        assertFalse(NoteStatsFormatPolicy.isBlankNote(3, 0))
        assertFalse(NoteStatsFormatPolicy.isBlankNote(3, 5))
    }

    @Test
    fun `word and char count labels pluralize in the locale`() {
        assertEquals("1 word", NoteStatsFormatPolicy.wordCountLabel(1, Locale.US))
        assertEquals("1,234 words", NoteStatsFormatPolicy.wordCountLabel(1234, Locale.US))
        assertEquals("5,678 chars", NoteStatsFormatPolicy.charCountLabel(5678, Locale.US))
    }

    @Test
    fun `full stats line joins all three parts`() {
        assertEquals(
            "1,234 words · ~6 min read · 5,678 chars",
            NoteStatsFormatPolicy.statsLabel(wordCount = 1234, readingTimeSeconds = 359, characterCount = 5678, locale = Locale.US)
        )
    }

    @Test
    fun `zero reading time omits the read segment`() {
        assertEquals(
            "1,234 words · 5,678 chars",
            NoteStatsFormatPolicy.statsLabel(wordCount = 1234, readingTimeSeconds = 0, characterCount = 5678, locale = Locale.US)
        )
    }

    @Test
    fun `blank note produces no stats line`() {
        assertNull(NoteStatsFormatPolicy.statsLabel(wordCount = 0, readingTimeSeconds = 0, characterCount = 0, locale = Locale.US))
    }

    @Test
    fun `latency guard skips recompute for small literal changes`() {
        // First sample always computes.
        assertTrue(NoteStatsFormatPolicy.shouldRecomputeStats(previousTextLength = -1, currentTextLength = 0))
        // A trailing-whitespace keystroke (delta < 8) keeps the previous result.
        assertFalse(NoteStatsFormatPolicy.shouldRecomputeStats(previousTextLength = 100, currentTextLength = 101))
        // Material growth triggers a recompute.
        assertTrue(NoteStatsFormatPolicy.shouldRecomputeStats(previousTextLength = 100, currentTextLength = 108))
    }
}