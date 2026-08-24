package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 199 review fixes — pure-JVM source pins for the release-shrink
 * toolchain shipped by phase-199 (baseline profiles + R8 fullMode +
 * shrinkResources), guarding exactly the defects the 2026-08-24 review found:
 *
 *  1. `android.enableR8.fullMode=true` + `isShrinkResources` stay enabled, and
 *  2. the AGP compileArtProfile crash stopgap is GUARDED (auto-lifts only when
 *     a real baseline profile is committed), not deleted outright;
 *  3. the `:plugin-sdk` consumer rules match ONLY the classes the SDK actually
 *     ships — consumer ProGuard rules apply GLOBALLY in the consuming build,
 *     so the original `plugins.**` wildcard also pinned the host app's own
 *     plugins.* subpackages (size + obfuscation loss, redundant keeps);
 *  4. every Gson-reflective source file is covered by a fullMode-safe keep
 *     rule — full mode turns off R8's missing-keep inference, so an unmapped
 *     Gson DTO would break release-only, invisibly to unit tests.
 */
class Phase199ReleaseShrinkTest {

    // --- 1. full mode + resource shrinking stay on ----------------------------

    @Test
    fun `r8 fullMode and shrinkResources remain enabled`() {
        assertTrue(
            "android.enableR8.fullMode must stay set (PERF 2.3)",
            read("gradle.properties").contains("android.enableR8.fullMode=true")
        )
        val appGradle = read("app/build.gradle.kts")
        assertTrue(
            "release build type must keep isShrinkResources = true (PERF 2.3)",
            appGradle.contains("isShrinkResources = true")
        )
        assertTrue(
            "release build type must keep minification on (shrinkResources requires it)",
            appGradle.contains("isMinifyEnabled = true")
        )
    }

    // --- 2. guarded ArtProfile stopgap ----------------------------------------

    @Test
    fun `compileArtProfile disable is guarded by committed-profile presence`() {
        val appGradle = read("app/build.gradle.kts")
        assertTrue(
            "the guard val must exist (phase-199 review finding 2)",
            appGradle.contains("val hasCommittedBaselineProfiles")
        )
        // The disable condition must be gated on the guard — an unconditional
        // restore would silently skip compiling a real committed profile, and
        // deleting the stopgap again would re-expose CI to the AGP 8.7.3
        // "String index out of range: 62" crash while no profile exists.
        assertTrue(
            "ArtProfile tasks must be disabled ONLY when no baseline profile is committed",
            appGradle.contains(Regex(
                "if \\(!hasCommittedBaselineProfiles &&\\s*\\n\\s*name\\.startsWith\\(\"compile\"\\) &&\\s*\\n\\s*name\\.endsWith\\(\"ArtProfile\"\\)"
            ))
        )
    }

    // --- 3. Gson coverage under R8 full mode -----------------------------------

    @Test
    fun `every Gson-reflective source file maps to a fullMode keep rule`() {
        val proguard = read("app/proguard-rules.pro")

        gsonUsageFiles().forEach { rel ->
            val requiredRule = gsonRuleFor(rel)
            if (requiredRule != null) {
                assertTrue(
                    "$rel uses Gson reflectively but no fullMode keep rule covers it — " +
                        "add a scoped rule to app/proguard-rules.pro AND map it here",
                    proguard.contains(requiredRule)
                )
            }
        }
    }

    @Test
    fun `gson usage discovery is exhaustive - new Gson files must extend this test`() {
        val discovered = gsonUsageFiles().toSortedSet()
        assertEquals(
            "A new Gson-using file appeared under app/src/main/kotlin — under R8 full mode " +
                "its wire DTOs need a keep rule in app/proguard-rules.pro plus a mapping in " +
                "gsonRuleFor(). Extend Phase199ReleaseShrinkTest in the same commit.",
            EXPECTED_GSON_FILES.sorted(), discovered.toList()
        )
    }

    /**
     * file path (repo-relative, forward slashes) -> keep-rule fragment that
     * MUST exist in app/proguard-rules.pro. `null` = verified no reflective
     * DTO (rule not needed) — the file stays in EXPECTED_GSON_FILES so the
     * discovery test still forces re-review if it ever gains one.
     */
    private fun gsonRuleFor(rel: String): String? = when {
        rel == "app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt" ->
            // Gson round-trips List<Stroke> (com.authorss81.noteflow.data.model.Stroke).
            "-keepclassmembers class com.authorss81.noteflow.data.model.** { *; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryCore.kt" ->
            "-keepclassmembers class com.authorss81.noteflow.plugins.dictionary.** { <fields>; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherCore.kt" ->
            "-keepclassmembers class com.authorss81.noteflow.plugins.weather.** { <fields>; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt" ->
            "-keepclassmembers class com.authorss81.noteflow.plugins.websearch.** { <fields>; }"
        rel.startsWith("app/src/main/kotlin/com/authorss81/noteflow/services/localsend/") ->
            "-keepclassmembers class com.authorss81.noteflow.services.localsend.** { <fields>; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt" ->
            // ManifestDto / PluginOfferDto are nested in PluginManifestParser.
            "-keepclassmembers class com.authorss81.noteflow.plugins.runtime.PluginManifestParser\$* { <fields>; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginEntryStore.kt" ->
            "-keep class com.authorss81.noteflow.plugins.runtime.PluginEntryCodec\$PluginEntryDto { *; }"
        rel == "app/src/main/kotlin/com/authorss81/noteflow/services/BrushPresetFileCodec.kt" ->
            // Builds its JSON via explicit JsonObject calls — no reflective DTO,
            // so no keep needed (verified phase-199; re-review if this changes).
            null
        else -> error("unmapped Gson file '$rel' — extend gsonRuleFor()")
    }

