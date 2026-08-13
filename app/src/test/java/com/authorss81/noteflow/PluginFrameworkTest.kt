package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableStore
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.Rot13TransformPlugin
import com.authorss81.noteflow.plugins.TextTransformPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10: the plugin framework must be genuinely testable on the JVM — the
 * framework core never touches Android (the production enable-store is the only
 * Android-backed piece), so these tests use an in-memory store and a null
 * context, exactly as [PluginRegistry]/[PluginManager] expect.
 */
class PluginFrameworkTest {

    private class InMemoryPluginEnableStore : PluginEnableStore {
        val state = mutableMapOf<String, Boolean>()
        override fun isEnabled(pluginId: String): Boolean = state[pluginId] == true
        override fun setEnabled(pluginId: String, enabled: Boolean) {
            state[pluginId] = enabled
        }
    }

    /** A plugin that declares WebSearch but can never run on this device. */
    private class UnavailablePlugin : NoteflowPlugin {
        override val id = "test.unavailable"
        override val name = "Unavailable Test Plugin"
        override val description = "Always unavailable."
        override val version = "0.0.1"
        override val capabilities: Set<PluginCapability> = setOf(PluginCapability.WebSearch)
        override fun isAvailable(context: Context?): Boolean = false
        override fun onEnable(context: Context?) {}
    }

    private val rot13 = Rot13TransformPlugin()

    @Test
    fun registryDiscoveryListsSamplePlugin() {
        val registry = PluginRegistry(InMemoryPluginEnableStore())
        assertTrue(registry.allPlugins.any { it.id == rot13.id && it.name == rot13.name })
        val found = registry.allPlugins.first { it.id == rot13.id }
        assertTrue(PluginCapability.TextTransform in found.capabilities)
        // The sample plugin is disabled by default (user opt-in).
        assertFalse(registry.isEnabled(rot13.id))
    }

    @Test
    fun enablingAndDisablingPersists() {
        val store = InMemoryPluginEnableStore()
        val registry = PluginRegistry(store)
        assertEquals(false, store.isEnabled(rot13.id))

        registry.setEnabled(rot13.id, enabled = true)
        assertTrue(store.isEnabled(rot13.id))
        assertTrue(registry.enabledPlugins().any { it.id == rot13.id })
        assertTrue(registry.availablePlugins(PluginCapability.TextTransform).any { it.id == rot13.id })

        registry.setEnabled(rot13.id, enabled = false)
        assertFalse(store.isEnabled(rot13.id))
        assertFalse(registry.enabledPlugins().any { it.id == rot13.id })
        assertFalse(registry.availablePlugins(PluginCapability.TextTransform).any { it.id == rot13.id })
    }

    @Test
    fun capabilityRoutingInvokesEnabledPlugin() {
        val registry = PluginRegistry(InMemoryPluginEnableStore())
        registry.setEnabled(rot13.id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("Hello, World!")
        }

        assertEquals("Uryyb, Jbeyq!", (result as PluginResult.Success).value)
    }

    @Test
    fun disabledPluginIsSkippedWithLoudFailure() {
        val registry = PluginRegistry(InMemoryPluginEnableStore()) // nothing enabled
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("ignored")
        }

        assertTrue(result is PluginResult.Failure)
        val message = (result as PluginResult.Failure).message
        assertTrue(message.contains("enable"))
        assertTrue(message.contains("Text Transform"))
    }

    @Test
    fun unavailableCapabilityFailsClearlyWithoutCrashing() {
        val registry = PluginRegistry(InMemoryPluginEnableStore()) // no plugin declares OCR
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.OCR, null) { it.id }

        assertTrue(result is PluginResult.Failure)
        assertTrue((result as PluginResult.Failure).message.contains("No plugin is installed"))
    }

    @Test
    fun unavailablePluginOnDeviceFailsClearly() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), listOf(UnavailablePlugin()))
        registry.setEnabled(UnavailablePlugin().id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.WebSearch, null) { it.id }

        assertTrue(result is PluginResult.Failure)
        assertTrue((result as PluginResult.Failure).message.contains("unavailable on this device"))
    }

    @Test
    fun enabledPluginsExcludesDeviceUnavailable() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), listOf(rot13, UnavailablePlugin()))
        registry.setEnabled(rot13.id, enabled = true)
        registry.setEnabled(UnavailablePlugin().id, enabled = true)

        val enabled = registry.enabledPlugins(context = null)
        assertTrue(enabled.any { it.id == rot13.id })
        assertFalse(enabled.any { it.id == "test.unavailable" })
    }

    @Test
    fun onEnableHookCalledExactlyOnce() {
        val store = InMemoryPluginEnableStore()
        var calls = 0
        val counting = object : NoteflowPlugin {
            override val id = "test.counting"
            override val name = "Counting"
            override val description = ""
            override val version = "1.0.0"
            override val capabilities: Set<PluginCapability> = emptySet()
            override fun isAvailable(context: Context?): Boolean = true
            override fun onEnable(context: Context?) { calls++ }
        }
        val registry = PluginRegistry(store, listOf(counting))
        registry.setEnabled(counting.id, enabled = true)
        registry.setEnabled(counting.id, enabled = true) // no-op re-enable
        registry.setEnabled(counting.id, enabled = false)
        registry.setEnabled(counting.id, enabled = true) // new transition → hook again
        assertEquals(2, calls)
    }

    @Test
    fun rot13RoundTrips() {
        val original = "The quick brown fox jumps over 13 lazy dogs. 123!"
        val encoded = rot13.transformText(original)
        assertEquals("Gur dhvpx oebja sbk whzcf bire 13 ynml qbtf. 123!", encoded)
        assertEquals(original, rot13.transformText(encoded))
    }
}