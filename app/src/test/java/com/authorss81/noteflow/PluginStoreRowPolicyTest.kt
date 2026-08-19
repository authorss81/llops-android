package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.services.PluginStoreRowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 173 feature 3: the compact per-plugin store metadata line — declared
 * capabilities (bounded) + shipping bucket + download honesty. Pins the
 * edge cases: long capability lists, downloadable (remote) entries, unserved /
 * unknown-size remote entries, and the defensive empty-capability set.
 */
class PluginStoreRowPolicyTest {

    private fun entry(
        source: PluginEntrySource = PluginEntrySource.BUNDLED,
        capabilities: Set<PluginCapability> = setOf(PluginCapability.FileTransfer),
        installSizeBytes: Long? = null
    ): PluginEntry = PluginEntry(
        id = "plugins.p",
        name = "Plugin P",
        description = "d",
        version = PluginVersion.parse("1.0.0")!!,
        capabilities = capabilities,
        category = "Files",
        permissions = emptySet(),
        installSizeBytes = installSizeBytes,
        downloadUrl = if (source == PluginEntrySource.REMOTE) "https://example.com/plugin.apk" else null,
        sha256 = if (source == PluginEntrySource.REMOTE) "ab" else null,
        pinnedCertHash = if (source == PluginEntrySource.REMOTE) "cd" else null,
        source = source
    )

    @Test
    fun `bundled entry renders capabilities and the in-app bucket`() {
        val line = PluginStoreRowPolicy.metadataLine(
            entry(capabilities = setOf(PluginCapability.FileTransfer))
        )
        assertEquals("Serves: File Transfer · Bundled (in app)", line)
    }

    @Test
    fun `downloadable entry with a known size shows the download size`() {
        val line = PluginStoreRowPolicy.metadataLine(
            entry(
                source = PluginEntrySource.REMOTE,
                capabilities = setOf(PluginCapability.Assistant),
                installSizeBytes = 2L * 1024L * 1024L * 1024L // 2 GB
            )
        )
        assertEquals("Serves: Assistant · Downloadable (verified) · ~2048 MB download", line)
    }

    @Test
    fun `downloadable entry with unknown size honestly needs the hosted channel`() {
        val line = PluginStoreRowPolicy.metadataLine(
            entry(source = PluginEntrySource.REMOTE, capabilities = setOf(PluginCapability.Assistant))
        )
        assertTrue(line.endsWith("needs the hosted channel"))
        assertEquals("Serves: Assistant · Downloadable (verified) · needs the hosted channel", line)
    }

    @Test
    fun `long capability lists are folded with a plus-N-more count`() {
        val caps = setOf(
            PluginCapability.FileTransfer,
            PluginCapability.OCR,
            PluginCapability.Weather,
            PluginCapability.Dictionary,
            PluginCapability.TextTransform
        )
        val label = PluginStoreRowPolicy.capabilitiesLabel(caps)
        assertTrue(label.contains(", +2 more"))
        // Exclusive (single-winner) headers first, then the rest — alphabetical
        // within each group: OCR + File Transfer are exclusive, then Dictionary.
        assertEquals(
            "File Transfer, OCR, Dictionary, +2 more",
            label
        )
        // The folded bundle never grows beyond MAX_CAPABILITIES_IN_LINE + 1 token.
        assertTrue(label.length <= 80)
    }

    @Test
    fun `empty capability set is defensive instead of fabricated`() {
        assertEquals("none declared", PluginStoreRowPolicy.capabilitiesLabel(emptySet()))
        assertEquals(
            "Serves: none declared · Bundled (in app)",
            PluginStoreRowPolicy.metadataLine(entry(capabilities = emptySet()))
        )
    }

    @Test
    fun `capability labels are sorted for determinism and deduplicated`() {
        assertEquals(
            "OCR, Text Transform",
            PluginStoreRowPolicy.capabilitiesLabel(
                setOf(PluginCapability.TextTransform, PluginCapability.OCR, PluginCapability.OCR)
            )
        )
    }

    @Test
    fun `downloadNote is null for bundled and bounded for remote`() {
        assertNull(PluginStoreRowPolicy.downloadNote(entry(source = PluginEntrySource.BUNDLED)))
        assertTrue(PluginStoreRowPolicy.downloadNote(entry(source = PluginEntrySource.REMOTE))!!.length <= 40)
    }
}