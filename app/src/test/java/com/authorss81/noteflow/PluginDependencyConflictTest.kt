package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginOrderResolution
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11: dependency resolution (topological enable order), unmet-dependency
 * refusal, and deterministic capability-conflict arbitration.
 */
class PluginDependencyConflictTest {

    private fun registryOf(store: InMemoryEnableStore, vararg plugins: TestPlugin) =
        PluginRegistry(store, plugins = plugins.toList(), currentApiLevel = 26)

    @Test
    fun enableOrderIsTopological() {
        val a = TestPlugin("t.a")
        val b = TestPlugin("t.b", dependencies = setOf("t.a"))
        val c = TestPlugin("t.c", dependencies = setOf("t.b"))
        val registry = registryOf(InMemoryEnableStore(), a, b, c)

        val order = registry.resolveEnableOrder() as PluginOrderResolution.Success
        val idx = order.order.withIndex().associate { (i, id) -> id to i }
        assertTrue("a before b", idx.getValue("t.a") < idx.getValue("t.b"))
        assertTrue("b before c", idx.getValue("t.b") < idx.getValue("t.c"))
    }

    @Test
    fun refusalWhenDependencyNotEnabled() {
        val base = TestPlugin("t.base")
        val dependent = TestPlugin("t.dependent", dependencies = setOf("t.base"))
        val registry = registryOf(InMemoryEnableStore(), base, dependent)

        val result = registry.setEnabled(dependent.id, true)
        assertTrue(result is PluginEnableResult.Refused)
        val reason = (result as PluginEnableResult.Refused).reason
        assertTrue(reason.contains("t.base"))
        assertTrue(reason.contains("enable it first"))
        assertTrue(!registry.isEnabled(dependent.id))
    }

    @Test
    fun refusalWhenDependencyNotInstalled() {
        val dependent = TestPlugin("t.dependent", dependencies = setOf("t.missing"))
        val registry = registryOf(InMemoryEnableStore(), dependent)

        val result = registry.setEnabled(dependent.id, true)
        assertTrue(result is PluginEnableResult.Refused)
        assertTrue((result as PluginEnableResult.Refused).reason.contains("not installed"))
    }

