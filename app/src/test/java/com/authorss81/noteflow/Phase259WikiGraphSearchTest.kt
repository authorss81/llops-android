package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockType
import com.authorss81.noteflow.services.VaultSearchPolicy
import com.authorss81.noteflow.services.WikiLinkParser as ServicesWikiLinkParser
import com.authorss81.noteflow.utils.WikiLinkParser as UtilsWikiLinkParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 259 — wiki/graph/search hardening pins (behavior + source wiring).
 *
 *  - escaped titles never crash the legacy matcher (C++/[TODO]/a|b);
 *  - per-page / total extraction caps enforced on BOTH parsers;
 *  - fenced code blocks contribute no links/tags (false edges);
 *  - page-list fingerprints are fixed-size hashes, not ~100KB keys;
 *  - table delimiter rows require a pipe (no `a | b` + `---` false TABLE);
 *  - tags column is searchable; exact probes still cover whole bodies;
 *  - graph/tag panels rekey on the corpus generation (source pins).
 */
class Phase259WikiGraphSearchTest {

    // ---------- behavior: escaped titles ----------

    @Test
    fun `metachar titles never crash the legacy matcher`() {
        val source = "We ship C++ and [TODO] items and a|b choices. Party tonight."
        val titles = listOf("C++", "[TODO]", "a|b", "(parens)", "party", "art", "note.md")
        val matches = UtilsWikiLinkParser.findLinkedPageIdsForContent(source, titles)
        assertTrue("plain title must still match", matches.contains("party"))
        assertFalse("must not match inside another word", matches.contains("art"))
    }

    @Test
    fun `legacy matcher handles empty inputs`() {
        assertTrue(UtilsWikiLinkParser.findLinkedPageIdsForContent("", listOf("a")).isEmpty())
        assertTrue(UtilsWikiLinkParser.findLinkedPageIdsForContent("text", emptyList()).isEmpty())
    }

    // ---------- behavior: caps ----------

    @Test
    fun `per-page link cap enforced on both parsers`() {
        val dense = (1..2000).joinToString(" ") { "[[page$it]]" }
        val links = ServicesWikiLinkParser.extractWikiLinks(dense)
        assertEquals(ServicesWikiLinkParser.MAX_LINKS_PER_PAGE, links.size)
        val legacy = UtilsWikiLinkParser.extractWikiLinks(dense)
        assertTrue(
            "legacy facade must not exceed the services cap",
            legacy.size <= ServicesWikiLinkParser.MAX_LINKS_PER_PAGE
        )
    }

    @Test
    fun `tag extraction is bounded`() {
        val manyTags = (1..30000).joinToString(" ") { "#tag$it" }
        val tags = ServicesWikiLinkParser.extractTags(manyTags)
        assertTrue(
            "services tags must be capped at MAX_TAGS",
            tags.size <= ServicesWikiLinkParser.MAX_TAGS
        )
        val legacy = UtilsWikiLinkParser.extractTags(manyTags)
        assertTrue(
            "legacy tags must be capped",
            legacy.size <= UtilsWikiLinkParser.MAX_TAGS_PER_TEXT
        )
    }

    // ---------- behavior: fenced code is not edges ----------

    @Test
    fun `fenced code blocks contribute no links`() {
        val text = "Real [[target]]\n```kotlin\ncode [[fake]]\n```\n"
        val links = ServicesWikiLinkParser.extractWikiLinks(text)
        assertEquals(listOf("target"), links.map { it.targetTitle })
        val legacy = UtilsWikiLinkParser.extractWikiLinks(text)
        assertEquals(listOf("target"), legacy.map { it.targetTitle })
    }

    @Test
    fun `fenced code blocks contribute no tags`() {
        val text = "real #realtag\n```\n#codetag\n```\n"
        val tags = ServicesWikiLinkParser.extractTags(text)
        assertTrue(tags.contains("realtag"))
        assertFalse(tags.contains("codetag"))
    }

    // ---------- behavior: fingerprint hash ----------

    @Test
    fun `page fingerprint is a fixed-size hash`() {
        val pages = (1..10).map {
            NotePageEntity(id = "p$it", sectionId = "s", title = "t$it", updatedAt = it.toLong())
        }
        val fp = ServicesWikiLinkParser.pagesFingerprint(pages)
        assertEquals(64, fp.length)
        assertTrue(fp.matches(Regex("[0-9a-f]+")))
        assertEquals("", ServicesWikiLinkParser.pagesFingerprint(emptyList()))
        val fp2 = ServicesWikiLinkParser.pagesFingerprint(pages.drop(1))
        assertNotEquals("different lists must fingerprint differently", fp, fp2)
    }

