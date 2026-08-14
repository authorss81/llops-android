package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.OutlineGeneratorPlugin
import com.authorss81.noteflow.plugins.OutlineOutcome
import com.authorss81.noteflow.plugins.OutlineStyle
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.outline.OutlineGeneratorCore
import com.authorss81.noteflow.plugins.outline.OutlineGeneratorPluginImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 26 Outline & Checklist plugin tests (PURE JVM).
 *
 * Covers the grouping/indent logic for both styles, existing-markdown
 * normalisation, empty-input handling and capability routing.
 */
class OutlineGeneratorTest {

    // ---- checklist -----------------------------------------------------------

    @Test
    fun `checklist converts plain lines into checkbox items`() {
        val out = OutlineGeneratorCore.generate("buy milk\nwalk the dog\n", OutlineStyle.CHECKLIST)
        assertEquals("- [ ] buy milk\n- [ ] walk the dog", out)
    }

    @Test
    fun `checklist keeps an existing checkbox untouched`() {
        val out = OutlineGeneratorCore.generate(
            "done already\n- [x] finished task",
            OutlineStyle.CHECKLIST
        )
        assertEquals("- [ ] done already\n- [x] finished task", out)
    }

    @Test
    fun `checklist strips existing list decoration`() {
        val out = OutlineGeneratorCore.generate(
            "- item one\n- item two\n",
            OutlineStyle.CHECKLIST
        )
        assertEquals("- [ ] item one\n- [ ] item two", out)
    }

    @Test
    fun `checklist drops blank lines but keeps order`() {
        val out = OutlineGeneratorCore.generate(
            "first\n\n\nsecond\n",
            OutlineStyle.CHECKLIST
        )
        assertEquals("- [ ] first\n- [ ] second", out)
    }

    // ---- outline --------------------------------------------------------------

    @Test
    fun `outline groups a multi-line block under its first line as a heading`() {
        val out = OutlineGeneratorCore.generate(
            "Project plan\nDesign the module\nTest the module",
            OutlineStyle.OUTLINE
        )
        assertEquals(
            "## Project plan\n- Design the module\n- Test the module",
            out
        )
    }

    @Test
    fun `outline splits separate blocks into separate sections`() {
        val out = OutlineGeneratorCore.generate(
            "Planning\nChoose the tools\n\nExecuting\nWrite the code",
            OutlineStyle.OUTLINE
        )
        assertEquals(
            "## Planning\n- Choose the tools\n\n## Executing\n- Write the code",
            out
        )
    }

    @Test
    fun `outline emits a bare heading for a single-line block`() {
        val out = OutlineGeneratorCore.generate("Just a heading", OutlineStyle.OUTLINE)
        assertEquals("## Just a heading", out)
    }

    @Test
    fun `outline normalises existing markdown heading and bullet decoration`() {
        val out = OutlineGeneratorCore.generate(
            "## Big topic\n- detail a\n1. detail b\n",
            OutlineStyle.OUTLINE
        )
        // decoration is stripped then re-emitted under a normalized heading
        assertEquals("## Big topic\n- detail a\n- detail b", out)
    }

    // ---- errors ----------------------------------------------------------------

    @Test
    fun `blank input returns null`() {
        assertNull(OutlineGeneratorCore.generate("", OutlineStyle.OUTLINE))
        assertNull(OutlineGeneratorCore.generate("   \n\n ", OutlineStyle.CHECKLIST))
    }

    @Test
    fun `plugin surfaces an honest error for empty input`() {
        val plugin = OutlineGeneratorPluginImpl()
        assertTrue(plugin.generateOutline("   ", OutlineStyle.OUTLINE) is OutlineOutcome.Error)
        val result = plugin.generateOutline("First line\nSecond line", OutlineStyle.OUTLINE)
        assertTrue(result is OutlineOutcome.Success)
    }

    // ---- plugin routing ---------------------------------------------------------

    @Test
    fun `plugin routes through the manager with the outline capability`() = runBlocking {
        val plugin = OutlineGeneratorPluginImpl()
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)
        val result = manager.withPluginAsync(PluginCapability.OutlineGenerator, null) { p ->
            (p as OutlineGeneratorPlugin).generateOutline("A\nB", OutlineStyle.CHECKLIST)
        }
        assertTrue(result is PluginResult.Success)
    }

    @Test
    fun `manifest declares outline generator capability`() {
        val plugin = OutlineGeneratorPluginImpl()
        assertTrue(PluginCapability.OutlineGenerator in plugin.capabilities)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.outline"))
    }
}