package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownBlockTokenizer
import com.authorss81.noteflow.services.MarkdownBlockTokenizer.MarkdownDocument
import com.authorss81.noteflow.services.MarkdownBlockType
import com.authorss81.noteflow.services.MarkdownInlineMath
import com.authorss81.noteflow.services.MarkdownInlineMath.CodeRangeIndex
import com.authorss81.noteflow.services.MathRun
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b5-FEA-02 / R2-b2b5-FEA-03 (phase-151) — Markdown main-thread performance.
 *
 * Findings (docs/security-report-round2.md):
 *  - R2-b2b5-FEA-02 (MEDIUM): `MarkdownInlineMath` scanned to end-of-string per
 *    backtick-run opener (O(n²) on ` ``x```x````x`````x… ` paragraphs) and did a
 *    linear `codeRanges.any { index in it }` per `$` (O(n·R)).
 *  - R2-b2b5-FEA-03 (MEDIUM): the hybrid editor re-tokenized the whole document
 *    per keystroke on the main thread — `blocks(text)` + `checkboxCandidates(text)`
 *    (which called `blocks` a SECOND time), plus `replaceBlockSource` doing a full
 *    `content.lines()` + `joinToString`.
 *
 * Fix shape (all provable on the pure JVM):
 *  - [MarkdownInlineMath] pre-computes every maximal backtick run in ONE pass and
 *    answers each closer lookup with a binary search over per-length position
 *    buckets; every code-span membership test goes through the interval index
 *    [CodeRangeIndex] (O(log R)).
 *  - [MarkdownBlockTokenizer.tokenize] computes blocks, checkbox candidates and
 *    the candidates-by-block index in ONE pass ([MarkdownDocument]); the editor's
 *    keystroke path calls [MarkdownBlockTokenizer.replaceBlock], which re-uses the
 *    cached lines, re-tokenizes ONLY the edited window and shifts the untouched
 *    later blocks.
 *
 * Tests: reference-equivalence (the OLD scanner logic is embedded below and both
 * implementations must agree on randomized + adversarial inputs), length-scaling
 * linearity assertions, and source pins proving the Android binding never calls
 * the full-document paths on the keystroke path.
 */
class Phase151MarkdownMainThreadPerfTest {

    // ========================================================================
    // R2-b2b5-FEA-02 — interval index + linear backtick closing
    // ========================================================================

    @Test
    fun `the interval index answers membership in O(log R) with correct edges`() {
        val index = CodeRangeIndex(listOf(0..4, 9..11, 20..20))
        assertTrue(index.contains(0))
        assertTrue(index.contains(4))
        assertTrue(index.contains(2))
        assertTrue(index.contains(9))
        assertTrue(index.contains(11))
        assertTrue(index.contains(20))
        assertFalse("between ranges is outside", index.contains(5))
        assertFalse("before the first range is outside", index.contains(-1))
        assertFalse("after the last range is outside", index.contains(21))
        assertFalse("gap between ranges is outside", index.contains(15))
        // Empty index never contains anything.
        val empty = CodeRangeIndex(emptyList())
        assertFalse(empty.contains(0))
        assertFalse(empty.contains(-5))
    }

    @Test
    fun `findCodeRanges matches the reference on the finding's adversarial reproducer`() {
        // ``x``x```x````x`````x… — every run has a DISTINCT length, so the OLD
        // scanner re-scanned far ahead per opener (its quadratic trigger); the
        // new run-index must produce byte-identical ranges.
        val text = buildString {
            var run = 1
            while (length < 4000) {
                repeat(run) { append('`') }
                append('x')
                run++
            }
        }
        val expected = referenceFindCodeRanges(text)
        assertEquals("reference and run-index agree on the adversarial reproducer",
            expected, MarkdownInlineMath.findCodeRanges(text))
        // And with $ sprinkled between runs, math must also match the reference.
        assertEquals(
            referenceFindMathRuns(text, expected),
            MarkdownInlineMath.findMathRuns(text, expected)
        )
    }

    @Test
    fun `findCodeRanges matches the reference on sub-run closers at the end of longer runs`() {
        // ``a```b`` — the length-3 run's final 2 backticks close the length-2 opener.
        val text = "``a```b``"
        assertEquals(referenceFindCodeRanges(text), MarkdownInlineMath.findCodeRanges(text))
        assertEquals(listOf(0..5), MarkdownInlineMath.findCodeRanges(text))
    }

