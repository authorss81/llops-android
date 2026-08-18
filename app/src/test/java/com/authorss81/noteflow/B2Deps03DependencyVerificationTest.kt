package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2-DEPS-03 (phase-75): the gradle build must never silently accept whatever
 * google()/mavenCentral() happen to publish. There are two repo-local guards,
 * both committed and source-pinned here:
 *
 *  1. `dependencyResolutionManagement` in `settings.gradle.kts` mirrors the
 *     `pluginManagement` content filters for the google() repository — google()
 *     may only serve `com.android.*`/`com.google.*`/`androidx.*` groups, so a
 *     polluted google index cannot publish a fake org.jetbrains/io.coil-kt/...
 *     artifact into the build graph.
 *  2. `gradle/verification-metadata.xml` is committed and every resolved
 *     artifact (including the AGP/Kotlin/KSP/compose build plugins) must match a
 *     checked-in sha256 checksum. Gradle auto-enables STRICT verification the
 *     moment that file is present — there is NO settings-level DSL to toggle it
 *     in Gradle 8.13, so the absence of the broken `dependencyVerification {}`
 *     settings block is itself part of the guard (a build that tries to opt out
 *     via that non-existent API cannot compile).
 *
 * These pure-JVM tests do NOT re-resolve the network — they source-pin the
 * committed build definition so the guards cannot silently creep back.
 */
class B2Deps03DependencyVerificationTest {

    @Test
    fun `settings mirrors pluginManagement content filters on google repo`() {
        val text = settingsText()
        val pluginBlock = block("pluginManagement", text)
        val resolutionBlock = block("dependencyResolutionManagement", text)

        assertTrue(
            "dependencyResolutionManagement must declare a google() repository (B2-DEPS-03)",
            text.contains("google {")
        )
        sharedFilters().forEach { filter ->
            assertTrue(
                "pluginManagement must keep google content filter '$filter' (B2-DEPS-03)",
                pluginBlock.contains(filter)
            )
            assertTrue(
                "dependencyResolutionManagement must mirror google content filter '$filter' (B2-DEPS-03)",
                resolutionBlock.contains(filter)
            )
        }
    }

    @Test
    fun `maven central remains available but one-way allow-listed for non-google groups`() {
        val text = settingsText()
        val resolutionBlock = text.substringAfter("dependencyResolutionManagement {")
            .substringBefore("include(\":app\")")
        assertTrue(
            "mavenCentral() must stay in dependencyResolutionManagement for non-google artifacts (B2-DEPS-03)",
            resolutionBlock.contains("mavenCentral {") || resolutionBlock.contains("mavenCentral()")
        )
        // R2-b2b2-DEP-04 (phase-146): Central must now carry a content allow-list,
        // closing the reverse gap of the google() one-way filter — unknown groups
        // (and androidx.*/com.google.* versions google() does not host) fail fast.
        assertTrue(
            "mavenCentral must be content-filtered with an allow-list (R2-b2b2-DEP-04)",
            resolutionBlock.contains("mavenCentral {") && resolutionBlock.contains("content {")
        )
        centralAllowlist().forEach { regex ->
            // The settings source stores each regex as a Kotlin string literal, so a
            // backslash in the logical regex appears doubled in the raw source text.
            val raw = regex.replace("\\", "\\\\")
            assertTrue(
                "mavenCentral allow-list must include group '$regex' (R2-b2b2-DEP-04)",
                resolutionBlock.contains("includeGroupByRegex(\"$raw\")")
            )
        }
    }

    @Test
    fun `settings does not use the nonexistent dependencyVerification DSL`() {
        val text = settingsText()
        assertFalse(
            "a settings-level 'dependencyVerification {}' block does not exist in Gradle 8.13 and " +
                "must never be added — it only breaks settings compilation (B2-DEPS-03)",
            text.contains("dependencyVerification") && text.contains("verify =")
        )
        assertFalse(
            "settings must not reference the non-existent 'verify =' DSL property (B2-DEPS-03)",
            text.contains("verify = \"all\"") || text.contains("verify = DependencyVerificationMode")
        )
    }

