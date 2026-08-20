package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b2-DEP-02 / DEP-03 / DEP-04 (phase-146) — build toolchain + lockfile
 * integrity pins, all source-pinned so the guards cannot silently creep back:
 *
 *  1. R2-b2b2-DEP-02 — the committed Gradle wrapper pins the 8.13 distribution
 *     with `distributionSha256Sum` (the exact SHA-256 services.gradle.org
 *     publishes for `gradle-8.13-bin.zip`), so wrapper-based provisioning
 *     refuses a tampered distribution. HONEST SCOPE: CI still provisions `gradle`
 *     (system) directly via `gradle/actions/setup-gradle` `gradle-version`
 *     WITHOUT a checksum on ALL CI paths (android/release/llops workflows), so the
 *     wrapper is the integrity source of truth for local/developer provisioning
 *     only. Wiring `distribution-sha256-sum` through setup-gradle / switching CI
 *     to `./gradlew` and checksum-pinning the opencode installer are DEFERRED to
 *     phase-147 (workflow edits require user approval) — this test guards that CI
 *     at least does not drift off the 8.13 version until then.
 *  2. R2-b2b2-DEP-04 — the lockfile now runs `<verify-signatures>true</verify-signatures>`
 *     against a committed `<trusted-keys>` block + an exported local keyring
 *     (GRADLE ~offline signature verification), and `mavenCentral()` in
 *     `dependencyResolutionManagement` is one-way allow-listed.
 *  3. R2-b2b2-DEP-03 — the stale POM-only build-graph entries are documented as
 *     accepted (dependencyInsight verdict) rather than deleted, so an audit blind
 *     spot becomes a tracked item.
 *
 * All tests are pure-JVM (read committed files only; no network, no Gradle run).
 */
class Phase146BuildIntegrityTest {

    // --- R2-b2b2-DEP-02: wrapper integrity -----------------------------------

    @Test
    fun `gradle wrapper is committed with the official 8_13 distributionSha256Sum`() {
        val props = read("gradle/wrapper/gradle-wrapper.properties")

        val url = props.lineSequence().first { it.startsWith("distributionUrl") }
        assertTrue(
            "wrapper must pin the same 8.13 distribution CI provisions (R2-b2b2-DEP-02): $url",
            url.contains("gradle-8.13-bin.zip")
        )
        assertFalse(
            "wrapper must never point at a SNAPSHOT/mutable distribution (R2-b2b2-DEP-02)",
            url.contains("snapshot") || url.contains("-all.") || url.contains("nightly")
        )

        val sum = props.lineSequence().first { it.startsWith("distributionSha256Sum") }
            .substringAfter("distributionSha256Sum=").trim()
        assertTrue(
            "distributionSha256Sum must be present — zero-integrity provisioning was the finding (R2-b2b2-DEP-02)",
            sum.isNotEmpty()
        )
        assertTrue(
            "distributionSha256Sum must be a 64-char lowercase SHA-256 digest, got '$sum'",
            Regex("^[0-9a-f]{64}$").matches(sum)
        )
        // services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 (2026-08-18).
        assertTrue(
            "the committed sum must equal the official gradle.org checksum (R2-b2b2-DEP-02): '$sum'",
            sum == "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
        )
    }

    @Test
    fun `wrapper components are committed and CI version has not drifted off the wrapper pin`() {
        val root = repoRoot()
        assertTrue(
            "gradlew must be committed so a wrapper invocation really verifies the sum (R2-b2b2-DEP-02)",
            File(root, "gradlew").isFile
        )
        assertTrue(
            "gradle-wrapper.jar must be committed (R2-b2b2-DEP-02)",
            File(root, "gradle/wrapper/gradle-wrapper.jar").isFile
        )
        val androidYml = File(root, ".github/workflows/android.yml").readText()
        assertTrue(
            "CI setup-gradle must still provision 8.13 — it must not drift from the wrapper pin " +
                "(R2-b2b2-DEP-02). NOTE: wiring distribution-sha256-sum / switching CI to the " +
                "wrapper is DEFERRED to phase-147, so until then CI provisioning stays " +
                "version-pinned but NOT checksum-pinned (documented residual gap)",
            androidYml.contains("gradle-version: \"8.13\"")
        )
    }

    // --- R2-b2b2-DEP-04: one-way Central filter + signature lockfile ---------