    @Test
    fun `findCodeRanges and findMathRuns are byte-equivalent to the reference on randomized texts`() {
        val rng = Random(151)
        val alphabet = listOf('a', 'b', 'x', ' ', '`', '$', '_', '.', '\n')
        repeat(2000) {
            val len = rng.nextInt(0, 300)
            val text = buildString { repeat(len) { append(alphabet[rng.nextInt(alphabet.size)]) } }
            val actualRanges = MarkdownInlineMath.findCodeRanges(text)
            assertEquals("code ranges diverge for: ${text.take(40)}…", referenceFindCodeRanges(text), actualRanges)
            val actualMath = MarkdownInlineMath.findMathRuns(text, actualRanges)
            assertEquals("math runs diverge for: ${text.take(40)}…", referenceFindMathRuns(text, actualRanges), actualMath)
            // The default-parameter path (ranges computed internally) must agree too.
            assertEquals("default-arg math runs diverge for: ${text.take(40)}…",
                referenceFindMathRuns(text, actualRanges), MarkdownInlineMath.findMathRuns(text))
        }
    }

    @Test
    fun `pathological uncloseable backtick runs parse in linear not quadratic time`() {
        val small = backtickAdversarial(256 * 1024)
        val large = backtickAdversarial(1024 * 1024)
        val tSmall = minMillis { MarkdownInlineMath.findCodeRanges(small) }
        val tLarge = minMillis { MarkdownInlineMath.findCodeRanges(large) }
        val ratio = tLarge / tSmall
        // The old scanner did an O(n) scan-to-end per opener (~16x for a 4x input);
        // the run-index is near-linear (~4-5x). 12 separates them with margin.
        assertTrue(
            "expected near-linear scaling, got ratio=$ratio (small=${"%.2f".format(tSmall)}ms large=${"%.2f".format(tLarge)}ms)",
            ratio < 12
        )
        assertTrue(
            "a 1 MiB adversarial paragraph must parse quickly, got ${"%.1f".format(tLarge)}ms",
            tLarge < 3000
        )
    }

    @Test
    fun `dollar-dense text with many code spans parses in linear not quadratic time`() {
        val small = dollarAdversarial(256 * 1024)
        val large = dollarAdversarial(1024 * 1024)
        val tSmall = minMillis { MarkdownInlineMath.findMathRuns(small) }
        val tLarge = minMillis { MarkdownInlineMath.findMathRuns(large) }
        val ratio = tLarge / tSmall
        // Old insideAny was O(codeRanges) per `$` (~16x for a 4x input); the
        // interval index is O(log R) per `$` (~4-5x).
        assertTrue(
            "expected near-linear scaling, got ratio=$ratio (small=${"%.2f".format(tSmall)}ms large=${"%.2f".format(tLarge)}ms)",
            ratio < 12
        )
        assertTrue(
            "a 1 MiB $-dense paragraph must parse quickly, got ${"%.1f".format(tLarge)}ms",
            tLarge < 3000
        )
    }

    @Test
    fun `the scanner stays linear on a giant single run of backticks`() {
        // One maximal run of 512k backticks — the bucket build must be O(backticks).
        val text = buildString { repeat(512 * 1024) { append('`') } }
        val t = minMillis { MarkdownInlineMath.findCodeRanges(text) }
        assertEquals(emptyList<IntRange>(), MarkdownInlineMath.findCodeRanges(text))
        assertTrue("single-run bucket build must be fast, got ${"%.1f".format(t)}ms", t < 2000)
    }

    @Test
    fun `the interval index is wired in place of the linear insideAny scan`() {
        val source = sourceFile("services/MarkdownInlineMath.kt")
        val code = stripComments(source)
        assertTrue("the interval index lives in the scanner", code.contains("CodeRangeIndex"))
        assertTrue("membership is a binary search", code.contains("java.util.Arrays.binarySearch(starts, index)"))
        assertFalse("the linear insideAny scan is gone", code.contains("codeRanges.any {"))
        assertFalse("insideAny is gone entirely", Regex("fun insideAny").containsMatchIn(code))
        // The one-pass backtick pre-pass is present; no per-opener scan-to-end.
        assertTrue("maximal runs are precomputed once", code.contains("findBacktickRuns(text)"))
        assertTrue("closer lookup is a binary search", code.contains("findClosingBacktick(closers"))
        assertFalse("the old substring-per-step scan is gone", code.contains("text.substring(i, i + run)"))
    }

