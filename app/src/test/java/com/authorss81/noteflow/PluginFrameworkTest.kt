package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableStore
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.Rot13TransformPlugin
import com.authorss81.noteflow.plugins.TextTransformPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 + 11: the plugin framework must be genuinely testable on the JVM —
 * the framework core never touches Android (the production enable/settings
 * stores are the only Android-backed pieces), so these tests use an in-memory
 * store and a null context, exactly as [PluginRegistry]/[PluginManager] expect.
 */
class PluginFrameworkTest {

    private class InMemoryPluginEnableStore : PluginEnableStore {
        val state = mutableMapOf<String, Boolean>()
        val ever = mutableSetOf<String>()
        override fun isEnabled(pluginId: String): Boolean = state[pluginId] == true
        override fun setEnabled(pluginId: String, enabled: Boolean) {
            state[pluginId] = enabled
            if (enabled) ever.add(pluginId)
        }
        override fun hasEverBeenEnabled(pluginId: String): Boolean = pluginId in ever
        override fun wipe(pluginId: String) {
            state[pluginId] = false
            ever.remove(pluginId)
        }
    }

    /** A plugin that declares WebSearch but can never run on this device. */
    private class UnavailablePlugin : NoteflowPlugin {
        override val manifest = com.authorss81.noteflow.plugins.PluginManifest(
            id = "test.unavailable",
            name = "Unavailable Test Plugin",
            version = com.authorss81.noteflow.plugins.SemanticVersion(0, 0, 1),
            minSupportedApi = 26,
            description = "Always unavailable.",
            capabilities = setOf(PluginCapability.WebSearch)
        )
        override fun availability(context: Context?): PluginAvailability =
            PluginAvailability.Unavailable("always unavailable")
        override fun onEnable(context: Context?, settings: PluginSettings) {}
    }

    private val rot13 = Rot13TransformPlugin()

    @Test
    fun registryDiscoveryListsSamplePlugin() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), currentApiLevel = 26)
        assertTrue(registry.allPlugins.any { it.id == rot13.id && it.name == rot13.name })
        val found = registry.allPlugins.first { it.id == rot13.id }
        assertTrue(PluginCapability.TextTransform in found.capabilities)
        // The sample plugin is disabled by default (user opt-in).
        assertFalse(registry.isEnabled(rot13.id))
    }

    @Test
    fun enablingAndDisablingPersists() {
        val store = InMemoryPluginEnableStore()
        val registry = PluginRegistry(store, currentApiLevel = 26)
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
        val registry = PluginRegistry(InMemoryPluginEnableStore(), currentApiLevel = 26)
        registry.setEnabled(rot13.id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("Hello, World!")
        }

        assertEquals("Uryyb, Jbeyq!", (result as PluginResult.Success).value)
    }

    @Test
    fun disabledPluginIsSkippedWithLoudFailure() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), currentApiLevel = 26) // nothing enabled
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
        // Phase 12/15: OCR, WebSearch, Export, ClipShare, TextTools,
        // LanguageDetection and WebCapture now have REAL plugins in the default
        // registry. FileTransfer is still an unserved (declared-only) extension
        // point, so it is the correct probe for "declared but unserved → loud
        // Failure, never fake".
        val registry = PluginRegistry(InMemoryPluginEnableStore(), currentApiLevel = 26) // no plugin declares FileTransfer
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.FileTransfer, null) { it.id }

        assertTrue(result is PluginResult.Failure)
        assertTrue((result as PluginResult.Failure).message.contains("No plugin is installed"))
    }

    @Test
    fun unavailablePluginOnDeviceFailsClearly() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), plugins = listOf(UnavailablePlugin()), currentApiLevel = 26)
        registry.setEnabled(UnavailablePlugin().id, enabled = true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.WebSearch, null) { it.id }

        assertTrue(result is PluginResult.Unavailable)
        assertTrue((result as PluginResult.Unavailable).message.contains("unavailable on this device"))
    }

    @Test
    fun enabledPluginsExcludesDeviceUnavailable() {
        val registry = PluginRegistry(InMemoryPluginEnableStore(), plugins = listOf(rot13, UnavailablePlugin()), currentApiLevel = 26)
        registry.setEnabled(rot13.id, enabled = true)
        registry.setEnabled(UnavailablePlugin().id, enabled = true)

        val enabled = registry.enabledPlugins(context = null)
        assertTrue(enabled.any { it.id == rot13.id })
        assertFalse(enabled.any { it.id == "test.unavailable" })
    }

    @Test
    fun onEnableDoesNotDuplicateWhileEnabledAndRefiresAfterDisable() {
        val store = InMemoryPluginEnableStore()
        var calls = 0
        var disableCalls = 0
        val counting = object : NoteflowPlugin {
            override val manifest = com.authorss81.noteflow.plugins.PluginManifest(
                id = "test.counting",
                name = "Counting",
                version = com.authorss81.noteflow.plugins.SemanticVersion(1, 0, 0),
                minSupportedApi = 26,
                description = "counting test plugin",
                capabilities = setOf(PluginCapability.TextTransform)
            )
            override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok
            override fun onEnable(context: Context?, settings: PluginSettings) { calls++ }
            override fun onDisable(context: Context?, settings: PluginSettings) { disableCalls++ }
        }
        val registry = PluginRegistry(store, plugins = listOf(counting), currentApiLevel = 26)
        registry.setEnabled(counting.id, enabled = true)
        registry.setEnabled(counting.id, enabled = true) // no-op re-enable: no duplicate onEnable
        assertEquals(1, calls)
        registry.setEnabled(counting.id, enabled = false)
        assertEquals(1, disableCalls)
        registry.setEnabled(counting.id, enabled = true) // re-enabled: onEnable fires again
        assertEquals(2, calls)
        assertEquals(1, disableCalls)
    }

    @Test
    fun onEnableHookReconcilesPluginsEnabledInPreviousProcess() {
        val store = InMemoryPluginEnableStore()
        var calls = 0
        val counting = object : NoteflowPlugin {
            override val manifest = com.authorss81.noteflow.plugins.PluginManifest(
                id = "test.coldstart",
                name = "Counting",
                version = com.authorss81.noteflow.plugins.SemanticVersion(1, 0, 0),
                minSupportedApi = 26,
                description = "counting test plugin",
                capabilities = setOf(PluginCapability.TextTransform)
            )
            override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok
            override fun onEnable(context: Context?, settings: PluginSettings) { calls++ }
        }
        // Simulate a previous process that enabled the plugin in the store.
        store.setEnabled(counting.id, enabled = true)

        val registry = PluginRegistry(store, plugins = listOf(counting), currentApiLevel = 26)
        assertEquals(0, calls) // not yet reconciled
        registry.onProcessStart(context = null)
        assertEquals(1, calls) // fired exactly once at cold start
        registry.onProcessStart(context = null) // idempotent
        registry.setEnabled(counting.id, enabled = true) // already on in store
        assertEquals(1, calls)
    }

    @Test
    fun rot13RoundTrips() {
        val original = "The quick brown fox jumps over 13 lazy dogs. 123!"
        val encoded = rot13.transformText(original)
        assertEquals("Gur dhvpx oebja sbk whzcf bire 13 ynml qbtf. 123!", encoded)
        assertEquals(original, rot13.transformText(encoded))
    }
}