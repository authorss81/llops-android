package com.authorss81.noteflow.plugins.store

import com.authorss81.noteflow.plugins.runtime.PluginEntry

/**
 * The store's seam for downloadable (REMOTE) plugins (Phase 23).
 *
 * The store controller stays pure JVM and testable; this interface is what it
 * holds for the remote path. The production implementation
 * ([com.authorss81.noteflow.services.DownloadablePluginInstaller]) wires the
 * real runtime: HTTPS download → pinned-cert + sha256 verification → load →
 * registry install. Tests inject a fake.
 *
 * Consent contract: the FIRST download of a plugin requires explicit user
 * consent ([isConsented]); [grantConsent] records it (wiped again by the
 * store's Delete). Downloaded plugins are OFF by default (registry install
 * starts REGISTERED) and toggleable from the store.
 */
interface RemotePluginInstaller {

    /** Whether the user has explicitly consented to download [pluginId]. */
    fun isConsented(pluginId: String): Boolean

    /** Persist the user's explicit consent for [pluginId]. */
    fun grantConsent(pluginId: String)

    /**
     * The ACTIVE persisted entry for [pluginId] (a previously downloaded and
     * already-updated remote plugin), or null when none is persisted yet. The
     * update path (Phase 24) compares against THIS version — not the bundled
     * catalog definition — so a second update never downgrades against a stale
     * catalog copy and records the true previous version for rollback. Default
     * null keeps existing (test) implementations source-compatible.
     */
    fun activeEntryFor(pluginId: String): PluginEntry? = null

    /**
     * Download → verify → load → install [entry] into the registry.
     * Reports progress `0f..1f` into the store's progress flow. Never throws.
     */
    suspend fun install(
        entry: PluginEntry,
        onProgress: (Float) -> Unit
    ): PluginStoreController.DownloadOutcome

    /**
     * Store Delete: remove the downloaded artifact + the persisted entry blob
     * (opt-in/settings wipe happens in the registry uninstall).
     */
    fun deleteArtifact(entry: PluginEntry)
}
