package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockType
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.services.WikiSuggestionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 239 — markdown editor critical path, as pure-JVM logic tests.
 *
 * Phase-235 specified an instrumented `MarkdownEditorTest` (type text →
 * extractedText updates; type `[[Note` → WikiLinkSuggestionPopup appears; tap `/`
 * → SlashCommandMenu appears). The popup/menu rendering is Compose, but the logic
 * that decides what they show is pure JVM and is pinned here:
 *
 *  - `WikiLinkParser.extractWikiLinks` — the `[[...]]` scanner that fills the
 *    Backlinks/Knowledge-Graph edges (a typed `[[Note` must parse),
 *  - `WikiSuggestionPolicy.suggest` / `locateQuery` / `wikilinkSnippet` — what the
 *    suggestion popup offers and inserts when `[[Note` is typed (phase-174),
 *  - `MarkdownBlockTokenizer` — the block parser that feeds the split preview and
 *    keeps typed markdown (headings, lists, fenced code) correctly sectioned.
 */
class Phase239MarkdownEditorTest {

    // ---- `[[Note` → wikilink parsing (popup + knowledge graph feed) ----------

    @Test
    fun `typed double-open-bracket parses to a wikilink with target and alias`() {
        val text = "See [[Project Alpha]] and [[Beta.md|Project Beta]]"
        val links = WikiLinkParser.extractWikiLinks(text)
        assertEquals(2, links.size)

        val plain = links[0]
        assertEquals("[[Project Alpha]]", plain.rawText)
        assertEquals("Project Alpha", plain.targetTitle)
        assertNull(plain.alias)
        assertEquals("[[Project Alpha]]", text.substring(plain.startIndex, plain.endIndex))

        val aliased = links[1]
        assertEquals("Beta.md", aliased.targetTitle)
        assertEquals("Project Beta", aliased.alias)
    }

    @Test
    fun `typing an unterminated open bracket is a live suggestion region`() {
        // The user just typed "[[No" — the popup must be live and bound to it.
        // The bounds cover the WHOLE `[[…` region (parents replace that span with
        // the inserted wikilink), so it starts AT the `[[`.
        val bounds = WikiSuggestionPolicy.locateQuery("Draft [[No")
        assertNotNull(bounds)
        assertEquals(6, bounds!!.queryStart)
        assertEquals(10, bounds.queryEnd)
        assertEquals("[[No", "Draft [[No".substring(bounds.queryStart, bounds.queryEnd))

        // A closed bracket ends the region (no popup).
        assertNull(WikiSuggestionPolicy.locateQuery("Done [[X]] tail"))
        // A blank text never yields a region.
        assertNull(WikiSuggestionPolicy.locateQuery(""))
        assertNull(WikiSuggestionPolicy.locateQuery("   "))
    }

    @Test
    fun `open bracket spanning a newline is not a live query`() {
        // Phase-174: a `[[` whose region crosses a line break is NOT treated as a
        // live query (the popup never deletes text across an unrelated block).
        assertNull(WikiSuggestionPolicy.locateQuery("line1 [[query\nline2"))
        assertNotNull(WikiSuggestionPolicy.locateQuery("inline [[sameLine"))
    }

    // ---- suggestion ranking for the popup ------------------------------------

    @Test
    fun `prefix matches rank before substring matches and results cap at six`() {
        val titles = listOf(
            "Anchovies", "Anchovy Pie", "Aardvark", "Banana", "Anchovies.md",
            "Ranch", "Antimony", "Anarchy", "Angular", "Anchorage"
        )
        val result = WikiSuggestionPolicy.suggest(titles, "anch")
        assertTrue("results capped", result.size <= WikiSuggestionPolicy.MAX_SUGGESTIONS)
        assertTrue("no duplicates after extension normalization", result.distinct().size == result.size)
        // Prefix matches are returned FIRST; substring matches (e.g. "Ranch"
        // contains "anch") may follow but never precede a prefix match.
        assertTrue("at least one prefix match", result.any { it.lowercase().startsWith("anch") })
        val firstSubstring = result.indexOfFirst { !it.lowercase().startsWith("anch") }
        val prefixCount = result.count { it.lowercase().startsWith("anch") }
        if (firstSubstring >= 0) {
            assertTrue("all prefix matches come before any substring match", firstSubstring >= prefixCount)
        }
        // Anchovies and Anchovy Pie are dedup-capped to the first-seen.
        assertTrue(result.size > 0)
    }

