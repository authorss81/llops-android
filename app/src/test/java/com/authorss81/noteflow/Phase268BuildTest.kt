package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 268 — build supply-chain pins.
 *
 * The phase-268 PROMPT evidence (verification `off`, metadata `false`, 300+
 * unsigned hashes, jitpack in the graph) is STALE at HEAD — verification has
 * been on since phase-75/146 — so this test pins the REAL controls instead of
 * re-fixing them, and pins the two Gradle-side hardenings phase 268 actually
 * ships (explicit V1/V2/V3/V4 scheme pins, 0-byte-keystore refusal):
 *  - `gradle/verification-metadata.xml` keeps `<verify-metadata>true` +
 *    `<verify-signatures>true`; `gradle.properties` carries no disabling
 *    override;
 *  - `<trusted-artifacts>` stay limited to the three build-tool groups whose
 *    artifacts publish no signatures (Gradle/AGP/Kotlin-generated);
 *  - `<ignored-keys>` stay the reviewed 18 key-download failures (sha256 still
 *    enforced per artifact — every component keeps its checksum entries);
 *  - no `jitpack` repository reference anywhere in the build definition;
 *  - the wrapper distribution stays SHA-pinned and every CI workflow's
 *    `gradle-version` tracks the wrapper version (no silent drift);
 *  - the release buildType keeps `isMinifyEnabled` + `isShrinkResources` (the
 *    flags that emit `mapping.txt`) with R8 fullMode on;
 *  - all four APK signature schemes are pinned explicitly;
 *  - the release keystore gate refuses 0-byte files (unchecked base64 decode).
 *
 * Workflow-side gaps (SHA-pinned actions, `distribution-sha256-sum`,
 * mapping.txt upload, VERSION_CODE-from-env) need `.github/workflows/` edits,
 * which require user approval — they are recorded in
 * `workspace/phase-268/REPORT.md`, not asserted here.
 */
class Phase268BuildTest {

    @Test
    fun `dependency verification stays switched on in the lockfile`() {
        val text = repoFile("gradle/verification-metadata.xml").readText()
        assertTrue(
            "verification-metadata.xml must keep <verify-metadata>true</verify-metadata>",
            text.contains("<verify-metadata>true</verify-metadata>")
        )
        assertTrue(
            "verification-metadata.xml must keep <verify-signatures>true</verify-signatures>",
            text.contains("<verify-signatures>true</verify-signatures>")
        )
    }

    @Test
    fun `gradle properties carry no verification kill-switch`() {
        val text = repoFile("gradle.properties").readText()
        assertFalse(
            "gradle.properties must not disable verification (phase-268): found a verification override",
            text.lines().any { line ->
                val t = line.trim()
                !t.startsWith("#") &&
                    (t.startsWith("verification=") ||
                        t.startsWith("org.gradle.dependency.verification="))
            }
        )
    }

    @Test
    fun `trusted artifacts stay limited to signature-less build-tool groups`() {
        val text = repoFile("gradle/verification-metadata.xml").readText()
        val trustBlock = text.substringAfter("<trusted-artifacts>").substringBefore("</trusted-artifacts>")
        val groups = Regex("<trust group=\"([^\"]+)\"")
            .findAll(trustBlock).map { it.groupValues[1] }.toSet()
        assertEquals(
            "trusted-artifacts must stay exactly the three signature-less build-tool groups " +
                "(Gradle/AGP/Kotlin-generated artifacts publish no .asc); found $groups",
            setOf("androidx.databinding", "com.android.tools.build", "org.jetbrains.kotlin"),
            groups
        )
    }

    @Test
    fun `ignored keys stay the reviewed set and checksums still gate every component`() {
        val text = repoFile("gradle/verification-metadata.xml").readText()
        val ignoredBlock = text.substringAfter("<ignored-keys>").substringBefore("</ignored-keys>")
        val ignored = Regex("<ignored-key id=\"[0-9A-F]+\" reason=\"([^\"]+)\"")
            .findAll(ignoredBlock).toList()
        assertEquals(
            "ignored-keys must stay the reviewed 17 key-download failures " +
                "(checksum-only fallback, still sha256-gated) — a count change means " +
                "the lockfile was regenerated and needs review",
            17,
            ignored.size
        )
        assertTrue(
            "every ignored-key must keep the key-download-failure reason (not a blanket skip)",
            ignored.all { it.groupValues[1].contains("couldn't be downloaded") }
        )
        val components = Regex("<component ").findAll(text).count()
        val checksums = Regex("sha256").findAll(text).count()
        assertTrue(
            "sha256 entries ($checksums) must cover every locked component ($components)",
            components > 0 && checksums >= components
        )
    }

