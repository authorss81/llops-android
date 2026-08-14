package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import com.authorss81.noteflow.plugins.store.PluginStoreController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 21: the plugin store's install/uninstall lifecycle, end to end —
 * download (bundled DEFINITION install) → available; disable → disabled with
 * data kept; enable → available again; delete → removed + settings wiped.
 *
 * Pure JVM: the registry, catalog, controller and all stores are in-memory.
 */
class PluginStoreLifecycleTest {

    private val baseIds = PluginRegistry.defaultPlugins().map { it.id }

    private fun newStore(): Triple<PluginRegistry, PluginStoreCatalog, PluginStoreController> {
        val enableStore = InMemoryEnableStore()
        val settingsStore = InMemoryPluginSettingsStore()
        val installStore = InMemoryPluginInstallStore(baseIds) // built-ins pre-installed
        val registry = PluginRegistry(
            enableStore = enableStore,
            settingsStore = settingsStore,
            installStore = installStore,
            // The store's optional definition ships compiled in the APK.
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val catalog = PluginStoreCatalog(registry)
        val controller = PluginStoreController(registry, catalog)
        return Triple(registry, catalog, controller)
    }

    private val caseChangeId = CaseChangePlugin().id

    @Test
    fun `built-in plugins are installed by default, optional ones are not`() {
        val (registry, catalog, controller) = newStore()

        assertEquals(baseIds, registry.allPlugins.map { it.id })
        // The optional definition ships compiled (so the store can list it as
        // "Not downloaded"), and the catalog lists it exactly once.
        assertTrue(registry.compiledPlugins.any { it.id == caseChangeId })
        assertEquals(1, catalog.entries().count { it.pluginId == caseChangeId })
        assertEquals(registry.compiledPlugins.size, catalog.entries().size)

        val rows = controller.rows(null)
        val rot13 = rows.first { it.entry.pluginId == "com.authorss81.noteflow.plugins.rot13" }
        assertTrue(rot13.installed)
        assertNotNull(rot13.state)

        val caseChange = rows.first { it.entry.pluginId == caseChangeId }
        assertTrue(caseChange.entry.optional)
        assertFalse(caseChange.installed)
        assertNull(caseChange.state)
    }

    @Test
    fun `download installs the bundled definition and makes it available`() = runBlocking {
        val (registry, catalog, controller) = newStore()
        // Definition is compiled-in but NOT installed before download.
        assertFalse(registry.isInstalled(caseChangeId))
        assertTrue(registry.compiledPlugins.any { it.id == caseChangeId })
        assertFalse(registry.allPlugins.any { it.id == caseChangeId })

        val progress = mutableListOf<Float>()
        val outcome = controller.download(caseChangeId, null) { progress.add(it) }

        assertEquals(
            PluginStoreController.DownloadOutcome.Installed(caseChangeId),
            outcome
        )
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0f)
        assertTrue(registry.isInstalled(caseChangeId))
        assertTrue(registry.allPlugins.any { it.id == caseChangeId })
        assertTrue(catalog.entryFor(caseChangeId)?.optional == true)
        assertEquals(1, catalog.entries().count { it.pluginId == caseChangeId })

        // Enable → derived state is AVAILABLE (gate is Ok on any API 26+).
        assertEquals(
            PluginEnableResult.Changed(caseChangeId, nowEnabled = true),
            registry.setEnabled(caseChangeId, true)
        )
        val state = registry.stateOf(caseChangeId)
        assertEquals(PluginLifecycleState.AVAILABLE, state?.state)

        // The installed definition actually routes + serves.
        val manager = PluginManager(registry)
        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("hello world")
        }
        assertEquals("HELLO WORLD", (result as PluginResult.Success).value)
    }

    @Test
    fun `an installed optional plugin is re-materialized after a process restart`() = runBlocking {
        // Shared persisted install store simulates SharedPreferences surviving
        // a process restart; the registry is reconstructed fresh.
        val installStore = InMemoryPluginInstallStore(baseIds)
        val reg1 = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = installStore,
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val ctl1 = PluginStoreController(reg1, PluginStoreCatalog(reg1))
        ctl1.download(caseChangeId, null) {}
        reg1.setEnabled(caseChangeId, true)
        assertTrue(reg1.allPlugins.any { it.id == caseChangeId })

        // "Restart": a brand-new registry over the same install store.
        val reg2 = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = installStore,
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        // The definition is active again (not just "installed in name"), and
        // opt-in is honored by the freshly derived state.
        assertTrue(reg2.isInstalled(caseChangeId))
        assertTrue(reg2.allPlugins.any { it.id == caseChangeId })
        val row = PluginStoreController(reg2, PluginStoreCatalog(reg2)).rows(null)
            .first { it.entry.pluginId == caseChangeId }
        assertTrue(row.installed)
        assertNotNull(row.state)

        // It is deleted cleanly (the pre-fix behavior left it stuck).
        val ctl2 = PluginStoreController(reg2, PluginStoreCatalog(reg2))
        val deleted = ctl2.delete(caseChangeId, null)
        assertTrue(deleted is PluginStoreController.DeleteOutcome.Deleted)
        assertFalse(installStore.isInstalled(caseChangeId))
    }

    @Test
    fun `download refuses an already-installed plugin`() = runBlocking {
        val (registry, _, controller) = newStore()
        val outcome = controller.download("com.authorss81.noteflow.plugins.rot13", null) { }

        assertTrue(outcome is PluginStoreController.DownloadOutcome.Failed)
        assertTrue((outcome as PluginStoreController.DownloadOutcome.Failed).message.contains("already"))
        assertTrue(registry.isInstalled("com.authorss81.noteflow.plugins.rot13"))
    }

    @Test
    fun `disable keeps data and derived state, enable restores it`() {
        val (registry, _, controller) = newStore()
        runBlocking { controller.download(caseChangeId, null) { } }
        registry.setEnabled(caseChangeId, true)
        registry.settingsFor(caseChangeId).setString("mode", "lower")

        registry.setEnabled(caseChangeId, false)
        assertFalse(registry.isEnabled(caseChangeId))
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf(caseChangeId)?.state)
        assertTrue(registry.isInstalled(caseChangeId))
        assertTrue(registry.allPlugins.any { it.id == caseChangeId })
        // Data is KEPT on disable (re-enableable) — unlike Delete.
        assertTrue(registry.settingsFor(caseChangeId).containsKey("mode"))
        assertEquals("lower", registry.settingsFor(caseChangeId).getString("mode"))

        registry.setEnabled(caseChangeId, true)
        assertTrue(registry.isEnabled(caseChangeId))
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(caseChangeId)?.state)
        assertEquals("lower", registry.settingsFor(caseChangeId).getString("mode"))
    }

    @Test
    fun `disabled plugin is skipped by capability routing`() {
        val (registry, _, controller) = newStore()
        runBlocking { controller.download(caseChangeId, null) { } }
        registry.setEnabled(caseChangeId, true)
        registry.setEnabled(caseChangeId, false)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("ignored")
        }

        assertTrue(result is PluginResult.Failure)
        assertEquals(PluginFailureReason.NONE_ENABLED, (result as PluginResult.Failure).reason)
    }

    @Test
    fun `delete removes the plugin and wipes enable history and settings`() {
        val (registry, _, controller) = newStore()
        runBlocking { controller.download(caseChangeId, null) { } }
        registry.setEnabled(caseChangeId, true)
        registry.settingsFor(caseChangeId).setString("mode", "upper")
        registry.settingsFor(caseChangeId).setInt("count", 3)

        val outcome = controller.delete(caseChangeId, null)

        assertTrue(outcome is PluginStoreController.DeleteOutcome.Deleted)
        assertFalse(registry.isInstalled(caseChangeId))
        assertFalse(registry.allPlugins.any { it.id == caseChangeId })
        // Derived state + settings + opt-in history are gone.
        assertNull(registry.stateOf(caseChangeId))
        assertFalse(registry.settingsFor(caseChangeId).containsKey("mode"))
        assertFalse(registry.settingsFor(caseChangeId).containsKey("count"))
        // Routing no longer sees it.
        val manager = PluginManager(registry)
        val result = manager.withPlugin(PluginCapability.TextTransform, null) { plugin ->
            (plugin as TextTransformPlugin).transformText("ignored")
        }
        assertEquals(PluginFailureReason.NONE_ENABLED, (result as PluginResult.Failure).reason)
    }

    @Test
    fun `deleted plugin can be re-downloaded and starts registered`() = runBlocking {
        val (registry, _, controller) = newStore()
        runBlocking { controller.download(caseChangeId, null) { } }
        registry.setEnabled(caseChangeId, true)
        runBlocking { controller.delete(caseChangeId, null) }

        // Re-download succeeds and starts from REGISTERED (off) — Delete wiped
        // enable history, so the derived state is fresh, not DISABLED.
        val redownload = controller.download(caseChangeId, null) { }
        assertTrue(redownload is PluginStoreController.DownloadOutcome.Installed)
        assertTrue(registry.isInstalled(caseChangeId))
        assertFalse(registry.isEnabled(caseChangeId))
        assertEquals(PluginLifecycleState.REGISTERED, registry.stateOf(caseChangeId)?.state)
    }

    @Test
    fun `delete refuses an unknown plugin`() {
        val (registry, _, controller) = newStore()
        val outcome = controller.delete("com.authorss81.noteflow.plugins.unknown", null)

        assertTrue(outcome is PluginStoreController.DeleteOutcome.Failed)
        assertTrue((outcome as PluginStoreController.DeleteOutcome.Failed).message.contains("not installed"))
        assertFalse(registry.isInstalled("com.authorss81.noteflow.plugins.unknown"))
    }
}