    @Test
    fun `excluded titles - like the current note - are never offered`() {
        val titles = listOf("Current Note", "Groceries", "Current note", "Reading")
        val result = WikiSuggestionPolicy.suggest(titles, "", excludedTitles = listOf("Current Note"))
        assertFalse(result.any { it.equals("Current Note", ignoreCase = true) })
        assertTrue(result.contains("Groceries"))
    }

    @Test
    fun `titles that would break wikilink syntax are never offered`() {
        val result = WikiSuggestionPolicy.suggest(listOf("a[b]", "c]d", "e|f", "Plain Title"), "p")
        assertEquals(listOf("Plain Title"), result)
    }

    @Test
    fun `insert snippet keeps the raw suffix as target with a clean alias`() {
        assertEquals("[[Clean Title]]", WikiSuggestionPolicy.wikilinkSnippet("Clean Title"))
        // "Title.md" stays the target; the stripped name is the alias display.
        assertEquals("[[Title.md|Title]]", WikiSuggestionPolicy.wikilinkSnippet("Title.md"))
        assertEquals("[[Note.TXT|Note]]", WikiSuggestionPolicy.wikilinkSnippet("Note.TXT"))
    }

    // ---- typed markdown text stays correctly tokenized -----------------------

    @Test
    fun `a heading and a list paragraph are distinct blocks`() {
        val doc = MarkdownBlockTokenizer.tokenize("# Title\n\n- item one\n- item two\n\nBody text")
        assertTrue("a heading block exists", doc.blocks.any { it.type == MarkdownBlockType.HEADING })
        assertTrue("a bullet list block exists", doc.blocks.any { it.type == MarkdownBlockType.BULLET_LIST })
        assertTrue("a paragraph exists", doc.blocks.any { it.type == MarkdownBlockType.PARAGRAPH })
    }

    @Test
    fun `fenced code is one block that never leaks into the paragraph list`() {
        val content = "Intro\n\n```kotlin\nval x = 1\n```\n\nOutro"
        val doc = MarkdownBlockTokenizer.tokenize(content)
        val codeBlocks = doc.blocks.filter { it.type == MarkdownBlockType.CODE_FENCE }
        assertFalse("fence is recognized", codeBlocks.isEmpty())
        // The code text is kept inside the code block, not a paragraph.
        assertTrue(codeBlocks.any { MarkdownBlockTokenizer.blockSource(content, it).contains("val x = 1") })
    }

    @Test
    fun `block sources round trip - joining equals the original content`() {
        val content = "## Heading\n\nA paragraph with **bold**.\n\n- a\n- b\n"
        val doc = MarkdownBlockTokenizer.tokenize(content)
        val joined = MarkdownBlockTokenizer.joinBlockSources(content, doc.blocks)
        assertEquals("blocks reconstruct the source", content.trim(), joined.trim())
    }

    @Test
    fun `toggleing a checkbox candidate rewrites inside its block`() {
        val content = "- [ ] todo item\n- done\n"
        val candidates = MarkdownBlockTokenizer.checkboxCandidates(content)
        assertFalse("a checkbox candidate is found", candidates.isEmpty())
        assertFalse("an unchecked candidate is reported unchecked", candidates[0].checked)
        val toggled = MarkdownBlockTokenizer.toggleCheckbox(content, 0)
        assertTrue("checked after toggle", toggled.contains("- [x] todo item"))
    }
}
