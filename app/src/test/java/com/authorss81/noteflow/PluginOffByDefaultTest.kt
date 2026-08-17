package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.Rot13TransformPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 126: ALL plugins are OFF by default — every bundled/compiled plugin is
 * strictly opt-in. A fresh install (or an upgrade) must never run a plugin
 * until the user explicitly enables it, and an upgrade must never silently
 * change what the user explicitly chose.
 *
 * These are pure-JVM regression tests pinning the invariant over the FULL
 * shipped set ([PluginRegistry.defaultPlugins]), so any future code that
 * auto-enables a bundled plugin fails here first.
 *
 * Production defaults pinned (file:line):
 * - `SettingsManager.isPluginEnabled` ⇒ `prefs.getBoolean("plugin_enabled_<id>", false)`
 *   (`services/SettingsManager.kt:340-341`) — absent key ⇒ disabled.
 * - `SettingsManager.hasPluginEverBeenEnabled` ⇒ default `false`
 *   (`services/SettingsManager.kt:350-351`) — REGISTERED distinct from DISABLED.
 * - `PluginRegistry.deriveState` REGISTERED path (`plugins/PluginRegistry.kt:731-740`).
 * - `PluginRegistry.onProcessStart` only initializes plugins that are already
 *   opted-in (`plugins/PluginRegistry.kt:195`) — boot never enables anything.
 * - Capability routing requires `states[id].enabled == true`
 *   (`plugins/PluginManager.kt:188-198`) — un-opted-in ⇒ `NONE_ENABLED`.
 * - Store install starts REGISTERED (off): `PluginRegistry.installPlugin`
 *   (`plugins/PluginRegistry.kt:394-405`).
 */
class PluginOffByDefaultTest {

    private fun freshRegistry(
        enableStore: InMemoryEnableStore = InMemoryEnableStore()
    ): PluginRegistry =
        PluginRegistry(enableStore, currentApiLevel = 26)

    /** Full compile-time shipped set (what a fresh install sees). */
    private fun shippedSet() = PluginRegistry.defaultPlugins()

    @Test
    fun `fresh install starts every bundled plugin off and REGISTERED`() {
        val plugins = shippedSet()
        assertTrue("the shipped set must be non-empty for this invariant to mean anything", plugins.isNotEmpty())
        val registry = freshRegistry()

        assertTrue("every shipped plugin must be active in the registry", plugins.all { p ->
            registry.allPlugins.any { it.id == p.id }
        })
        plugins.forEach { plugin ->
            val info = registry.stateOf(plugin.id)
            assertFalse("${plugin.id} must be off on a fresh install", registry.isEnabled(plugin.id))
            assertEquals(
                "${plugin.id} must derive REGISTERED (installed, off, never enabled) on a fresh install",
                PluginLifecycleState.REGISTERED,
                info?.state
            )
            assertFalse("${plugin.id} must not be runnable on a fresh install",
                registry.enabledPlugins(context = null).any { it.id == plugin.id })
        }
    }

    @Test
    fun `an explicit enable persists across a process restart`() {
        val store = InMemoryEnableStore()
        val rot13 = Rot13TransformPlugin()

        val first = freshRegistry(store)
        assertEquals(
            PluginEnableResult.Changed(rot13.id, nowEnabled = true),
            first.setEnabled(rot13.id, enabled = true)
        )

        // "Restart": a brand-new registry over the SAME persisted store.
        val second = freshRegistry(store)
        assertTrue("explicitly-enabled plugin must stay enabled after restart", second.isEnabled(rot13.id))
        assertTrue("ever-enabled history must survive the restart", store.hasEverBeenEnabled(rot13.id))
        val info = second.stateOf(rot13.id)
        assertEquals("enabled plugin must derive AVAILABLE after restart", PluginLifecycleState.AVAILABLE, info?.state)
        assertTrue(info?.enabled == true)
    }

