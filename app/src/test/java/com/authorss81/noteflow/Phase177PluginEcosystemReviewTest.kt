package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.SemanticVersion
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
 * Phase 177: pin the invariants the ecosystem review verified. These fill the
 * gaps the pre-existing plugin suite did not cover:
 *
 * 1. STORE ROW STATE ACCURACY (Step 3) — every [PluginStoreController.StoreRow]
 *    reflects the SAME install store + enable store the router uses, at every
 *    lifecycle stage; installed/state are never both/neither; "off" vs "on"
 *    derived-state groups are mutually exclusive and driven by enable state.
 * 2. DELETE RUNS deleteDownloadedAssets (Step 4) — the destructive asset wipe is
 *    invoked exactly once per successful delete and never for a refused delete.
 * 3. REJECTED PLUGINS CANNOT BE ENABLED (Step 4) — the registry refuses opt-in
 *    and resolves REJECTED, backing the store UI's "Delete, no Enable" rule.
 */
class Phase177PluginEcosystemReviewTest {

    private val baseIds = PluginRegistry.defaultPlugins().map { it.id }
    // ON states require the user's opt-in flag to be set (the router's rule:
    // PluginManager.resolvePlugin serves only `enabled == true`). DISABLED is
    // deliberately NOT a pure OFF state — it has two honest faces: a user turned
    // it off (enabled=false) OR it deterministically lost capability arbitration
    // while remaining opted-in (enabled=true; see deriveState at
    // PluginRegistry.kt:721-728, where a conflict loser reports DISABLED with
    // enabled=true).
    private val onStates = setOf(
        PluginLifecycleState.ENABLED,
        PluginLifecycleState.AVAILABLE,
        PluginLifecycleState.UNAVAILABLE
    )

    /** The deriveState contract (PluginRegistry.kt:698-776): which lifecycle
     *  state is possible with which opt-in flag. REGISTERED/REJECTED are never
     *  enabled; on-states always are; DISABLED covers both faces. */
    private fun assertStateContract(id: String, state: PluginLifecycleState, enabled: Boolean) {
        when (state) {
            PluginLifecycleState.REGISTERED,
            PluginLifecycleState.REJECTED ->
                assertFalse("REGISTERED/REJECTED must never be enabled ($id)", enabled)
            in onStates ->
                assertTrue("on-state $state requires enabled ($id)", enabled)
            else -> Unit // DISABLED: user-off OR arbitration loser (see above)
        }
    }

    private fun newStore(): Triple<PluginRegistry, PluginStoreCatalog, PluginStoreController> {
        val enableStore = InMemoryEnableStore()
        val settingsStore = InMemoryPluginSettingsStore()
        val installStore = InMemoryPluginInstallStore(baseIds) // built-ins pre-installed
        val registry = PluginRegistry(
            enableStore = enableStore,
            settingsStore = settingsStore,
            installStore = installStore,
            optionalPluginFactories = listOf({ CaseChangePlugin() }),
            currentApiLevel = 26
        )
        val catalog = PluginStoreCatalog(registry)
        return Triple(registry, catalog, PluginStoreController(registry, catalog))
    }

    private fun PluginStoreController.rowsById(registry: PluginRegistry) =
        rows(null).associateBy { it.entry.pluginId }

    /** The store UI's enable affordance (PluginStoreDialog.kt:484-485). */
    private fun offersEnable(state: PluginLifecycleState?): Boolean =
        state == PluginLifecycleState.REGISTERED || state == PluginLifecycleState.DISABLED

