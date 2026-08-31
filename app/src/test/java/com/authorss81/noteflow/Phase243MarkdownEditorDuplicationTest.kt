package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockTokenizer.MarkdownDocument
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 243 — regression tests for the Markdown editor text-duplication bug.
 *
 * Root cause (verified: the tokenizer round-trips byte-exact; the COMposable's
 * per-keystroke edit was anchored to a block INDEX). One keystroke can re-split
 * the edited block's window into a different block topology, so the next
 * keystroke's `replaceBlock(doc, staleIndex, newRaw)` rewrote a drifted slot
 * while the old fragments survived — the repeated `_bu - hi` on screen.
 *
 * The fix anchors every keystroke to the previous edited source BY TEXT: the
 * editor locates `prevRaw` via `content.indexOf(prevRaw, editingAnchorByte)`
 * and calls [MarkdownBlockTokenizer.replaceContentRun]. No matter how the
 * window re-splits, exactly the previous run's bytes are replaced, so nothing
 * duplicates. Blocks are (mostly) blank-separated paragraphs here so each one
 * is its own region — mirroring how the "grown back" blocks in the real editor
 * carry their leading blank as part of the field text.
 */
class Phase243MarkdownEditorDuplicationTest {

    // ---- Helpers that mirror the HybridMarkdownEditor runtime ----------------

    private fun sourceOffsetOfLine(lines: List<String>, startLine: Int): Int {
        var off = 0
        for (i in 0 until startLine) off += lines[i].length + 1
        return off
    }

    /**
     * Blocks are contiguous and non-overlapping, covering every content line
     * (0 until the first uncovered line). The tokenizer deliberately leaves a
     * TRAILING BLANK RUN (a trailing `\n`/`\n\n` after the last content) owned
     * by no block — see [MarkdownBlockTokenizer.blocksFromLines] — so the
     * highest covered line may be less than `lines.lastIndex`. The optional
     * uncovered tail (if any) must be entirely blank lines.
     */
    private fun assertInvariants(doc: MarkdownDocument) {
        if (doc.blocks.isEmpty()) {
            assertTrue("all-blank docs still keep every line", doc.lines.all { it.isBlank() })
            return
        }
        var expectedStart = 0
        for (b in doc.blocks) {
            assertEquals("block must start where previous ended", expectedStart, b.startLine)
            assertTrue("end >= start", b.endLine >= b.startLine)
            assertTrue("end < lines", b.endLine < doc.lines.size)
            expectedStart = b.endLine + 1
        }
        assertTrue(
            "uncovered trailing lines must be blank (got: ${doc.lines.drop(expectedStart).map { "[$it]" }})",
            doc.lines.drop(expectedStart).all { it.isBlank() }
        )
    }

    /** The incremental doc must agree exactly with a fresh full re-tokenize. */
    private fun assertConsistent(doc: MarkdownDocument) {
        assertInvariants(doc)
        val fresh = MarkdownBlockTokenizer.tokenize(doc.content)
        assertEquals("content stored verbatim", doc.content, fresh.content)
        assertEquals("blocks match a fresh full re-tokenize", fresh.blocks, doc.blocks)
        assertEquals("candidates match a fresh full pass", fresh.candidates, doc.candidates)
        assertEquals("candidatesByBlock is consistent", fresh.candidatesByBlock, doc.candidatesByBlock)
        // Round-trip: block sources joined with `\n` reproduce the content up to
        // the last owned line; any trailing BLANK RUN is preserved as the content's
        // trailing newlines and asserted separately (the tokenizer owns no trailing
        // block for a trailing `\n`). Several blank tail lines == several trailing
        // `\n`s in the content string.
        val joined = doc.blocks.joinToString("\n") { doc.blockSource(it) }
        val lastOwned = if (doc.blocks.isEmpty()) -1 else doc.blocks.last().endLine
        assertEquals("block-source join reproduces the covered content line-for-line",
            doc.lines.subList(0, lastOwned + 1).joinToString("\n"), joined)
        val trailingBlankCount = doc.lines.size - (lastOwned + 1)
        assertEquals("content is exactly the covered join plus the trailing blank run",
            joined + "\n".repeat(trailingBlankCount), doc.content)
    }

