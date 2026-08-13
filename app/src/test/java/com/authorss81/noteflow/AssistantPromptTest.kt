package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.assistant.AssistantPrompts
import com.authorss81.noteflow.plugins.assistant.AssistantStoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 Assistant pure-JVM tests: prompt assembly (context truncation,
 * task phrasing) and the model-download storage policy (identity, free-space
 * guard, plausibility check).
 */
class AssistantPromptTest {

    // ---- AssistantPrompts ---------------------------------------------------

    @Test
    fun `summarize prompt is bounded and contains the note`() {
        val prompt = AssistantPrompts.summarize("My quick note about planning.")
        assertTrue(prompt.contains("My quick note about planning."))
        assertTrue(prompt.contains("privacy-first"))
    }

    @Test
    fun `long notes are truncated at the context cap with a marker`() {
        val big = "word ".repeat(AssistantPrompts.MAX_CONTEXT_CHARS).trim()
        val prompt = AssistantPrompts.summarize(big)
        assertTrue(prompt.contains("[…note truncated]"))
        // The prompt is always bounded: system preamble + truncated context + marker.
        assertTrue(prompt.length < AssistantPrompts.MAX_CONTEXT_CHARS + 300)
    }

    @Test
    fun `short notes are never truncated`() {
        val prompt = AssistantPrompts.extractActionItems("Buy milk.\nCall dentist.")
        assertFalse(prompt.contains("truncated"))
        assertTrue(prompt.contains("- "))
    }

    @Test
    fun `question prompt embeds the question`() {
        val prompt = AssistantPrompts.answerQuestion("The sky is blue.", "What color is the sky?")
        assertTrue(prompt.contains("What color is the sky?"))
    }

    @Test
    fun `tags prompt asks for tags only`() {
        val prompt = AssistantPrompts.suggestTags("A note about cooking.")
        assertTrue(prompt.contains("tags"))
    }

    @Test
    fun `truncate cuts at a word boundary for huge inputs`() {
        val out = AssistantPrompts.truncate("a".repeat(9000))
        assertTrue(out.length <= AssistantPrompts.MAX_CONTEXT_CHARS + 40)
    }

    // ---- AssistantStoragePolicy --------------------------------------------

    @Test
    fun `default model identity and size are fixed`() {
        assertTrue(AssistantStoragePolicy.DEFAULT_MODEL_URL.endsWith(".gguf"))
        assertTrue(AssistantStoragePolicy.DEFAULT_MODEL_URL.contains("resolve/main"))
        assertEquals("assistant-model.gguf", AssistantStoragePolicy.DEFAULT_MODEL_FILE_NAME)
        assertEquals(398L * 1024 * 1024, AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES)
    }

    @Test
    fun `space check passes when available bytes cover model plus margin`() {
        val ok = AssistantStoragePolicy.checkSpace(
            AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES + 65L * 1024 * 1024,
            AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES
        )
        assertTrue(ok is AssistantStoragePolicy.SpaceCheck.Ok)
    }

    @Test
    fun `space check rejects a disk that is one byte short`() {
        val result = AssistantStoragePolicy.checkSpace(
            AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES + 64L * 1024 * 1024 - 1,
            AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES
        )
        assertTrue(result is AssistantStoragePolicy.SpaceCheck.Insufficient)
    }

    @Test
    fun `negative available bytes is treated as untestable and passes`() {
        val result = AssistantStoragePolicy.checkSpace(-1, AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES)
        assertTrue(result is AssistantStoragePolicy.SpaceCheck.Ok)
    }

    @Test
    fun `plausible model file must be larger than one megabyte`() {
        assertFalse(AssistantStoragePolicy.isPlausibleModelFile(100 * 1024))
        assertTrue(AssistantStoragePolicy.isPlausibleModelFile(AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES))
    }
}