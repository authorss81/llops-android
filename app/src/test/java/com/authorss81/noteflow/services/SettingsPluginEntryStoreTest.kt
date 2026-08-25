package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginEntryStore] — the persisted unified catalog for
 * REMOTE (downloadable) plugin definitions. Bundled entries are derivable
 * facts of the APK and must NEVER be persisted (saving one actively removes
 * any stale blob).
 */
class SettingsPluginEntryStoreTest {

    private fun remoteEntry(
        id: String = "com.example.remote.ocr",
        version: PluginVersion = PluginVersion(1, 2, 3)
    ) = PluginEntry(
        id = id,
        name = "Remote OCR",
        description = "Downloadable OCR engine",
        version = version,
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = "https://plugins.example.com/ocr-$version.apk",
        installSizeBytes = 1024L * 1024L,
        sha256 = "a".repeat(64),
        pinnedCertHash = "sha256/AAAA",
        source = PluginEntrySource.REMOTE
    )

    private fun bundledEntry() = PluginEntry(
        id = "com.authorss81.noteflow.plugins.rot13",
        name = "Rot13",
        description = "Built-in transform",
        version = PluginVersion(1, 0, 0),
        capabilities = setOf(PluginCapability.TextTransform),
        category = "Text",
        source = PluginEntrySource.BUNDLED
    )

    @Test
    fun `remote entries round-trip through prefs`() {
        val store = SettingsPluginEntryStore(settingsOver(FakePrefs()))
        val entry = remoteEntry()

        store.save(entry)

        assertEquals(entry, store.find(entry.id))
    }

    @Test
    fun `saving a BUNDLED entry never persists anything`() {
        val prefs = FakePrefs()
        val store = SettingsPluginEntryStore(settingsOver(prefs))

        store.save(bundledEntry())

        assertNull(store.find(bundledEntry().id))
        assertTrue(prefs.map.keys.none { it.startsWith("plugin_entry_") })
    }

    @Test
    fun `saving a bundled entry over a remote blob removes the residue`() {
        val store = SettingsPluginEntryStore(settingsOver(FakePrefs()))
        val id = "com.example.hybrid"
        store.save(remoteEntry(id = id))
        assertNotNull(store.find(id))

        // Same id re-classified as bundled (e.g. it became compiled-in): the
        // persisted remote definition must be dropped.
        store.save(bundledEntry().copy(id = id))

        assertNull(store.find(id))
    }

    @Test
    fun `remove deletes the blob and tolerates unknown ids`() {
        val store = SettingsPluginEntryStore(settingsOver(FakePrefs()))
        store.save(remoteEntry())

        store.remove("com.example.remote.ocr")
        assertNull(store.find("com.example.remote.ocr"))

        store.remove("never.saved")
        assertNull(store.find("never.saved"))
    }

    @Test
    fun `all enumerates every persisted remote entry`() {
        val store = SettingsPluginEntryStore(settingsOver(FakePrefs()))
        store.save(remoteEntry(id = "com.example.a"))
        store.save(remoteEntry(id = "com.example.b", version = PluginVersion(0, 9, 0)))

        val ids = store.all().map { it.id }.toSet()
        assertEquals(setOf("com.example.a", "com.example.b"), ids)
        assertTrue(store.all().all { it.source == PluginEntrySource.REMOTE })
    }

    @Test
    fun `a malformed persisted blob fails closed to null`() {
        val prefs = FakePrefs()
        val store = SettingsPluginEntryStore(settingsOver(prefs))
        prefs.map["plugin_entry_com.example.corrupt"] = "{not json"

        assertNull(store.find("com.example.corrupt"))
    }

    @Test
    fun `entries survive a process restart with digests intact`() {
        val prefs = FakePrefs()
        val entry = remoteEntry()
        SettingsPluginEntryStore(settingsOver(prefs)).save(entry)

        val restored = SettingsPluginEntryStore(settingsOver(prefs)).find(entry.id)

        assertEquals(entry.downloadUrl, restored?.downloadUrl)
        assertEquals(entry.sha256, restored?.sha256)
        assertEquals(entry.pinnedCertHash, restored?.pinnedCertHash)
        assertEquals(entry.version, restored?.version)
    }
}
