package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.utils.ConstantTime
import java.net.URL

/**
 * One released version of a downloadable (remote) plugin, with its
 * COMPILE-TIME pinned identity: the artifact SHA-256 and the signing
 * certificate / TLS pin (`sha256/<base64>`) that release was published with.
 *
 * This is the ONLY trust anchor the update chain accepts (B1-NET-03). A
 * version that has no pinned identity in the build is simply not updatable by
 * the Phase-24 mechanism — the manifest can never introduce one.
 */
data class PinnedReleaseVersion(
    val sha256: String,
    val pinnedCertHash: String
)

/**
 * Declarative row for seeding a [CompileTimePluginPinStore] — one released
 * version of one downloadable plugin. Used to build the production release
 * table ([CompileTimePluginPins.RELEASES]) and by tests.
 */
data class PinnedPluginRelease(
    val id: String,
    val version: PluginVersion,
    val sha256: String,
    val pinnedCertHash: String
)

/** Verdict of checking an update offer / target against the compile-time table. */
sealed class PinVerdict {
    /** The offer/target matches the compile-time pin; [pin] carries the values. */
    data class Verified(val pin: PinnedReleaseVersion) : PinVerdict()

    /** The offer/target must be refused; [reason] is user-facing and specific. */
    data class Rejected(val reason: String) : PinVerdict()
}

/**
 * The compile-time per-plugin pinned identity table — the **B1-NET-03 fix**.
 *
 * The hosted manifest was always able to redefine the update trust anchor:
 * `HostedPluginVersion` carries `downloadUrl` + `sha256` + `pinnedCertHash`,
 * `PluginUpdateChecker.toTargetEntry` copied them verbatim into the persisted
 * entry, and every later verifier (TLS pin, artifact signature) ran against
 * those MANIFEST-supplied values — so a manifest that passes transport
 * validation could point the app at attacker DEX and the checks would pass by
 * construction.
 *
 * Here the anchor moves into the APK: for every released version of every
 * downloadable plugin the build carries the exact SHA-256 + signing-cert pin
 * that release was published with. [verifyOffer] / [verifyEntry] reject any
 * offer/target whose values differ, that has no pin in the build, or whose
 * artifact URL is not hosted on an allow-listed download host.
 *
 * *Fail closed:* with an empty release table (as in this build — no remote
 * plugin has shipped yet) NO hosted update is ever accepted. When the operator
 * publishes a genuine release they add its pinned row here AND bump the app,
 * exactly like the placeholder [PLUGIN_MANIFEST_CERT_PIN] stance. A manifest's
 * own `sha256`/`pinnedCertHash` are compared, never trusted — they cannot
 * re-key the chain.
 *
 * Pure JVM; digests compared constant-time via
 * [`com.authorss81.noteflow.utils.ConstantTime`].
 *
 * @property releases id → (version → pinned identity) for every released version.
 * @property allowedDownloadHosts the only hosts artifact bytes may be fetched
 *   from (includes the manifest host); hosts are stored normalized (lowercase,
 *   trailing-dot stripped).
 */