    @Test
    fun `mavenCentral is one-way allow-listed in dependency AND plugin resolution`() {
        val text = read("settings.gradle.kts")

        // dependency resolution
        val resolutionBlock = block("dependencyResolutionManagement", text)
        val central = centralBlock(resolutionBlock)
        assertTrue(
            "mavenCentral must be configured with a content block (R2-b2b2-DEP-04)",
            central.contains("content {")
        )
        allowlistedCentralGroups().forEach { regex ->
            val raw = regex.replace("\\", "\\\\")
            assertTrue(
                "mavenCentral allow-list must include '$regex' (R2-b2b2-DEP-04)",
                central.contains("includeGroupByRegex(\"$raw\")")
            )
        }

        // plugin resolution (review-fix): pluginManagement is pre-evaluated and
        // cannot reference script-level declarations, so the allow-list is written
        // out literally there too — pin that block as well so the two lists (and
        // CentralAllowlist) can never drift silently.
        val pluginBlock = block("pluginManagement", text)
        val pluginCentral = centralBlock(pluginBlock)
        assertTrue(
            "pluginManagement mavenCentral must also carry the allow-list (R2-b2b2-DEP-04)",
            pluginCentral.contains("content {")
        )
        allowlistedCentralGroups().forEach { regex ->
            val raw = regex.replace("\\", "\\\\")
            assertTrue(
                "pluginManagement mavenCentral allow-list must include '$regex' (R2-b2b2-DEP-04)",
                pluginCentral.contains("includeGroupByRegex(\"$raw\")")
            )
        }

        // The google-namespace wildcard must stay one-way: androidx.*/com.android.*
        // (and the bare com.google.* wildcard) never resolve from Central, so a
        // version google() does not host fails instead of silently falling back.
        assertFalse(
            "no androidx wildcard may be allowed from Central (R2-b2b2-DEP-04)",
            central.contains("includeGroupByRegex(\"androidx")
        )
        assertFalse(
            "no com.android wildcard may be allowed from Central (R2-b2b2-DEP-04)",
            central.contains("includeGroupByRegex(\"com\\.android")
        )
        assertFalse(
            "no androidx wildcard may be allowed from Central in pluginManagement (R2-b2b2-DEP-04)",
            pluginCentral.contains("includeGroupByRegex(\"androidx")
        )
        // And the google() repo keeps the B2-DEPS-03 filters (regression guard).
        assertTrue(
            "google() must keep its content filter (B2-DEPS-03)",
            resolutionBlock.contains("includeGroupByRegex(\"com\\\\.google.*\")")
        )
    }

    @Test
    fun `lockfile verifies signatures against committed trusted keys plus keyring`() {
        val xml = read("gradle/verification-metadata.xml")

        assertTrue(
            "lockfile must enable signature verification (R2-b2b2-DEP-04)",
            xml.contains("<verify-signatures>true</verify-signatures>")
        )
        assertTrue(
            "metadata verification must stay on",
            xml.contains("<verify-metadata>true</verify-metadata>")
        )
        val trustedKeys = xml.substringAfter("<trusted-keys>").substringBefore("</trusted-keys>")
        assertTrue(
            "a trusted-keys block must be present (R2-b2b2-DEP-04)",
            trustedKeys.isNotEmpty() && trustedKeys.contains("<trusted-key ")
        )
        // Highest-signal artifacts are PGP-trusted (Kotlin + KSP; AGP falls back to
        // strict sha256 because Google's AGP signing key is not on public key servers).
        assertTrue(
            "org.jetbrains.kotlin must be key-trusted (R2-b2b2-DEP-04)",
            trustedKeys.contains("org.jetbrains.kotlin") || trustedKeys.contains("org[.]jetbrains[.]kotlin")
        )
        assertTrue(
            "com.google.devtools.ksp must be key-trusted (R2-b2b2-DEP-04)",
            trustedKeys.contains("com.google.devtools.ksp")
        )
        assertTrue(
            "the Google umbrella key must be trusted (covers AGP-chain com.google artifacts) (R2-b2b2-DEP-04)",
            trustedKeys.contains("^com[.]google($|([.].*))")
        )
        // Local keyring committed so verification never depends on a key server.
        assertTrue(
            "binary keyring must be committed (R2-b2b2-DEP-04)",
            File(repoRoot(), "gradle/verification-keyring.gpg").isFile
        )
        assertTrue(
            "armored keyring must be committed (R2-b2b2-DEP-04)",
            File(repoRoot(), "gradle/verification-keyring.keys").isFile
        )
    }

