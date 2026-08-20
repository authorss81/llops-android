package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.services.ProjectMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 131: `metadata.json` (repo root) is kept in sync with the actual build
 * configuration — every identity/version/SDK/capability field is cross-checked
 * against the Gradle build files (`app/build.gradle.kts`, `settings.gradle.kts`,
 * `gradle/libs.versions.toml`) and the plugin framework (`PluginCapability.ALL`,
 * `PluginRegistry.defaultPlugins()`). A metadata field that drifts from the
 * build breaks the build.
 *
 * Also pins the `plugins/llm` build-script alignment: the module is a
 * downloadable plugin (NOT a dependency of `:app`), its id/class/engine/signing
 * env vars in `metadata.json` match `plugins/llm/build.gradle.kts`, and the base
 * APK never embeds the MediaPipe engine.
 *
 * Pure-JVM source/behavior tests (repo precedent: `B1Plat01ReleaseSigningTest`).
 */
class Phase131MetadataAlignmentTest {

    @Test
    fun `metadata json exists and validates cleanly`() {
        val metadata = metadata()
        val problems = metadata.validate()
        assertTrue(
            "metadata.json must validate cleanly (problems: $problems)",
            problems.isEmpty()
        )
    }

    @Test
    fun `metadata name and identities match the build files`() {
        val metadata = metadata()
        val settings = fileText("settings.gradle.kts")
        val appBuild = fileText("app/build.gradle.kts")

        assertTrue("rootProject.name must be InkFlow", settings.contains("rootProject.name = \"InkFlow\""))
        assertEquals("metadata.name must match rootProject.name", "InkFlow", metadata.name)

        assertTrue("app/build.gradle.kts must declare namespace com.authorss81.noteflow", appBuild.contains("namespace = \"com.authorss81.noteflow\""))
        assertEquals("metadata.namespace must match app/build.gradle.kts", "com.authorss81.noteflow", metadata.namespace)

        assertTrue("app/build.gradle.kts must declare the real applicationId", appBuild.contains("applicationId = \"com.aistudio.inkflow.app.bkxjrz\""))
        assertEquals("metadata.applicationId must match app/build.gradle.kts", "com.aistudio.inkflow.app.bkxjrz", metadata.applicationId)
    }

    @Test
    fun `metadata version matches the build files and env overrides`() {
        val metadata = metadata()
        val appBuild = fileText("app/build.gradle.kts")

        assertTrue("versionCode must be env-overridable via VERSION_CODE", appBuild.contains("VERSION_CODE"))
        assertTrue("versionName must be env-overridable via VERSION_NAME", appBuild.contains("VERSION_NAME"))
        assertTrue("versionCode default must be 2", appBuild.contains("?: 2"))
        assertTrue("versionName default must be 1.0.0", appBuild.contains("?: \"1.0.0\""))

        val version = metadata.version
        assertTrue("version must be present", version != null)
        assertEquals("metadata versionCode must match the 2 default", 2, version!!.versionCode)
        assertEquals("metadata versionName must match the 1.0.0 default", "1.0.0", version.versionName)
        assertEquals("metadata env override must name VERSION_CODE", "VERSION_CODE", version.envOverrides?.versionCode)
        assertEquals("metadata env override must name VERSION_NAME", "VERSION_NAME", version.envOverrides?.versionName)
    }

    @Test
    fun `metadata sdk levels match the build files`() {
        val metadata = metadata()
        val sdk = metadata.android
        assertTrue("android must be present", sdk != null)

        assertTrue("compileSdk must be 36", fileMatches("app/build.gradle.kts", Regex("""compileSdk\s*=\s*36\b""")))
        assertTrue("minSdk must be 26", fileMatches("app/build.gradle.kts", Regex("""minSdk\s*=\s*26\b""")))
        assertTrue("targetSdk must be 36", fileMatches("app/build.gradle.kts", Regex("""targetSdk\s*=\s*36\b""")))
        assertEquals(36, sdk!!.compileSdk)
        assertEquals(26, sdk.minSdk)
        assertEquals(36, sdk.targetSdk)
    }