    @Test
    fun enableSucceedsAfterDependencyEnabled() {
        val base = TestPlugin("t.base")
        val dependent = TestPlugin("t.dependent", dependencies = setOf("t.base"))
        val registry = registryOf(InMemoryEnableStore(), base, dependent)

        registry.setEnabled(base.id, true)
        val result = registry.setEnabled(dependent.id, true)
        assertTrue(result is PluginEnableResult.Changed)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(dependent.id)?.state)
    }

    @Test
    fun dependencyCycleIsDetected() {
        val a = TestPlugin("t.cycleA", dependencies = setOf("t.cycleB"))
        val b = TestPlugin("t.cycleB", dependencies = setOf("t.cycleA"))
        val store = InMemoryEnableStore()
        val registry = registryOf(store, a, b)

        val resolution = registry.resolveEnableOrder()
        assertTrue(resolution is PluginOrderResolution.Cyclic)
        val cyclicIds = (resolution as PluginOrderResolution.Cyclic).pluginIds
        assertTrue(cyclicIds.containsAll(listOf("t.cycleA", "t.cycleB")))

        // A previously-enabled store that now implies a cycle must resolve to
        // UNAVAILABLE with a clear reason — never a crash or stale AVAILABLE.
        store.forceEnabled("t.cycleA")
        store.forceEnabled("t.cycleB")
        val aState = registry.stateOf("t.cycleA")
        val bState = registry.stateOf("t.cycleB")
        assertEquals(PluginLifecycleState.UNAVAILABLE, aState?.state)
        assertEquals(PluginLifecycleState.UNAVAILABLE, bState?.state)
        assertTrue(aState?.reason?.contains("cycle") == true)
    }

    @Test
    fun conflictArbitrationPicksHigherVersionDeterministically() {
        val w1 = TestPlugin("t.w1", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val w2 = TestPlugin("t.w2", version = SemanticVersion(2, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val registry = registryOf(InMemoryEnableStore(), w1, w2)

        registry.setEnabled(w1.id, true)
        registry.setEnabled(w2.id, true) // w2 (higher version) wins

        val loser = registry.stateOf(w1.id)!!
        val winner = registry.stateOf(w2.id)!!
        assertEquals(PluginLifecycleState.DISABLED, loser.state)
        assertEquals("t.w2", loser.conflictWinnerId)
        assertEquals(PluginLifecycleState.AVAILABLE, winner.state)
    }

    @Test
    fun conflictTieBreaksByRegistrationOrder() {
        val w1 = TestPlugin("t.w1", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val w2 = TestPlugin("t.w2", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val store = InMemoryEnableStore()
        val registry = registryOf(store, w1, w2)

        // w2 would lose (w1 registered earlier) -> setEnabled refuses.
        registry.setEnabled(w1.id, true)
        val refusal = registry.setEnabled(w2.id, true)
        assertTrue(refusal is PluginEnableResult.Refused)
        assertTrue((refusal as PluginEnableResult.Refused).reason.contains("t.w1"))

        // Simulate a prior session where both were enabled: arbitration must
        // deterministically pick w1 (earlier registration) and disable w2.
        store.forceEnabled("t.w2")
        val loser = registry.stateOf("t.w2")!!
        val winner = registry.stateOf("t.w1")!!
        assertEquals(PluginLifecycleState.DISABLED, loser.state)
        assertEquals("t.w1", loser.conflictWinnerId)
        assertEquals(PluginLifecycleState.AVAILABLE, winner.state)
    }

    @Test
    fun routingGoesToConflictWinner() {
        val w1 = TestPlugin(
            "t.w1", version = SemanticVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            transformBlock = { "W1" }
        )
        val w2 = TestPlugin(
            "t.w2", version = SemanticVersion(2, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            transformBlock = { "W2" }
        )
        val registry = registryOf(InMemoryEnableStore(), w1, w2)
        registry.setEnabled(w1.id, true)
        registry.setEnabled(w2.id, true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.WebSearch, null) { it.id }
        assertTrue(result is PluginResult.Success)
        assertEquals("t.w2", (result as PluginResult.Success).value)

        // The loser was never invoked; the winner's invocation is recorded.
        assertTrue(manager.lastInvocation("t.w2")?.ok == true)
        assertTrue(manager.lastInvocation("t.w1") == null)
    }

    @Test
    fun disablingConflictWinnerFreesLoser() {
        val w1 = TestPlugin("t.w1", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val w2 = TestPlugin("t.w2", version = SemanticVersion(2, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val registry = registryOf(InMemoryEnableStore(), w1, w2)
        registry.setEnabled(w1.id, true)
        registry.setEnabled(w2.id, true)

        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf("t.w1")?.state)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf("t.w2")?.state)

        // Disabling the winner must re-arbitrate and free the loser (no stale
        // DISABLED left over from the previous resolution).
        registry.setEnabled(w2.id, false)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf("t.w1")?.state)
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf("t.w2")?.state)
    }

    @Test
    fun conflictDisabledPluginSurfacesAsUnavailableOnRouting() {
        // w2 (higher version) wins the exclusive WebSearch capability, but a
        // dependency on `base` means disabling `base` knocks w2 out of the
        // available set while arbitration still disables w1. Routing must then
        // fail loudly with the conflict reason rather than crash or guess.
        val base = TestPlugin("t.base")
        val w1 = TestPlugin("t.w1", version = SemanticVersion(1, 0, 0), capabilities = setOf(PluginCapability.WebSearch))
        val w2 = TestPlugin(
            "t.w2", version = SemanticVersion(2, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            dependencies = setOf("t.base")
        )
        val registry = registryOf(InMemoryEnableStore(), base, w1, w2)
        registry.setEnabled(base.id, true)
        registry.setEnabled(w1.id, true)
        registry.setEnabled(w2.id, true)
        registry.setEnabled(base.id, false) // w2 loses its dependency

        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf("t.w1")?.state)
        assertEquals(PluginLifecycleState.UNAVAILABLE, registry.stateOf("t.w2")?.state)

        val manager = PluginManager(registry)
        val result = manager.withPlugin(PluginCapability.WebSearch, null) { it.id }
        assertTrue(result is PluginResult.Unavailable)
        val message = (result as PluginResult.Unavailable).message
        assertTrue(message.contains("conflicts") || message.contains("disabled"))
    }

    @Test
    fun arbitrationLoserReceivesOnDisableExactlyOnce() {
        var w1Disables = 0
        val w1 = TestPlugin(
            "t.w1", version = SemanticVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            onDisableBlock = { _, _ -> w1Disables++ }
        )
        val w2 = TestPlugin(
            "t.w2", version = SemanticVersion(2, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch)
        )
        val registry = registryOf(InMemoryEnableStore(), w1, w2)
        registry.setEnabled(w1.id, true)
        assertEquals(0, w1Disables)

        // Enabling the higher-version rival arbitrates w1 to DISABLED loser.
        registry.setEnabled(w2.id, true)
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf(w1.id)?.state)
        assertEquals(1, w1Disables)

        // A further setEnabled on the already-enabled loser is refused; it must
        // NOT fire onDisable again (once per arbitration round).
        assertTrue(registry.setEnabled(w1.id, true) is PluginEnableResult.Refused)
        assertEquals(1, w1Disables)

        // Disabling the winner frees the loser; arbitration tracking is released,
        // so a NEW arbitration round can fire onDisable again.
        registry.setEnabled(w2.id, false)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(w1.id)?.state)
        registry.setEnabled(w2.id, true) // re-arbitration -> w1 loses again
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf(w1.id)?.state)
        assertEquals(2, w1Disables)
    }

    @Test
    fun onProcessStartDoesNotInitializeArbitrationLoser() {
        var loserEnables = 0
        var winnerEnables = 0
        val store = InMemoryEnableStore()
        val loser = TestPlugin(
            "t.loser", version = SemanticVersion(1, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            onEnableBlock = { _, _ -> loserEnables++ }
        )
        val winner = TestPlugin(
            "t.winner", version = SemanticVersion(2, 0, 0),
            capabilities = setOf(PluginCapability.WebSearch),
            onEnableBlock = { _, _ -> winnerEnables++ }
        )
        // Simulate a previous session where BOTH rivals were enabled in the store.
        store.forceEnabled(loser.id)
        store.forceEnabled(winner.id)

        val registry = PluginRegistry(store, plugins = listOf(loser, winner), currentApiLevel = 26)
        registry.onProcessStart(null)

        // The winner is initialized; the arbitration loser is NOT (it would never
        // serve, and its onEnable would have no matching onDisable).
        assertEquals(1, winnerEnables)
        assertEquals(0, loserEnables)
        assertEquals(PluginLifecycleState.DISABLED, registry.stateOf(loser.id)?.state)
        assertEquals(PluginLifecycleState.AVAILABLE, registry.stateOf(winner.id)?.state)
    }
}