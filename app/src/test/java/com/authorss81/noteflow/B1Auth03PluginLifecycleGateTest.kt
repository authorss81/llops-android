package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B1-AUTH-03 (phase-67) behavioral + wiring tests for the downloadable-plugin
 * lifecycle lock gate.
 *
 * Finding: `NoteflowViewModel`'s init block re-materialized downloadable
 * plugins (`pluginEntryStore.all() → pluginRuntime.load`) and then ran
 * `pluginRegistry.onProcessStart(appContext)` unconditionally — an installed +
 * enabled plugin's `onEnable(context)` received a live application Context on
 * every cold start while the app sat on the LockScreen, i.e. plugin code ran
 * in a security state (locked) the UI otherwise forbids.
 *
 * What is provable on the pure JVM: [PluginRegistry]'s lifecycle gate — while
 * paused (vault locked) `onEnable` never fires (neither via `onProcessStart`
 * nor the enable path of `setEnabled`), `onDisable`/`onConfigChanged` never
 * fire without a matching this-process `onEnable`, and the plugin's
 * `availability` gate is never invoked; `resumeLifecycle` after an unlock
 * fires hooks exactly once; `pauseLifecycle` tears down live hooks; the
 * persisted opt-in state is untouched and nothing is routed on the LockScreen
 * — plus source-level wiring pins proving the ViewModel only boots the plugin
 * layer for a passwordless (already-authenticated) boot or after a successful
 * unlock, tears it down on lock, and quiesces state refresh/diagnostics.
 *
 * The phase-67 review closed the lifecycle-ADJACENT live-Context surfaces too:
 * a disabled/uninstalled plugin is only torn down if its `onEnable` actually
 * ran (never `onDisable` without `onEnable`, and never with a live Context on
 * the LockScreen), `onConfigChanged` is paused with everything else, the
 * availability gate reports `Unavailable` while locked so every derived-state
 * query + capability route fails closed, and the ViewModel's
 * `refreshPluginStates()`/`testPlugin()` no-ops while the vault is locked.
 */
class B1Auth03PluginLifecycleGateTest {

    /** Counting plugin that records every lifecycle-hook + availability invocation. */
    private class CountingPlugin(
        id: String,
        val onEnableCalls: MutableList<Context?> = mutableListOf(),
        val onDisableCalls: MutableList<Context?> = mutableListOf(),
        val onConfigChangedCalls: MutableList<Context?> = mutableListOf(),
        val availabilityCalls: MutableList<Context?> = mutableListOf()
    ) : NoteflowPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = "Counting $id",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = 26,
            description = "counts lifecycle hook invocations for B1-AUTH-03",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        override fun availability(context: Context?): PluginAvailability {
            availabilityCalls.add(context)
            return PluginAvailability.Ok
        }
        override fun onEnable(context: Context?, settings: PluginSettings) {
            onEnableCalls.add(context)
        }

        override fun onDisable(context: Context?, settings: PluginSettings) {
            onDisableCalls.add(context)
        }