    // ========================================================================
    // R2-b2b5-FEA-03 — one-pass tokenize + incremental replaceBlock
    // ========================================================================

    @Test
    fun `tokenize computes blocks candidates and the block index in one pass`() {
        val content = "- [ ] a\n- [x] b\n\n# h\n\n- [ ] c"
        val doc = MarkdownBlockTokenizer.tokenize(content)
        assertEquals(MarkdownBlockTokenizer.blocks(content), doc.blocks)
        assertEquals(MarkdownBlockTokenizer.checkboxCandidates(content), doc.candidates)
        assertEquals("one pass → candidatesByBlock agrees with the global scan",
            doc.candidates.groupBy { it.blockIndex }.mapValues { e -> e.value.map { it.index } },
            doc.candidatesByBlock)
        assertEquals("block 0 is a bullet list", MarkdownBlockType.BULLET_LIST, doc.blocks[0].type)
        assertEquals(listOf(0, 1), doc.candidatesByBlock[0])
        assertEquals("candidate 2 lives in block 2 (bullet list at lines 4..5)", listOf(2), doc.candidatesByBlock[2])
        assertEquals("heading block has no candidates", emptyList<Int>(), doc.candidatesByBlock[1])
        assertEquals("content is stored verbatim", content, doc.content)
    }

    @Test
    fun `doc blockSource matches the tokenizer blockSource and round-trips`() {
        val rng = Random(1511)
        repeat(500) {
            val text = randomMarkdownDoc(rng, maxLines = 40)
            val doc = MarkdownBlockTokenizer.tokenize(text)
            val blocks = MarkdownBlockTokenizer.blocks(text)
            assertEquals(doc.blocks, blocks)
            for (block in blocks) {
                assertEquals("blockSource agrees for ${block.type}",
                    MarkdownBlockTokenizer.blockSource(text, block), doc.blockSource(block))
            }
            assertEquals("join of doc blockSources round-trips", text,
                doc.blocks.joinToString("\n") { doc.blockSource(it) })
        }
    }

