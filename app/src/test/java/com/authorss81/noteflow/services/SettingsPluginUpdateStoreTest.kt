package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 212: [SettingsPluginUpdateStore] — the update flow's rollback root
 * (`plugin_update_previous_<id>`), written BEFORE any update byte moves and
 * wiped by the store's Delete.
 */
class SettingsPluginUpdateStoreTest {

    private fun entry(v: PluginVersion) = PluginEntry(
        id = "com.example.updatable",
        name = "Updatable",
        description = "d",
        version = v,
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = "https://plugins.example.com/updatable-$v.apk",
        sha256 = "b".repeat(64),
        pinnedCertHash = "sha256/BBBB",
        source = PluginEntrySource.REMOTE
    )

    @Test
    fun `fresh plugins have no rollback root`() {
        val store = SettingsPluginUpdateStore(settingsOver(FakePrefs()))

        assertNull(store.previousFor("com.example.updatable"))
    }

    @Test
    fun `the pre-update entry round-trips`() {
        val store = SettingsPluginUpdateStore(settingsOver(FakePrefs()))
        val previous = entry(PluginVersion(1, 0, 0))

        store.savePrevious(previous)

        assertEquals(previous, store.previousFor("com.example.updatable"))
    }

    @Test
    fun `a newer save wins (latest rollback root)`() {
        val store = SettingsPluginUpdateStore(settingsOver(FakePrefs()))
        store.savePrevious(entry(PluginVersion(1, 0, 0)))
        store.savePrevious(entry(PluginVersion(1, 1, 0)))

        assertEquals(
            PluginVersion(1, 1, 0),
            store.previousFor("com.example.updatable")?.version
        )
    }

    @Test
    fun `clearPrevious removes the root and tolerates unknown ids`() {
        val store = SettingsPluginUpdateStore(settingsOver(FakePrefs()))
        store.savePrevious(entry(PluginVersion(2, 0, 0)))

        store.clearPrevious("com.example.updatable")
        assertNull(store.previousFor("com.example.updatable"))

        store.clearPrevious("never.saved")
        assertNull(store.previousFor("never.saved"))
    }

    @Test
    fun `rollback root survives a restart but not a Delete`() {
        val prefs = FakePrefs()
        val previous = entry(PluginVersion(3, 1, 4))
        SettingsPluginUpdateStore(settingsOver(prefs)).savePrevious(previous)

        val settings = settingsOver(prefs)
        assertEquals(previous, SettingsPluginUpdateStore(settings).previousFor("com.example.updatable"))

        settings.wipePluginState("com.example.updatable")
        assertNull(SettingsPluginUpdateStore(settings).previousFor("com.example.updatable"))
    }
}
