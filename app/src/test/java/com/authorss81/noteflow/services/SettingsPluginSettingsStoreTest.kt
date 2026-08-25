package com.authorss81.noteflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginSettingsStore] — the per-plugin namespaced
 * `plugins.<id>.<key>` settings surface. Two plugins can never collide, typed
 * accessors round-trip, and removeAll (= store Delete) wipes the whole
 * namespace while leaving other plugins' settings untouched.
 */
class SettingsPluginSettingsStoreTest {

    private val id = "com.example.configured"

    @Test
    fun `string settings round-trip and unset reads give null`() {
        val store = SettingsPluginSettingsStore(settingsOver(FakePrefs()))

        assertNull(store.getString(id, "mode"))
        store.setString(id, "mode", "upper")
        assertEquals("upper", store.getString(id, "mode"))

        store.setString(id, "mode", null)
        assertNull(store.getString(id, "mode"))
    }

    @Test
    fun `int and boolean settings round-trip with defaults`() {
        val store = SettingsPluginSettingsStore(settingsOver(FakePrefs()))

        assertEquals(5, store.getInt(id, "count", 5))
        store.setInt(id, "count", 42)
        assertEquals(42, store.getInt(id, "count", 5))

        assertTrue(store.getBoolean(id, "flag", true))
        store.setBoolean(id, "flag", false)
        assertFalse(store.getBoolean(id, "flag", true))
    }

    @Test
    fun `keys are namespaced per plugin`() {
        val store = SettingsPluginSettingsStore(settingsOver(FakePrefs()))
        store.setString(id, "mode", "upper")
        store.setString("com.example.other", "mode", "lower")

        assertEquals("upper", store.getString(id, "mode"))
        assertEquals("lower", store.getString("com.example.other", "mode"))
    }

    @Test
    fun `containsKey reflects writes only`() {
        val store = SettingsPluginSettingsStore(settingsOver(FakePrefs()))

        assertFalse(store.containsKey(id, "mode"))
        store.setString(id, "mode", "upper")
        assertTrue(store.containsKey(id, "mode"))
    }

    @Test
    fun `removeAll wipes only this plugin's namespace`() {
        val prefs = FakePrefs()
        val store = SettingsPluginSettingsStore(settingsOver(prefs))
        store.setString(id, "a", "1")
        store.setInt(id, "b", 2)
        store.setBoolean(id, "c", true)
        store.setString("com.example.other", "keep", "yes")

        store.removeAll(id)

        assertFalse(store.containsKey(id, "a"))
        assertFalse(store.containsKey(id, "b"))
        assertFalse(store.containsKey(id, "c"))
        assertEquals("yes", store.getString("com.example.other", "keep"))
        assertTrue(prefs.map.keys.none { it.startsWith("plugins.$id.") })
    }
}
