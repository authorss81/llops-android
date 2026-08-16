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
 * nor the enable path of `setEnabled`); `resumeLifecycle` after an unlock
 * fires it exactly once; `pauseLifecycle` tears down live hooks with
 * `onDisable`; the persisted opt-in state is untouched — plus source-level
 * wiring pins proving the ViewModel only boots the plugin layer for a
 * passwordless (already-authenticated) boot or after a successful unlock, and
 * tears it down on lock.
 */
class B1Auth03PluginLifecycleGateTest {

    /** Counting plugin that records every onEnable/onDisable invocation. */
    private class CountingPlugin(
        id: String,
        val onEnableCalls: MutableList<Context?> = mutableListOf(),
        val onDisableCalls: MutableList<Context?> = mutableListOf()
    ) : NoteflowPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = "Counting $id",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = 26,
            description = "counts lifecycle hook invocations for B1-AUTH-03",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok
        override fun onEnable(context: Context?, settings: PluginSettings) {
            onEnableCalls.add(context)
        }

        override fun onDisable(context: Context?, settings: PluginSettings) {
            onDisableCalls.add(context)
        }
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
    fun `pause preserves the persisted opt-in state`() {
        val store = InMemoryEnableStore()
        val plugin = CountingPlugin("test.optin")
        store.forceEnabled(plugin.id)
        val registry = PluginRegistry(store, plugins = listOf(plugin), currentApiLevel = 26)

        registry.resumeLifecycle(null)
        registry.pauseLifecycle(null)

        assertTrue("lock stops the HOOK, not the user's enable decision",
            registry.isEnabled(plugin.id))
        assertTrue("the plugin must still be routable/served once unlocked",
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

    // ---------- wiring pins: the Android-bound ViewModel (source-level) ----------

    // The boot gate lives in the Android-bound NoteflowViewModel which cannot be
    // instantiated on the pure JVM. Pin the wiring at source level (same
    // technique as B1Auth02LockedOpenTest/phase-47) so a future refactor cannot
    // silently move the boot back into the pre-auth init block.

    @Test
    fun `viewModel init boots the plugin layer ONLY for a passwordless already-authenticated start`() {
        val source = readNoteflowViewModelSource()
        val pluginInitBlock = source
            .substringAfter("private var pluginLifecycleStarted = false")
            .substringAfter("init {")
            .substringBefore("private fun startPluginLifecycle()")
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
    }

    @Test
    fun `startPluginLifecycle owns re-materialization plus the hook resume`() {
        val source = readNoteflowViewModelSource()
        val bootMethod = source.substringAfter("private fun startPluginLifecycle()")
            .substringBefore("private val _storeProgress")
        assertTrue("the plugin boot must re-materialize persisted downloadables",
            bootMethod.contains("pluginEntryStore.all()"))
        assertTrue("hooks must re-arm through PluginRegistry.resumeLifecycle",
            bootMethod.contains("pluginRegistry.resumeLifecycle(appContext)"))
        assertTrue("the plugin state flows must refresh after the boot",
            bootMethod.contains("refreshPluginStates()"))
        assertTrue("the boot must register the downloadable runtime in the seam",
            bootMethod.contains("PluginRuntimeRegistry.register(pluginRuntime)"))
    }

    @Test
    fun `lock tears down + quiesces the plugin lifecycle`() {
        val source = readNoteflowViewModelSource()
        val lockBlock = source.substringAfter("fun lock()")
            .substringBefore("override fun onCleared()")
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
        val verifyBlock = source.substringAfter("suspend fun verifyMasterPassword", "END")
            .substringBefore("suspend fun isMasterPasswordValid", "END")
        val biometricsBlock = source.substringAfter("fun verifyBiometricsAndUnlock", "END")
            .substringBefore("fun disableBiometricFallback", "END")
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
    fun `registry gates the hooks on a paused lifecycle flag`() {
        val source = readPluginRegistrySource()
        assertTrue("the registry must expose pauseLifecycle/resumeLifecycle",
            source.contains("fun pauseLifecycle(") && source.contains("fun resumeLifecycle("))
        val onProcessStartBlock = source.substringAfter("fun onProcessStart(context: Context?)")
            .substringBefore("fun pauseLifecycle(")
        assertTrue(
            "onProcessStart must be quiesced while the lifecycle is paused",
            onProcessStartBlock.contains("if (lifecyclePaused) return")
        )
        val setEnabledBlock = source.substringAfter("fun setEnabled(pluginId: String, enabled: Boolean")
            .substringBefore("fun notifyConfigChanged")
        assertTrue(
            "the enable path of setEnabled must never fire onEnable while paused",
            setEnabledBlock.contains("!lifecyclePaused")
        )
    }

    // ---------- helpers ----------

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