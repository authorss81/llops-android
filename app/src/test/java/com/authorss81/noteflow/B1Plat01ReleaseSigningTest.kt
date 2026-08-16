package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B1-PLAT-1 (phase-57): a release APK must NEVER be signed with the public
 * Android debug keystore. The vulnerable `app/build.gradle.kts` fallbacks —
 * decoding `debug.keystore.base64` into `${rootDir}/debug.keystore`
 * (password/alias `android`/`androiddebugkey`) and defaulting the release
 * signingConfig to AGP's auto-generated debug keystore when no release keystore
 * exists — are removed. Now the release signingConfig reads its identity ONLY
 * from `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` and stays
 * EMPTY (storeFile = null) when they are unset, so AGP's
 * `:app:validateSigningRelease` fails the release build loudly instead of
 * silently emitting a debug-signed "release" APK.
 *
 * These pure-JVM tests source-pin the build definition and the release guide so
 * the debug fallbacks cannot silently creep back:
 *  - `app/build.gradle.kts` no longer references the base64 blob, the
 *    `debug.keystore`, the `androiddebugkey` alias, or any "debug" signing
 *    config as the release signer;
 *  - the release buildType signingConfig is bound unconditionally to
 *    `releaseConfig` (fail-closed when the env keystore is unset);
 *  - the debug buildType keeps AGP's auto-generated debug keystore (assembleDebug
 *    unaffected);
 *  - `.gitignore` blocks committed keystore material / base64 blobs;
 *  - `docs/RELEASE.md` states that debug-signed builds must never be distributed
 *    and that a missing keystore is a loud build failure, not a fallback.
 */
class B1Plat01ReleaseSigningTest {

    @Test
    fun `release signing has no base64-decode debug keystore fallback`() {
        val text = buildFileText()
        assertFalse(
            "build.gradle.kts must not decode a keystore from an in-repo base64 blob " +
                "(B1-PLAT-1): found '${firstMatch(text, listOf("Base64", "debug.keystore.base64"))}'",
            text.contains("Base64") || text.contains("debug.keystore.base64")
        )
        assertFalse(
            "build.gradle.kts must not reference a repo-root debug.keystore fallback (B1-PLAT-1): " +
                "found '${firstMatch(text, listOf("\${rootDir}/debug.keystore", "file(\"debug.keystore\")"))}'",
            text.contains("\${rootDir}/debug.keystore") || text.contains("file(\"debug.keystore\")")
        )
    }

    @Test
    fun `release signing has no androiddebugkey alias or android passwords`() {
        val text = buildFileText()
        assertFalse(
            "the well-known Android debug key must never be referenced (B1-PLAT-1): " +
                "found 'androiddebugkey'",
            "androiddebugkey" in text
        )
        assertFalse(
            "release signing must not hardcode the debug password 'android' (B1-PLAT-1)",
            text.contains("storePassword = \"android\"") || text.contains("keyPassword = \"android\"")
        )
    }

    @Test
    fun `release buildType signing config is unconditionally releaseConfig`() {
        val text = buildFileText()
        assertTrue(
            "release buildType must bind signingConfig to releaseConfig (fail-closed)",
            text.contains("signingConfig = signingConfigs.getByName(\"releaseConfig\")")
        )
        val releaseConfigSlice = text.substringAfter("create(\"releaseConfig\")").substringBefore("}")
        assertTrue(
            "release signing config must read KEYSTORE_FILE from the environment",
            releaseConfigSlice.contains("KEYSTORE_FILE")
        )
        assertTrue(
            "release signing config must read KEYSTORE_PASSWORD from the environment",
            releaseConfigSlice.contains("KEYSTORE_PASSWORD")
        )
        assertTrue(
            "release signing config must read KEY_ALIAS from the environment",
            releaseConfigSlice.contains("KEY_ALIAS")
        )
        assertTrue(
            "release signing config must read KEY_PASSWORD from the environment",
            releaseConfigSlice.contains("KEY_PASSWORD")
        )
    }

    @Test
    fun `release buildType never falls back to the debug signing config`() {
        val text = buildFileText()
        assertFalse(
            "release signing must never fall back to signingConfigs.getByName(\"debug\") (B1-PLAT-1)",
            text.contains("signingConfig = signingConfigs.getByName(\"debug\")")
        )
    }

    @Test
    fun `debug buildType keeps AGP auto generated debug keystore`() {
        val text = buildFileText()
        val debugBlock = text.substringBefore("buildTypes {") +
            text.substringAfter("buildTypes {").substringBefore("release {")
        assertFalse(
            "the debug buildType must not assign a custom signingConfig " +
                "(AGP auto-generates the debug keystore)",
            Regex("signingConfig\\s*=").containsMatchIn(debugBlock)
        )
    }

    @Test
    fun `gitignore still blocks keystore material and base64 blobs`() {
        val gitignore = File(repoRoot(), ".gitignore")
        assertTrue("repo root must contain .gitignore", gitignore.isFile)
        val text = gitignore.readText()
        assertTrue(
            ".gitignore must keep blocking *.keystore commits",
            text.contains("*.keystore")
        )
        assertTrue(
            ".gitignore must keep blocking *.jks commits",
            text.contains("*.jks")
        )
        assertTrue(
            ".gitignore must keep blocking debug.keystore.base64 commits",
            text.contains("debug.keystore.base64")
        )
        assertTrue(
            ".gitignore must keep blocking debug.keystore commits",
            text.contains("debug.keystore")
        )
    }

    @Test
    fun `no debug keystore or base64 blob lives in the repo tree`() {
        repoRoot().walkTopDown()
            .onEnter { dir -> dir.name !in PRUNED_DIRS }
            .filter { it.isFile && (it.name == "debug.keystore" || it.name == "debug.keystore.base64" || it.name.endsWith(".jks") || it.name.endsWith(".keystore")) }
            .forEach { file ->
                assertFalse(
                    "keystore material must never be committed (funding requires external keystore env vars): " +
                        relativeToRepo(file),
                    true
                )
            }
    }

    @Test
    fun `release guide forbids distributing debug-signed builds`() {
        val releaseMd = File(repoRoot(), "docs/RELEASE.md")
        assertTrue("docs/RELEASE.md must exist", releaseMd.isFile)
        val text = releaseMd.readText()
        assertTrue(
            "docs/RELEASE.md must state that debug-signed builds must never be distributed",
            text.contains("never distribute a debug-signed build", ignoreCase = true)
        )
        assertTrue(
            "docs/RELEASE.md must state the release build fails loudly without a keystore",
            text.contains("FAILS", ignoreCase = true) && text.contains("validateSigningRelease")
        )
        assertFalse(
            "docs/RELEASE.md must not advertise a debug-keystore fallback",
            text.contains("CI/dev fallback only")
        )
        assertTrue(
            "docs/RELEASE.md must mention the removed fallback only in removal context",
            text.lineSequence()
                .filter { it.contains("debug.keystore.base64") }
                .all { it.contains("removed", ignoreCase = true) || it.contains("old ", ignoreCase = true) }
        )
    }

    private fun buildFileText(): String {
        val buildFile = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("repo root must contain app/build.gradle.kts", buildFile.isFile)
        return buildFile.readText()
    }

    private fun firstMatch(text: String, needles: List<String>): String =
        needles.firstOrNull { text.contains(it) } ?: "none"

    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath).removePrefix(File.separator)

    companion object {
        private val PRUNED_DIRS = setOf(".git", ".gradle", ".kotlin", "build", "app/build", "logs")

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