    @Test
    fun `lockfile regeneration provenance is documented in settings`() {
        val text = read("settings.gradle.kts")
        assertTrue(
            "settings must document the exact regeneration command (R2-b2b2-DEP-04)",
            text.contains("--write-verification-metadata sha256,pgp --export-keys")
        )
        assertTrue(
            "settings must warn that the lockfile is never regenerated blindly",
            text.contains("generated deliberately, never blindly")
        )
    }

    // --- R2-b2b2-DEP-03: stale-graph verdict documented, not deleted ---------

    @Test
    fun `stale POM-only graph entries are documented with the dependencyInsight verdict`() {
        val settings = read("settings.gradle.kts")
        assertTrue(
            "settings must record the okhttp 3.0.0 -> 4.12.0 verdict + mlkit ownership (R2-b2b2-DEP-03)",
            settings.contains("okhttp") && settings.contains("3.0.0 -> 4.12.0") &&
                settings.contains(":plugins:mlkit")
        )
        assertTrue(
            "settings must record that the mlkit graph now resolves okhttp jars (never dropped, still verified) (R2-b2b2-DEP-03)",
            settings.contains("pinned + verified in the lockfile")
        )
        assertTrue(
            "settings must track the LLM-graph line ownership (R2-b2b2-DEP-03)",
            settings.contains(":plugins:llm") && settings.contains("tasks-genai")
        )

        val xml = read("gradle/verification-metadata.xml")
        assertTrue(
            "okhttp-3.0.0 must be tracked in the lockfile (R2-b2b2-DEP-03)",
            xml.contains("okhttp-3.0.0.pom") || xml.contains("okhttp-3.0.0.jar")
        )
        assertTrue(
            "okio-1.6.0 must be tracked in the lockfile (R2-b2b2-DEP-03)",
            xml.contains("okio-1.6.0.pom") || xml.contains("okio-1.6.0.jar")
        )
        // Phase 175 moved ML Kit into `:plugins:mlkit`; there `mlkit:translate`
        // resolves okhttp-3.0.0 (and okio-1.6.0) as ACTIVE runtime deps, so the
        // lockfile now pins their verified jars. If a future graph reverts that,
        // the entry falls back to POM-only — either way it is NEVER dropped and
        // NEVER unpinned. The fallback is exact: the POM-only form is accepted
        // ONLY when no jar entry is pinned at all. A jar entry that exists but
        // lost its sha256 fails this backstop. (Real enforcement is Gradle's own
        // build-time dependency verification: a resolved jar without a trusted
        // checksum fails the build regardless of this coarse source pin.)
        val okhttpComponent = xml.substringAfter("name=\"okhttp\" version=\"3.0.0\">")
            .substringBefore("</component>")
        val okhttpJarPinned = okhttpComponent.contains("okhttp-3.0.0.jar") &&
            okhttpComponent.contains("sha256")
        val okhttpPomOnly = okhttpComponent.contains("okhttp-3.0.0.pom") &&
            !okhttpComponent.contains("okhttp-3.0.0.jar")
        assertTrue(
            "okhttp-3.0.0 must pin a VERIFIED jar, or be a retained POM-only entry (no jar at all) (R2-b2b2-DEP-03)",
            okhttpJarPinned || okhttpPomOnly
        )
    }

    // --- helpers --------------------------------------------------------------

    /** The group allow-list the settings mavenCentral() filter must carry (full set, shared). */
    private fun allowlistedCentralGroups(): List<String> = CentralAllowlist.groups

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("sanity: $relative exists", file.isFile)
        return file.readText()
    }

    /** Extracts the balanced `name { ... }` block (first occurrence) from a settings text. */
    private fun block(name: String, text: String): String {
        val start = text.indexOf("$name {")
        check(start >= 0) { "block '$name' not found" }
        val bodyStart = text.indexOf('{', start)
        check(bodyStart > start) { "block '$name' has no body" }
        var depth = 0
        for (i in bodyStart until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(bodyStart + 1, i)
                }
            }
        }
        error("unbalanced block '$name'")
    }

    /** Extracts the `mavenCentral { ... }` block from a dependencyResolutionManagement body. */
    private fun centralBlock(resolutionBody: String): String {
        val marker = "mavenCentral {"
        val start = resolutionBody.indexOf(marker)
        if (start < 0) return "" // bare mavenCentral() — filtered? fail the caller's assertion
        val brace = resolutionBody.indexOf('{', start)
        var depth = 0
        for (i in brace until resolutionBody.length) {
            when (resolutionBody[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return resolutionBody.substring(brace + 1, i)
                }
            }
        }
        error("unbalanced mavenCentral block")
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