    // --- 4. plugin-sdk consumer rules are exactly-scoped ------------------------

    @Test
    fun `consumer rules keep the sdk surface and nothing broader`() {
        // Assert against ACTIVE RULES only — comments may legitimately mention
        // the banned wildcard when documenting why it must never return.
        val rules = read("plugin-sdk/consumer-rules.pro")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

        assertTrue(
            "root-package keep must be SINGLE-star so host subpackages stay shrinkable",
            rules.contains("-keep class com.authorss81.noteflow.plugins.* { *; }")
        )
        listOf("PluginContext", "PluginEntry", "PluginVersion").forEach {
            assertTrue(
                "plugins.runtime.$it must be kept by exact name (host owns plugins.runtime.* too)",
                rules.contains("-keep class com.authorss81.noteflow.plugins.runtime.$it { *; }")
            )
        }
        assertFalse(
            "the leaked plugins.** wildcard must never return (phase-199 review finding 3)",
            rules.contains("com.authorss81.noteflow.plugins.**")
        )
        assertFalse(
            "a plugins.runtime.** wildcard would leak into HOST runtime internals",
            rules.contains("plugins.runtime.**")
        )
        assertTrue(
            "constructor keep for reflective entry points must stay exactly scoped",
            rules.trim().endsWith(
                "-keepclasseswithmembernames class com.authorss81.noteflow.plugins.* {\n" +
                    "    public <init>(...);\n}"
            )
        )
    }

    @Test
    fun `sdk packages stay inside the consumer-rule scope`() {
        val sdkRoot = File(repoRoot(), "plugin-sdk/src/main/kotlin")
        val files = sdkRoot.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("sanity: :plugin-sdk has sources", files.isNotEmpty())
        val allowedPackages = setOf(
            "com.authorss81.noteflow.plugins",
            "com.authorss81.noteflow.plugins.runtime",
        )
        files.forEach { f ->
            val pkg = f.readLines().firstOrNull { it.startsWith("package ") }
                ?.removePrefix("package ")?.trim()
            assertTrue(
                "${f.relativeTo(repoRoot())} declares package '$pkg' which is NOT covered by " +
                    "plugin-sdk/consumer-rules.pro — list it there explicitly (a wildcard is how " +
                    "this scope leaked last time)",
                pkg in allowedPackages
            )
        }
        val runtimeFiles = files.mapNotNull { f ->
            f.readLines().firstOrNull { it.startsWith("package ") }
                ?.takeIf { it.contains("plugins.runtime") }
                ?.let { f.nameWithoutExtension }
        }.sorted()
        assertEquals(
            "a new plugins.runtime class was added to :plugin-sdk — name it explicitly in " +
                "plugin-sdk/consumer-rules.pro and update this pin",
            listOf("PluginContext", "PluginEntry", "PluginVersion"),
            runtimeFiles
        )
    }

    // --- 5. baseline-profile toolchain wiring stays intact -----------------------

    @Test
    fun `baseline profile toolchain wiring remains committed`() {
        assertTrue(
            ":baselineprofile producer module must stay included",
            read("settings.gradle.kts").contains("include(\":baselineprofile\")")
        )
        val toml = read("gradle/libs.versions.toml")
        assertTrue(toml.contains("androidxBenchmark = \"1.3.4\""))
        assertTrue(toml.contains("profileinstaller = \"1.4.1\""))
        assertTrue(
            ":app must declare profileinstaller explicitly",
            read("app/build.gradle.kts").contains("libs.androidx.profileinstaller")
        )
        val producer = read("baselineprofile/build.gradle.kts")
        assertTrue(producer.contains("targetProjectPath = \":app\""))
    }

    // --- helpers -----------------------------------------------------------------

    /** Every app main-source file that touches Gson (import, construction, or annotations). */
    private fun gsonUsageFiles(): Set<String> =
        File(repoRoot(), "app/src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains("com.google.gson") || Regex("\\bGson\\(\\)").containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot()).invariantSeparatorsPath }
            .toSet()

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("sanity: $relative exists", file.isFile)
        return file.readText()
    }

    companion object {
        /** The complete, verified set of Gson-touching app sources at phase-199-review time. */
        private val EXPECTED_GSON_FILES = listOf(
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/dictionary/DictionaryCore.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginEntryStore.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/weather/WeatherCore.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/websearch/DuckDuckGoClient.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/BrushPresetFileCodec.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendPairing.kt",
            "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendProtocol.kt",
        )

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