    @Test
    fun `store rows are the single source of truth at every lifecycle stage`() = runBlocking {
        val (registry, catalog, controller) = newStore()
        val optId = "com.authorss81.noteflow.plugins.casechange"
        val builtinId = "com.authorss81.noteflow.plugins.rot13"

        fun assertRowsAccurate() {
            // Every catalog row must agree with installStore + enableStore.
            catalog.entries().forEach { entry ->
                val id = entry.pluginId
                val row = controller.rowsById(registry).getValue(id)
                val installed = registry.isInstalled(id)
                val enabled = registry.isEnabled(id)
                assertEquals("row.installed must equal installStore for $id", installed, row.installed)
                // Installed ⇔ a derived state exists: never both, never neither.
                if (installed) assertNotNull("installed $id must carry a state", row.state)
                else assertNull("not-downloaded $id must not carry a state", row.state)
                assertEquals("row.plugin presence must match installed for $id", installed, row.plugin != null)
                if (installed) {
                    val state = row.state!!.state
                    // enabled travels with the enable store the router reads
                    // (PluginManager.resolvePlugin requires enabled==true), and
                    // the deriveState state/opt-in contract holds (DISABLED may
                    // be an arbitration loser that is still opted-in).
                    assertEquals(
                        "enabled flag of $id must travel with the row state",
                        enabled, row.state!!.enabled
                    )
                    assertStateContract(id, state, enabled)
                    // The store button is derived from the row state value ONLY
                    // (PluginStoreDialog.kt:484-485): Enable iff REGISTERED/DISABLED.
                    // A genuinely off row must offer Enable; a genuinely on row must
                    // never. An arbitration loser (DISABLED with enabled=true,
                    // absent from today's catalog) is the one row the state-derived
                    // label does not fully describe — pinned separately below.
                    if (state in onStates) assertFalse("on row must not offer Enable ($id)", offersEnable(state))
                    if (!enabled) assertTrue("off row must offer Enable ($id)", offersEnable(state))
                }
            }
        }

        // Stage 1: fresh install — built-ins installed+off, optional not downloaded.
        assertRowsAccurate()
        assertFalse(registry.isInstalled(optId))
        assertNull(controller.rowsById(registry)[optId]?.state)

        // Stage 2: download the optional plugin — installed, still OFF (never enabled).
        controller.download(optId, null) {}
        assertTrue(registry.isInstalled(optId))
        assertFalse(registry.isEnabled(optId))
        assertEquals(PluginLifecycleState.REGISTERED, registry.stateOf(optId)?.state)
        assertRowsAccurate()

        // Stage 3: enable -> ON (AVAILABLE).
        registry.setEnabled(optId, true)
        assertTrue(registry.isEnabled(optId))
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(optId)?.state)
        assertRowsAccurate()

