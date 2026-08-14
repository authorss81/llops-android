package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.runtime.InMemoryPluginEntryStore
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntryCodec
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 22: unified [PluginEntry] catalog model + [PluginEntryStore] persistence.
 * Pure JVM — codec round-trips, store semantics, invariant validation.
 */
class PluginEntryStoreTest {

    private fun remoteEntry(id: String = "com.authorss81.noteflow.plugins.remote.ocr"): PluginEntry =
        PluginEntry(
            id = id,
            name = "Remote OCR",
            description = "Heavy downloadable OCR engine.",
            version = PluginVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.OCR),
            category = "Vision",
            permissions = setOf(PluginPermission.Internet),
            downloadUrl = "https://plugins.example.com/ocr-1.0.0.apk",
            installSizeBytes = 45_000_000,
            updateChannel = "stable",
            sha256 = "ab12cd34ef56",
            pinnedCertHash = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            source = PluginEntrySource.REMOTE
        )

    private fun bundledEntry() = PluginEntry(
        id = "com.authorss81.noteflow.plugins.rot13",
        name = "ROT13",
        description = "ROT13 transform.",
        version = PluginVersion(1, 0, 0),
        capabilities = setOf(PluginCapability.TextTransform),
        category = "Text",
        source = PluginEntrySource.BUNDLED
    )

    @Test
    fun `codec round-trips a remote entry with all fields intact`() {
        val codec = PluginEntryCodec()
        val original = remoteEntry()

        val decoded = codec.decode(codec.encode(original))

        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertEquals(PluginVersion(1, 0, 0), decoded!!.version)
        assertEquals(setOf(PluginCapability.OCR), decoded.capabilities)
        assertEquals(setOf(PluginPermission.Internet), decoded.permissions)
        assertTrue(decoded.isDownloadable)
        assertFalse(decoded.isBundled)
    }

    @Test
    fun `codec round-trips a bundled entry and rejects malformed blobs`() {
        val codec = PluginEntryCodec()
        val original = bundledEntry()

        val decoded = codec.decode(codec.encode(original))

        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertTrue(decoded!!.isBundled)
        assertNull(decoded.downloadUrl)
        assertNull(decoded.sha256)
        assertNull(decoded.pinnedCertHash)

        assertNull(codec.decode("not json at all {"))
        assertNull(codec.decode(""))
    }

    @Test
    fun `codec rejects parseable blobs that violate entry invariants`() {
        val codec = PluginEntryCodec()

        // REMOTE source but no sha256 — parseable JSON, invalid entry.
        assertNull(
            codec.decode(
                """{"id":"r","name":"R","description":"d","version":"1.0.0","""" +
                    """"capabilityKeys":["ocr"],"category":"Vision","permissionKeys":[],""" +
                    """"updateChannel":"stable","downloadUrl":"https://x.example.com/a.apk","source":"REMOTE"}"""
            )
        )

        // BUNDLED source carrying a downloadUrl — also invalid.
        assertNull(
            codec.decode(
                """{"id":"b","name":"B","description":"d","version":"1.0.0","""" +
                    """"capabilityKeys":["text_transform"],"category":"Text","permissionKeys":[],""" +
                    """"updateChannel":"stable","downloadUrl":"https://x.example.com/a.apk","source":"BUNDLED"}"""
            )
        )
    }

    @Test
    fun `in-memory store save find all remove round-trip`() {
        val store = InMemoryPluginEntryStore()
        val a = remoteEntry("id.a")
        val b = remoteEntry("id.b")

        assertNull(store.find("id.a"))
        store.save(a)
        store.save(b)
        assertEquals(a, store.find("id.a"))
        assertEquals(setOf("id.a", "id.b"), store.all().map { it.id }.toSet())

        store.remove("id.a")
        assertNull(store.find("id.a"))
        assertEquals(listOf("id.b"), store.all().map { it.id })
    }

    @Test
    fun `entry invariants are enforced for both sources`() {
        // Valid entries pass.
        assertTrue(remoteEntry().isValid())
        assertTrue(bundledEntry().isValid())

        // A remote entry must carry HTTPS downloadUrl + both digests.
        assertFalse(
            remoteEntry().copy(downloadUrl = null).validationErrors().isEmpty()
        )
        assertFalse(
            remoteEntry().copy(downloadUrl = "http://insecure.example.com/x.apk").validationErrors().isEmpty()
        )
        assertFalse(
            remoteEntry().copy(sha256 = null).validationErrors().isEmpty()
        )
        assertFalse(
            remoteEntry().copy(pinnedCertHash = "").validationErrors().isEmpty()
        )
        // A bundled entry must NOT carry download material.
        assertFalse(
            bundledEntry().copy(downloadUrl = "https://x.example.com/a.apk").validationErrors().isEmpty()
        )
        assertFalse(
            bundledEntry().copy(sha256 = "abc").validationErrors().isEmpty()
        )
        // Blank identity is always invalid.
        assertFalse(bundledEntry().copy(name = "").isValid())
    }

    @Test
    fun `catalog merges persisted remote entries alongside bundled definitions`() {
        val enableStore = InMemoryEnableStore()
        val settingsStore = InMemoryPluginSettingsStore()
        val installStore = com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore(emptyList())
        val registry = PluginRegistry(
            enableStore = enableStore,
            settingsStore = settingsStore,
            installStore = installStore,
            currentApiLevel = 26
        )
        val entryStore = InMemoryPluginEntryStore()
        entryStore.save(remoteEntry())

        val catalog = com.authorss81.noteflow.plugins.store.PluginStoreCatalog(registry, entryStore)
        val rows = catalog.entries()

        // Every compiled built-in ships as a BUNDLED entry.
        val rot13 = rows.first { it.pluginId == "com.authorss81.noteflow.plugins.rot13" }
        assertTrue(rot13.bundled)
        assertEquals("bundled", rot13.sourceLabel)
        assertEquals(PluginVersion(1, 0, 0), rot13.version)
        assertEquals(null, rot13.downloadUrl)

        // The persisted remote entry is listed too, marked "remote" with its
        // unified download metadata.
        val remote = rows.first { it.pluginId == "com.authorss81.noteflow.plugins.remote.ocr" }
        assertFalse(remote.bundled)
        assertEquals("remote", remote.sourceLabel)
        assertEquals(PluginVersion(1, 0, 0), remote.version)
        assertEquals("https://plugins.example.com/ocr-1.0.0.apk", remote.downloadUrl)
        assertEquals(45_000_000L, remote.installSizeBytes)
        assertEquals("Vision", remote.category)
        // Not installed: the registry has no compiled definition for it.
        assertFalse(registry.isInstalled(remote.pluginId))
    }
}
