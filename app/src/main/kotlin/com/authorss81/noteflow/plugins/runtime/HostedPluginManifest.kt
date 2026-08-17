package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginLogPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * One plugin's update offer in the Phase-24 hosted version manifest.
 *
 * The manifest is the ONLY source of truth for what "latest" means — the app
 * never guesses a version or a digest. Every field the update path needs to
 * re-verify a fresh artifact is carried here:
 *
 * - [id] must match the installed plugin's [PluginEntry.id].
 * - [version] is the latest published version (semver; the update is only
 *   offered when [version] is strictly newer than what is installed).
 * - [downloadUrl] is the HTTPS artifact URL, restricted to the allow-listed
 *   download hosts ([DEFAULT_DOWNLOAD_HOSTS], which includes the manifest host)
 *   and served over the pinned TLS transport.
 * - [sha256] is the hex SHA-256 of the NEW artifact (re-verified on download).
 * - [pinnedCertHash] is the `sha256/<base64>` pin of the NEW artifact's signing
 *   certificate AND of the TLS session that serves [downloadUrl].
 * - [installSizeBytes] (optional) drives the update's size preview in the
 *   approval dialog and the downloader's free-space guard.
 * - [updateNotes] (optional) is shown to the user in the approval dialog
 *   ("what changed").
 * - [updateChannel] must equal the installed entry's channel; a plugin only
 *   sees offers from the channel it was installed from ("stable" default).
 *
 * Trust note (B1-NET-03): NONE of `downloadUrl`/`sha256`/`pinnedCertHash` are
 * trusted off the wire. [CompileTimePluginPinStore] verifies every offer
 * against the compile-time per-plugin release table BEFORE it becomes an
 * update; these fields only carry what the pinned manifest HAPPENS to claim,
 * and any mismatch with the compiled-in anchor is rejected.
 */
data class HostedPluginVersion(
    val id: String,
    val version: PluginVersion,
    val downloadUrl: String,
    val sha256: String,
    val pinnedCertHash: String,
    val installSizeBytes: Long? = null,
    val updateNotes: String? = null,
    val updateChannel: String = PluginEntry.DEFAULT_CHANNEL
) {
    /** Cross-field validation of a single manifest entry. Returns user-facing
     *  errors, or an empty list when the offer is well-formed. Never throws. */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        // Phase-93 review fix (FINDING #4): these strings are user-facing, but
        // they are built from attacker-influenceable fields — a CR/LF-bearing
        // id/url must never be echoed into them either, so the labels are
        // redacted through PluginLogPolicy.redactLineBreak (a hostile value
        // yields the fixed "(redacted)" marker).
        val idLabel = PluginLogPolicy.redactLineBreak(id)
        val urlLabel = PluginLogPolicy.redactLineBreak(downloadUrl)
        if (id.isBlank()) errors += "manifest entry is missing its plugin id"
        if (version < PluginVersion.ZERO) errors += "manifest version '${version}' is not a valid Major.Minor.Patch version"
        if (downloadUrl.isBlank()) errors += "manifest entry for '$idLabel' is missing downloadUrl"
        if (!downloadUrl.isBlank() && !downloadUrl.startsWith("https://")) {
            errors += "manifest entry for '$idLabel' must use an HTTPS downloadUrl (got '$urlLabel')"
        }
        if (!downloadUrl.isBlank() && PluginLogPolicy.hasLineBreak(downloadUrl)) {
            errors += PluginLogPolicy.lineBreakError("downloadUrl")
        }
        if (sha256.isBlank()) errors += "manifest entry for '$idLabel' is missing sha256"
        if (pinnedCertHash.isBlank()) errors += "manifest entry for '$idLabel' is missing pinnedCertHash"
        if (updateChannel.isBlank()) errors += "manifest entry for '$idLabel' has a blank updateChannel"
        if (installSizeBytes != null && installSizeBytes < 0) {
            errors += "manifest entry for '$idLabel' has a negative installSizeBytes"
        }
        // B2-LOG-04 (phase-93): CR/LF in these fields is a logcat line-forgery
        // vehicle — refuse the manifest leg at parse time, never echo the value.
        if (PluginLogPolicy.hasLineBreak(id)) errors += PluginLogPolicy.lineBreakError("manifest id")
        return errors
    }
}

/**
 * The Phase-24 hosted version manifest: the full set of update offers the store
 * fetched from [DEFAULT_PLUGIN_MANIFEST_URL]. Parsed by [PluginManifestParser];
 * compared against installed entries by `PluginUpdateChecker`. Pure data.
 */
data class HostedPluginManifest(
    val plugins: List<HostedPluginVersion>
) {
    /** The offer for [pluginId], or null when the manifest does not list it. */
    fun offerFor(pluginId: String): HostedPluginVersion? =
        plugins.firstOrNull { it.id == pluginId }
}

/**
 * Result of [PluginManifestParser.parse]. [Valid] carries a fully-validated
 * manifest; [Invalid] carries user-facing errors and applies NOTHING (a
 * manifest containing a single malformed/tampered offer is refused whole).
 */
sealed class ManifestParseResult {
    data class Valid(val manifest: HostedPluginManifest) : ManifestParseResult()
    data class Invalid(override val errors: List<String>) : ManifestParseResult()

    val isValid: Boolean get() = this is Valid
    open val errors: List<String> get() = (this as? Invalid)?.errors.orEmpty()
}

