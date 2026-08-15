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
 */
data class MathRun(
    val startIndex: Int,
    val endIndex: Int,
    val isBlock: Boolean
)

object MarkdownInlineMath {

    /** Ranges of backtick code spans in [text] (`` `code` `` and ``` ``python`` ``` etc). */
    fun findCodeRanges(text: String): List<IntRange> {
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
            val closing = findClosingBackticks(text, i + run, run)
            if (closing >= 0) {
                out.add(i..(closing + run - 1))
                i = closing + run
            } else {
                i += run
            }
        }
        return out
    }

    private fun findClosingBackticks(text: String, from: Int, run: Int): Int {
        var i = from
        while (i + run <= text.length) {
            if (i + run <= text.length && text.substring(i, i + run).all { it == '`' }) {
                // prevent matching a LONGER run as a valid closer for a shorter opener
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

    /**
     * Find `$...$` / `$$...$$` runs in [text], skipping any region reported in
     * [codeRanges]. Returns runs in ascending order. Unclosed delimiters and lone
     * `$` signs are ignored (no partial/half runs).
     */
    fun findMathRuns(text: String, codeRanges: List<IntRange> = findCodeRanges(text)): List<MathRun> {
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
            if (insideAny(text, i, codeRanges)) {
                i += openLen
                continue
            }
            val close = findClosingDollar(text, i + openLen, isBlock, codeRanges)
            if (close < 0) {
                // Unclosed — stop scanning; a stray `$` usually means prose.
                break
            }
            out.add(MathRun(i, close + (if (isBlock) 1 else 0), isBlock))
            i = close + (if (isBlock) 2 else 1)
        }
        return out
    }

    private fun findClosingDollar(text: String, from: Int, isBlock: Boolean, codeRanges: List<IntRange>): Int {
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
                if (insideAny(text, i - 1, codeRanges)) {
                    i++
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    private fun insideAny(text: String, index: Int, codeRanges: List<IntRange>): Boolean =
        codeRanges.any { index in it }
}