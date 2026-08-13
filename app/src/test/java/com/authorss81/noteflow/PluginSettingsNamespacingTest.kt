package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.PluginSettingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11: persisted plugin settings must be namespaced per plugin
 * (`plugins.<id>.<key>`) so two plugins can never collide on the same key.
 */
class PluginSettingsNamespacingTest {

    @Test
    fun keyConventionIsNamespaced() {
        assertEquals("plugins.a.theme", PluginSettingKey.key("a", "theme"))
        assertEquals("plugins.b.theme", PluginSettingKey.key("b", "theme"))
        assertNotEquals(PluginSettingKey.key("a", "theme"), PluginSettingKey.key("b", "theme"))
        assertNotEquals(PluginSettingKey.key("a", "theme"), PluginSettingKey.key("a", "theme_2"))
    }

    @Test
    fun twoPluginsSameKeyDoNotCollide() {
        val store = InMemoryPluginSettingsStore()
        val a = PluginSettings(store, "a")
        val b = PluginSettings(store, "b")

        a.setString("theme", "dark")
        b.setString("theme", "light")

        assertEquals("dark", a.getString("theme"))
        assertEquals("light", b.getString("theme"))
        // The full namespaced keys are distinct in the backing store.
        assertTrue(store.getString("a", "theme") != store.getString("b", "theme"))
    }

    @Test
    fun intsAndBooleansAreIsolated() {
        val store = InMemoryPluginSettingsStore()
        val a = PluginSettings(store, "a")
        val b = PluginSettings(store, "b")

        a.setInt("max_chars", 100)
        b.setInt("max_chars", 5)
        a.setBoolean("enabled", true)

        assertEquals(100, a.getInt("max_chars", -1))
        assertEquals(5, b.getInt("max_chars", -1))
        assertEquals(true, a.getBoolean("enabled", false))
        assertEquals(false, b.getBoolean("enabled", false)) // b untouched
    }

    @Test
    fun nullClearsOnlyOwnKey() {
        val store = InMemoryPluginSettingsStore()
        val a = PluginSettings(store, "a")
        val b = PluginSettings(store, "b")

        a.setString("token", "abc")
        b.setString("token", "xyz")
        a.setString("token", null)

        assertNull(a.getString("token"))
        assertEquals("xyz", b.getString("token"))
    }
}