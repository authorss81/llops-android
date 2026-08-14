package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission

/**
 * Where a plugin's code ships (Phase 22 hybrid model).
 *
 * - [BUNDLED] — compiled into the base APK (lightweight: pure-JVM or
 *   small-keyless-HTTP features that cost only a few KB). Never downloaded,
 *   never loaded dynamically.
 * - [REMOTE] — downloaded on explicit user consent, then signature-verified
 *   before ANY load (heavy/native features such as camera OCR/QR, large ML
 *   engines or the local LLM). A remote entry MUST carry [PluginEntry.downloadUrl],
 *   [PluginEntry.sha256] and [PluginEntry.pinnedCertHash].
 */
enum class PluginEntrySource(val label: String) {
    BUNDLED("bundled"),
    REMOTE("remote")
}

/**
 * THE unified catalog-entry model of the hybrid plugin architecture (Phase 22).
 *
 * One type covers BOTH compile-time (bundled) and downloadable (remote)
 * plugins, so the Phase-21 store, the Phase-23 runtime and the Phase-24 update
 * model all speak the same vocabulary. See `docs/plugin-architecture.md`.
 *
 * Invariants:
 * - [source] == REMOTE ⇔ [downloadUrl], [sha256] and [pinnedCertHash] are all
 *   non-blank ([validationErrors] enforces this).
 * - [source] == BUNDLED ⇒ all three are null (compiled in — nothing to fetch
 *   or verify).
 *
 * @param id globally-unique plugin id (matches the manifest id for built-ins).
 * @param name user-facing plugin name.
 * @param description one-line user-facing description (store listing).
 * @param version semantic version; the seam type is [PluginVersion] (compare +
 *   bump) so Phase 24 can reason about updates.
 * @param capabilities the [PluginCapability]s this plugin can serve.
 * @param category store category, e.g. "Vision", "AI", "Text".
 * @param permissions permissions the plugin may need (declared for visibility —
 *   NEVER granted to the plugin; the capability facade controls actual access).
 * @param downloadUrl HTTPS URL of the signed artifact. Null for built-ins.
 * @param installSizeBytes expected on-device size after install (models,
 *   native libs). Null when unknown or negligible.
 * @param updateChannel per-plugin update channel ("stable" by default; Phase 24
 *   uses it to pick which manifest stream offers updates).
 * @param sha256 hex SHA-256 of the artifact, verified before any load.
 * @param pinnedCertHash pinned TLS certificate hash of the download host,
 *   verified BEFORE any bytes are trusted.
 * @param source [PluginEntrySource.BUNDLED] or [PluginEntrySource.REMOTE].
 */
data class PluginEntry(
    val id: String,
    val name: String,
    val description: String,
    val version: PluginVersion,
    val capabilities: Set<PluginCapability>,
    val category: String,
    val permissions: Set<PluginPermission> = emptySet(),
    val downloadUrl: String? = null,
    val installSizeBytes: Long? = null,
    val updateChannel: String = DEFAULT_CHANNEL,
    val sha256: String? = null,
    val pinnedCertHash: String? = null,
    val source: PluginEntrySource
) {

    /** Convenience alias: is this a downloadable (verified) plugin? */
    val isDownloadable: Boolean get() = source == PluginEntrySource.REMOTE

    /** Convenience alias: does this plugin ship compiled in the base APK? */
    val isBundled: Boolean get() = source == PluginEntrySource.BUNDLED

    /**
     * Structural validation of the entry's own invariants (cross-field rules
     * beyond the plain "is it non-blank" checks). Returns user-facing error
     * strings, or an empty list when the entry is well-formed. Never throws.
     */
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (id.isBlank()) errors += "id must not be blank"
        if (name.isBlank()) errors += "name must not be blank"
        if (description.isBlank()) errors += "description must not be blank"
        if (version < PluginVersion.ZERO) errors += "version '${version}' is not a valid Major.Minor.Patch version"
        if (capabilities.isEmpty()) errors += "must declare at least one capability"
        if (category.isBlank()) errors += "category must not be blank"
        if (updateChannel.isBlank()) errors += "updateChannel must not be blank"
        when (source) {
            PluginEntrySource.REMOTE -> {
                if (downloadUrl.isNullOrBlank()) errors += "remote entry must carry a downloadUrl"
                if (!downloadUrl.isNullOrBlank() && !downloadUrl.startsWith("https://")) {
                    errors += "downloadUrl must be HTTPS (got '$downloadUrl')"
                }
                if (sha256.isNullOrBlank()) errors += "remote entry must carry a sha256"
                if (pinnedCertHash.isNullOrBlank()) errors += "remote entry must carry a pinnedCertHash"
            }
            PluginEntrySource.BUNDLED -> {
                if (downloadUrl != null) errors += "bundled entry must not carry a downloadUrl"
                if (sha256 != null) errors += "bundled entry must not carry a sha256"
                if (pinnedCertHash != null) errors += "bundled entry must not carry a pinnedCertHash"
            }
        }
        return errors
    }

    /** Whether the entry satisfies all [validationErrors] checks. */
    fun isValid(): Boolean = validationErrors().isEmpty()

    companion object {
        /** The default per-plugin update channel. */
        const val DEFAULT_CHANNEL = "stable"
    }
}