    @Test
    fun `metadata build info matches the toolchain and settings`() {
        val metadata = metadata()
        val toml = fileText("gradle/libs.versions.toml")
        val settings = fileText("settings.gradle.kts")
        val build = metadata.build
        assertTrue("build must be present", build != null)

        assertTrue("toml must pin AGP 8.7.3", toml.contains("agp = \"8.7.3\""))
        assertTrue("toml must pin Kotlin 2.0.21", toml.contains("kotlin = \"2.0.21\""))
        assertTrue("toml must pin KSP 2.0.21-1.0.25", toml.contains("ksp = \"2.0.21-1.0.25\""))
        assertEquals("metadata agpVersion must match the catalog", "8.7.3", build!!.agpVersion)
        assertEquals("metadata kotlinVersion must match the catalog", "2.0.21", build.kotlinVersion)
        assertEquals("metadata kspVersion must match the catalog", "2.0.21-1.0.25", build.kspVersion)

        // JVM target + Gradle version (previously documented-only fields) now have
        // real build pins: app/build.gradle.kts forces JVM 17 and the CI workflows
        // pin system Gradle 8.13 (no wrapper).
        assertTrue("app/build.gradle.kts must force JVM 17", fileText("app/build.gradle.kts").contains("JvmTarget.JVM_17"))
        assertEquals("metadata jvmTarget must match app/build.gradle.kts", "17", build.jvmTarget)
        assertTrue("CI workflow must pin Gradle 8.13", Regex("gradle-version:\\s*\"8\\.13\"").containsMatchIn(fileText(".github/workflows/android.yml")))
        assertEquals("metadata gradleVersion must match the pinned CI Gradle", "8.13", build.gradleVersion)

        assertFalse(
            "metadata must document that the repo uses system Gradle (no wrapper)",
            build.usesGradleWrapper ?: true
        )

        // Module wiring: every included module must be listed.
        for (module in listOf("\":app\"", "\":plugin-sdk\"", "\":plugins:llm\"", "\":plugins:mlkit\"")) {
            assertTrue("settings.gradle.kts must include $module", settings.contains("include($module)"))
        }
        val expectedModules = listOf("app", "plugin-sdk", "plugins:llm", "plugins:mlkit")
        assertEquals("metadata build.modules must match settings.gradle.kts includes", expectedModules, build.modules)
    }

    @Test
    fun `metadata capability surface matches the plugin framework`() {
        val metadata = metadata()
        val caps = metadata.capabilities
        assertTrue("capabilities must be present", caps != null)

        // 1. Every key in every bucket is a real PluginCapability key.
        for (key in (caps!!.servedByCompileTimePlugins.orEmpty() +
            caps.servedByDownloadablePlugins.orEmpty() + caps.unserved.orEmpty())) {
            assertTrue(
                "capability key '$key' must exist in PluginCapability.ALL",
                PluginCapability.byKey(key) != null
            )
        }

        // 2. The union of the three buckets must cover EVERY framework capability (no gap).
        val listed = (caps.servedByCompileTimePlugins.orEmpty() +
            caps.servedByDownloadablePlugins.orEmpty() + caps.unserved.orEmpty()).distinct().sorted()
        val framework = PluginCapability.ALL.map { it.key }.sorted()
        assertEquals(
            "metadata capability buckets must cover PluginCapability.ALL exactly",
            framework,
            listed
        )

        // 3. The compile-time bucket must match what PluginRegistry.defaultPlugins() actually ships.
        val shipped = PluginRegistry.defaultPlugins()
            .flatMap { it.capabilities }
            .map { it.key }
            .distinct()
            .sorted()
        assertEquals(
            "metadata servedByCompileTimePlugins must match PluginRegistry.defaultPlugins()",
            shipped,
            caps.servedByCompileTimePlugins.orEmpty().distinct().sorted()
        )

        // 4. Assistant is served ONLY by the downloadable LLM plugin; FileTransfer
        //    is served by the compile-time LocalSend plugin (phase-173) — not unserved.
        assertTrue("assistant must be served by a downloadable plugin", "assistant" in caps.servedByDownloadablePlugins.orEmpty())
        assertTrue("assistant must NOT be a compile-time capability", "assistant" !in caps.servedByCompileTimePlugins.orEmpty())
        assertTrue("file_transfer must be served by a compile-time plugin (phase-173)", "file_transfer" in caps.servedByCompileTimePlugins.orEmpty())
        assertTrue("file_transfer must NOT be unserved anymore", "file_transfer" !in caps.unserved.orEmpty())
    }

