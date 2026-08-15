package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownInlineMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 37 inline math + code-span scanner.
 * Math runs must be located on the normalized literal, never inside a code span,
 * and highlighting must never drop characters.
 */
class MarkdownInlineMathTest {

    @Test
    fun `single-dollar math run is found`() {
        val runs = MarkdownInlineMath.findMathRuns("\$x = 1\$")
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].startIndex)
        assertEquals(6, runs[0].endIndex)
        assertEquals(false, runs[0].isBlock)
    }

    @Test
    fun `double-dollar math run is flagged block`() {
        val runs = MarkdownInlineMath.findMathRuns("\$\$x\$\$")
        assertEquals(1, runs.size)
        assertEquals(true, runs[0].isBlock)
        assertTrue(runs[0].startIndex >= 0)
    }

    @Test
    fun `math inside backtick code spans is never reported`() {
        val text = "`\$x\$` stays plain code"
        val codeRanges = MarkdownInlineMath.findCodeRanges(text)
        assertTrue(codeRanges.size >= 1)
        assertEquals(0, MarkdownInlineMath.findMathRuns(text, codeRanges).size)
    }

    @Test
    fun `unclosed dollar sign is ignored, not half-highlighted`() {
        assertEquals(0, MarkdownInlineMath.findMathRuns("I have $5 and no math").size)
    }

    @Test
    fun `multiple disjoint math runs are ordered and complete`() {
        val text = "left \$a\$ middle \$b\$ right"
        val runs = MarkdownInlineMath.findMathRuns(text)
        assertEquals(2, runs.size)
        assertEquals(text.substring(runs[0].startIndex, runs[0].endIndex + 1), "\$a\$")
        assertEquals(text.substring(runs[1].startIndex, runs[1].endIndex + 1), "\$b\$")
    }

    @Test
    fun `code ranges cover full spans and tolerate triple backticks`() {
        val text = "```python\nx\n```"
        val ranges = MarkdownInlineMath.findCodeRanges(text)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].first)
        assertEquals(text.length - 1, ranges[0].last)
    }
}