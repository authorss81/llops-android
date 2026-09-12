package com.authorss81.noteflow.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginInstallStore] — the store's install/delete
 * persistence. Default (absent key) = INSTALLED for built-ins so existing
 * builds keep every bundled plugin (no migration); Delete writes the explicit
 * uninstalled flag. Phase 270: OPTIONAL bundled store plugins (CaseChange)
 * default to NOT installed when the key is absent (fresh install reads as
 * "Not downloaded", not "Available — off").
 */
class SettingsPluginInstallStoreTest {

    private val id = "com.authorss81.noteflow.plugins.casechange"
    private val builtInId = "com.authorss81.noteflow.plugins.rot13"

    @Test
    fun `built-in plugins are installed by default (backward compatible)`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))

        assertTrue("absent plugin_uninstalled_<id> must mean installed for built-ins", store.isInstalled(builtInId))
    }

    @Test
    fun `optional plugins are NOT installed by default (fresh install)`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))

        assertFalse("absent plugin_uninstalled_<id> must mean NOT installed for optional plugins", store.isInstalled(id))
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