    @Test
    fun `llm plugin metadata matches the plugin build script`() {
        val metadata = metadata()
        val llm = metadata.downloadablePlugins?.llm
        assertTrue("downloadablePlugins.llm must be present", llm != null)

        val llmBuild = fileText("plugins/llm/build.gradle.kts")
        val settings = fileText("settings.gradle.kts")
        val toml = fileText("gradle/libs.versions.toml")
        val appBuild = fileText("app/build.gradle.kts")

        assertEquals("llm module must be declared in settings.gradle.kts", ":plugins:llm", llm!!.module)
        assertTrue("settings.gradle.kts must include :plugins:llm", settings.contains("include(\":plugins:llm\")"))

        // id + class must match the descriptor the Phase-23 loader reads.
        assertTrue("llm build must emit the plugin id in the descriptor", llmBuild.contains("plugin.id=com.authorss81.noteflow.plugins.llm"))
        assertEquals("llm id must match the descriptor", "com.authorss81.noteflow.plugins.llm", llm.id)
        assertTrue("llm build must emit the plugin class in the descriptor", llmBuild.contains("plugin.class=com.authorss81.noteflow.llm.LocalLlmPlugin"))
        assertEquals("llm className must match the descriptor", "com.authorss81.noteflow.llm.LocalLlmPlugin", llm.className)

        // Engine: MediaPipe tasks-genai, version from the catalog.
        assertTrue("llm build must depend on the catalog tasks-genai artifact", llmBuild.contains("libs.mediapipe.tasks.genai"))
        assertTrue("toml must pin tasks-genai 0.10.25", toml.contains("mediapipeTasksGenai = \"0.10.25\""))
        assertEquals("llm engine name must be MediaPipe tasks-genai", "MediaPipe tasks-genai", llm.engine?.name)
        assertEquals("llm engine version must match the catalog", "0.10.25", llm.engine?.version)

        // Base-APK rule: :app must NOT embed the engine.
        assertFalse("the base app must never depend on the tasks-genai engine", appBuild.contains("implementation(libs.mediapipe.tasks.genai)"))
        assertFalse("the base app must never declare the raw tasks-genai coordinate", appBuild.contains("com.google.mediapipe"))
        assertEquals("llm inBaseApk must be false (downloadable-plugin rule)", false, llm.inBaseApk)

        // Signing env vars (B2-DEPS-04) must be documented in the metadata.
        assertTrue("llm build must require PLUGIN_SIGNING_KEYSTORE_B64", llmBuild.contains("PLUGIN_SIGNING_KEYSTORE_B64"))
        assertTrue("llm build must require PLUGIN_SIGNING_STORE_PASS", llmBuild.contains("PLUGIN_SIGNING_STORE_PASS"))
        assertTrue("metadata must list PLUGIN_SIGNING_KEYSTORE_B64", "PLUGIN_SIGNING_KEYSTORE_B64" in llm.signingEnvVars.orEmpty())
        assertTrue("metadata must list PLUGIN_SIGNING_STORE_PASS", "PLUGIN_SIGNING_STORE_PASS" in llm.signingEnvVars.orEmpty())

        // Pinned state: null (fail-closed — no release pin committed yet) matches the generated seed.
        assertEquals("llm pinnedReleaseVersion must be null (fail closed)", null, llm.pinnedReleaseVersion)
        val seed = fileText("app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedLlmPluginPin.kt")
        assertTrue("GeneratedLlmPluginPin must still be the fail-closed null seed", seed.contains("llmPluginSeedRelease: PinnedPluginRelease? = null"))
    }