    /** Mirrors the editor's anchored keystroke path (emitBlockEdit). */
    private inner class EditorSim(
        start: String,
        editingBlock: Int
    ) {
        var doc: MarkdownDocument = MarkdownBlockTokenizer.tokenize(start)
        var editingText: String = doc.blockSource(doc.blocks[editingBlock])
        private val anchor = sourceOffsetOfLine(doc.lines, doc.blocks[editingBlock].startLine)

        fun type(newRaw: String): EditorSim {
            val prev = editingText
            editingText = newRaw
            val at = doc.content.indexOf(prev, anchor)
            assertTrue("previous edited source must be locatable: \"$prev\"", at >= 0)
            doc = MarkdownBlockTokenizer.replaceContentRun(doc, at, at + prev.length, newRaw)
            return this
        }
    }

    // ---- The phase-243 reproduction -----------------------------------------

    @Test
    fun `second keystroke after a block window resplit never duplicates`() {
        // "A..E" blank-separated paragraphs. The user edits block 2 ("C", whose
        // field text is "\nC" after the grown blank) and presses Enter + types a
        // bullet on the SAME edit; the window re-splits (the bullet now grows
        // back over the blank run). The next keystroke must rewrite exactly the
        // edited run — never a drifted block slot.
        val sim = EditorSim("A\n\nB\n\nC\n\nD\n\nE", editingBlock = 2)
        sim.type("\nC\n- x")
        assertEquals(
            "keystroke 1 content",
            "A\n\nB\n\nC\n- x\n\nD\n\nE",
            sim.doc.content
        )
        assertConsistent(sim.doc)

        sim.type("\nC\n- xy")
        assertEquals(
            "keystroke 2 must replace the run, not duplicate it",
            "A\n\nB\n\nC\n- xy\n\nD\n\nE",
            sim.doc.content
        )
        assertConsistent(sim.doc)
        assertTrue("no duplicated fragment remains", !sim.doc.content.contains("- x\nC"))
        assertEquals("the edited text appears exactly once", 1, sim.doc.content.lines().count { it == "C" })
        assertEquals(1, sim.doc.content.lines().count { it == "- xy" })
    }

    @Test
    fun `typing a second line into a heading block never duplicates it`() {
        // Phase 246 strict verification: the user-facing symptom was "typing
        // Enter + a second line into a block's editor spawns duplicate-looking
        // rows under the still-open editor". The heading is single-line by
        // construction, so a keystroke that introduces a body line re-splits
        // the block window; the byte-run-anchored path must rewrite exactly the
        // old run and leave exactly one heading + one grown body line.
        val sim = EditorSim("# Title\n\nBody paragraph\n\nTail", editingBlock = 0)
        sim.type("# Title\nSecond line")
        assertEquals(
            "first keystroke appends a body line under the heading",
            "# Title\nSecond line\n\nBody paragraph\n\nTail",
            sim.doc.content
        )
        assertConsistent(sim.doc)
        sim.type("# Title\nSecond line again")
        assertEquals(
            "second keystroke rewrites only the edited run",
            "# Title\nSecond line again\n\nBody paragraph\n\nTail",
            sim.doc.content
        )
        assertConsistent(sim.doc)
        assertEquals("the heading appears exactly once", 1, sim.doc.content.lines().count { it == "# Title" })
        assertEquals("the edited line appears exactly once", 1, sim.doc.content.lines().count { it == "Second line again" })
        assertEquals("neighbours survive intact", 1, sim.doc.content.lines().count { it == "Tail" })
    }

