package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.store.PluginStoreEntry
import com.authorss81.noteflow.services.PluginCapabilityDirectory
import com.authorss81.noteflow.services.PluginCapabilityDirectory.Coverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-157 feature 1: the pure-JVM capability → plugin mapping table that
 * powers the Plugin Store's "What can plugins do?" view + the per-capability
 * store filter. Pins the coverage verdicts (INSTALLED / AVAILABLE_ON_STORE /
 * UNSERVED), the deterministic row order and the compact serving summaries.
 */
class PluginCapabilityDirectoryTest {

    private fun entry(
        id: String,
        name: String,
        capabilities: Set<PluginCapability>
    ): PluginStoreEntry = PluginStoreEntry(
        entry = PluginEntry(
            id = id,
            name = name,
            description = "desc of $name",
            version = PluginVersion.parse("1.0.0")!!,
            capabilities = capabilities,
            category = "Text",
            source = PluginEntrySource.BUNDLED
        ),
        optional = true
    )

    /** A tiny standalone registry instance so this test owns its inputs. */
    private fun entries(): List<PluginStoreEntry> = listOf(
        entry("rot13", "ROT13", setOf(PluginCapability.TextTransform)),
        entry("ogg", "OCR Engine", setOf(PluginCapability.OCR)),
        entry("ddg", "Web Search", setOf(PluginCapability.WebSearch)),
        entry("xport", "Export", setOf(PluginCapability.Export))
    )

    @Test
    fun `rows follow PluginCapability ALL order deterministically`() {
        val rows = PluginCapabilityDirectory.rows(entries()) { true }
        assertEquals(
            PluginCapability.ALL.map { it.key },
            rows.map { it.capability.key }
        )
        // Row order is stable across calls.
        assertEquals(rows, PluginCapabilityDirectory.rows(entries()) { true })
    }

    @Test
    fun `installed plugins are marked served`() {
        val rows = PluginCapabilityDirectory.rows(entries()) { id -> id != "xport" }

        val ocr = rows.first { it.capability == PluginCapability.OCR }
        assertEquals(Coverage.INSTALLED, ocr.coverage)
        assertTrue(ocr.isServed)
        assertEquals(listOf("OCR Engine"), ocr.installedPlugins.map { it.name })
        assertTrue(ocr.availablePlugins.isEmpty())

        val textTransform = rows.first { it.capability == PluginCapability.TextTransform }
        assertEquals(Coverage.INSTALLED, textTransform.coverage)
        assertEquals(listOf("ROT13"), textTransform.installedPlugins.map { it.name })
    }

    @Test
    fun `not-downloaded catalog plugin is available on the store`() {
        val rows = PluginCapabilityDirectory.rows(entries()) { id -> id != "xport" }

        val export = rows.first { it.capability == PluginCapability.Export }
        assertEquals(Coverage.AVAILABLE_ON_STORE, export.coverage)
        assertFalse(export.isServed)
        assertTrue(export.installedPlugins.isEmpty())
        assertEquals(listOf("Export"), export.availablePlugins.map { it.name })
    }

    @Test
    fun `capability with no catalog plugin at all is honestly unserved`() {
        val rows = PluginCapabilityDirectory.rows(entries()) { true }

        // Neither FileTransfer nor Assistant is in the fixture catalog.
        val fileTransfer = rows.first { it.capability == PluginCapability.FileTransfer }
        assertEquals(Coverage.UNSERVED, fileTransfer.coverage)
        assertFalse(fileTransfer.isServed)
        assertTrue(fileTransfer.installedPlugins.isEmpty())
        assertTrue(fileTransfer.availablePlugins.isEmpty())

        val assistant = rows.first { it.capability == PluginCapability.Assistant }
        assertEquals(Coverage.UNSERVED, assistant.coverage)
    }

    @Test
    fun `a plugin serving multiple capabilities appears in every matching row`() {
        val rows = PluginCapabilityDirectory.rows(
            listOf(entry("duo", "Dual", setOf(PluginCapability.OCR, PluginCapability.WebSearch)))
        ) { true }

        rows.first { it.capability == PluginCapability.OCR }.let {
            assertEquals(Coverage.INSTALLED, it.coverage)
            assertEquals(listOf("Dual"), it.installedPlugins.map { p -> p.name })
        }
        rows.first { it.capability == PluginCapability.WebSearch }.let {
            assertEquals(Coverage.INSTALLED, it.coverage)
            assertEquals(listOf("Dual"), it.installedPlugins.map { p -> p.name })
        }
    }

    @Test
    fun `capabilitiesInStore only lists offered capabilities in ALL order`() {
        val caps = PluginCapabilityDirectory.capabilitiesInStore(entries())
        assertEquals(
            listOf(
                PluginCapability.TextTransform,
                PluginCapability.OCR,
                PluginCapability.WebSearch,
                PluginCapability.Export
            ),
            caps
        )
        // An entry with nothing interesting adds nothing.
        assertEquals(
            listOf(PluginCapability.Export),
            PluginCapabilityDirectory.capabilitiesInStore(entries().filter { it.pluginId == "xport" })
        )
    }

    @Test
    fun `coverage labels are fixed and non-alarming`() {
        assertEquals("Installed", PluginCapabilityDirectory.coverageLabel(Coverage.INSTALLED))
        assertEquals("Available in store", PluginCapabilityDirectory.coverageLabel(Coverage.AVAILABLE_ON_STORE))
        assertEquals("No plugin yet", PluginCapabilityDirectory.coverageLabel(Coverage.UNSERVED))
    }

    @Test
    fun `serving summary folds long lists and stays compact`() {
        val many = (0 until 6).map { id -> entry("p$id", "Plugin $id", setOf(PluginCapability.OCR)) }
        val rows = PluginCapabilityDirectory.rows(many) { true }
        val ocr = rows.first { it.capability == PluginCapability.OCR }
        val summary = PluginCapabilityDirectory.servingSummary(ocr)

        assertEquals(Coverage.INSTALLED, ocr.coverage)
        assertTrue(summary!!.startsWith("installed: Plugin 0, Plugin 1, Plugin 2"))
        assertTrue(summary.endsWith(" +3 more"))
        assertTrue(summary.length <= 100)
    }

    @Test
    fun `unserved row has no serving summary`() {
        val rows = PluginCapabilityDirectory.rows(entries()) { true }
        val fileTransfer = rows.first { it.capability == PluginCapability.FileTransfer }
        assertNull(PluginCapabilityDirectory.servingSummary(fileTransfer))
    }
}