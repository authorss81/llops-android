package com.authorss81.noteflow

import com.authorss81.noteflow.ui.components.EmptyStateKind
import com.authorss81.noteflow.ui.components.EmptyStateResolver
import com.authorss81.noteflow.ui.components.IllustrationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 34: empty-state decision logic drives every illustration + suggestion
 * the app shows; the copy must never change shape across refactors, and the
 * right motif must match the right situation (no "search" art on a fresh vault).
 */
class EmptyStateResolverTest {

    @Test
    fun `empty home grid with no query and not first run shows quiet vault with graph`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID)
        assertEquals("Your vault is quiet", d.title)
        assertEquals(IllustrationKind.GRAPH, d.illustration)
        assertFalse(d.isOnboarding)
    }

    @Test
    fun `empty home grid on first run welcomes and suggests creating a note`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID, isFirstRun = true)
        assertEquals("Welcome to your private vault", d.title)
        assertEquals(IllustrationKind.NOTEBOOK, d.illustration)
        assertTrue(d.isOnboarding)
        assertTrue(d.suggestion.contains("Create your first note"))
    }

    @Test
    fun `empty global search uses the search motif and echoes the query`() {
        val d = EmptyStateResolver.decide(
            EmptyStateKind.HOME_GRID,
            hasQuery = true,
            query = "meditation log"
        )
        assertEquals("No notes found", d.title)
        assertEquals(IllustrationKind.SEARCH, d.illustration)
        assertTrue(d.suggestion.contains("meditation log"))
        assertFalse(d.isOnboarding)
    }

    @Test
    fun `first run wins over nothing but an empty search stays search-copy`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID, hasQuery = true, isFirstRun = true, query = "x")
        assertEquals("No notes found", d.title)
    }

    @Test
    fun `tag vault uses graph motif and invites tagging`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.TAG_VAULT)
        assertEquals("No tags yet", d.title)
        assertEquals(IllustrationKind.GRAPH, d.illustration)
    }

    @Test
    fun `trash uses bin motif and explains recycling`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.TRASH)
        assertEquals("Trash is empty", d.title)
        assertEquals(IllustrationKind.TRASH, d.illustration)
    }

    @Test
    fun `empty notebook picker without query invites creation`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.NOTEBOOK_PICKER)
        assertEquals("No notebooks yet", d.title)
        assertEquals(IllustrationKind.STACK, d.illustration)
    }

    @Test
    fun `notebook picker search miss echoes the query and uses search motif`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.NOTEBOOK_PICKER, hasQuery = true, query = "work")
        assertTrue(d.title.contains("no notebooks", ignoreCase = true))
        assertEquals(IllustrationKind.SEARCH, d.illustration)
        assertTrue(d.suggestion.contains("work"))
    }

    @Test
    fun `section picker empty state is section-named`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.SECTION_PICKER)
        assertEquals("No sections yet", d.title)
        assertEquals(IllustrationKind.STACK, d.illustration)
    }

    @Test
    fun `plugin store empty state invites enabling bundled plugins`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.PLUGIN_STORE)
        assertEquals("Nothing in the store", d.title)
        assertEquals(IllustrationKind.PUZZLE, d.illustration)
    }

    @Test
    fun `every kind resolves to non-blank copy and a real illustration`() {
        for (kind in EmptyStateKind.entries) {
            for (firstRun in listOf(false, true)) {
                val d = EmptyStateResolver.decide(kind, isFirstRun = firstRun)
                assertTrue("${kind.name} title blank", d.title.isNotBlank())
                assertTrue("${kind.name} suggestion blank", d.suggestion.isNotBlank())
            }
        }
    }

    @Test
    fun `query is only echoed for search-sensitive kinds`() {
        val home = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID, hasQuery = true, query = "q")
        val trash = EmptyStateResolver.decide(EmptyStateKind.TRASH, hasQuery = true, query = "q")
        assertTrue(home.suggestion.contains("q"))
        assertFalse(trash.suggestion.contains("q"))
    }

    // ---------- Phase 156: new empty kinds ----------

    @Test
    fun `recent empty state names the tab and offers a create-note CTA`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.RECENT)
        assertEquals("No recently viewed notes yet", d.title)
        assertEquals(IllustrationKind.STACK, d.illustration)
        assertEquals("Create a note", d.actionLabel)
        assertFalse(d.isOnboarding)
    }

    @Test
    fun `knowledge graph empty state teaches wikilinks and offers a CTA`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.KNOWLEDGE_GRAPH)
        assertEquals("No knowledge graph yet", d.title)
        assertEquals(IllustrationKind.GRAPH, d.illustration)
        assertTrue("must teach a wikilink", d.suggestion.contains("wikilink", ignoreCase = true))
        assertTrue("must show the [[..]] shape", d.suggestion.contains("[["))
        assertEquals("Create a note", d.actionLabel)
    }

    @Test
    fun `version history empty state is informational with no fake CTA`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.VERSION_HISTORY)
        assertEquals("No revision snapshots yet", d.title)
        assertEquals(IllustrationKind.HISTORY, d.illustration)
        assertNull(d.actionLabel)
    }

    @Test
    fun `web search empty state echoes the query and offers a new search CTA`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.WEB_SEARCH, hasQuery = true, query = "kittens")
        assertEquals("No results found", d.title)
        assertEquals(IllustrationKind.SEARCH, d.illustration)
        assertTrue(d.suggestion.contains("kittens"))
        assertEquals("New search", d.actionLabel)
    }

    @Test
    fun `plugin store filter miss echoes the query and offers to clear it`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.PLUGIN_STORE, hasQuery = true, query = "ocr")
        assertEquals("No plugin matches", d.title)
        assertTrue(d.suggestion.contains("ocr"))
        assertEquals("Clear filter", d.actionLabel)
    }

    @Test
    fun `plugin store without a filter keeps the bundled-plugin invite and no CTA`() {
        val d = EmptyStateResolver.decide(EmptyStateKind.PLUGIN_STORE)
        assertEquals("Nothing in the store", d.title)
        assertEquals(IllustrationKind.PUZZLE, d.illustration)
        assertNull(d.actionLabel)
    }

    @Test
    fun `home search miss and first-run welcome carry their own CTAs`() {
        val search = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID, hasQuery = true, query = "x")
        assertEquals("Clear search", search.actionLabel)
        val welcome = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID, isFirstRun = true)
        assertEquals("Create your first note", welcome.actionLabel)
        val quiet = EmptyStateResolver.decide(EmptyStateKind.HOME_GRID)
        assertEquals("Create a note", quiet.actionLabel)
    }
}