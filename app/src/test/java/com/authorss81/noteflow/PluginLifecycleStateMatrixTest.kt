package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11: the derived lifecycle state must be correct across the whole
 * transition matrix — enable/disable, permission loss/gain, dependency
 * loss/gain, capability-requirement loss/gain — and never go stale.
 */
class PluginLifecycleStateMatrixTest {

    private val textTransform = PluginCapability.TextTransform

    private fun registryOf(vararg plugins: TestPlugin) =
        PluginRegistry(InMemoryEnableStore(), plugins = plugins.toList(), currentApiLevel = 26)

    @Test
    fun registerThenEnableThenDisable() {
        val store = InMemoryEnableStore()
        val plugin = TestPlugin("t.plugin")
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        // REGISTERED: installed, off, never enabled.
        assertEquals(PluginLifecycleState.REGISTERED, registry.stateOf(plugin.id)?.state)
        assertEquals(false, registry.stateOf(plugin.id)?.enabled)

        // REGISTERED -> AVAILABLE after opt-in (deps ok, device ok).
        val enabled = registry.setEnabled(plugin.id, true)
        assertTrue(enabled is com.authorss81.noteflow.plugins.PluginEnableResult.Changed)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(plugin.id)?.state)
        assertTrue(store.hasEverBeenEnabled(plugin.id))

