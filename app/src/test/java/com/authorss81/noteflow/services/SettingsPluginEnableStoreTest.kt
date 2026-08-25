package com.authorss81.noteflow.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginEnableStore] — the production opt-in persistence
 * for plugins, exercised against a REAL [SettingsManager] over fake prefs.
 * Pins the default-OFF rule (phase-10/177) and the ever-enabled latch that
 * distinguishes REGISTERED from DISABLED (phase-11).
 */
class SettingsPluginEnableStoreTest {

    private val id = "com.authorss81.noteflow.plugins.rot13"

    @Test
    fun `plugins are DISABLED by default`() {
        val store = SettingsPluginEnableStore(settingsOver(FakePrefs()))

        assertFalse("no key must mean off (plugin_enabled_<id> defaults false)", store.isEnabled(id))
        assertFalse(store.hasEverBeenEnabled(id))
    }

    @Test
    fun `enabling persists and latches ever-enabled`() {
        val store = SettingsPluginEnableStore(settingsOver(FakePrefs()))

        store.setEnabled(id, true)

        assertTrue(store.isEnabled(id))
        assertTrue(store.hasEverBeenEnabled(id))
    }

    @Test
    fun `disabling keeps the ever-enabled latch (DISABLED, not REGISTERED)`() {
        val store = SettingsPluginEnableStore(settingsOver(FakePrefs()))
        store.setEnabled(id, true)

        store.setEnabled(id, false)

        assertFalse(store.isEnabled(id))
        assertTrue("the user opted in once — the plugin stays DISABLED-classed", store.hasEverBeenEnabled(id))
    }

    @Test
    fun `wipe resets both the flag and the history (REGISTERED again)`() {
        val store = SettingsPluginEnableStore(settingsOver(FakePrefs()))
        store.setEnabled(id, true)
        store.setEnabled(id, false)

        store.wipe(id)

        assertFalse(store.isEnabled(id))
        assertFalse(store.hasEverBeenEnabled(id))
    }

    @Test
    fun `state survives a process restart over the same prefs`() {
        val prefs = FakePrefs()
        val first = SettingsPluginEnableStore(settingsOver(prefs))
        first.setEnabled(id, true)
        first.setEnabled("other.plugin", false)

        // "Restart": a brand-new manager + adapter over the same persisted map.
        val second = SettingsPluginEnableStore(settingsOver(prefs))
        assertTrue(second.isEnabled(id))
        assertTrue(second.hasEverBeenEnabled(id))
        assertFalse(second.isEnabled("other.plugin"))
    }

    @Test
    fun `different plugins never collide`() {
        val store = SettingsPluginEnableStore(settingsOver(FakePrefs()))
        store.setEnabled("plugin.a", true)

        assertTrue(store.isEnabled("plugin.a"))
        assertFalse(store.isEnabled("plugin.b"))
    }
}
