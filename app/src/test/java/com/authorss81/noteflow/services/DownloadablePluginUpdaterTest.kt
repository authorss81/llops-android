package com.authorss81.noteflow.services

import com.authorss81.noteflow.InMemoryEnableStore
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.HostedPluginManifest
import com.authorss81.noteflow.plugins.runtime.HostedPluginVersion
import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.ManifestTransport
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginManifestFetcher
import com.authorss81.noteflow.plugins.runtime.PluginUpdateResult
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [DownloadablePluginUpdater] — the store's update coordinator
 * adapter. The runtime engine owns approval/verify/smoke-test/rollback; this
 * suite pins the ADAPTER contract: manifest forwarding, success re-load +
 * version reporting, and honest failure mapping (engine failure ⇒ RolledBack,
 * not-yet-implemented ⇒ Failed) without ever touching the live registry.
 */
class DownloadablePluginUpdaterTest {

    private val current = Phase212Fakes.remoteEntry(
        id = "com.example.updatable.plugin", version = PluginVersion(1, 0, 0)
    )
    private val target = Phase212Fakes.remoteEntry(
        id = "com.example.updatable.plugin", version = PluginVersion(1, 1, 0)
    )

    private fun newUpdater(
        updateOutcome: RuntimeOutcome<PluginUpdateResult>,
        loadOk: Boolean = true
    ): Pair<DownloadablePluginUpdater, FakePluginRuntime> {
        val runtime = FakePluginRuntime()
        runtime.updateOutcome = updateOutcome
        if (!loadOk) runtime.loadOutcome = RuntimeOutcome.Failed("reload failed")
        val fetcher = PluginManifestFetcher(transport = ManifestTransport { url ->
            if (url.startsWith("https://")) {
                ManifestFetchResult.Loaded(
                    HostedPluginManifest(
                        listOf(
                            HostedPluginVersion(
                                id = target.id,
                                version = target.version,
                                downloadUrl = "https://plugins.example.com/new.apk",
                                sha256 = "d".repeat(64),
                                pinnedCertHash = "sha256/DDDD"
                            )
                        )
                    )
                )
            } else {
                ManifestFetchResult.Failed("non-TLS refused")
            }
        })
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            plugins = emptyList(),
            installStore = InMemoryPluginInstallStore(listOf(target.id)),
            currentApiLevel = 26
        )
        return Pair(DownloadablePluginUpdater(registry, runtime, fetcher), runtime)
    }

    @Test
    fun `fetchManifest forwards a loaded manifest`() = runBlocking {
        val (updater, _) = newUpdater(RuntimeOutcome.Failed("unused"))

        val result = updater.fetchManifest()

        assertTrue(result is ManifestFetchResult.Loaded)
        assertEquals(
            target.id,
            (result as ManifestFetchResult.Loaded).manifest.offerFor(target.id)?.id
        )
    }

    @Test
    fun `an approved update swaps the live plugin and reports versions`() = runBlocking {
        val outcome = RuntimeOutcome.Success(PluginUpdateResult(current, PluginVersion(1, 0, 0), PluginVersion(1, 1, 0)))
        val (updater, runtime) = newUpdater(outcome)
        val progress = mutableListOf<Float>()

        val result = updater.runUpdate(current, target, userApproved = true, onProgress = { progress.add(it) })

        assertEquals(
            PluginStoreController.UpdateOutcome.Updated(target.id, PluginVersion(1, 0, 0), PluginVersion(1, 1, 0)),
            result
        )
        // The adapter forwarded the call with the user's approval intact...
        assertEquals(Triple(current, target, true), runtime.updateCalls.single())
        // ...and re-loaded the NEW version for in-session serving.
        assertEquals(listOf(target), runtime.loadedEntries)
        assertTrue(progress.contains(0.5f))
    }

    @Test
    fun `an engine failure maps to RolledBack and never reloads`() = runBlocking {
        val (updater, runtime) = newUpdater(RuntimeOutcome.Failed("smoke test failed; rolled back"))

        val result = updater.runUpdate(current, target, userApproved = true, onProgress = {})

        assertEquals(
            PluginStoreController.UpdateOutcome.RolledBack(target.id, "smoke test failed; rolled back"),
            result
        )
        assertTrue("a rolled-back version must never be loaded", runtime.loadedEntries.isEmpty())
    }

    @Test
    fun `an unimplemented engine maps to Failed`() = runBlocking {
        val (updater, _) = newUpdater(
            RuntimeOutcome.NotYetImplemented(24, "updates land in phase 24")
        )

        val result = updater.runUpdate(current, target, userApproved = true, onProgress = {})

        assertTrue(result is PluginStoreController.UpdateOutcome.Failed)
    }

    @Test
    fun `a post-update reload failure still reports Updated (persisted entry serves next launch)`() = runBlocking {
        // The engine already smoke-tested + persisted the swap; the adapter's
        // re-load is best-effort. A failed reload must NOT turn the update
        // into a rollback lie.
        val outcome = RuntimeOutcome.Success(PluginUpdateResult(current, PluginVersion(1, 0, 0), PluginVersion(1, 1, 0)))
        val (updater, runtime) = newUpdater(outcome, loadOk = false)

        val result = updater.runUpdate(current, target, userApproved = true, onProgress = {})

        assertEquals(
            PluginStoreController.UpdateOutcome.Updated(target.id, PluginVersion(1, 0, 0), PluginVersion(1, 1, 0)),
            result
        )
        // The re-load attempt ran and failed honestly (recorded by the fake).
        assertTrue(runtime.loadedEntries.contains(target))
    }
}
