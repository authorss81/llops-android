package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11: a failing/malicious/buggy plugin must NEVER crash the app or
 * propagate an exception to the caller. Exceptions (incl. RuntimeException),
 * null results and throwing lifecycle hooks are all contained and surfaced as
 * typed results.
 */
class PluginErrorIsolationTest {

    @Test
    fun throwingPluginIsContainedInAResult() {
        val throwing = TestPlugin(
            id = "t.throwing",
            transformBlock = { throw RuntimeException("boom") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(throwing), currentApiLevel = 26)
        registry.setEnabled(throwing.id, true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin(PluginCapability.TextTransform, null) {
            (it as com.authorss81.noteflow.plugins.TextTransformPlugin).transformText("hello")
        }
        assertTrue(result is PluginResult.Failure)
        val failure = result as PluginResult.Failure
        assertEquals(PluginFailureReason.PLUGIN_ERROR, failure.reason)
        assertTrue(failure.message.contains("t.throwing"))
        assertTrue(failure.message.contains("RuntimeException"))
    }

    @Test
    fun exceptionDoesNotPropagateToCaller() {
        val throwing = TestPlugin(
            id = "t.propagate",
            transformBlock = { throw RuntimeException("should not escape") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(throwing), currentApiLevel = 26)
        registry.setEnabled(throwing.id, true)
        val manager = PluginManager(registry)

        var result: PluginResult<String>? = null
        try {
            result = manager.withPlugin(PluginCapability.TextTransform, null) {
                (it as com.authorss81.noteflow.plugins.TextTransformPlugin).transformText("x")
            }
        } catch (e: RuntimeException) {
            throw AssertionError("exception leaked out of the plugin manager: ${e.message}", e)
        }
        assertTrue(result is PluginResult.Failure)
    }

    @Test
    fun nullResultIsContainedAsFailure() {
        val plugin = TestPlugin("t.null")
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)
        registry.setEnabled(plugin.id, true)
        val manager = PluginManager(registry)

        val result = manager.withPlugin<Int?>(PluginCapability.TextTransform, null) { null }
        assertTrue(result is PluginResult.Failure)
        assertEquals(PluginFailureReason.PLUGIN_ERROR, (result as PluginResult.Failure).reason)
        assertTrue(result.message.contains("no result"))
    }

    @Test
    fun throwingOnEnableDoesNotCrashAndStillEnables() {
        val plugin = TestPlugin(
            id = "t.onenable",
            onEnableBlock = { _, _ -> throw RuntimeException("onEnable boom") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)

        val result = registry.setEnabled(plugin.id, true) // must not throw
        assertTrue(result is com.authorss81.noteflow.plugins.PluginEnableResult.Changed)
        assertEquals(com.authorss81.noteflow.plugins.PluginLifecycleState.AVAILABLE, registry.stateOf(plugin.id)?.state)
        assertTrue(registry.isEnabled(plugin.id))
    }

    @Test
    fun throwingAvailabilityIsContained() {
        val plugin = TestPlugin(
            id = "t.avail",
            availabilityResult = { throw IllegalStateException("gate exploded") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)

        // resolve() / stateOf() must not throw even though availability throws.
        registry.setEnabled(plugin.id, true)
        assertEquals(com.authorss81.noteflow.plugins.PluginLifecycleState.UNAVAILABLE, registry.stateOf(plugin.id)?.state)
    }

    @Test
    fun failedInvocationRecordsDiagnosticsForLastInvocation() {
        val ok = TestPlugin(
            id = "t.ok",
            transformBlock = { "transformed" }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(ok), currentApiLevel = 26)
        registry.setEnabled(ok.id, true)
        val manager = PluginManager(registry)

        manager.withPlugin(PluginCapability.TextTransform, null) {
            (it as com.authorss81.noteflow.plugins.TextTransformPlugin).transformText("x")
        }
        val record = manager.lastInvocation(ok.id)
        assertTrue(record != null && record.ok)
    }

    @Test
    fun asyncInvocationRunsOffTheCallingThread() {
        val plugin = TestPlugin(
            id = "t.async",
            transformBlock = { "ASYNC" }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)
        registry.setEnabled(plugin.id, true)
        val manager = PluginManager(registry)

        val callerThread = Thread.currentThread().name
        var actionThread: String? = null
        val result = runBlocking {
            manager.withPluginAsync(PluginCapability.TextTransform, null) {
                actionThread = Thread.currentThread().name
                (it as com.authorss81.noteflow.plugins.TextTransformPlugin).transformText("x")
            }
        }
        assertTrue(result is PluginResult.Success)
        assertEquals("ASYNC", (result as PluginResult.Success).value)
        assertTrue(actionThread != null && actionThread != callerThread)
    }

    @Test
    fun selfCheckContainedAndRecordsFailure() {
        val plugin = TestPlugin(
            id = "t.selfcheck",
            selfCheckResult = { throw RuntimeException("self-check boom") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)
        registry.setEnabled(plugin.id, true)
        val manager = PluginManager(registry)

        val result = manager.selfCheck(plugin.id, null)
        assertTrue(result is com.authorss81.noteflow.plugins.PluginCheckResult.Failure)
        val record = manager.lastInvocation(plugin.id)
        assertTrue(record != null && !record.ok)
    }

    @Test
    fun lastInvocationSummaryNeverContainsContent() {
        val plugin = TestPlugin(
            id = "t.secret",
            transformBlock = { throw RuntimeException("fragile") }
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(plugin), currentApiLevel = 26)
        registry.setEnabled(plugin.id, true)
        val manager = PluginManager(registry)

        val secret = "top-secret-note-text-42"
        manager.withPlugin(PluginCapability.TextTransform, null) {
            (it as com.authorss81.noteflow.plugins.TextTransformPlugin).transformText(secret)
        }
        val record = manager.lastInvocation(plugin.id) ?: throw AssertionError("no record")
        assertTrue(record.summary.contains("RuntimeException"))
        assertTrue(!record.summary.contains(secret))
    }
}