class CompileTimePluginPinStore(
    releases: Map<String, Map<PluginVersion, PinnedReleaseVersion>>,
    allowedDownloadHosts: Set<String>
) {

    /** Test/operator-friendly seeds: `CompileTimePluginPinStore(PinnedPluginRelease(...), ...)`. */
    constructor(
        vararg releases: PinnedPluginRelease,
        allowedDownloadHosts: Set<String> = DEFAULT_DOWNLOAD_HOSTS
    ) : this(buildReleaseTable(*releases), allowedDownloadHosts)

    private val releaseTable: Map<String, Map<PluginVersion, PinnedReleaseVersion>> = releases
    private val allowList: Set<String> =
        allowedDownloadHosts.mapTo(mutableSetOf()) { it.lowercase().trimEnd('.') }

    /** The compile-time pinned identity for [id] v[version], or null when this
     *  build has never shipped that release (⇒ its updates are refused). */
    fun pinnedFor(id: String, version: PluginVersion): PinnedReleaseVersion? =
        releaseTable[id]?.get(version)

    /** True when [url]'s host is on the allow-list (a host gate — hostnames are
     *  never secrets, so the comparison need not be constant-time; used by
     *  [PluginDownloader]). */
    fun isAllowedDownloadHost(url: String): Boolean =
        isHostAllowListed(url, allowList)

    /** Verify a manifest offer ([[HostedPluginVersion]) against the compile-time
     *  table + download-host allow-list. */
    fun verifyOffer(offer: HostedPluginVersion): PinVerdict =
        verifyValues(offer.id, offer.version, offer.sha256, offer.pinnedCertHash, offer.downloadUrl)

    /** Verify a [PluginEntry] target (the persisted form of an accepted offer)
     *  against the compile-time table + download-host allow-list. */
    fun verifyEntry(entry: PluginEntry): PinVerdict =
        verifyValues(entry.id, entry.version, entry.sha256, entry.pinnedCertHash, entry.downloadUrl.orEmpty())

    private fun verifyValues(
        id: String,
        version: PluginVersion,
        sha256: String?,
        pinnedCertHash: String?,
        downloadUrl: String
    ): PinVerdict {
        val pin = pinnedFor(id, version)
            ?: return PinVerdict.Rejected(
                "no compile-time pinned identity for '$id' v$version is shipped in this build " +
                    "- updates are only offered for released versions the app was built against."
            )
        if (sha256.isNullOrEmpty() || !ConstantTime.hexEqual(pin.sha256.lowercase(), sha256.trim().lowercase())) {
            return PinVerdict.Rejected(
                "the SHA-256 offered for '$id' v$version does not match the compile-time pin."
            )
        }
        if (pinnedCertHash.isNullOrEmpty() || !ConstantTime.hexEqual(pin.pinnedCertHash, pinnedCertHash.trim())) {
            return PinVerdict.Rejected(
                "the certificate pin offered for '$id' v$version does not match the compile-time pin."
            )
        }
        if (!isAllowedDownloadHost(downloadUrl)) {
            return PinVerdict.Rejected(
                "the download URL offered for '$id' v$version is not hosted on an allow-listed download host."
            )
        }
        return PinVerdict.Verified(pin)
    }

    companion object {
        /** Build the id → (version → pin) table from declarative rows. */
        fun buildReleaseTable(vararg releases: PinnedPluginRelease): Map<String, Map<PluginVersion, PinnedReleaseVersion>> =
            releases.fold(emptyMap()) { acc, row ->
                val existing = acc[row.id].orEmpty()
                acc + (row.id to (existing + (row.version to PinnedReleaseVersion(row.sha256, row.pinnedCertHash))))
            }
    }
}

/** The only hosts plugin artifacts may be downloaded from (B1-NET-03): the
 *  operator publishes artifacts on the SAME host as the update manifest. */
val DEFAULT_DOWNLOAD_HOSTS: Set<String> = setOf(DEFAULT_MANIFEST_HOST)

/**
 * The PRODUCTION compile-time pin table.
 *
 * [RELEASES] is deliberately EMPTY in this build: no downloadable (remote)
 * plugin has been released yet, so nothing is updatable — the update path
 * fails closed (B1-NET-03) exactly like the placeholder
 * [PLUGIN_MANIFEST_CERT_PIN] does for the manifest transport. Publishing a
 * genuine plugin release REQUIRES adding its `PinnedPluginRelease` row(s) here
 * (and bumping the app); the manifest can never introduce an unpinned release.
 */
object CompileTimePluginPins {

    val RELEASES: Map<String, Map<PluginVersion, PinnedReleaseVersion>> = emptyMap()

    /** The store/engine/downloader default — secure unless injected otherwise. */
    val defaultStore: CompileTimePluginPinStore =
        CompileTimePluginPinStore(RELEASES, DEFAULT_DOWNLOAD_HOSTS)
}

/** Shared host allow-list gate: lowercase + trailing-dot folded on both sides,
 *  port ignored (the artifact URL is HTTPS-only and the port is not sensitive).
 *  Returns false for anything that does not parse as a URL with a host. */
internal fun isHostAllowListed(url: String, allowedHosts: Set<String>): Boolean {
    val host = runCatching { URL(url).host }.getOrNull()
        ?: return false
    if (host.isBlank()) return false
    val normalized = host.lowercase().trimEnd('.')
    return allowedHosts.any { it == normalized }
}