    @Test
    fun `typing asterisks into the last block of an existing note shows text once`() {
        val sim = EditorSim("# Title\n\n- task one\n\nplain *here* text", editingBlock = 2)
        val base = "plain *here* text"
        val typed = StringBuilder()
        for (ch in "*hi*") {
            typed.append(ch)
            sim.type("\n$base$typed")
            assertEquals("content tracks every keystroke",
                "# Title\n\n- task one\n\n$base$typed", sim.doc.content)
            assertConsistent(sim.doc)
        }
        assertEquals("stored content holds the note + typed text exactly once",
            "# Title\n\n- task one\n\n$base$typed", sim.doc.content)
        // Only the two NEW asterisks were typed; the two from the base block
        // are still there and nothing was duplicated.
        assertEquals(4, sim.doc.content.count { it == '*' })
        assertEquals(1, sim.doc.content.lines().count { it == base + typed })
    }

    @Test
    fun `ime burst between frames uses the state text not the composition value`() {
        // The editor reads prevRaw from the FIELD STATE (not the composition-time
        // `value`), so a burst "a" → "ab" → "abc" before recomposition must apply
        // three sequential run replaces, not clobber the second.
        val sim = EditorSim("seed", editingBlock = 0)
        sim.type("seed a")
        sim.type("seed ab")
        sim.type("seed abc")
        assertEquals("seed abc", sim.doc.content)
        assertConsistent(sim.doc)
    }

    @Test
    fun `paragraph edited into a multi item list then continued never duplicates`() {
        // The "body" paragraph block's field text is "\nbody" (grown blank).
        // Replacing it with a growing multi-item list across keystrokes must not
        // duplicate an item or eat the paragraphs that surround the edit.
        val sim = EditorSim("intro\n\nbody\n\nfooter", editingBlock = 1)
        sim.type("\n- first")
        assertEquals("intro\n\n- first\n\nfooter", sim.doc.content)
        assertConsistent(sim.doc)
        sim.type("\n- first\n- second")
        assertEquals("intro\n\n- first\n- second\n\nfooter", sim.doc.content)
        assertConsistent(sim.doc)
        sim.type("\n- first\n- second\n- third")
        assertEquals("intro\n\n- first\n- second\n- third\n\nfooter", sim.doc.content)
        // The trailing paragraph that followed the edited block survived intact.
        assertTrue(sim.doc.content.endsWith("footer"))
        assertConsistent(sim.doc)
        // Old bug signature: any repeated "- first".
        assertEquals(1, sim.doc.content.lines().count { it == "- first" })
        assertEquals(1, sim.doc.content.lines().count { it == "- third" })
    }

    // ---- replaceContentRun contract -----------------------------------------

    @Test
    fun `edits near a trailing blank run stay consistent`() {
        // Content that ends in a trailing newline (a trailing empty line the
        // tokenizer deliberately owns with no block). assertConsistent's relaxed
        // invariants must still hold after replacing a run adjacent to it.
        val sim = EditorSim("first\n\nlast\n", editingBlock = 0)
        sim.type("firstX")
        assertEquals("firstX\n\nlast\n", sim.doc.content)
        assertConsistent(sim.doc)
        sim.type("firstXY")
        assertEquals("firstXY\n\nlast\n", sim.doc.content)
        assertConsistent(sim.doc)
        assertEquals(1, sim.doc.content.lines().count { it == "last" })
        assertTrue(sim.doc.content.endsWith("last\n"))
    }