    // ---------- behavior: table delimiter needs a pipe ----------

    @Test
    fun `pipe prose plus thematic break is not a table`() {
        val blocks = MarkdownBlockTokenizer.blocks("a | b\n---\n")
        assertTrue(blocks.none { it.type == MarkdownBlockType.TABLE })
    }

    @Test
    fun `real tables still tokenize as tables`() {
        val blocks = MarkdownBlockTokenizer.blocks("name | age\n--- | ---\nfoo | 3\n")
        assertTrue(blocks.any { it.type == MarkdownBlockType.TABLE })
    }

    // ---------- behavior: tags searchable, exact whole-body ----------

    @Test
    fun `tags column matches exact queries`() {
        val page = NotePageEntity(
            id = "p", sectionId = "s", title = "Unrelated",
            extractedText = "nothing here", tags = "travel, japan"
        )
        assertTrue(VaultSearchPolicy.pageMatches(page, "japan"))
        assertEquals(
            VaultSearchPolicy.SearchMatchTier.EXACT,
            VaultSearchPolicy.pageMatchTier(page, "japan")
        )
        assertFalse(VaultSearchPolicy.pageMatches(page, "zzzqqq"))
    }

    @Test
    fun `exact probe still covers the whole body past the fuzzy cap`() {
        val bigBody = "x".repeat(20000) + "needle"
        val page = NotePageEntity(
            id = "p", sectionId = "s", title = "t",
            extractedText = bigBody, tags = ""
        )
        assertTrue(VaultSearchPolicy.pageMatches(page, "needle"))
    }

    // ---------- source pins: shadow facade ----------

    @Test
    fun `legacy shadow escapes titles and delegates`() {
        val source = readSource("utils/WikiLinkParser.kt")
        assertTrue(source.contains("Regex.escape"))
        assertTrue(source.contains("ServicesWikiLinkParser.extractWikiLinks"))
        assertFalse(
            "unescaped per-page title interpolation must be gone",
            source.contains("\$page")
        )
    }

    // ---------- source pins: services parser ----------

    @Test
    fun `services parser hashes fingerprints caps tags and skips fences`() {
        val source = readSource("services/WikiLinkParser.kt")
        assertTrue(source.contains("MessageDigest"))
        assertTrue(source.contains("fun fenceRanges"))
        assertTrue(
            source.contains("extractTagsBounded(text, MAX_TAGS)")
        )
    }

    // ---------- source pins: graph rekeys on generation ----------

    @Test
    fun `knowledge graph rebuilds on corpus generation`() {
        val source = readSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(source.contains("searchCorpusGenerationFlow"))
        assertTrue(source.contains("LaunchedEffect(corpusGeneration)"))
        assertFalse(
            "once-only build must be gone",
            source.contains("LaunchedEffect(Unit)")
        )
        assertTrue(source.contains("rememberUpdatedState(nodes)"))
        assertFalse(
            "per-node edge recount must be gone",
            source.contains("edgeRefs.count")
        )
    }

    @Test
    fun `tag explorer rekeys on generation and clears on lock`() {
        val source = readSource("ui/components/TagExplorerView.kt")
        assertTrue(
            source.contains(
                "LaunchedEffect(notebookId, selectedNotebook?.tags, corpusGeneration, authenticated)"
            )
        )
        assertTrue(source.contains("searchCorpusGenerationFlow"))
    }

    // ---------- source pins: repository + search policy ----------

    @Test
    fun `deep search is ledger-silent cancellable and tags are searchable`() {
        val repo = readSource("data/repository/NoteRepository.kt")
        assertTrue(repo.contains("searchCorpusGenerationFlow"))
        assertTrue(repo.contains(".mapNotNull { decryptPageOrNullForCorpus(it) }"))
        assertTrue(repo.contains("currentCoroutineContext().ensureActive()"))
        val policy = readSource("services/VaultSearchPolicy.kt")
        assertTrue(policy.contains("FUZZY_BODY_SCAN_CAP"))
        assertTrue(policy.contains("page.tags.contains"))
    }

    // ---------- source pins: tokenizer ----------

    @Test
    fun `closing fence regex is precompiled and tables need pipes`() {
        val source = readSource("services/MarkdownBlockTokenizer.kt")
        assertTrue(source.contains("closingBacktickFenceRe"))
        assertTrue(source.contains("closingTildeFenceRe"))
        assertFalse(
            "per-block fence Regex compile must be gone",
            source.contains("val closing = Regex(")
        )
        assertTrue(source.contains("lines[i + 1].contains(\"|\")"))
    }

    // ---------- source readers ----------

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return stripComments(file.readText())
    }

    private fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
