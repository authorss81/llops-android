package com.authorss81.noteflow.services

/**
 * Phase 37 — PURE JVM inline math span tokenizer.
 *
 * Fully offline (a local parser — no network, no key). Finds `$...$` and
 * `$$...$$` runs inside a paragraph's literal text so the shared preview/hybrid
 * renderer can give them a distinct "math chip" style. Math runs that live inside
 * a code span (single/double/triple backticks) are never reported, so ```$x$```
 * stays plain code.
 *
 * This is a *highlighter*, not a typesetter: real LaTeX rendering is deferred and
 * documented in REPORT.md. Rendered output stays byte-identical to the source —
 * no characters are ever dropped or transformed.
 *
 * Phase 151 (R2-b2b5-FEA-02): the scanner is now linear-ish on adversarial
 * paragraphs instead of quadratic. [findCodeRanges] pre-computes every maximal
 * backtick run in ONE left-to-right pass and answers each closer lookup with a
 * binary search over per-length position buckets (the old
 * `findClosingBackticks` re-scanned to end-of-string per opener), and every
 * "inside a code span?" membership test goes through the interval index
 * [CodeRangeIndex] (O(log R)) instead of a linear `codeRanges.any { index in it }`.
 */
data class MathRun(
    val startIndex: Int,
    val endIndex: Int,
    val isBlock: Boolean
)

object MarkdownInlineMath {

    /** A maximal run of backticks, found by the single left-to-right pre-pass. */
    private data class BacktickRun(val start: Int, val length: Int)

    /**
     * Interval index over [ranges] giving O(log R) membership tests. The ranges
     * must be ascending and disjoint (the scanner emits them so). Replaces the
     * old linear `codeRanges.any { index in it }` per `$` (R2-b2b5-FEA-02).
     */
    internal class CodeRangeIndex(ranges: List<IntRange>) {
        private val starts: IntArray
        private val ends: IntArray

        init {
            val size = ranges.size
            starts = IntArray(size)
            ends = IntArray(size)
            for ((idx, r) in ranges.withIndex()) {
                starts[idx] = r.first
                ends[idx] = r.last
            }
        }

        /** True iff [index] lies inside one of the indexed ranges. */
        fun contains(index: Int): Boolean {
            if (starts.isEmpty()) return false
            val pos = java.util.Arrays.binarySearch(starts, index)
            if (pos >= 0) return true
            val insertion = -pos - 1
            if (insertion == 0) return false
            return index <= ends[insertion - 1]
        }
    }

    /** Ranges of backtick code spans in [text] (`` `code` `` and ``` ``python`` ``` etc). */
    fun findCodeRanges(text: String): List<IntRange> {
        val runs = findBacktickRuns(text)
        if (runs.isEmpty()) return emptyList()
        val closers = closingPositionIndex(runs)
        val out = mutableListOf<IntRange>()
        var runIndex = 0
        while (runIndex < runs.size) {
            val opener = runs[runIndex]
            val closing = findClosingBacktick(closers, opener.length, from = opener.start + opener.length)
            if (closing >= 0) {
                out.add(opener.start..(closing + opener.length - 1))
                // The closing run is consumed; resume scanning after it.
                runIndex = nextRunIndex(runs, closing + opener.length)
            } else {
                runIndex++
            }
        }
        return out
    }

    private fun findBacktickRuns(text: String): List<BacktickRun> {
        val out = mutableListOf<BacktickRun>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '`') {
                i++
                continue
            }
            var run = 1
            while (i + run < text.length && text[i + run] == '`') run++
            out.add(BacktickRun(i, run))
            i += run
        }
        return out
    }

    /**
     * For every maximal backtick run of length >= L, its LAST L backticks form a
     * valid closing position for an opener of length L (the original scanner
     * matched a sub-run at the end of a longer run too). Bucketed by L so a
     * closer lookup is a single binary search instead of a scan to end-of-string.
     * Total bucket entries == total backtick characters, so building is O(n).
     */
    private fun closingPositionIndex(runs: List<BacktickRun>): Map<Int, IntArray> {
        val buckets = HashMap<Int, MutableList<Int>>()
        for (r in runs) {
            for (l in 1..r.length) {
                buckets.getOrPut(l) { mutableListOf() }.add(r.start + r.length - l)
            }
        }
        return buckets.mapValues { (_, v) -> v.toIntArray() }
    }

    private fun findClosingBacktick(index: Map<Int, IntArray>, run: Int, from: Int): Int {
        val starts = index[run] ?: return -1
        var lo = 0
        var hi = starts.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] >= from) {
                result = starts[mid]
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return result
    }

    private fun nextRunIndex(runs: List<BacktickRun>, after: Int): Int {
        var lo = 0
        var hi = runs.size - 1
        var result = runs.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid].start >= after) {
                result = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return result
    }

    /**
     * Find `$...$` / `$$...$$` runs in [text], skipping any region reported in
     * [codeRanges]. Returns runs in ascending order. Unclosed delimiters and lone
     * `$` signs are ignored (no partial/half runs).
     */
    fun findMathRuns(text: String, codeRanges: List<IntRange> = findCodeRanges(text)): List<MathRun> {
        val out = mutableListOf<MathRun>()
        val codeIndex = CodeRangeIndex(codeRanges)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$') {
                i++
                continue
            }
            val isBlock = i + 1 < text.length && text[i + 1] == '$'
            val openLen = if (isBlock) 2 else 1
            if (codeIndex.contains(i)) {
                i += openLen
                continue
            }
            val close = findClosingDollar(text, i + openLen, isBlock, codeIndex)
            if (close < 0) {
                // Unclosed — stop scanning; a stray `$` usually means prose.
                break
            }
            out.add(MathRun(i, close + (if (isBlock) 1 else 0), isBlock))
            i = close + (if (isBlock) 2 else 1)
        }
        return out
    }

    private fun findClosingDollar(text: String, from: Int, isBlock: Boolean, codeIndex: CodeRangeIndex): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '$') {
                if (isBlock) {
                    if (i + 1 < text.length && text[i + 1] == '$') {
                        return i
                    }
                    i++
                    continue
                }
                if (codeIndex.contains(i - 1)) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }
}