    @Test
    fun `verification metadata file exists and is committed`() {
        val metadata = File(repoRoot(), "gradle/verification-metadata.xml")
        assertTrue(
            "gradle/verification-metadata.xml must exist and be committed — its presence enables " +
                "Gradle STRICT checksum verification (B2-DEPS-03)",
            metadata.isFile
        )
        val text = metadata.readText()
        assertTrue(
            "verification metadata must be the real root element (B2-DEPS-03)",
            text.trimStart().contains("<verification-metadata xmlns=")
        )
        assertTrue(
            "verification metadata must not be a stub — it needs checksums for the build graph (B2-DEPS-03)",
            text.contains("<components>") && text.contains("sha256 value=")
        )
    }

    @Test
    fun `verification metadata covers build plugins and app artifacts`() {
        val text = File(repoRoot(), "gradle/verification-metadata.xml").readText()

        assertTrue(
            "metadata must not be committed empty — it must list verified components (B2-DEPS-03)",
            text.contains("<component ")
        )
        buildPluginGroups().forEach { group ->
            assertTrue(
                "verification metadata must pin build-plugin group '$group' so a compromised " +
                    "plugin cannot compile into the signed APK (B2-DEPS-03)",
                text.contains("group=\"$group\" name=")
            )
        }
    }

    private fun sharedFilters(): List<String> = listOf(
        "includeGroupByRegex(\"com\\\\.android.*\")",
        "includeGroupByRegex(\"com\\\\.google.*\")",
        "includeGroupByRegex(\"androidx.*\")"
    )

    /** Every group this build legitimately resolves from Central (R2-b2b2-DEP-04 allow-list). */
    private fun centralAllowlist(): List<String> = listOf(
        "org\\.jetbrains.*",
        "io\\.coil.*",
        "com\\.squareup.*",
        "org\\.commonmark.*",
        "org\\.jsoup.*",
        "net\\.zetetic.*",
        "com\\.github.*",
        "junit",
        "org\\.hamcrest.*",
        "org\\.junit.*",
        "org\\.jspecify.*",
        "org\\.checkerframework.*",
        "com\\.google\\.errorprone.*",
        "com\\.google\\.j2objc.*",
        "com\\.google\\.code.*",
        "org\\.tensorflow.*",
        "com\\.google\\.protobuf.*",
        "com\\.google\\.flatbuffers.*",
        "io\\.grpc.*",
        "io\\.netty.*",
        "io\\.perfmark.*",
        "org\\.xerial.*",
        "com\\.google\\.guava.*",
        "com\\.google\\.crypto.*",
        "com\\.google\\.dagger.*",
        "com\\.google\\.jimfs.*",
        "com\\.google\\.auto.*",
        "com\\.google\\.api.*",
        "com\\.google\\.accompanist.*",
        "com\\.google\\.devtools.*",
        "com\\.google\\.android",
        "com\\.googlecode.*",
        "com\\.intellij.*",
        "com\\.sun.*",
        "jakarta.*",
        "javax.*",
        "commons-.*",
        "org\\.apache.*",
        "org\\.bouncycastle.*",
        "org\\.codehaus.*",
        "org\\.ow2.*",
        "org\\.jdom.*",
        "org\\.eclipse.*",
        "org\\.glassfish.*",
        "org\\.jvnet.*",
        "org\\.sonatype.*",
        "org\\.bitbucket.*",
        "org\\.slf4j.*",
        "it\\.unimi\\.dsi.*",
        "net\\.java.*",
        "net\\.sf.*"
    )

    private fun buildPluginGroups(): List<String> = listOf(
        "com.android.tools.build", // AGP + aapt2 + lint gradle
        "org.jetbrains.kotlin", // Kotlin compiler + compose compiler
        "com.google.devtools.ksp" // KSP
    )

    private fun settingsText(): String {
        val settings = File(repoRoot(), "settings.gradle.kts")
        assertTrue("repo root must contain settings.gradle.kts", settings.isFile)
        return settings.readText()
    }

    /** Extracts the balanced `name { ... }` block (first occurrence) from a settings text. */
    private fun block(name: String, text: String): String {
        val start = text.indexOf("$name {")
        val bodyStart = text.indexOf('{', start)
        check(bodyStart > start) { "block '$name' not found in settings.gradle.kts" }
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
        error("unbalanced block '$name' in settings.gradle.kts")
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