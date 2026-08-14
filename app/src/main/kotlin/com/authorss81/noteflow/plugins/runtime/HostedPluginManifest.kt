package com.authorss81.noteflow.plugins.runtime

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
 * - [downloadUrl] is the HTTPS artifact URL; the pinned-cert + SHA-256 gate
 *   below is the trust anchor, so the URL host itself never needs to be a
 *   fixed allow-list.
 * - [sha256] is the hex SHA-256 of the NEW artifact (re-verified on download).
 * - [pinnedCertHash] is the `sha256/<base64>` pin of the NEW artifact's signing
 *   certificate AND of the TLS session that serves [downloadUrl].
 * - [installSizeBytes] (optional) drives the update's size preview in the
 *   approval dialog and the downloader's free-space guard.
 * - [updateNotes] (optional) is shown to the user in the approval dialog
 *   ("what changed").
 * - [updateChannel] must equal the installed entry's channel; a plugin only
 *   sees offers from the channel it was installed from ("stable" default).
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
        if (id.isBlank()) errors += "manifest entry is missing its plugin id"
        if (version < PluginVersion.ZERO) errors += "manifest version '${version}' is not a valid Major.Minor.Patch version"
        if (downloadUrl.isBlank()) errors += "manifest entry for '$id' is missing downloadUrl"
        if (!downloadUrl.isBlank() && !downloadUrl.startsWith("https://")) {
            errors += "manifest entry for '$id' must use an HTTPS downloadUrl (got '$downloadUrl')"
        }
        if (sha256.isBlank()) errors += "manifest entry for '$id' is missing sha256"
        if (pinnedCertHash.isBlank()) errors += "manifest entry for '$id' is missing pinnedCertHash"
        if (updateChannel.isBlank()) errors += "manifest entry for '$id' has a blank updateChannel"
        if (installSizeBytes != null && installSizeBytes < 0) {
            errors += "manifest entry for '$id' has a negative installSizeBytes"
        }
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
                errors += "manifest entry '${dto.id ?: "(no id)"}' has an invalid version '${dto.version}'"
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
                errors += "manifest lists plugin '${offer.id}' more than once"
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
 * pinned cert + SHA-256 (see `docs/plugin-architecture.md` § Security model).
 */
const val DEFAULT_PLUGIN_MANIFEST_URL: String =
    "https://plugin-updates.inkflow.app/v1/manifest.json"
