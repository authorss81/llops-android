package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File

/**
 * Phase 131: pure-JVM parse + validation of the repo-root `metadata.json`
 * project/app metadata file.
 *
 * `metadata.json` is the single committed source of truth for the project's
 * identity, version, SDK/build configuration and plugin-capability surface. It
 * is deliberately parsed with Gson (already a base-app dependency) on the plain
 * JVM so `Phase131MetadataAlignmentTest` can cross-check every field against the
 * real Gradle configuration (`app/build.gradle.kts`, `settings.gradle.kts`,
 * `gradle/libs.versions.toml`) and the plugin framework (`PluginCapability.ALL`)
 * — any drift between the metadata file and the build files fails the test.
 *
 * All model fields are nullable so a missing/typo'd field is reported by
 * [validate] (fail-closed) instead of silently deserializing to a default.
 *
 * Pure JVM (`java.io` + Gson), API 26+ floor, no Android imports. Lives in the
 * TEST source set (phase-131 review fix): it is never invoked by the app at
 * runtime, so it must not ship in the base APK (base-APK-size hard rule).
 */
data class ProjectMetadata(
    val name: String? = null,
    val namespace: String? = null,
    val applicationId: String? = null,
    val version: Version? = null,
    val android: AndroidSdk? = null,
    val build: BuildInfo? = null,
    val capabilities: Capabilities? = null,
    val downloadablePlugins: DownloadablePlugins? = null
) {

    data class Version(
        val versionCode: Int? = null,
        val versionName: String? = null,
        val envOverrides: EnvOverrides? = null
    ) {
        data class EnvOverrides(
            val versionCode: String? = null,
            val versionName: String? = null
        )
    }

    data class AndroidSdk(
        val compileSdk: Int? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null
    )

    data class BuildInfo(
        val gradleVersion: String? = null,
        val usesGradleWrapper: Boolean? = null,
        val agpVersion: String? = null,
        val kotlinVersion: String? = null,
        val kspVersion: String? = null,
        val jvmTarget: String? = null,
        val modules: List<String>? = null
    )

    data class Capabilities(
        val servedByCompileTimePlugins: List<String>? = null,
        val servedByDownloadablePlugins: List<String>? = null,
        val unserved: List<String>? = null
    )

    data class DownloadablePlugins(val llm: LlmPlugin? = null) {
        data class LlmPlugin(
            val id: String? = null,
            val className: String? = null,
            val module: String? = null,
            val capability: String? = null,
            val inBaseApk: Boolean? = null,
            val engine: Engine? = null,
            val pinnedReleaseVersion: String? = null,
            val signingEnvVars: List<String>? = null
        ) {
            data class Engine(val name: String? = null, val version: String? = null)
        }
    }

    /**
     * Structural + framework validation of this metadata. Returns every problem
     * found (empty = valid). Framework keys are cross-checked against
     * [PluginCapability.ALL], so a capability added to the SDK without being
     * reflected here (or a stale key that no longer exists) is reported.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val field = { path: String, ok: Boolean, problem: String -> if (!ok) errors += "$path: $problem" }

        field("name", !name.isNullOrBlank(), "missing/blank")

        field("namespace", !namespace.isNullOrBlank(), "missing/blank")
        if (!namespace.isNullOrBlank() && !namespace.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            errors += "namespace: not a dotted package name: '$namespace'"
        }

        field("applicationId", !applicationId.isNullOrBlank(), "missing/blank")
        if (!applicationId.isNullOrBlank() && !applicationId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            errors += "applicationId: not a dotted package name: '$applicationId'"
        }

        val v = version
        field("version", v != null, "missing")
        if (v != null) {
            field("version.versionCode", v.versionCode != null && v.versionCode!! >= 1, "must be >= 1")
            field("version.versionName", !v.versionName.isNullOrBlank(), "missing/blank")
            if (!v.versionName.isNullOrBlank() &&
                !v.versionName.matches(Regex("[0-9]+(\\.[0-9]+){1,2}"))
            ) {
                errors += "version.versionName: not 'major.minor' or 'major.minor.patch': '${v.versionName}'"
            }
            field(
                "version.envOverrides.versionCode",
                v.envOverrides?.versionCode != null,
                "missing (must name the VERSION_CODE env var the build reads)"
            )
            field(
                "version.envOverrides.versionName",
                v.envOverrides?.versionName != null,
                "missing (must name the VERSION_NAME env var the build reads)"
            )
        }

        val sdk = android
        field("android", sdk != null, "missing")
        if (sdk != null) {
            field("android.compileSdk", sdk.compileSdk != null, "missing")
            if (sdk.compileSdk != null && sdk.compileSdk < 26) {
                errors += "android.compileSdk: must be >= 26, got ${sdk.compileSdk}"
            }
            field("android.minSdk", sdk.minSdk != null, "missing")
            if (sdk.minSdk != null && sdk.minSdk < 26) {
                errors += "android.minSdk: must be >= 26 (API 26+ floor), got ${sdk.minSdk}"
            }
            field("android.targetSdk", sdk.targetSdk != null, "missing")
            if (sdk.targetSdk != null && sdk.targetSdk < 26) {
                errors += "android.targetSdk: must be >= 26, got ${sdk.targetSdk}"
            }
            if (sdk.minSdk != null && sdk.targetSdk != null && sdk.minSdk > sdk.targetSdk) {
                errors += "android: minSdk (${sdk.minSdk}) must be <= targetSdk (${sdk.targetSdk})"
            }
            if (sdk.targetSdk != null && sdk.compileSdk != null && sdk.targetSdk > sdk.compileSdk) {
                errors += "android: targetSdk (${sdk.targetSdk}) must be <= compileSdk (${sdk.compileSdk})"
            }
        }

        val b = build
        field("build", b != null, "missing")
        if (b != null) {
            field("build.gradleVersion", !b.gradleVersion.isNullOrBlank(), "missing/blank")
            field("build.usesGradleWrapper", b.usesGradleWrapper == false, "must be false (no Gradle wrapper in this repo — system Gradle only)")
            field("build.agpVersion", !b.agpVersion.isNullOrBlank(), "missing/blank")
            if (!b.agpVersion.isNullOrBlank() && !b.agpVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                errors += "build.agpVersion: not 'major.minor.patch': '${b.agpVersion}'"
            }
            field("build.kotlinVersion", !b.kotlinVersion.isNullOrBlank(), "missing/blank")
            field("build.kspVersion", !b.kspVersion.isNullOrBlank(), "missing/blank")
            field("build.jvmTarget", !b.jvmTarget.isNullOrBlank(), "missing/blank")
            field("build.modules", b.modules != null && b.modules!!.isNotEmpty(), "missing/empty")
            if (b.modules != null) {
                val dupes = b.modules.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                field("build.modules", dupes.isEmpty(), "duplicate modules: $dupes")
            }
        }

        val caps = capabilities
        field("capabilities", caps != null, "missing")
        if (caps != null) {
            val known = PluginCapability.ALL.map { it.key }.toSet()
            val buckets = listOf(
                "servedByCompileTimePlugins" to caps.servedByCompileTimePlugins,
                "servedByDownloadablePlugins" to caps.servedByDownloadablePlugins,
                "unserved" to caps.unserved
            )
            for ((bucket, list) in buckets) {
                field("capabilities.$bucket", list != null, "missing")
                if (list != null) {
                    val unknown = list.filterNot { it in known }
                    field("capabilities.$bucket", unknown.isEmpty(), "unknown capability key(s) not in PluginCapability.ALL: $unknown")
                    val dupes = list.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                    field("capabilities.$bucket", dupes.isEmpty(), "duplicate keys: $dupes")
                }
            }
            val listed = (caps.servedByCompileTimePlugins.orEmpty() +
                caps.servedByDownloadablePlugins.orEmpty() +
                caps.unserved.orEmpty()).distinct().toSet()
            val missingFromFramework = known - listed
            val extraNotInFramework = listed - known
            field(
                "capabilities",
                missingFromFramework.isEmpty(),
                "capability keys missing from metadata (must cover PluginCapability.ALL): $missingFromFramework"
            )
            field(
                "capabilities",
                extraNotInFramework.isEmpty(),
                "capability keys not in PluginCapability.ALL: $extraNotInFramework"
            )
        }

        val llm = downloadablePlugins?.llm
        field("downloadablePlugins.llm", llm != null, "missing")
        if (llm != null) {
            field("downloadablePlugins.llm.id", !llm.id.isNullOrBlank(), "missing/blank")
            field("downloadablePlugins.llm.className", !llm.className.isNullOrBlank(), "missing/blank")
            field("downloadablePlugins.llm.module", !llm.module.isNullOrBlank(), "missing/blank")
            field("downloadablePlugins.llm.capability", !llm.capability.isNullOrBlank(), "missing/blank")
            field("downloadablePlugins.llm.inBaseApk", llm.inBaseApk != null, "missing")
            if (llm.inBaseApk == true) {
                errors += "downloadablePlugins.llm.inBaseApk: must be false (base-APK-size hard rule — the LLM is a downloadable plugin)"
            }
            if (!llm.capability.isNullOrBlank() && llm.capability != "assistant") {
                errors += "downloadablePlugins.llm.capability: must be 'assistant', got '${llm.capability}'"
            }
            field("downloadablePlugins.llm.engine", llm.engine != null, "missing")
            if (llm.engine != null) {
                field("downloadablePlugins.llm.engine.name", !llm.engine.name.isNullOrBlank(), "missing/blank")
                field("downloadablePlugins.llm.engine.version", !llm.engine.version.isNullOrBlank(), "missing/blank")
            }
            field(
                "downloadablePlugins.llm.signingEnvVars",
                llm.signingEnvVars != null &&
                    llm.signingEnvVars!!.contains("PLUGIN_SIGNING_KEYSTORE_B64") &&
                    llm.signingEnvVars.contains("PLUGIN_SIGNING_STORE_PASS"),
                "must list PLUGIN_SIGNING_KEYSTORE_B64 + PLUGIN_SIGNING_STORE_PASS (B2-DEPS-04)"
            )
        }

        return errors
    }

    companion object {
        private val gson = Gson()

        /** Loads + parses [file] as project metadata. */
        fun load(file: File): ProjectMetadata = parse(file.readText())

        /** Parses [json]; throws [IllegalArgumentException] when it is not valid JSON. */
        fun parse(json: String): ProjectMetadata {
            val parsed = try {
                gson.fromJson(json, ProjectMetadata::class.java)
            } catch (e: JsonParseException) {
                throw IllegalArgumentException("metadata.json is not valid JSON", e)
            }
            if (parsed == null) {
                throw IllegalArgumentException("metadata.json is not a JSON object")
            }
            return parsed
        }
    }
}
