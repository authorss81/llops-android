package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.dictation.DictationAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 Dictation pure-JVM tests: whitespace normalization, spacing and
 * sentence-capitalization rules for folding recognized utterances into a note.
 */
class DictationAssemblerTest {

    @Test
    fun `blank note capitalizes the first utterance`() {
        assertEquals("Hello world", DictationAssembler.appendUtterance("", "hello world"))
    }

    @Test
    fun `whitespace collapses and the utterance is trimmed`() {
        assertEquals("Hello again there", DictationAssembler.appendUtterance("Hello", "again   there"))
    }

    @Test
    fun `continuing a note adds exactly one space and keeps the casing`() {
        assertEquals("Hello again", DictationAssembler.appendUtterance("Hello", "again"))
    }

    @Test
    fun `utterance starting after a period becomes a new capitalized sentence`() {
        assertEquals("Hello. World", DictationAssembler.appendUtterance("Hello.", "world"))
    }

    @Test
    fun `utterance after an exclamation point is capitalized`() {
        assertEquals("Great! Wow", DictationAssembler.appendUtterance("Great!", "wow"))
    }

    @Test
    fun `mid-sentence insertion keeps the recognizer casing`() {
        assertEquals("I like cats and dogs", DictationAssembler.appendUtterance("I like", "cats and dogs"))
    }

    @Test
    fun `note that already ends with a newline appends on its own line`() {
        assertEquals("Alpha\nbeta", DictationAssembler.appendUtterance("Alpha\n", "beta"))
    }

    @Test
    fun `whitespace-only utterance makes no change`() {
        assertEquals("Alpha", DictationAssembler.appendUtterance("Alpha", "   "))
    }

    @Test
    fun `trailing whitespace in the existing note is normalized to a single space`() {
        assertEquals("Alpha beta", DictationAssembler.appendUtterance("Alpha  ", "beta"))
    }

    @Test
    fun `folded text is never empty for a valid utterance`() {
        val out = DictationAssembler.appendUtterance("", "test")
        assertTrue(out.isNotBlank())
    }
}