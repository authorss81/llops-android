package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.TtsSpeechPlan
import com.authorss81.noteflow.plugins.readaloud.ReadAloudPolicy
import com.authorss81.noteflow.plugins.readaloud.TtsChunkSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 Read-Aloud pure-JVM tests: passage chunking (fenced code spoken
 * verbatim, prose packed by sentence/paragraph) and the quiet-mode policy.
 */
class TtsChunkSplitterTest {

    @Test
    fun `blank passage produces no chunks`() {
        assertTrue(TtsChunkSplitter.chunkText("   \n  ").isEmpty())
    }

    @Test
    fun `plain prose yields one chunk under the cap`() {
        val chunks = TtsChunkSplitter.chunkText("Hello world, this is a note.")
        assertEquals(1, chunks.size)
        assertTrue(!chunks[0].isCode)
    }

    @Test
    fun `paragraphs are split into separate chunks`() {
        val chunks = TtsChunkSplitter.chunkText("First paragraph.\n\nSecond paragraph.")
        assertEquals(2, chunks.size)
    }

    @Test
    fun `fenced code is spoken verbatim in line chunks and marked code`() {
        val passage = "Intro.\n\n```kotlin\nfun main() {\n    println(\"ok\")\n}\n```\n\nOutro."
        val chunks = TtsChunkSplitter.chunkText(passage)
        val codeChunks = chunks.filter { it.isCode }
        // 1 prose (Intro.) + 3 code lines + 1 prose (Outro.).
        assertEquals(5, chunks.size)
        assertEquals(3, codeChunks.size)
        assertTrue(codeChunks.all { !it.text.contains("\n") })
    }

    @Test
    fun `long single sentence is hard-wrapped without losing words`() {
        val long = "one two three four five six seven eight nine ten"
        val chunks = TtsChunkSplitter.chunkText(long, maxChunkChars = 12)
        val joined = chunks.joinToString(" ").replace("  ", " ")
        assertTrue(joined.contains("one"))
        assertTrue(joined.contains("ten"))
        assertTrue(chunks.all { it.text.length <= 40 })
    }

    @Test
    fun `chunk indices are sequential`() {
        val chunks = TtsChunkSplitter.chunkText("a.\n\nb.\n\nc.", maxChunkChars = 4)
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    // ---- ReadAloudPolicy ----------------------------------------------------

    @Test
    fun `blank passage refuses with NothingToSpeak even in quiet mode`() {
        assertTrue(ReadAloudPolicy.plan("", quietMode = false) is TtsSpeechPlan.NothingToSpeak)
    }

    @Test
    fun `quiet mode refuses a readable passage with a reason`() {
        val plan = ReadAloudPolicy.plan("Readable.", quietMode = true)
        assertTrue(plan is TtsSpeechPlan.RefuseQuiet)
        assertTrue((plan as TtsSpeechPlan.RefuseQuiet).message.isNotBlank())
    }

    @Test
    fun `normal mode plays the chunked passage`() {
        val plan = ReadAloudPolicy.plan("Read me out loud.", quietMode = false)
        assertTrue(plan is TtsSpeechPlan.Play)
        assertTrue((plan as TtsSpeechPlan.Play).chunks.isNotEmpty())
    }

    @Test
    fun `quiet mode never produces a Play plan`() {
        val plan = ReadAloudPolicy.plan("Even a very long passage stays quiet.", quietMode = true)
        assertTrue(plan is TtsSpeechPlan.RefuseQuiet)
    }
}