    @Test
    fun `replaceContentRun matches a full re-tokenize on random docs`() {
        val rng = Random(243)
        repeat(800) {
            val docText = randomDoc(rng)
            if (docText.isEmpty()) return@repeat
            val doc = MarkdownBlockTokenizer.tokenize(docText)
            if (doc.blocks.isEmpty()) return@repeat
            val blockIndex = rng.nextInt(doc.blocks.size)
            val run = doc.blockSource(doc.blocks[blockIndex])
            val startByte = sourceOffsetOfLine(doc.lines, doc.blocks[blockIndex].startLine)
            val endByte = startByte + run.length
            val replacement = randomBlockSource(rng)
            val patched = MarkdownBlockTokenizer.replaceContentRun(doc, startByte, endByte, replacement)
            val expectedContent = docText.replaceRange(startByte, endByte, replacement)
            assertEquals("content is a plain range replace", expectedContent, patched.content)
            val fresh = MarkdownBlockTokenizer.tokenize(expectedContent)
            assertEquals("blocks equal a fresh re-tokenize", fresh.blocks, patched.blocks)
            assertEquals("candidates equal a fresh pass", fresh.candidates, patched.candidates)
            assertEquals("candidatesByBlock equal a fresh pass", fresh.candidatesByBlock, patched.candidatesByBlock)
        }
    }

    @Test
    fun `replaceContentRun agrees with replaceBlock for a single block run`() {
        val rng = Random(2431)
        repeat(500) {
            val docText = randomDoc(rng)
            if (docText.isEmpty()) return@repeat
            val doc = MarkdownBlockTokenizer.tokenize(docText)
            if (doc.blocks.isEmpty()) return@repeat
            val blockIndex = rng.nextInt(doc.blocks.size)
            val block = doc.blocks[blockIndex]
            val run = doc.blockSource(block)
            val startByte = sourceOffsetOfLine(doc.lines, block.startLine)
            val replacement = randomBlockSource(rng)
            val viaRun = MarkdownBlockTokenizer.replaceContentRun(doc, startByte, startByte + run.length, replacement)
            val viaBlock = MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, replacement)
            assertEquals("the two paths agree on content", viaBlock.content, viaRun.content)
            assertEquals("the two paths agree on blocks", viaBlock.blocks, viaRun.blocks)
            assertEquals("the two paths agree on candidates", viaBlock.candidates, viaRun.candidates)
        }
    }

    @Test
    fun `empty or out of range runs are safe no-ops`() {
        val doc = MarkdownBlockTokenizer.tokenize("a\n\nb")
        assertEquals("empty run leaves the doc untouched", doc,
            MarkdownBlockTokenizer.replaceContentRun(doc, 2, 2, "x"))
        assertEquals("negative start is a no-op", doc,
            MarkdownBlockTokenizer.replaceContentRun(doc, -3, 2, "x"))
        assertEquals("start after end is a no-op", doc,
            MarkdownBlockTokenizer.replaceContentRun(doc, 5, 2, "x"))
        // The whole first block is the exact single-block source run "a".
        val runAt = MarkdownBlockTokenizer.replaceContentRun(doc, 0, 1, "zzz")
        assertEquals("replacing the whole first block", "zzz\n\nb", runAt.content)
        assertConsistent(runAt)
    }

    // ---- generators ---------------------------------------------------------

    private val vocab = listOf(
        "# Heading", "plain paragraph text", "**bold** and `code`",
        "- item", "- [ ] todo", "- [x] done", "1. numbered",
        "> quote", "> [!NOTE] callout", "| a | b |", "| --- | --- |",
        "---", "$$", "x = 1", "", "   ", "line with *stars*"
    )

    private fun randomDoc(rng: Random): String {
        val lines = ArrayList<String>()
        val count = rng.nextInt(0, 20)
        repeat(count) { lines.add(vocab[rng.nextInt(vocab.size)]) }
        if (lines.isNotEmpty() && lines.last().isBlank()) {
            lines[lines.lastIndex] = "final paragraph"
        }
        return lines.joinToString("\n")
    }

    private fun randomBlockSource(rng: Random): String {
        val count = rng.nextInt(0, 4)
        return buildString {
            repeat(count) {
                append(vocab[rng.nextInt(vocab.size)])
                append('\n')
            }
        }
    }
}