    @Test
    fun `mlkit plugin metadata matches the plugin build script`() {
        val metadata = metadata()
        val mlkit = metadata.downloadablePlugins?.mlkit
        assertTrue("downloadablePlugins.mlkit must be present", mlkit != null)

        val mlkitBuild = fileText("plugins/mlkit/build.gradle.kts")
        val settings = fileText("settings.gradle.kts")
        val toml = fileText("gradle/libs.versions.toml")
        val appBuild = fileText("app/build.gradle.kts")

        assertEquals("mlkit module must be declared in settings.gradle.kts", ":plugins:mlkit", mlkit!!.module)
        assertTrue("settings.gradle.kts must include :plugins:mlkit", settings.contains("include(\":plugins:mlkit\")"))

        // id + class must match the descriptor the Phase-23 loader reads.
        assertTrue("mlkit build must emit the plugin id in the descriptor", mlkitBuild.contains("plugin.id=com.authorss81.noteflow.plugins.mlkit"))
        assertEquals("mlkit id must match the descriptor", "com.authorss81.noteflow.plugins.mlkit", mlkit.id)
        assertTrue("mlkit build must emit the plugin class in the descriptor", mlkitBuild.contains("plugin.class=com.authorss81.noteflow.mlkit.OnDeviceMlKitPlugin"))
        assertEquals("mlkit className must match the descriptor", "com.authorss81.noteflow.mlkit.OnDeviceMlKitPlugin", mlkit.className)

        // Engines: ML Kit text-recognition + translate, versions from the catalog.
        assertTrue("mlkit build must depend on the catalog text-recognition artifact", mlkitBuild.contains("libs.mlkit.text.recognition"))
        assertTrue("mlkit build must depend on the catalog translate artifact", mlkitBuild.contains("libs.mlkit.translate"))
        assertTrue("toml must pin mlkit text-recognition 16.0.1", toml.contains("mlkitTextRecognition = \"16.0.1\""))
        assertTrue("toml must pin mlkit translate 17.0.3", toml.contains("mlkitTranslate = \"17.0.3\""))

        // The two capabilities it serves.
        assertEquals("mlkit capabilities must be [ocr, translation]", listOf("ocr", "translation"), mlkit.capabilities)

        // Base-APK rule: :app must NEVER embed the ML Kit engines.
        assertFalse("the base app must never depend on the text-recognition engine", appBuild.contains("implementation(libs.mlkit.text.recognition)"))
        assertFalse("the base app must never depend on the translate engine", appBuild.contains("implementation(libs.mlkit.translate)"))
        assertEquals("mlkit inBaseApk must be false (downloadable-plugin rule)", false, mlkit.inBaseApk)

        // Signing env vars (B2-DEPS-04) must be documented in the metadata.
        assertTrue("mlkit build must require PLUGIN_SIGNING_KEYSTORE_B64", mlkitBuild.contains("PLUGIN_SIGNING_KEYSTORE_B64"))
        assertTrue("mlkit build must require PLUGIN_SIGNING_STORE_PASS", mlkitBuild.contains("PLUGIN_SIGNING_STORE_PASS"))
        assertTrue("metadata must list PLUGIN_SIGNING_KEYSTORE_B64", "PLUGIN_SIGNING_KEYSTORE_B64" in mlkit.signingEnvVars.orEmpty())
        assertTrue("metadata must list PLUGIN_SIGNING_STORE_PASS", "PLUGIN_SIGNING_STORE_PASS" in mlkit.signingEnvVars.orEmpty())

        // Pinned state: null (fail-closed — no release pin committed yet) matches the generated seed.
        assertEquals("mlkit pinnedReleaseVersion must be null (fail closed)", null, mlkit.pinnedReleaseVersion)
        val seed = fileText("app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedMlKitPluginPin.kt")
        assertTrue("GeneratedMlKitPluginPin must still be the fail-closed null seed", seed.contains("mlKitPluginSeedRelease: PinnedPluginRelease? = null"))
    }

    // --- Pure-JVM parser/validator behavior ---------------------------------

