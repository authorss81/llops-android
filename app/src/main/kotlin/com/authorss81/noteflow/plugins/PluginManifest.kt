package com.authorss81.noteflow.plugins

/**
 * A strictly-ordered semantic version (`Major.Minor.Patch`).
 *
 * Used for plugin versioning and for deterministic capability-conflict
 * arbitration (the higher version wins). Parsing is strict: exactly three
 * non-negative integer components, otherwise `null` (a plugin with an
 * unparseable version is rejected by [PluginManifestValidator]).
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Parse `"Major.Minor.Patch"`. Returns null when not strictly valid. */
        fun parse(value: String): SemanticVersion? {
            val parts = value.trim().split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            return SemanticVersion(nums[0], nums[1], nums[2])
        }
    }
}

/**
 * A device permission a plugin may need. Permissions are declared in the
 * manifest for user visibility; whether the permission is actually held is the
 * plugin's responsibility when computing [NoteflowPlugin.availability] (e.g.
 * checking [android.content.pm.PackageManager.checkSelfPermission] on the real
 * `Context`). A revoked permission therefore surfaces as
 * [PluginAvailability.Unavailable] and the registry records the plugin as
 * `UNAVAILABLE` — never a crash and never stale.
 */
sealed class PluginPermission(val key: String, val label: String) {
    data object Internet : PluginPermission("internet", "Internet access")

    /** Microphone access — only used while the user is actively dictating. */
    data object RecordAudio : PluginPermission("record_audio", "Microphone (while dictating)")
}

/**
 * Machine-readable, upgradeable description of a plugin.
 *
 * The manifest is the single source of truth for a plugin's identity
 * ([NoteflowPlugin.id], [NoteflowPlugin.name], [NoteflowPlugin.description],
 * [NoteflowPlugin.version], [NoteflowPlugin.capabilities] are all derived from
 * it). Bumping [version] is the documented signal for a plugin to run its own
 * settings migration (see docs/PLUGIN_SDK.md).
 *
 * @param id globally-unique reverse-DNS id; registry rejects duplicates.
 * @param version semantic `Major.Minor.Patch`.
 * @param minSupportedApi minimum `Build.VERSION.SDK_INT` this plugin needs;
 *   a plugin requiring a higher API than the current device is rejected.
 * @param capabilities the [PluginCapability]s this plugin can serve.
 * @param permissions permissions the plugin may need (declared for visibility).
 * @param dependencies ids of OTHER plugins this plugin requires to be installed
 *   and enabled before it can serve.
 * @param requiresCapabilities capabilities that must be served by some other
 *   installed, enabled and available plugin before this plugin can serve.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: SemanticVersion,
    val minSupportedApi: Int,
    val description: String,
    val capabilities: Set<PluginCapability>,
    val permissions: Set<PluginPermission> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val requiresCapabilities: Set<PluginCapability> = emptySet()
)

/** Outcome of single-manifest validation. */
sealed class ManifestValidation {
    data object Valid : ManifestValidation()
    data class Invalid(val errors: List<String>) : ManifestValidation()
}

/** Validates a single [PluginManifest] against fixed schema rules. */
object PluginManifestValidator {

    /**
     * @param currentApiLevel the device's `Build.VERSION.SDK_INT` (injected so
     *   the validator stays JVM-unit-testable).
     */
    fun validate(manifest: PluginManifest, currentApiLevel: Int): ManifestValidation {
        val errors = mutableListOf<String>()
        if (manifest.id.isBlank()) errors.add("plugin id must not be blank")
        if (manifest.name.isBlank()) errors.add("plugin name must not be blank")
        if (manifest.description.isBlank()) errors.add("plugin description must not be blank")
        if (manifest.version.major < 0 || manifest.version.minor < 0 || manifest.version.patch < 0) {
            errors.add("plugin version '${manifest.version}' is not a valid Major.Minor.Patch version")
        }
        if (manifest.minSupportedApi < 1) {
            errors.add("minSupportedApi must be >= 1")
        }
        if (manifest.minSupportedApi > currentApiLevel) {
            errors.add("requires API ${manifest.minSupportedApi} but this device is API $currentApiLevel")
        }
        if (manifest.capabilities.isEmpty()) {
            errors.add("must declare at least one capability")
        }
        if (manifest.id in manifest.dependencies) {
            errors.add("must not depend on itself ('${manifest.id}')")
        }
        return if (errors.isEmpty()) ManifestValidation.Valid else ManifestValidation.Invalid(errors)
    }
}