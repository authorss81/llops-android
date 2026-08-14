package com.authorss81.noteflow.plugins.store

import com.authorss81.noteflow.plugins.runtime.ManifestFetchResult
import com.authorss81.noteflow.plugins.runtime.PluginEntry

/**
 * The plugin store's seam for the Phase-24 dynamic-update flow.
 *
 * The store controller stays pure JVM and testable; this interface is what it
 * holds for the two update operations:
 *
 * - [fetchManifest] — fetch + parse the hosted version manifest (keyless,
 *   HTTPS-only, user-initiated). The store uses the result for BOTH the
 *   "Check for updates" listing and the per-update approval flow (a fresh
 *   manifest is parsed at install time, so an offer is always current).
 * - [runUpdate] — execute an ALREADY-APPROVED verified update of [entry] to
 *   [target]. The approval must already have been recorded by the caller —
 *   the controller never forwards a non-approved update here, and the runtime
 *   engine enforces the same gate again (defense in depth).
 *
 * The production implementation
 * ([com.authorss81.noteflow.services.DownloadablePluginUpdater]) wires the
 * real manifest fetcher + `PluginRuntime.update` and re-loads the new version
 * into the registry on success. Tests inject a fake.
 */
interface PluginUpdateCoordinator {

    /** Fetch + parse the hosted version manifest. Never throws. */
    suspend fun fetchManifest(): ManifestFetchResult

    /**
     * Run a user-approved verified update of [entry] to [target]. Reports
     * progress `0f..1f` into the store's progress flow. Never throws.
     */
    suspend fun runUpdate(
        entry: PluginEntry,
        target: PluginEntry,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): PluginStoreController.UpdateOutcome
}