        // Disable -> DISABLED (user-turned-off, distinct from REGISTERED).
        registry.setEnabled(plugin.id, false)
        val info = registry.stateOf(plugin.id)
        assertEquals(PluginLifecycleState.DISABLED, info?.state)
        assertTrue(info?.reason?.contains("Disabled by the user") == true)
        assertTrue(store.hasEverBeenEnabled(plugin.id))
    }

    @Test
    fun permissionLossFlipsToUnavailableAndRegainFlipsBack() {
        var hasInternet = true
        val plugin = TestPlugin(
            id = "t.web",
            capabilities = setOf(PluginCapability.WebSearch),
            availabilityResult = {
                if (hasInternet) PluginAvailability.Ok
                else PluginAvailability.Unavailable("INTERNET permission missing")
            }
        )
        val registry = registryOf(plugin)
        registry.setEnabled(plugin.id, true)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(plugin.id)?.state)

        // Permission revoked -> derived state must update (no stale cache).
        hasInternet = false
        val unavailable = registry.stateOf(plugin.id)
        assertEquals(PluginLifecycleState.UNAVAILABLE, unavailable?.state)
        assertTrue(unavailable?.reason?.contains("INTERNET") == true)

        // Permission re-granted -> derived state recovers.
        hasInternet = true
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(plugin.id)?.state)
    }

    @Test
    fun dependencyLossFlipsDependentToUnavailable() {
        val base = TestPlugin("t.base")
        val dependent = TestPlugin("t.dependent", dependencies = setOf("t.base"))
        val registry = registryOf(base, dependent)

        // Cannot enable before its dependency.
        val refusal = registry.setEnabled(dependent.id, true)
        assertTrue(refusal is com.authorss81.noteflow.plugins.PluginEnableResult.Refused)
        assertTrue((refusal as com.authorss81.noteflow.plugins.PluginEnableResult.Refused).reason.contains("enable it first"))

        // Enable base then dependent -> dependent AVAILABLE.
        registry.setEnabled(base.id, true)
        registry.setEnabled(dependent.id, true)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(dependent.id)?.state)

        // Dependency disabled -> dependent flips UNAVAILABLE with a clear reason.
        registry.setEnabled(base.id, false)
        val depInfo = registry.stateOf(dependent.id)
        assertEquals(PluginLifecycleState.UNAVAILABLE, depInfo?.state)
        assertTrue(depInfo?.reason?.contains("t.base") == true)

        // Dependency re-enabled -> dependent AVAILABLE again (no stale state).
        registry.setEnabled(base.id, true)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(dependent.id)?.state)
    }

    @Test
    fun capabilityRequirementLossFlipsToUnavailable() {
        val ocrEngine = TestPlugin("t.ocr", capabilities = setOf(PluginCapability.OCR))
        val needsOcr = TestPlugin(
            "t.assistant",
            capabilities = setOf(PluginCapability.Assistant),
            requiresCapabilities = setOf(PluginCapability.OCR)
        )
        val registry = registryOf(ocrEngine, needsOcr)

        // No OCR enabled yet -> cannot enable needsOcr.
        val refusal = registry.setEnabled(needsOcr.id, true)
        assertTrue(refusal is com.authorss81.noteflow.plugins.PluginEnableResult.Refused)
        assertTrue((refusal as com.authorss81.noteflow.plugins.PluginEnableResult.Refused).reason.contains("OCR"))

        // OCR enabled + available -> needsOcr AVAILABLE.
        registry.setEnabled(ocrEngine.id, true)
        registry.setEnabled(needsOcr.id, true)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(needsOcr.id)?.state)

        // OCR plugin loses availability -> needsOcr flips UNAVAILABLE.
        registry.setEnabled(ocrEngine.id, false)
        val info = registry.stateOf(needsOcr.id)
        assertEquals(PluginLifecycleState.UNAVAILABLE, info?.state)
        assertTrue(info?.reason?.contains("OCR") == true)
    }

    @Test
    fun enabledWhenAvailabilityUnknown() {
        // A context-gated plugin: without a real Context it cannot tell whether
        // it can run, so the derived state is ENABLED (awaiting verification).
        val plugin = TestPlugin(
            id = "t.gated",
            availabilityResult = { if (it == null) PluginAvailability.Unknown else PluginAvailability.Ok }
        )
        val registry = registryOf(plugin)
        registry.setEnabled(plugin.id, true)
        val info = registry.stateOf(plugin.id)
        assertEquals(PluginLifecycleState.ENABLED, info?.state)
        assertNotNull(info?.reason)
    }

    @Test
    fun availabilityThrowIsContainedAsUnavailable() {
        val plugin = TestPlugin(
            id = "t.throwing",
            availabilityResult = { throw RuntimeException("boom") }
        )
        val registry = registryOf(plugin)
        registry.setEnabled(plugin.id, true) // must not throw
        val info = registry.stateOf(plugin.id)
        assertEquals(PluginLifecycleState.UNAVAILABLE, info?.state)
        assertTrue(info?.reason?.contains("availability check failed") == true)
    }

    @Test
    fun conflictWinnerAvailableLoserDisabled() {
        val w1 = TestPlugin("t.w1", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val w2 = TestPlugin("t.w2", version = SemanticVersion(2, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val registry = registryOf(w1, w2)

        registry.setEnabled(w1.id, true)
        registry.setEnabled(w2.id, true) // w2 has the higher version -> deterministic winner

        val w1Info = registry.stateOf(w1.id)
        val w2Info = registry.stateOf(w2.id)
        assertEquals(PluginLifecycleState.DISABLED, w1Info?.state)
        assertEquals("t.w2", w1Info?.conflictWinnerId)
        assertTrue(w1Info?.reason?.contains("conflicts") == true)
        assertEquals(PluginLifecycleState.AVAILABLE, w2Info?.state)
    }

    @Test
    fun stateNeverStaleAcrossManyTransitions() {
        var gate = true
        val plugin = TestPlugin(
            id = "t.stress",
            capabilities = setOf(textTransform),
            availabilityResult = {
                if (gate) PluginAvailability.Ok else PluginAvailability.Unavailable("device gate closed")
            }
        )
        val registry = registryOf(plugin)

        var expected = PluginLifecycleState.REGISTERED
        repeat(10) { round ->
            registry.setEnabled(plugin.id, enabled = round % 2 == 0)
            gate = round % 3 == 0
            expected = if (!registry.isEnabled(plugin.id)) {
                if (round == 0) PluginLifecycleState.REGISTERED else PluginLifecycleState.DISABLED
            } else if (!gate) {
                PluginLifecycleState.UNAVAILABLE
            } else {
                PluginLifecycleState.AVAILABLE
            }
            assertEquals("round $round", expected, registry.stateOf(plugin.id)?.state)
        }
    }
}