        override fun onConfigChanged(context: Context?, settings: PluginSettings) {
            onConfigChangedCalls.add(context)
        }
    }

    /** Availability-gate spy: records ONLY every [NoteflowPlugin.availability] call. */
    private class AvailabilityCountingPlugin(id: String) : NoteflowPlugin {
        var availabilityCalls = 0
        override val manifest = PluginManifest(
            id = id,
            name = "Availability $id",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = 26,
            description = "counts availability-gate invocations for B1-AUTH-03",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        override fun availability(context: Context?): PluginAvailability {
            availabilityCalls++
            return PluginAvailability.Ok
        }

        override fun onEnable(context: Context?, settings: PluginSettings) {}
    }

    // ---------- behavior: the registry lifecycle gate (pure JVM) ----------

    @Test
    fun `locked vault - an enabled plugin's onEnable never fires - after unlock it fires once`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.locked", calls, disables)
        // A previous session left the plugin enabled in the persisted store.
        store.forceEnabled(plugin.id)

        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        // Cold start while the vault is locked: the ViewModel pauses the lifecycle
        // BEFORE any hook could fire (password-protected vaults never boot the
        // plugin layer pre-unlock) AND the direct call is itself quiesced.
        registry.pauseLifecycle(null)
        assertEquals("no onEnable may fire on a locked cold start", 0, calls.size)
        assertEquals("nothing was live, so nothing is torn down either", 0, disables.size)

        // A stray/redundant onProcessStart while locked must stay inert.
        registry.onProcessStart(null)
        assertEquals("onProcessStart must be quiesced while the vault is locked", 0, calls.size)

        // The user unlocks: hooks re-arm and fire exactly once.
        registry.resumeLifecycle(null)
        assertEquals("after unlock the enabled plugin's onEnable fires exactly once", 1, calls.size)

        // Resuming again in the same session is idempotent.
        registry.resumeLifecycle(null)
        assertEquals("resume is idempotent - onEnable fires exactly once per session", 1, calls.size)
    }

    @Test
    fun `unlocked boot fires onEnable once then lock tears it down with onDisable`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.teardown", calls, disables)
        store.forceEnabled(plugin.id)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        // Passwordless boot (authenticated from boot): hook fires immediately.
        registry.resumeLifecycle(null)
        assertEquals(1, calls.size)

        // The vault locks: the plugin is stopped, not left running on the LockScreen.
        registry.pauseLifecycle(null)
        assertEquals("lock must fire onDisable for every plugin whose onEnable ran", 1, disables.size)

        // While locked no further hook can fire.
        registry.onProcessStart(null)
        assertEquals(1, calls.size)

        // Next unlock re-initializes the still-enabled plugin.
        registry.resumeLifecycle(null)
        assertEquals("unlock re-fires onEnable for a torn-down but still-enabled plugin", 2, calls.size)
    }

    @Test
    fun `setEnabled while locked persists the opt-in but never fires onEnable until unlock`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.enablerace", calls, disables)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        // Lock first, then a store/UI action races the lock boundary.
        registry.pauseLifecycle(null)
        registry.setEnabled(plugin.id, enabled = true, context = null)
        assertTrue("the persisted opt-in must go through (delete/disable semantics unchanged)",
            store.isEnabled(plugin.id))
        assertEquals("onEnable must not fire while the vault is locked", 0, calls.size)

        registry.resumeLifecycle(null)
        assertEquals("the enable takes effect on the next unlock, exactly once", 1, calls.size)
    }

    @Test
    fun `pause preserves the persisted opt-in state but stops serving while locked`() {
        val store = InMemoryEnableStore()
        val plugin = CountingPlugin("test.optin")
        store.forceEnabled(plugin.id)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.resumeLifecycle(null)
        registry.pauseLifecycle(null)

        assertTrue("lock stops the HOOK, not the user's enable decision",
            registry.isEnabled(plugin.id))
        // phase-67 review-fix: the availability gate reports Unavailable while
        // locked, so NO plugin is routed/served on the LockScreen — serving only
        // resumes on the next unlock.
        assertTrue("while locked nothing is routed or served",
            registry.enabledPlugins(null).isEmpty())
        registry.resumeLifecycle(null)
        assertTrue("after unlock the still-enabled plugin is routable again",
            registry.enabledPlugins(null).isNotEmpty())
    }

    @Test
    fun `pausing twice or resuming without enable is safe`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.empty", calls, disables)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.pauseLifecycle(null)
        registry.pauseLifecycle(null) // double lock: no crash, no double teardown
        assertEquals(0, disables.size)

        registry.resumeLifecycle(null) // nothing enabled in the store: nothing fires
        assertEquals(0, calls.size)
    }

    @Test
    fun `while the vault is locked plugin availability gates never run`() {
        val store = InMemoryEnableStore()
        val plugin = AvailabilityCountingPlugin("test.avail")
        store.forceEnabled(plugin.id)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.resumeLifecycle(null)
        assertTrue("the availability gate must run while unlocked",
            plugin.availabilityCalls > 0)

        registry.pauseLifecycle(null)
        val callsAfterPause = plugin.availabilityCalls

        // Every availability-derived surface must fail closed WITHOUT invoking the
        // plugin's gate bytecode while the vault is locked.
        registry.enabledPlugins(null)
        registry.availablePlugins(PluginCapability.TextTransform, null)
        registry.resolve(null)
        registry.stateOf(plugin.id, null)
        registry.setEnabled(plugin.id, enabled = false, context = null)
        assertEquals("no availability bytecode may run while the vault is locked",
            callsAfterPause, plugin.availabilityCalls)

        registry.resumeLifecycle(null)
        assertTrue("the availability gate runs again after unlock",
            plugin.availabilityCalls > callsAfterPause)
    }

    @Test
    fun `disable while locked never fires onDisable without a matching onEnable`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.disablerace", calls, disables)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.pauseLifecycle(null)
        // Enable races the lock: the opt-in persists but the hook defers.
        registry.setEnabled(plugin.id, enabled = true, context = null)
        assertEquals(0, calls.size)

        // Disable before any unlock: no onEnable ever ran ⇒ no onDisable may run
        // (never hand plugin bytecode a live Context on the LockScreen, and never
        // onDisable without onEnable).
        registry.setEnabled(plugin.id, enabled = false, context = null)
        assertEquals("deferred enable torn down by a locked disable must not fire onDisable",
            0, disables.size)

        registry.resumeLifecycle(null)
        assertEquals("after unlock the plugin is disabled, so nothing fires", 0, calls.size)
        assertEquals(0, disables.size)
    }

    @Test
    fun `onConfigChanged never fires while the vault is locked`() {
        val store = InMemoryEnableStore()
        val calls = mutableListOf<Context?>()
        val disables = mutableListOf<Context?>()
        val configs = mutableListOf<Context?>()
        val plugin = CountingPlugin("test.config", calls, disables, configs)
        store.forceEnabled(plugin.id)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.resumeLifecycle(null)
        assertEquals(1, calls.size)
        registry.notifyConfigChanged(plugin.id, null)
        assertEquals("a config change while unlocked reaches the plugin", 1, configs.size)

        registry.pauseLifecycle(null)
        assertEquals(1, disables.size) // torn down on lock
        registry.notifyConfigChanged(plugin.id, null)
        assertEquals("onConfigChanged must never run while the vault is locked",
            1, configs.size)

        registry.resumeLifecycle(null) // next unlock re-initializes
        assertEquals(2, calls.size)
    }

    // ---------- wiring pins: the Android-bound ViewModel (source-level) ----------

    // The boot gate lives in the Android-bound NoteflowViewModel which cannot be
    // instantiated on the pure JVM. Pin the wiring at source level (same
    // technique as B1Auth02LockedOpenTest/phase-47) so a future refactor cannot
    // silently move the boot back into the pre-auth init block.

    // phase-67 review-fix: the pins now extract each target's brace-balanced
    // body instead of matching on brittle substringBefore/After anchor strings,
    // so cosmetic reformatting inside a body can never break (or vacuously
    // widen) a pin — a renamed/moved declaration still fails it loudly.

    @Test
    fun `viewModel init boots the plugin layer ONLY for a passwordless already-authenticated start`() {
        val source = readNoteflowViewModelSource()
        val afterFlag = source.substringAfter("private var pluginLifecycleStarted")
        val pluginInitBlock = extractCodeBlock(afterFlag, Regex("init \\{"))
        assertTrue(
            "the plugin init block must gate the whole boot behind passwordless (already-authenticated)",
            pluginInitBlock.contains("if (!settings.hasMasterPassword)")
        )
        assertTrue(
            "the gated branch must delegate to startPluginLifecycle()",
            pluginInitBlock.contains("startPluginLifecycle()")
        )
        assertFalse(
            "the init block must NOT directly re-materialize store entries",
            pluginInitBlock.contains("pluginEntryStore.all()")
        )
        assertFalse(
            "the init block must NOT directly fire lifecycle hooks",
            pluginInitBlock.contains("pluginRegistry.onProcessStart(")
        )
        assertFalse(
            "the init block must NOT directly resume lifecycle hooks either",
            pluginInitBlock.contains("pluginRegistry.resumeLifecycle(")
        )
        // phase-67 review-fix (race hardening): the booted flag must be @Volatile
        // so lock()'s reset is visible to the next unlock on another thread.
        val flagDeclPos = source.indexOf("private var pluginLifecycleStarted")
        assertTrue(flagDeclPos > 0)
        val declPrefix = source.substring(flagDeclPos - 120, flagDeclPos)
        assertTrue(
            "the booted flag must be @Volatile (cross-thread lock/unlock handoff)",
            declPrefix.contains("@Volatile")
        )
    }

    @Test
    fun `startPluginLifecycle owns re-materialization plus the hook resume`() {
        val source = readNoteflowViewModelSource()
        val bootMethod = extractCodeBlock(source, Regex("private fun startPluginLifecycle\\(\\)"))
        assertTrue("the plugin boot must re-materialize persisted downloadables",
            bootMethod.contains("pluginEntryStore.all()"))
        assertTrue("hooks must re-arm through PluginRegistry.resumeLifecycle",
            bootMethod.contains("pluginRegistry.resumeLifecycle(appContext)"))
        assertTrue("the plugin state flows must refresh after the boot",
            bootMethod.contains("refreshPluginStates()"))
        assertTrue("the boot must register the downloadable runtime in the seam",
            bootMethod.contains("PluginRuntimeRegistry.register(pluginRuntime)"))
        // phase-67 review-fix (race hardening): the exactly-once gate must be
        // enforced with a short critical section, not a bare check-then-act.
        assertTrue("the boot gate must be double-checked under a lock",
            bootMethod.contains("synchronized(this)"))
    }

    @Test
    fun `lock tears down + quiesces the plugin lifecycle`() {
        val source = readNoteflowViewModelSource()
        val lockBlock = extractCodeBlock(source, Regex("fun lock\\(\\)"))
        assertTrue(
            "lock must tear down + pause the plugin lifecycle hooks",
            lockBlock.contains("pluginRegistry.pauseLifecycle(appContext)")
        )
        assertTrue(
            "lock must reset the booted flag so the next unlock re-boots the layer",
            lockBlock.contains("pluginLifecycleStarted = false")
        )
    }

    @Test
    fun `every successful unlock re-boots the plugin layer`() {
        val source = readNoteflowViewModelSource()
        val verifyBlock = extractCodeBlock(source, Regex("suspend fun verifyMasterPassword\\("))
        val biometricsBlock = extractCodeBlock(source, Regex("fun verifyBiometricsAndUnlock\\("))
        assertTrue(
            "a successful password unlock must boot the plugin layer",
            verifyBlock.contains("startPluginLifecycle()")
        )
        assertTrue(
            "a successful biometric unlock must boot the plugin layer too",
            biometricsBlock.contains("startPluginLifecycle()")
        )
    }

    @Test
    fun `registry gates the hook and gate surfaces on a paused lifecycle flag`() {
        val source = readPluginRegistrySource()
        assertTrue("the registry must expose pauseLifecycle/resumeLifecycle",
            source.contains("fun pauseLifecycle(") && source.contains("fun resumeLifecycle("))
        assertTrue("the registry must expose the paused flag for the ViewModel",
            source.contains("val isLifecyclePaused"))
        val onProcessStartBlock = extractCodeBlock(source, Regex("fun onProcessStart\\(context: Context\\?\\)"))
        assertTrue(
            "onProcessStart must be quiesced while the lifecycle is paused",
            onProcessStartBlock.contains("if (lifecyclePaused) return")
        )
        val setEnabledBlock = extractCodeBlock(source, Regex("fun setEnabled\\(pluginId: String, enabled: Boolean"))
        assertTrue(
            "the enable path of setEnabled must never fire onEnable while paused",
            setEnabledBlock.contains("!lifecyclePaused")
        )
        // phase-67 review-fix: onDisable may only fire for a plugin whose onEnable
        // ran this process (a live hook) — no disconnected teardown on the LockScreen.
        assertTrue(
            "the disable path must only tear down live hooks",
            setEnabledBlock.contains("if (enabledNotified.remove(pluginId))")
        )
        // phase-67 review-fix: the availability gate + config hook fail closed
        // while paused.
        val availabilityBlock = extractCodeBlock(
            source, Regex("private fun containedAvailability\\(plugin: NoteflowPlugin, context: Context\\?\\)")
        )
        assertTrue(
            "the availability gate must not invoke plugin bytecode while paused",
            availabilityBlock.contains("lifecyclePaused")
        )
        val configBlock = extractCodeBlock(source, Regex("fun notifyConfigChanged\\(pluginId: String, context: Context\\?"))
        assertTrue(
            "onConfigChanged must not fire while paused",
            configBlock.contains("if (lifecyclePaused) return")
        )
    }

    @Test
    fun `viewModel state refresh and diagnostics are quiesced while the vault is locked`() {
        val source = readNoteflowViewModelSource()
        val refreshBlock = extractCodeBlock(source, Regex("private fun refreshPluginStates\\(\\)"))
        assertTrue(
            "state refresh must no-op while the plugin lifecycle is paused",
            refreshBlock.contains("if (pluginRegistry.isLifecyclePaused) return")
        )
        val testBlock = extractCodeBlock(source, Regex("fun testPlugin\\(pluginId: String\\)"))
        assertTrue(
            "the plugin self-check must no-op while the plugin lifecycle is paused",
            testBlock.contains("if (pluginRegistry.isLifecyclePaused) return")
        )
    }

    // ---------- helpers ----------

    /**
     * Returns the brace-balanced body of the first declaration whose signature
     * matches [signature] (block-comment / line-comment / string / char-literal /
     * raw-string aware). Returns "" when the signature is absent — an assertion
     * against "" then fails loudly, which is exactly what a rename should do.
     */
    private fun extractCodeBlock(source: String, signature: Regex): String {
        val sigStart = signature.find(source)?.range?.first ?: return ""
        val open = source.indexOf('{', sigStart)
        if (open < 0) return ""
        var depth = 1
        var i = open + 1
        var inLineComment = false
        var inBlockComment = false
        var inRawString = false
        var inString = false
        var inChar = false
        while (i < source.length) {
            val c = source[i]
            val n2 = if (i + 1 < source.length) source[i + 1] else '\u0000'
            val n3 = if (i + 2 < source.length) source[i + 2] else '\u0000'
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (c == '*' && n2 == '/') { inBlockComment = false; i++ }
                inRawString -> if (c == '"' && n2 == '"' && n3 == '"') { inRawString = false; i += 2 }
                inString -> if (c == '\\') { i++ } else if (c == '"') inString = false
                inChar -> if (c == '\\') { i++ } else if (c == '\'') inChar = false
                else -> when (c) {
                    '/' -> if (n2 == '/') inLineComment = true
                    else if (n2 == '*') { inBlockComment = true; i++ }
                    '"' -> if (n2 == '"' && n3 == '"') { inRawString = true; i += 2 } else inString = true
                    '\'' -> inChar = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(open, i + 1)
                    }
                    else -> Unit
                }
            }
            i++
        }
        return source.substring(open, i)
    }

    private fun readNoteflowViewModelSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        )
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readPluginRegistrySource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/PluginRegistry.kt"
        )
        assertTrue("PluginRegistry.kt must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}