        // Stage 4: disable -> OFF (DISABLED), data kept.
        registry.setEnabled(optId, false)
        assertFalse(registry.isEnabled(optId))
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf(optId)?.state)
        assertRowsAccurate()

        // Stage 5: delete -> not downloaded again, no row state.
        controller.delete(optId, null)
        assertFalse(registry.isInstalled(optId))
        assertRowsAccurate()

        // Stage 6: built-in still healthy through all of it.
        assertEquals(builtinId, controller.rowsById(registry)[builtinId]?.plugin?.id)
        assertTrue(registry.isInstalled(builtinId))

        // Arbitration zone: two installed, enabled, available rivals for an
        // EXCLUSIVE capability — the deterministic winner serves (AVAILABLE),
        // the loser stays opted-in but reports DISABLED with enabled=true
        // (deriveState's honest second face, PluginRegistry.kt:721-728). This
        // pins that the state/opt-in contract above accepts exactly that row
        // shape instead of red-laning a legitimate product state.
        val allowWinner = booleanArrayOf(false)
        val winner = TestPlugin(
            id = "t.win",
            name = "Winner",
            version = SemanticVersion(2, 0, 0),
            capabilities = setOf(PluginCapability.OCR)
        )
        val loser = TestPlugin(
            id = "t.lose",
            name = "Loser",
            version = SemanticVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.OCR),
            availabilityResult = {
                if (allowWinner[0]) PluginAvailability.Ok
                else PluginAvailability.Unavailable("winner-side not ready")
            }
        )
        val arbRegistry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            plugins = listOf(winner, loser),
            currentApiLevel = 26
        )
        // Enable the loser while the winner is not yet available (no refusal,
        // because the availability gate is evaluated live on the rival).
        arbRegistry.setEnabled(loser.id, true)
        assertTrue(arbRegistry.isEnabled(loser.id))
        // Winner becomes available + enabled: the loser now loses deterministically
        // (higher version wins, PluginRegistry.kt:672-691) and is reported DISABLED
        // while still opted-in.
        allowWinner[0] = true
        arbRegistry.setEnabled(winner.id, true)
        val arbRows = PluginStoreController(arbRegistry, PluginStoreCatalog(arbRegistry))
            .rows(null)
            .associateBy { it.entry.pluginId }
        val winnerRow = arbRows.getValue(winner.id)
        val loserRow = arbRows.getValue(loser.id)
        assertEquals(PluginLifecycleState.AVAILABLE, winnerRow.state?.state)
        assertTrue(winnerRow.state!!.enabled)
        assertEquals(PluginLifecycleState.DISABLED, loserRow.state?.state)
        assertTrue("a conflict loser stays opted-in", loserRow.state!!.enabled)
        assertEquals(winner.id, loserRow.state?.conflictWinnerId)
        // NOTE (pre-existing product nuance, not fixed here): the store's Enable
        // affordance is derived from the row STATE only (PluginStoreDialog.kt:484-485),
        // so a conflict loser still renders "Enable" although the registry refuses
        // the tap (refusalReasonForEnable, PluginRegistry.kt:830-836). The honest
        // facts pinned here are the state + opt-in + conflict-winner; the affordance
        // for losers is deliberately NOT asserted to match !enabled.
        assertStateContract(loser.id, loserRow.state!!.state, enabled = true)
        assertStateContract(winner.id, winnerRow.state!!.state, enabled = true)
        // The loser can still be turned off by the user (opt-in revocable).
        assertTrue(arbRegistry.setEnabled(loser.id, false) is PluginEnableResult.Changed)
        assertFalse(arbRegistry.isEnabled(loser.id))
    }

    /** A plugin that records destructive asset deletion for the delete pin. */
    private class AssetTrackingPlugin : NoteflowPlugin, TextTransformPlugin {
        var assetDeletions = 0
        private val core = TestPlugin(
            id = "t.assets",
            name = "Asset Tracking",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        override val manifest: PluginManifest get() = core.manifest
        override fun availability(context: Context?): PluginAvailability = core.availability(context)
        override fun onEnable(context: Context?, settings: com.authorss81.noteflow.plugins.PluginSettings) =
            core.onEnable(context, settings)
        override fun onDisable(context: Context?, settings: com.authorss81.noteflow.plugins.PluginSettings) =
            core.onDisable(context, settings)
        override fun deleteDownloadedAssets(context: Context?) {
            assetDeletions++
        }
        override fun transformText(text: String): String = "ASSETS"
    }

    @Test
    fun `delete invokes deleteDownloadedAssets exactly once and never for a refused delete`() = runBlocking {
        val plugin = AssetTrackingPlugin()
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(), // nothing installed yet
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry))

        // Not installed yet -> delete refuses and must NOT wipe assets.
        controller.delete(plugin.id, null)
        assertEquals("refused delete must not wipe assets", 0, plugin.assetDeletions)

        // Successful delete -> the offline model/assets wipe runs (controller
        // invokes it BEFORE the registry forgets the plugin).
        registry.installPlugin(plugin, null)
        val outcome = controller.delete(plugin.id, null)
        assertTrue(outcome is PluginStoreController.DeleteOutcome.Deleted)
        assertEquals("delete must wipe downloaded assets exactly once", 1, plugin.assetDeletions)
        assertFalse(registry.isInstalled(plugin.id))

        // A second delete now refuses (plugin gone) and must NOT wipe again.
        val second = controller.delete(plugin.id, null)
        assertTrue(second is PluginStoreController.DeleteOutcome.Failed)
        assertEquals("refused second delete must not wipe assets again", 1, plugin.assetDeletions)
    }

    @Test
    fun `rejected plugins cannot be enabled and resolve rejected`() {
        // Manifest invalid for this device: minSupportedApi above API 26.
        val rejected = TestPlugin(
            id = "t.badapi",
            minSupportedApi = 99
        )
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            plugins = listOf(rejected),
            currentApiLevel = 26
        )
        assertTrue(registry.isRejected(rejected.id))
        val info = registry.stateOf(rejected.id)
        assertEquals(PluginLifecycleState.REJECTED, info?.state)

        // The registry refuses opt-in (this is what backs the store's
        // "Rejected => Delete, no Enable" rule at PluginStoreDialog.kt:483).
        val result = registry.setEnabled(rejected.id, true)
        assertTrue(result is PluginEnableResult.Refused)
        assertFalse(registry.isEnabled(rejected.id))
        assertFalse(registry.enabledPlugins(null).any { it.id == rejected.id })
    }
}
