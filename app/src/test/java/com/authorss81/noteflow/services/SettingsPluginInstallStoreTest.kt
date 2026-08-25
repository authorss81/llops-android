package com.authorss81.noteflow.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginInstallStore] — the store's install/delete
 * persistence. Default (absent key) = INSTALLED so existing builds keep every
 * bundled plugin (no migration); Delete writes the explicit uninstalled flag.
 */
class SettingsPluginInstallStoreTest {

    private val id = "com.authorss81.noteflow.plugins.casechange"

    @Test
    fun `plugins are installed by default (backward compatible)`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))

        assertTrue("absent plugin_uninstalled_<id> must mean installed", store.isInstalled(id))
    }

    @Test
    fun `delete marks the plugin uninstalled`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))

        store.setInstalled(id, false)

        assertFalse(store.isInstalled(id))
    }

    @Test
    fun `re-download round-trips back to installed`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))
        store.setInstalled(id, false)

        store.setInstalled(id, true)

        assertTrue(store.isInstalled(id))
    }

    @Test
    fun `uninstall state survives a process restart`() {
        val prefs = FakePrefs()
        SettingsPluginInstallStore(settingsOver(prefs)).setInstalled(id, false)

        val afterRestart = SettingsPluginInstallStore(settingsOver(prefs))
        assertFalse("a deleted plugin stays deleted across restarts", afterRestart.isInstalled(id))
    }

    @Test
    fun `install state is per-plugin`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))
        store.setInstalled(id, false)

        assertTrue(store.isInstalled("com.authorss81.noteflow.plugins.rot13"))
        assertFalse(store.isInstalled(id))
    }
}
