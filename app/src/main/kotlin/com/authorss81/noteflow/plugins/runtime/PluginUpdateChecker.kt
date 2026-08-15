package com.authorss81.noteflow.plugins.runtime

/**
 * One available update for an INSTALLED downloadable plugin (Phase 24).
 *
 * Produced by [PluginUpdateChecker.check] ONLY when the hosted manifest offers
 * a version strictly newer than the installed one. It carries everything the
 * approval dialog and the verified-update install need: the new semver, the
 * new artifact's HTTPS URL, its pinned cert hash + SHA-256, the optional size
 * and the human-readable update notes.
 */
data class PluginUpdateInfo(
    val pluginId: String,
    val currentVersion: PluginVersion,
    val newVersion: PluginVersion,
    val downloadUrl: String,
    val sha256: String,
    val pinnedCertHash: String,
    val installSizeBytes: Long? = null,
    val updateNotes: String? = null,
    val updateChannel: String = PluginEntry.DEFAULT_CHANNEL
) {
    /**
     * The [PluginEntry] the manifest offers for [installed]: the installed
     * entry's identity/name/description/capabilities/category are preserved
     * (the manifest only lists version + digests + URL), while the version,
     * download URL, size, channel and digests come from THIS offer. Remote-only
     * (a bundled plugin is never updated by this mechanism).
     */
    fun toTargetEntry(installed: PluginEntry): PluginEntry = installed.copy(
        version = newVersion,
        downloadUrl = downloadUrl,
        installSizeBytes = installSizeBytes,
        updateChannel = updateChannel,
        sha256 = sha256,
        pinnedCertHash = pinnedCertHash,
        source = PluginEntrySource.REMOTE
    )
}

/**
 * The PURE update-comparison logic of Phase 24. Compares the installed
 * downloadable [PluginEntry]s against the hosted [HostedPluginManifest] and
 * reports exactly the updates that SHOULD be offered:
 *
 * - **Never downgrade.** An offer is reported only when
 *   `manifestVersion.isNewerThan(installedVersion)` — equal versions and older
 *   offers are never reported (no no-op, no downgrade).
 * - **Channel-matched.** An offer is reported only when its updateChannel
 *   equals the installed entry's channel (a plugin installed from "stable"
 *   never sees "beta" offers).
 * - **Downloadable only.** Bundled (compile-time) plugins are excluded —
 *   they are updated by the normal app release, never by this mechanism.
 *
 * Deterministic output, ordered by plugin id. Pure JVM, unit-tested.
 *
 * Trust note (B1-CRYPTO-01): [PluginUpdateInfo] copies the offer's
 * `downloadUrl`/`sha256`/`pinnedCertHash` verbatim into the target entry, and
 * that is safe ONLY because the [HostedPluginManifest] arrived through the
 * compile-time-pinned [HttpsManifestTransport] — an unauthenticated manifest
 * can never reach this comparator.
 */
object PluginUpdateChecker {

    /**
     * The updates available for [installed], or an empty list when everything
     * is current (or the manifest offers nothing newer). Never throws.
     */
    fun check(
        installed: Collection<PluginEntry>,
        manifest: HostedPluginManifest
    ): List<PluginUpdateInfo> =
        installed.asSequence()
            .filter { it.isDownloadable }
            .mapNotNull { entry ->
                val offer = manifest.offerFor(entry.id) ?: return@mapNotNull null
                if (offer.updateChannel != entry.updateChannel) return@mapNotNull null
                if (!offer.version.isNewerThan(entry.version)) return@mapNotNull null
                PluginUpdateInfo(
                    pluginId = entry.id,
                    currentVersion = entry.version,
                    newVersion = offer.version,
                    downloadUrl = offer.downloadUrl,
                    sha256 = offer.sha256,
                    pinnedCertHash = offer.pinnedCertHash,
                    installSizeBytes = offer.installSizeBytes,
                    updateNotes = offer.updateNotes,
                    updateChannel = offer.updateChannel
                )
            }
            .sortedWith(compareBy { it.pluginId })
            .toList()
}