    @Test
    fun `replaceBlock is byte-identical to the full pipeline on random docs`() {
        val rng = Random(1512)
        repeat(3000) {
            val docText = randomMarkdownDoc(rng, maxLines = 35)
            val doc = MarkdownBlockTokenizer.tokenize(docText)
            if (doc.blocks.isEmpty()) return@repeat
            val blockIndex = rng.nextInt(doc.blocks.size)
            val newSource = randomBlockSource(rng)
            val incremental = MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newSource)
            val expectedContent = MarkdownBlockTokenizer.replaceBlockSource(docText, doc.blocks, blockIndex, newSource)
            assertEquals("content must be byte-identical for edit $blockIndex",
                expectedContent, incremental.content)
            val expectedBlocks = MarkdownBlockTokenizer.blocks(expectedContent)
            val expectedCandidates = MarkdownBlockTokenizer.checkboxCandidates(expectedContent)
            assertEquals("blocks must match a fresh full re-tokenize", expectedBlocks, incremental.blocks)
            assertEquals("candidates must match a fresh full pass", expectedCandidates, incremental.candidates)
            assertEquals("candidatesByBlock must be consistent", expectedCandidates
                .groupBy { it.blockIndex }.mapValues { e -> e.value.map { it.index } },
                incremental.candidatesByBlock)
        }
    }

    @Test
    fun `replaceBlock matches the full pipeline on the region-merge edge cases`() {
        val cases = listOf(
            "a\n\nb" to listOf(
                0 to "p\n\n", 0 to "p", 0 to "x\ny", 0 to "",
                1 to "c\nd", 1 to "c", 1 to "", 1 to "c\n"
            ),
            "# h\npara" to listOf(0 to "plain", 0 to "# n", 1 to "para\nmore", 1 to ""),
            "- a\n- b\npara" to listOf(0 to "- a", 0 to "- a\n- b\n- c", 0 to "x", 1 to "- c"),
            "- a\n\npara" to listOf(0 to "x\ny", 0 to "x", 0 to "", 1 to "x"),
            "a\n\n\nb" to listOf(0 to "c", 1 to "c", 1 to "", 1 to "c\n\n"),
            "```\ncode\n```\n\npara" to listOf(0 to "```\nmore\n```", 0 to "not a fence", 1 to "x"),
            "$$\nx\n$$\n\npara" to listOf(0 to "$$", 1 to "y", 1 to "$$\ny\n$$"),
            "> q\n> q2\n\n- [ ] t" to listOf(0 to "> only", 1 to "- [x] t"),
            "| a | b |\n| --- | --- |\n| 1 | 2 |" to listOf(0 to "| x | y |\n| --- | --- |", 0 to "plain")
        )
        for ((docText, edits) in cases) {
            for ((blockIndex, newSource) in edits) {
                val doc = MarkdownBlockTokenizer.tokenize(docText)
                if (blockIndex >= doc.blocks.size) continue
                val incremental = MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newSource)
                val expectedContent = MarkdownBlockTokenizer.replaceBlockSource(docText, doc.blocks, blockIndex, newSource)
                assertEquals("content for [$docText] edit $blockIndex -> ${newSource.take(20)}",
                    expectedContent, incremental.content)
                assertEquals("blocks for [$docText] edit $blockIndex -> ${newSource.take(20)}",
                    MarkdownBlockTokenizer.blocks(expectedContent), incremental.blocks)
                assertEquals("candidates for [$docText] edit $blockIndex -> ${newSource.take(20)}",
                    MarkdownBlockTokenizer.checkboxCandidates(expectedContent), incremental.candidates)
            }
        }
    }

    @Test
    fun `a blank-isolated edit touches only the edited window and shifts the rest`() {
        // para0 / para1 / ... / para199 separated by blank lines: every block is a
        // paragraph whose ONLY blank-owned neighbour is a single leading blank.
        val docText = (0 until 200).joinToString("\n\n") { "para$it" }
        val doc = MarkdownBlockTokenizer.tokenize(docText)
        // Blank-padded replacement keeps the edit isolated from its neighbours, so
        // blocks outside the window are either byte-identical or purely shifted.
        val newSource = "\nedited\n\n"
        val full = MarkdownBlockTokenizer.tokenize(
            MarkdownBlockTokenizer.replaceBlockSource(docText, doc.blocks, 100, newSource)
        )
        val incremental = MarkdownBlockTokenizer.replaceBlock(doc, 100, newSource)
        assertEquals("incremental agrees with a full re-tokenize", full.blocks, incremental.blocks)
        // All blocks before the edited region keep their exact ranges.
        assertEquals("earlier blocks are byte-identical", doc.blocks.take(100), incremental.blocks.take(100))
        // Blocks far after the edit keep their TYPE; only line ranges shift.
        val delta = newSource.lines().size - (doc.blocks[100].endLine - doc.blocks[100].startLine + 1)
        val after = incremental.blocks.drop(102)
        val shifted = doc.blocks.drop(102).map { b ->
            MarkdownBlock(b.type, b.startLine + delta, b.endLine + delta)
        }
        assertEquals("blocks after the window shift only by the line delta", shifted, after)
    }

    @Test
    fun `typing simulation on a 5k-line note keeps content blocks and checkboxes in sync`() {
        val note = buildString {
            for (i in 0 until 5000) {
                append(if (i % 3 == 0) "- [ ] item $i" else "paragraph $i with some words")
                append('\n')
            }
        }
        assertTrue("the note is ~50 KB", note.length in 40_000..250_000)
        val doc = MarkdownBlockTokenizer.tokenize(note)

        // One keystroke: append a character to the raw source of a middle block.
        val target = doc.blocks.size / 2
        val block = doc.blocks[target]
        val newRaw = doc.blockSource(block) + "x"
        val t = minMillis(3) {
            MarkdownBlockTokenizer.replaceBlock(doc, target, newRaw)
        }
        val incremental = MarkdownBlockTokenizer.replaceBlock(doc, target, newRaw)
        val expected = MarkdownBlockTokenizer.tokenize(
            MarkdownBlockTokenizer.replaceBlockSource(note, doc.blocks, target, newRaw)
        )
        assertEquals(expected.blocks, incremental.blocks)
        assertEquals(expected.candidates, incremental.candidates)
        assertEquals(expected.candidatesByBlock, incremental.candidatesByBlock)
        assertTrue(
            "per-keystroke incremental edit on a 5k-line note must be bounded, got ${"%.2f".format(t)}ms",
            t < 50
        )

        // The OLD keystroke path (full replaceBlockSource + 2× full tokenize) must
        // be strictly slower than the incremental path on the same note.
        val tOld = minMillis(3) {
            val replaced = MarkdownBlockTokenizer.replaceBlockSource(note, doc.blocks, target, newRaw)
            MarkdownBlockTokenizer.blocks(replaced)
            MarkdownBlockTokenizer.checkboxCandidates(replaced)
        }
        assertTrue(
            "incremental (${"%.2f".format(t)}ms) must beat the old full re-tokenize (${"%.2f".format(tOld)}ms)",
            t < tOld
        )
    }

    @Test
    fun `doc-based toggleCheckbox flips the marker without re-tokenizing and stays in sync`() {
        val content = "- [ ] a\n\n- [x] b\n\n# h"
        val doc = MarkdownBlockTokenizer.tokenize(content)
        val flipped = MarkdownBlockTokenizer.toggleCheckbox(doc, 0)
        assertEquals("- [x] a\n\n- [x] b\n\n# h", flipped.content)
        assertEquals("blocks are untouched by a marker flip", doc.blocks, flipped.blocks)
        assertEquals("candidate 0 flips checked", true, flipped.candidates[0].checked)
        assertEquals("candidate 1 is untouched", true, flipped.candidates[1].checked)
        assertEquals("the block index survives the flip", doc.candidatesByBlock, flipped.candidatesByBlock)
        val unflipped = MarkdownBlockTokenizer.toggleCheckbox(flipped, 0)
        assertEquals("round-trips to the original content", content, unflipped.content)
        // Public string API stays in sync with the doc API.
        assertEquals(flipped.content, MarkdownBlockTokenizer.toggleCheckbox(content, 0))
        assertEquals(content, MarkdownBlockTokenizer.toggleCheckbox(content, 99))
    }

    @Test
    fun `the hybrid editor keystroke path never full-tokenizes or line-splits the document`() {
        val editor = sourceFile("ui/components/markdown/HybridMarkdownEditor.kt")
        assertFalse("no full-document blocks() call remains", editor.contains("MarkdownBlockTokenizer.blocks("))
        assertFalse("no full-document checkboxCandidates() call remains",
            editor.contains("MarkdownBlockTokenizer.checkboxCandidates("))
        assertFalse("no full-document lines()+join replaceBlockSource call remains",
            editor.contains("replaceBlockSource("))
        assertFalse("no per-block candidate filter remains", editor.contains("candidates.filter { it.blockIndex == index }"))
        assertTrue("the document is tokenized once into a MarkdownDocument",
            editor.contains("MarkdownBlockTokenizer.tokenize(value)"))
        assertTrue("the keystroke path is the incremental replaceBlock",
            editor.contains("MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newRaw)"))
        assertTrue("candidate indexes come from the pre-indexed map",
            editor.contains("doc.candidatesByBlock[index] ?: emptyList()"))
        assertTrue("block sources come from the cached lines",
            editor.contains("doc.blockSource(block)"))
    }

    @Test
    fun `the tokenizer's incremental replaceBlock never re-splits the whole document`() {
        val source = sourceFile("services/MarkdownBlockTokenizer.kt")
        val replace = source.substringAfter("fun replaceBlock(").substringBefore("private fun classifyWindow")
        assertTrue("replaceBlock re-uses the cached lines", replace.contains("doc.lines"))
        assertFalse("replaceBlock never calls content.lines() on the whole document",
            replace.contains("content.lines()"))
        assertTrue("only the edited block's raw source is split", replace.contains("newSource.lines()"))
        assertTrue("the edited window is re-classified in isolation", source.contains("classifyWindow("))
        assertTrue("candidates are recomputed in a single pass", source.contains("candidatesFrom(out, newBlocks)"))

        val checkbox = source.substringAfter("fun checkboxCandidates").substringBefore("private fun candidatesFrom")
        assertTrue("checkboxCandidates delegates to the ONE-pass tokenize",
            checkbox.contains("tokenize(content).candidates"))
        assertFalse("checkboxCandidates never calls blocks() a second time", checkbox.contains("blocks(content)"))
    }

    @Test
    fun `an empty edit and an all-blank document stay byte-identical`() {
        val docText = "# t\n\npara"
        val doc = MarkdownBlockTokenizer.tokenize(docText)
        val emptied = MarkdownBlockTokenizer.replaceBlock(doc, doc.blocks.lastIndex, "")
        val expectedEmpty = MarkdownBlockTokenizer.replaceBlockSource(docText, doc.blocks, doc.blocks.lastIndex, "")
        assertEquals(expectedEmpty, emptied.content)
        assertEquals(MarkdownBlockTokenizer.blocks(expectedEmpty), emptied.blocks)

        val blanks = MarkdownBlockTokenizer.tokenize("\n\n\n")
        assertEquals(MarkdownBlockTokenizer.blocks("\n\n\n"), blanks.blocks)
        val edited = MarkdownBlockTokenizer.replaceBlock(blanks, 0, "x")
        assertEquals("x\n\n\n", edited.content)
        assertEquals(MarkdownBlockTokenizer.blocks("x\n\n\n"), edited.blocks)
    }

    // ========================================================================
    // reference implementation of the pre-fix scanner (embedded for equivalence)
    // ========================================================================

    private fun referenceFindCodeRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '`') {
                i++
                continue
            }
            var run = 1
            while (i + run < text.length && text[i + run] == '`') run++
            val closing = referenceFindClosingBackticks(text, i + run, run)
            if (closing >= 0) {
                out.add(i..(closing + run - 1))
                i = closing + run
            } else {
                i += run
            }
        }
        return out
    }

    private fun referenceFindClosingBackticks(text: String, from: Int, run: Int): Int {
        var i = from
        while (i + run <= text.length) {
            if (text.substring(i, i + run).all { it == '`' }) {
                if (i + run < text.length && text[i + run] == '`') {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun referenceFindMathRuns(text: String, codeRanges: List<IntRange>): List<MathRun> {
        val out = mutableListOf<MathRun>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$') {
                i++
                continue
            }
            val isBlock = i + 1 < text.length && text[i + 1] == '$'
            val openLen = if (isBlock) 2 else 1
            if (codeRanges.any { i in it }) {
                i += openLen
                continue
            }
            val close = referenceFindClosingDollar(text, i + openLen, isBlock, codeRanges)
            if (close < 0) break
            out.add(MathRun(i, close + (if (isBlock) 1 else 0), isBlock))
            i = close + (if (isBlock) 2 else 1)
        }
        return out
    }

    private fun referenceFindClosingDollar(text: String, from: Int, isBlock: Boolean, codeRanges: List<IntRange>): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '$') {
                if (isBlock) {
                    if (i + 1 < text.length && text[i + 1] == '$') return i
                    i++
                    continue
                }
                if (codeRanges.any { (i - 1) in it }) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    // ========================================================================
    // generators + timing helper
    // ========================================================================

    private fun backtickAdversarial(n: Int): String {
        val sb = StringBuilder()
        var run = 1
        while (sb.length < n) {
            repeat(run) { sb.append('`') }
            sb.append('x')
            run++
        }
        return sb.take(n).toString()
    }

    private fun dollarAdversarial(n: Int): String {
        val sb = StringBuilder()
        while (sb.length < n) sb.append("\$ `c` ")
        return sb.take(n).toString()
    }

    private val lineVocab = listOf(
        "# Heading",
        "## Sub",
        "plain paragraph text",
        "**bold** and `code` and \$x\$",
        "- item",
        "- [ ] todo",
        "- [x] done",
        "1. numbered",
        "1. [ ] numbered todo",
        "> quote",
        "> [!NOTE] callout",
        "> [!WARNING] careful",
        "| a | b |",
        "| --- | --- |",
        "| 1 | 2 |",
        "---",
        "$$",
        "x = 1",
        "```",
        "fenced = true",
        "> nested line",
        "another paragraph here",
        "",
        "   ",
        "plain",
        "| x | y |",
        "***",
        "`backtick span`",
        "\$5 and \$10 prices"
    )

    private fun randomMarkdownDoc(rng: Random, maxLines: Int): String {
        val lines = ArrayList<String>()
        val lineCount = rng.nextInt(0, maxLines)
        repeat(lineCount) { lines.add(lineVocab[rng.nextInt(lineVocab.size)]) }
        if (lines.isNotEmpty() && lines.last().isBlank()) {
            // Round-trip is byte-exact only when the document does not end on a
            // blank line (a trailing blank is genuinely unowned by any block).
            lines[lines.lastIndex] = "final paragraph"
        }
        return lines.joinToString("\n")
    }

    private fun randomBlockSource(rng: Random): String {
        val lineCount = rng.nextInt(0, 5)
        return buildString {
            repeat(lineCount) {
                append(lineVocab[rng.nextInt(lineVocab.size)])
                append('\n')
            }
        }
    }

    private fun minMillis(times: Int = 5, block: () -> Unit): Double {
        block() // warm-up / JIT
        var best = Double.MAX_VALUE
        repeat(times) {
            val start = System.nanoTime()
            block()
            val ms = (System.nanoTime() - start) / 1e6
            if (ms < best) best = ms
        }
        return best
    }

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
