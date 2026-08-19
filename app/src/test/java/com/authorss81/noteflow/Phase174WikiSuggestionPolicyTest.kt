package com.authorss81.noteflow

import com.authorss81.noteflow.services.WikiSuggestionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 174 (Feature 3) — wiki-link `[[` autocomplete decision table.
 */
class Phase174WikiSuggestionPolicyTest {

    @Test
    fun `prefix matches rank ahead of substring matches`() {
        val candidates = listOf("Meeting notes", "Project meeting", "Other")
        val result = WikiSuggestionPolicy.suggest(candidates, "meet")
        assertEquals("Meeting notes", result[0])
        assertEquals("Project meeting", result[1])
    }

    @Test
    fun `matching is case insensitive`() {
        val candidates = listOf("MEETING-agenda", "meeting-log")
        val result = WikiSuggestionPolicy.suggest(candidates, "MEETing")
        assertTrue(result.contains("MEETING-agenda"))
        assertTrue(result.contains("meeting-log"))
    }

    @Test
    fun `results are deduped case-insensitively keeping first-seen order`() {
        val candidates = listOf("Notes", "notes", "NOTES", "Plan")
        val result = WikiSuggestionPolicy.suggest(candidates, "")
        assertEquals(listOf("Notes", "Plan"), result)
    }

    @Test
    fun `blank query returns a capped set of titled notes`() {
        val candidates = (1..20).map { "Note $it" }
        val result = WikiSuggestionPolicy.suggest(candidates, "")
        assertEquals(WikiSuggestionPolicy.MAX_SUGGESTIONS, result.size)
        assertEquals("Note 1", result.first())
    }

    @Test
    fun `the result is never larger than the cap`() {
        val candidates = (1..50).map { "prefix$it" }
        val result = WikiSuggestionPolicy.suggest(candidates, "prefix")
        assertEquals(WikiSuggestionPolicy.MAX_SUGGESTIONS, result.size)
    }

    @Test
    fun `titles that would break wikilink syntax are never offered`() {
        val result = WikiSuggestionPolicy.suggest(listOf("bad[title", "also]bad", "pipe|bad", "good title"), "b")
        // Only "good title" survives — but note the query "b" no longer prefixes
        // it, so the empty-query path is what preserves it.
        val resultEmpty = WikiSuggestionPolicy.suggest(listOf("bad[title", "also]bad", "pipe|bad", "good title"), "")
        assertEquals(listOf("good title"), resultEmpty)
        assertTrue(result.none { it.contains('[') || it.contains(']') || it.contains('|') })
    }

    @Test
    fun `excluded titles (current note) are never offered`() {
        val candidates = listOf("Meeting", "Plan", "meeting")
        val result = WikiSuggestionPolicy.suggest(candidates, "", excludedTitles = listOf("meeting"))
        assertEquals(listOf("Plan"), result)
    }

    @Test
    fun `raw md txt suffixes are stripped for ranking and dedup`() {
        val result = WikiSuggestionPolicy.suggest(listOf("Meeting.md", "Meeting.txt", "Plan.md"), "meet")
        assertEquals(listOf("Meeting"), result)
    }

    @Test
    fun `syntax-breaking check`() {
        assertTrue(WikiSuggestionPolicy.breaksWikilinkSyntax("a[b"))
        assertTrue(WikiSuggestionPolicy.breaksWikilinkSyntax("a]b"))
        assertTrue(WikiSuggestionPolicy.breaksWikilinkSyntax("a|b"))
        assertTrue(!WikiSuggestionPolicy.breaksWikilinkSyntax("plain"))
    }

    @Test
    fun `plain titles insert without an alias`() {
        assertEquals("[[Meeting]]", WikiSuggestionPolicy.wikilinkSnippet("Meeting"))
    }

    @Test
    fun `suffixed titles keep the raw target with stripped alias`() {
        assertEquals("[[Meeting.md|Meeting]]", WikiSuggestionPolicy.wikilinkSnippet("Meeting.md"))
        assertEquals("[[meeting.TXT|meeting]]", WikiSuggestionPolicy.wikilinkSnippet("meeting.TXT"))
    }

    @Test
    fun `locateQuery finds the last unterminated double-bracket region`() {
        val bounds = WikiSuggestionPolicy.locateQuery("before [[Me")
        assertEquals(7, bounds!!.queryStart)
        assertEquals("before [[Me".length, bounds.queryEnd)
    }

    @Test
    fun `locateQuery ignores closed links and blanks`() {
        assertNull(WikiSuggestionPolicy.locateQuery(""))
        assertNull(WikiSuggestionPolicy.locateQuery("   "))
        assertNull(WikiSuggestionPolicy.locateQuery("[[done]] trailing"))
        assertNull(WikiSuggestionPolicy.locateQuery("plain text"))
    }

    @Test
    fun `locateQuery returns null when an open query spans a line break`() {
        // Multi-line block content after the `[[` is NOT query ink — the popup
        // must stay out so selecting a suggestion can't delete unrelated lines.
        assertNull(WikiSuggestionPolicy.locateQuery("note: [[Meeting\nmore prose on the next line"))
        assertNull(WikiSuggestionPolicy.locateQuery("[[unclosed\n\n\n"))
        // A single-line query stays live.
        val bounds = WikiSuggestionPolicy.locateQuery("plain [[Me")
        assertEquals(6, bounds!!.queryStart)
    }

    @Test
    fun `locateQuery picks the last run when multiple opens exist`() {
        val bounds = WikiSuggestionPolicy.locateQuery("[[Done]] and [[WIP")
        assertEquals("[[Done]] and [[WIP".indexOf("[[WIP"), bounds!!.queryStart)
    }
}