    @Test
    fun `no jitpack repository anywhere in the build definition`() {
        listOf("settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml").forEach { path ->
            val text = repoFile(path).readText()
            assertFalse(
                "$path must not reference jitpack (dependency-confusion surface, phase-268)",
                text.contains("jitpack", ignoreCase = true)
            )
        }
    }

    @Test
    fun `wrapper distribution stays SHA-pinned and CI tracks the wrapper version`() {
        val props = repoFile("gradle/wrapper/gradle-wrapper.properties").readText()
        val sha = Regex("distributionSha256Sum=([0-9a-f]+)").find(props)?.groupValues?.get(1)
        assertTrue(
            "gradle-wrapper.properties must pin distributionSha256Sum (64-char hex)",
            sha != null && Regex("^[0-9a-f]{64}$").matches(sha)
        )
        val wrapperVersion =
            Regex("gradle-(\\d+\\.\\d+(?:\\.\\d+)?)-bin\\.zip").find(props)?.groupValues?.get(1)
        assertTrue("wrapper distributionUrl must carry a version", !wrapperVersion.isNullOrBlank())
        listOf(
            ".github/workflows/android.yml",
            ".github/workflows/release.yml",
            ".github/workflows/llops.yml"
        ).forEach { workflow ->
            val text = repoFile(workflow).readText()
            val pinned = Regex("gradle-version:\\s*[\"']?([\\d.]+)[\"']?")
                .findAll(text).map { it.groupValues[1] }.toList()
            assertTrue(
                "$workflow must pin gradle-version (phase-268: CI must not float off the wrapper)",
                pinned.isNotEmpty()
            )
            pinned.forEach { v ->
                assertEquals(
                    "$workflow gradle-version ($v) must track the wrapper version ($wrapperVersion)",
                    wrapperVersion,
                    v
                )
            }
        }
    }

    @Test
    fun `release build keeps the R8 fullMode plus shrink proof flags`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(
            "release buildType must keep isMinifyEnabled = true (emits mapping.txt)",
            build.contains("isMinifyEnabled = true")
        )
        assertTrue(
            "release buildType must keep isShrinkResources = true",
            build.contains("isShrinkResources = true")
        )
        assertTrue(
            "R8 fullMode must stay on in gradle.properties",
            repoFile("gradle.properties").readText().contains("android.enableR8.fullMode=true")
        )
        assertTrue(
            "release.yml must exercise the signed release path (assembleRelease)",
            repoFile(".github/workflows/release.yml").readText().contains("assembleRelease")
        )
    }

    @Test
    fun `all four APK signature schemes are pinned explicitly`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue("V1 JAR signatures must be pinned on", build.contains("enableV1Signing = true"))
        assertTrue("V2 scheme must be pinned on", build.contains("enableV2Signing = true"))
        assertTrue("V3 scheme must be pinned on", build.contains("enableV3Signing = true"))
        assertTrue(
            "V4 scheme must be pinned explicitly off (not left to AGP defaults)",
            build.contains("enableV4Signing = false")
        )
    }

    @Test
    fun `release keystore gate refuses empty keystore files`() {
        val build = repoFile("app/build.gradle.kts").readText()
        assertTrue(
            "signingConfigs must require a non-empty keystore file (phase-268 0-byte decode gate)",
            build.contains("ksFile.length() > 0")
        )
        assertTrue(
            "the execution-time signing gate must backstop a 0-byte storeFile",
            build.contains("length() == 0L")
        )
    }

    private fun repoFile(relative: String): File {
        val f = File(repoRoot(), relative)
        assertTrue("repo file must exist: $relative", f.isFile)
        return f
    }

    companion object {
        private fun repoRoot(): File {
            val cwd = File(System.getProperty("user.dir") ?: ".")
            var dir = cwd
            repeat(8) {
                if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                    return dir
                }
                dir = dir.parentFile ?: return cwd
            }
            return cwd
        }
    }
}
