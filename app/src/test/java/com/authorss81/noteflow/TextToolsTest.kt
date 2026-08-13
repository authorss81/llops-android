package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.DiffOp
import com.authorss81.noteflow.plugins.texttools.TextNoteDiff
import com.authorss81.noteflow.plugins.texttools.TextToolsAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 Text Tools pure-JVM tests: structural statistics (words/chars/
 * paragraphs/sentences/reading time/Flesch-Kincaid) and the line-diff.
 */
class TextToolsTest {

    // ---- analysis ----------------------------------------------------------

    @Test
    fun `wordTokens splits on whitespace and drops pure-punctuation tokens`() {
        val tokens = TextToolsAnalyzer.wordTokens("hello, world ...")
        assertEquals(listOf("hello,", "world"), tokens)
    }

    @Test
    fun `analyze counts words and characters`() {
        val a = TextToolsAnalyzer.analyze("The quick brown fox jumps over the lazy dog.")
        assertEquals(9, a.wordCount)
        assertEquals(44, a.characterCount)
        assertEquals(1, a.sentenceCount)
    }

    @Test
    fun `analyze counts multiple sentences by terminal punctuation`() {
        val a = TextToolsAnalyzer.analyze("One sentence. Two sentence! Three sentence?")
        assertEquals(3, a.sentenceCount)
    }

    @Test
    fun `analyze splits paragraphs on blank lines`() {
        val a = TextToolsAnalyzer.analyze("First paragraph.\n\nSecond paragraph.\n\nThird.")
        assertEquals(3, a.paragraphCount)
    }

    @Test
    fun `analyze returns zero stats for blank input`() {
        val a = TextToolsAnalyzer.analyze("   ")
        assertEquals(0, a.wordCount)
        assertEquals(0, a.paragraphCount)
        assertEquals(0, a.sentenceCount)
        assertEquals(0, a.readingTimeSeconds)
        assertEquals("No text", a.fleschKincaidLabel)
    }

    @Test
    fun `reading time is words divided by 200 wpm`() {
        val a = TextToolsAnalyzer.analyze("word ".repeat(200).trim() + ".")
        // 200 words ≈ 1 minute.
        assertEquals(60, a.readingTimeSeconds)
    }

    @Test
    fun `readability label is bounded and present for real text`() {
        val a = TextToolsAnalyzer.analyze("This is a very simple sentence to read.")
        assertTrue(a.fleschKincaidLabel in setOf("Very easy", "Easy", "Fairly easy", "Average"))
    }

    // ---- diff --------------------------------------------------------------

    @Test
    fun `identical texts produce no hunks`() {
        assertTrue(TextNoteDiff.diff("line a\nline b", "line a\nline b").isEmpty())
    }

    @Test
    fun `added line produces an ADDED hunk`() {
        val hunks = TextNoteDiff.diff("a\nb", "a\nnew\nb")
        assertEquals(1, hunks.size)
        assertEquals(DiffOp.ADDED, hunks[0].op)
        assertEquals(2, hunks[0].startLine)
    }

    @Test
    fun `removed line produces a REMOVED hunk`() {
        val hunks = TextNoteDiff.diff("a\ngone\nb", "a\nb")
        assertEquals(1, hunks.size)
        assertEquals(DiffOp.REMOVED, hunks[0].op)
    }

    @Test
    fun `changed line shows both a removal and an addition`() {
        val hunks = TextNoteDiff.diff("keep\nold\nkeep2", "keep\nnew\nkeep2")
        val ops = hunks.map { it.op }.toSet()
        assertTrue(DiffOp.ADDED in ops)
        assertTrue(DiffOp.REMOVED in ops)
    }

    @Test
    fun `excerpt is truncated to a bounded length`() {
        val long = "x".repeat(200)
        val hunks = TextNoteDiff.diff("", long)
        assertTrue(hunks.isNotEmpty())
        assertTrue(hunks.all { it.excerpt.length <= 80 })
    }
}