    @Test
    fun `parser rejects non-JSON input`() {
        try {
            ProjectMetadata.parse("not json {")
            throw AssertionError("parse must reject malformed JSON")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `validator reports missing fields fail-closed`() {
        val problems = ProjectMetadata.parse(
            """{"name":"InkFlow"}"""
        ).validate()
        assertTrue(
            "an empty metadata must produce structural problems",
            problems.isNotEmpty()
        )
        assertTrue(
            "missing capabilities must be reported",
            problems.any { it.startsWith("capabilities:") }
        )
        assertTrue(
            "missing downloadables must be reported",
            problems.any { it.startsWith("downloadablePlugins.llm:") }
        )
    }

    @Test
    fun `validator rejects a base-apk-embedded llm engine`() {
        val problems = ProjectMetadata.parse(
            metadataJson(inBaseApk = true)
        ).validate()
        assertTrue(
            "inBaseApk=true must be rejected (base-APK-size hard rule)",
            problems.any { it.contains("inBaseApk") }
        )
    }

    @Test
    fun `validator rejects unknown capability keys and framework gaps`() {
        val problems = ProjectMetadata.parse(
            metadataJson(
                inBaseApk = false,
                servedByCompileTimePlugins = listOf("ocr"),
                servedByDownloadablePlugins = listOf("assistant"),
                unserved = listOf("file_transfer", "made_up_capability")
            )
        ).validate()
        assertTrue(
            "an unknown capability key must be rejected",
            problems.any { it.contains("made_up_capability") }
        )
        assertTrue(
            "a framework capability missing from the buckets must be reported (no gap)",
            problems.any { it.contains("missing from metadata") }
        )
    }

    private fun metadata(): ProjectMetadata {
        val file = File(repoRoot(), "metadata.json")
        assertTrue("repo root must contain metadata.json", file.isFile)
        val metadata = ProjectMetadata.load(file)
        return metadata
    }

    private fun fileText(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("repo root must contain $relative", file.isFile)
        return file.readText()
    }

    private fun fileMatches(relative: String, pattern: Regex): Boolean =
        pattern.containsMatchIn(fileText(relative))

    /**
     * A structurally complete metadata JSON used by the validator negative tests,
     * parameterized so the same fixture isn't copy/pasted three times.
     */
    private fun metadataJson(
        inBaseApk: Boolean,
        servedByCompileTimePlugins: List<String> = emptyList(),
        servedByDownloadablePlugins: List<String> = listOf("assistant"),
        unserved: List<String> = listOf("file_transfer")
    ): String {
        fun jsonArr(keys: List<String>): String =
            keys.joinToString(", ", "[", "]") { "\"$it\"" }
        return """
            {
              "name": "InkFlow",
              "namespace": "com.authorss81.noteflow",
              "applicationId": "com.aistudio.inkflow.app.bkxjrz",
              "version": {"versionCode": 2, "versionName": "1.0.0", "envOverrides": {"versionCode": "VERSION_CODE", "versionName": "VERSION_NAME"}},
              "android": {"compileSdk": 36, "minSdk": 26, "targetSdk": 36},
              "build": {"gradleVersion": "8.13", "usesGradleWrapper": false, "agpVersion": "8.7.3", "kotlinVersion": "2.0.21", "kspVersion": "2.0.21-1.0.25", "jvmTarget": "17", "modules": ["app", "plugin-sdk", "plugins:llm"]},
              "capabilities": {
                "servedByCompileTimePlugins": ${jsonArr(servedByCompileTimePlugins)},
                "servedByDownloadablePlugins": ${jsonArr(servedByDownloadablePlugins)},
                "unserved": ${jsonArr(unserved)}
              },
              "downloadablePlugins": {
                "llm": {
                  "id": "com.authorss81.noteflow.plugins.llm",
                  "className": "com.authorss81.noteflow.llm.LocalLlmPlugin",
                  "module": ":plugins:llm",
                  "capability": "assistant",
                  "inBaseApk": $inBaseApk,
                  "engine": {"name": "MediaPipe tasks-genai", "version": "0.10.25"},
                  "pinnedReleaseVersion": null,
                  "signingEnvVars": ["PLUGIN_SIGNING_KEYSTORE_B64", "PLUGIN_SIGNING_STORE_PASS"]
                }
              }
            }
        """.trimIndent()
    }

    companion object {
        private fun repoRoot(): File {
            val cwd = File(System.getProperty("user.dir") ?: ".")
            var dir = cwd
            repeat(8) {
                if (File(dir, "gradle/libs.versions.toml").isFile &&
                    File(dir, "app").isDirectory
                ) {
                    return dir
                }
                dir = dir.parentFile ?: return cwd
            }
            return cwd
        }
    }
}
