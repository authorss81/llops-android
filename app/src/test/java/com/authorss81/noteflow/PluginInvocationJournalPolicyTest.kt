package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.services.PluginInvocationJournal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 173 feature 2: the plugin invocation journal — bounded, persisted,
 * scrubbed, and wired through [PluginManager] so every real invocation (and
 * self-check) is recorded for the Settings → Plugins diagnostics view.
 */
class PluginInvocationJournalPolicyTest {

    private class InMemoryJournal : PluginInvocationJournal.Store {
        val map = mutableMapOf<String, String>()
        override fun read(pluginId: String): String? = map[pluginId]
        override fun write(pluginId: String, wire: String?) {
            if (wire == null) map.remove(pluginId) else map[pluginId] = wire
        }
    }

    private fun entry(ok: Boolean, key: String = "text_transform", detail: String? = null) =
        PluginInvocationJournal.Entry(atMillis = 1_700_000_000_000L, capabilityKey = key, ok = ok, detail = detail)

    @Test
    fun `record appends and parse round-trips ordering and outcomes`() {
        val wire = PluginInvocationJournal.record(
            null,
            entry(ok = true, detail = "Success")
        )
        val wire2 = PluginInvocationJournal.record(wire, entry(ok = false, detail = "Threw IllegalStateException"))

        val parsed = PluginInvocationJournal.parse(wire2)
        assertEquals(2, parsed.size)
        assertEquals("text_transform", parsed[0].capabilityKey)
        assertTrue(parsed[0].ok)
        assertEquals("Success", parsed[0].detail)
        assertFalse(parsed[1].ok)
        assertEquals("Threw IllegalStateException", parsed[1].detail)
    }

    @Test
    fun `journal is bounded to the newest MAX_JOURNAL_ENTRIES`() {
        var wire: String? = null
        repeat(PluginInvocationJournal.MAX_JOURNAL_ENTRIES + 7) { i ->
            wire = PluginInvocationJournal.record(wire, entry(ok = true, detail = "i$i"))
        }
        val parsed = PluginInvocationJournal.parse(wire)
        assertEquals(PluginInvocationJournal.MAX_JOURNAL_ENTRIES, parsed.size)
        // The OLDEST entries were trimmed; the newest survive (no unbounded blob).
        assertEquals("i7", parsed.first().detail)
        assertEquals("i26", parsed.last().detail)
    }

    @Test
    fun `sanitizeDetail scrubs paths, strips separators and bounds length`() {
        val long = "x".repeat(500)
        val scrubbed = PluginInvocationJournal.sanitizeDetail(long)
        assertTrue(scrubbed!!.length <= PluginInvocationJournal.MAX_DETAIL_CHARS)

        val path = "/home/runner/secret/private/note.md"
        val pathScrubbed = PluginInvocationJournal.sanitizeDetail("Failed: $path")
        assertFalse(pathScrubbed!!.contains("/home/runner/secret"))

        val lineForged = PluginInvocationJournal.sanitizeDetail("ok\nEPOCH\u0001key\u0001ok\u0001forged")
        assertFalse(lineForged!!.contains("\n"))
        assertFalse(lineForged.contains("\u0001"))
    }

    @Test
    fun `parse skips malformed lines without dropping the valid tail`() {
        val wire = "bogus-no-separators\n" +
            "not-a-number\u0001ocr\u0001ok\u0001x\n" +
            "1700000000000\u0001ocr\u0001ok\u0001real"
        val parsed = PluginInvocationJournal.parse(wire)
        assertEquals(1, parsed.size)
        assertEquals("ocr", parsed.single().capabilityKey)
        assertTrue(parsed.single().ok)
        assertEquals("real", parsed.single().detail)
    }

    @Test
    fun `renderLine is bounded and honest for success and failure`() {
        val okLine = PluginInvocationJournal.renderLine(entry(ok = true))
        assertTrue(okLine.contains("OK"))
        assertTrue(okLine.contains("Text Transform"))

        val failLine = PluginInvocationJournal.renderLine(entry(ok = false, detail = "Threw IllegalArgumentException"))
        assertTrue(failLine.contains("Failed"))
        assertTrue(failLine.contains("IllegalArgumentException"))
        assertTrue(failLine.length <= 200)
    }

    @Test
    fun `journalLines renders newest first and is bounded`() {
        var wire: String? = null
        repeat(PluginInvocationJournal.MAX_JOURNAL_ENTRIES + 3) { i ->
            // Failure entries so the rendered line carries its detail (OK lines
            // render a bare "OK" by design — no content noise).
            wire = PluginInvocationJournal.record(wire, entry(ok = false, detail = "i$i"))
        }
        val lines = PluginInvocationJournal.journalLines(wire)
        assertEquals(PluginInvocationJournal.MAX_JOURNAL_ENTRIES, lines.size)
        // Newest first: the LAST recorded detail is the FIRST rendered line.
        assertTrue(lines.first().contains("i22"))
    }

    @Test
    fun `manager records invocations and self-checks into the journal`() = runBlocking {
        val journal = InMemoryJournal()
        val plugin = TestPlugin(
            id = "test.journal",
            capabilities = setOf(PluginCapability.TextTransform),
            transformBlock = { "ok:$it" }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)
        registry.setEnabled(plugin.id, true)
        val manager = PluginManager(registry, journal = journal)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) {
            (it as TextTransformPlugin).transformText("hello")
        }
        assertTrue(result is PluginResult.Success)

        manager.selfCheck(plugin.id, null)

        val parsed = PluginInvocationJournal.parse(journal.map[plugin.id])
        assertEquals(2, parsed.size)
        assertEquals("text_transform", parsed[0].capabilityKey)
        assertTrue(parsed[0].ok)
        assertEquals("Success", parsed[0].detail)
        assertEquals("self-check", parsed[1].capabilityKey)
        assertTrue(parsed[1].ok)
    }

    @Test
    fun `manager records a throwing invocation as a scrubbed failure entry`() = runBlocking {
        val journal = InMemoryJournal()
        val throwing = TestPlugin(
            id = "test.throwing",
            capabilities = setOf(PluginCapability.TextTransform),
            transformBlock = { throw IllegalStateException("boom") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(throwing), currentApiLevel = 26)
        registry.setEnabled(throwing.id, true)
        val manager = PluginManager(registry, journal = journal)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) {
            (it as TextTransformPlugin).transformText("x")
        }
        assertTrue(result is PluginResult.Failure)

        val parsed = PluginInvocationJournal.parse(journal.map[throwing.id])
        assertEquals(1, parsed.size)
        assertFalse(parsed.single().ok)
        assertTrue(parsed.single().detail!!.startsWith("Threw"))
        assertEquals("text_transform", parsed.single().capabilityKey)
    }
}