/**
 * PURE-JVM parser for the hosted plugin version manifest (Phase 24).
 *
 * Wire format (one JSON document fetched from the default HTTPS manifest URL):
 *
 * ```
 * {
 *   "plugins": [
 *     {
 *       "id": "com.authorss81.noteflow.plugins.remote.ocr",
 *       "version": "1.2.0",
 *       "downloadUrl": "https://plugins.example.com/ocr-1.2.0.apk",
 *       "sha256": "0f9c...",                       // hex SHA-256 of the artifact
 *       "pinnedCertHash": "sha256/<base64>",       // signing-cert / TLS pin
 *       "installSizeBytes": 204800,                // optional
 *       "updateChannel": "stable",                 // optional (default "stable")
 *       "updateNotes": "Faster on low-end devices" // optional
 *     }
 *   ]
 * }
 * ```
 *
 * Strictness rules (all enforced + unit-tested):
 * - Malformed JSON / a missing `plugins` array / a non-string version field →
 *   [ManifestParseResult.Invalid].
 * - A single invalid offer (blank id, unparseable version, non-HTTPS
 *   downloadUrl, missing sha256 or pinnedCertHash, blank channel, duplicate id,
 *   negative size) invalidates the WHOLE manifest — nothing from a partially
 *   bad document is applied.
 * - An empty `plugins` array is valid ("no updates offered").
 *
 * Built on Gson (already a base-app dependency, same as `PluginEntryCodec`).
 * Never throws; never logs artifact contents.
 */
class PluginManifestParser {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parse(json: String): ManifestParseResult {
        val dto = try {
            gson.fromJson(json, ManifestDto::class.java)
        } catch (_: Throwable) {
            return ManifestParseResult.Invalid(listOf("The update manifest is not valid JSON."))
        } ?: return ManifestParseResult.Invalid(listOf("The update manifest is empty."))
        val entries = dto.plugins ?: return ManifestParseResult.Invalid(
            listOf("The update manifest is missing its \"plugins\" list.")
        )
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val offers = entries.mapNotNull { dto ->
            val version = PluginVersion.parse(dto.version ?: "")
            if (version == null) {
                // Phase-93 review fix (FINDING #4): the id is attacker-influenceable —
                // never echo a CR/LF-bearing one, even into user-facing parse errors.
                val versionId = dto.id?.let { PluginLogPolicy.redactLineBreak(it) } ?: "(no id)"
                errors += "manifest entry '$versionId' has an invalid version '${dto.version}'"
                return@mapNotNull null
            }
            val offer = HostedPluginVersion(
                id = dto.id.orEmpty(),
                version = version,
                downloadUrl = dto.downloadUrl.orEmpty(),
                sha256 = dto.sha256.orEmpty(),
                pinnedCertHash = dto.pinnedCertHash.orEmpty(),
                installSizeBytes = dto.installSizeBytes,
                updateNotes = dto.updateNotes,
                updateChannel = dto.updateChannel?.takeIf { it.isNotBlank() } ?: PluginEntry.DEFAULT_CHANNEL
            )
            if (!seen.add(offer.id)) {
                errors += "manifest lists plugin '${PluginLogPolicy.redactLineBreak(offer.id)}' more than once"
                return@mapNotNull null
            }
            val entryErrors = offer.validationErrors()
            if (entryErrors.isNotEmpty()) {
                errors += entryErrors
                return@mapNotNull null
            }
            offer
        }
        if (errors.isNotEmpty()) {
            return ManifestParseResult.Invalid(errors)
        }
        return ManifestParseResult.Valid(HostedPluginManifest(offers))
    }

    internal data class ManifestDto(
        val plugins: List<PluginOfferDto>?
    )

    internal data class PluginOfferDto(
        val id: String?,
        val version: String?,
        val downloadUrl: String?,
        val sha256: String?,
        val pinnedCertHash: String?,
        val installSizeBytes: Long?,
        val updateChannel: String?,
        val updateNotes: String?
    )
}

/**
 * The default hosted-plugin manifest URL (Phase 24). HTTPS only — the fetch
 * path ([PluginManifestFetcher] / [HttpsManifestTransport]) refuses any other
 * scheme. Keyless: the app makes no authenticated request; verification of
 * everything that actually executes happens per-artifact via the compile-time
 * pinned cert + SHA-256, and **the manifest transport itself is pinned to a
 * compile-time certificate** ([PLUGIN_MANIFEST_CERT_PIN]) so the update offer
 * (which carries `downloadUrl`/`sha256`/`pinnedCertHash`) can never be forged
 * by a network MITM (B1-CRYPTO-01).
 *
 * @see DEFAULT_MANIFEST_HOST
 * @see PLUGIN_MANIFEST_CERT_PIN
 */
const val DEFAULT_MANIFEST_HOST: String = "plugin-updates.inkflow.app"

const val DEFAULT_PLUGIN_MANIFEST_URL: String =
    "https://$DEFAULT_MANIFEST_HOST/v1/manifest.json"

/**
 * The COMPILE-TIME certificate pin the manifest transport authenticates against
 * — `sha256/<base64>` of the SHA-256 of `DEFAULT_MANIFEST_HOST`'s leaf
 * certificate DER encoding (format enforced by [PinnedCertHash.parse]).
 *
 * **This is the trust anchor for the whole Phase-23/24 update chain**
 * (B1-CRYPTO-01): [HttpsManifestTransport] refuses any fetch whose server leaf
 * does not hash to this pin, so the manifest (and therefore the `sha256` /
 * `pinnedCertHash` / `downloadUrl` it offers) can never be replaced by an
 * unauthenticated source. It is compiled in and must NEVER come from the
 * network or user settings.
 *
 * The value below is a well-formed-but-REPLACEMENT placeholder: it must be set
 * to the real hash of the production manifest host's serving certificate before
 * the hosted update channel goes live. Until then the app FAILS CLOSED —
 * "Check for updates" answers a clear non-alarming "disabled" message and no
 * manifest is ever accepted. Do not dilute this into a warning.
 */
const val PLUGIN_MANIFEST_CERT_PIN: String =
    "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