    @Test
    fun `upgrade keeps prior explicit choices and never-touched plugins stay off`() {
        val plugins = shippedSet()
        val store = InMemoryEnableStore()
        val previouslyEnabled = plugins.take(2).toList()
        val neverTouched = plugins.drop(2)

        // Simulate the persisted store of a user on an OLDER version who already
        // explicitly enabled these two plugins in Settings → Plugins / Store.
        previouslyEnabled.forEach { store.forceEnabled(it.id) }

        // Upgrade = a fresh registry constructed over the same persisted store.
        val registry = freshRegistry(store)
        previouslyEnabled.forEach { plugin ->
            val info = registry.stateOf(plugin.id)
            assertTrue("upgrade must keep ${plugin.id} enabled (explicit prior choice)",
                registry.isEnabled(plugin.id))
            assertTrue("${plugin.id} must not drop back to REGISTERED after upgrade",
                info?.state != PluginLifecycleState.REGISTERED)
            assertTrue(info?.enabled == true)
        }
        neverTouched.forEach { plugin ->
            assertFalse("upgrade must NOT enable never-touched ${plugin.id}",
                registry.isEnabled(plugin.id))
            assertEquals(
                "never-touched ${plugin.id} must stay REGISTERED (off) after upgrade",
                PluginLifecycleState.REGISTERED,
                registry.stateOf(plugin.id)?.state
            )
        }
    }

    @Test
    fun `no lifecycle hook runs before explicit opt-in`() {
        var onEnableCalls = 0
        var onDisableCalls = 0
        val plugin = TestPlugin(
            id = "t.offbydefault",
            onEnableBlock = { _, _ -> onEnableCalls++ },
            onDisableBlock = { _, _ -> onDisableCalls++ }
        )

        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        // Cold start / boot with nothing opted in: NO onEnable may fire.
        registry.onProcessStart(context = null)
        assertEquals("boot must not fire onEnable for an un-opted-in plugin", 0, onEnableCalls)

        // Opt-in -> exactly one onEnable.
        registry.setEnabled(plugin.id, enabled = true)
        assertEquals(1, onEnableCalls)

        // Opt-out -> onDisable; re-opt-in -> onEnable again.
        registry.setEnabled(plugin.id, enabled = false)
        assertEquals(1, onDisableCalls)
        registry.setEnabled(plugin.id, enabled = true)
        assertEquals(2, onEnableCalls)
    }

    @Test
    fun `no capability is served before explicit opt-in`() {
        val plugins = shippedSet()
        val registry = freshRegistry()
        val manager = PluginManager(registry)

        val servedCapabilities = plugins.flatMap { it.capabilities }.distinct()
        assertTrue(servedCapabilities.isNotEmpty())

        servedCapabilities.forEach { capability ->
            val result = manager.withPlugin(capability, context = null) { it.id }
            assertTrue(
                "capability ${capability.label} must refuse routing before opt-in (nothing enabled)",
                result is PluginResult.Failure
            )
            assertEquals(
                "capability ${capability.label} must fail with NONE_ENABLED before opt-in",
                PluginFailureReason.NONE_ENABLED,
                (result as PluginResult.Failure).reason
            )
        }
    }

    @Test
    fun `store-wipe resets enable AND ever-enabled history so reinstall starts off`() {
        val store = InMemoryEnableStore()
        val rot13 = Rot13TransformPlugin()
        val registry = freshRegistry(store)

        registry.setEnabled(rot13.id, enabled = true)
        assertTrue(store.isEnabled(rot13.id))
        assertTrue(store.hasEverBeenEnabled(rot13.id))

        // Delete (store) contract: opt-in flag + ever-enabled history both wiped.
        store.wipe(rot13.id)
        assertFalse(store.isEnabled(rot13.id))
        assertFalse(store.hasEverBeenEnabled(rot13.id))
        assertEquals(PluginLifecycleState.REGISTERED, registry.stateOf(rot13.id)?.state)
    }
}