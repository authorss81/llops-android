package com.authorss81.noteflow

import com.authorss81.noteflow.services.CalloutType
import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 37 block tokenizer (hybrid block editor slice).
 * Focus: classification, exact source round-trip (joinBlockSources == content),
 * checkbox candidate/toggle stability, and typed-callout classification.
 */
class MarkdownBlockTokenizerTest {

    @Test
    fun `blank and empty documents are handled safely`() {
        val emptyBlocks = MarkdownBlockTokenizer.blocks("")
        assertTrue(emptyBlocks.isNotEmpty())
        assertEquals("", MarkdownBlockTokenizer.joinBlockSources("", emptyBlocks))
        val onlyBlanks = MarkdownBlockTokenizer.blocks("\n\n")
        assertTrue(onlyBlanks.isNotEmpty())
        // Round-trip still exact for an all-blank doc.
        assertEquals("\n\n", MarkdownBlockTokenizer.joinBlockSources("\n\n", onlyBlanks))
    }

    @Test
    fun `heading is classified as a heading block`() {
        val blocks = MarkdownBlockTokenizer.blocks("# Big Title")
        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlockType.HEADING, blocks.first().type)
    }

    @Test
    fun `code fence becomes a single code-fence block`() {
        val content = "```\nval x = 1\n```"
        val blocks = MarkdownBlockTokenizer.blocks(content)
        assertEquals(listOf(MarkdownBlockType.CODE_FENCE), blocks.map { it.type })
        assertEquals(content, MarkdownBlockTokenizer.joinBlockSources(content, blocks))
    }

    @Test
    fun `math block $$$$ is classified as math`() {
        val content = "$$\nx = 1\n$$"
        val blocks = MarkdownBlockTokenizer.blocks(content)
        assertEquals(listOf(MarkdownBlockType.MATH_BLOCK), blocks.map { it.type })
        assertEquals(content, MarkdownBlockTokenizer.joinBlockSources(content, blocks))
    }

    @Test
    fun `thematic break is detected`() {
        val blocks = MarkdownBlockTokenizer.blocks("---")
        assertEquals(MarkdownBlockType.THEMATIC_BREAK, blocks.first().type)
    }

    @Test
    fun `a quote is a blockquote and a fenced callout is a callout`() {
        val quoteBlocks = MarkdownBlockTokenizer.blocks("> hello")
        assertEquals(MarkdownBlockType.BLOCKQUOTE, quoteBlocks.first().type)

        val calloutBlocks = MarkdownBlockTokenizer.blocks("> [!NOTE] heads up")
        assertEquals(MarkdownBlockType.CALLOUT, calloutBlocks.first().type)
    }

    @Test
    fun `bullet and ordered lists are typed separately`() {
        val bullet = MarkdownBlockTokenizer.blocks("- one\n- two")
        assertEquals(MarkdownBlockType.BULLET_LIST, bullet.first().type)

        val ordered = MarkdownBlockTokenizer.blocks("1. one\n2. two")
        assertEquals(MarkdownBlockType.ORDERED_LIST, ordered.first().type)
    }

    @Test
    fun `round-trip preserves the entire document byte-for-byte`() {
        val content = """
            # Title

            A **bold** paragraph with `code` and [a link](https://example.com).

            - [ ] unchecked task
            - [x] checked task

            ```
            println("hi")
            ```

            > [!WARNING] Careful there

            | a | b |
            |---|---|
            | 1 | 2 |

            ${'$'}x = 5${'$'}

            ---
        """.trimIndent()
        val blocks = MarkdownBlockTokenizer.blocks(content)
        assertTrue("expected many blocks, got ${blocks.size}", blocks.size >= 8)
        assertEquals(content, MarkdownBlockTokenizer.joinBlockSources(content, blocks))
    }

    @Test
    fun `checkbox candidates are reported in document order`() {
        val content = "- [ ] first\n- [x] second\n- [ ] third"
        val candidates = MarkdownBlockTokenizer.checkboxCandidates(content)
        assertEquals(3, candidates.size)
        assertEquals(false, candidates[0].checked)
        assertEquals(true, candidates[1].checked)
        assertEquals(false, candidates[2].checked)
        assertEquals(listOf(0, 1, 2), candidates.map { it.index })
    }

    @Test
    fun `toggling a checkbox flips only its marker line`() {
        val content = "- [ ] first\n- [x] second"
        val flipped = MarkdownBlockTokenizer.toggleCheckbox(content, 0)
        assertEquals("- [x] first\n- [x] second", flipped)
        val unflipped = MarkdownBlockTokenizer.toggleCheckbox(flipped, 0)
        assertEquals(content, unflipped)
    }

    @Test
    fun `toggling an out-of-range candidate is a safe no-op`() {
        val content = "- [ ] first"
        assertEquals(content, MarkdownBlockTokenizer.toggleCheckbox(content, 99))
        assertEquals(content, MarkdownBlockTokenizer.toggleCheckbox(content, -1))
    }

    @Test
    fun `calloutOf maps typed markers to callout types`() {
        assertEquals(CalloutType.WARNING, MarkdownBlockTokenizer.calloutOf("[!WARNING] ouch")?.type)
        assertEquals(CalloutType.TIP, MarkdownBlockTokenizer.calloutOf("[!TIP] try this")?.type)
        assertEquals(CalloutType.IMPORTANT, MarkdownBlockTokenizer.calloutOf("[!IMPORTANT] note it")?.type)
        assertEquals(CalloutType.NOTE, MarkdownBlockTokenizer.calloutOf("[!NOTE] heads up")?.type)
        assertNull(MarkdownBlockTokenizer.calloutOf("normal quote text"))
    }

    @Test
    fun `replaceBlockSource swaps only the target block`() {
        val content = "# Title\nhello world"
        val blocks = MarkdownBlockTokenizer.blocks(content)
        assertEquals(MarkdownBlockType.PARAGRAPH, blocks[1].type)
        val replaced = MarkdownBlockTokenizer.replaceBlockSource(content, blocks, 1, "replaced paragraph")
        assertEquals("# Title\nreplaced paragraph", replaced)
    }
}