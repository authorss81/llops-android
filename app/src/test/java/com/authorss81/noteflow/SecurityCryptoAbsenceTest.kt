package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2-DEPS-02 (phase-112): `androidx.security:security-crypto` is an UNMAINTAINED
 * 3-year-old alpha (1.1.0-alpha06) that was declared in the Gradle catalog and
 * pulled into the base APK but never referenced anywhere in the source. The
 * forward-looking risk was that any future wiring of `EncryptedSharedPreferences`
 * would reintroduce Tink keyset-manager failure classes (AEADBadTagException on
 * backup-restore/key-loss) with zero future security maintenance.
 *
 * The fix REMOVED the dependency: no `implementation(libs.security.crypto)` line,
 * no catalog version, no catalog library entry. Google deprecated the whole API
 * and points at `SharedPreferences`/`KeyGenerator` + AndroidKeyStore instead.
 *
 * These pure-JVM tests pin the absence so the dependency cannot silently creep
 * back into the build or any Kotlin module's source:
 *
 *  - `app/build.gradle.kts` no longer references the library accessor;
 *  - `gradle/libs.versions.toml` no longer carries the version or library entry;
 *  - no production Kotlin/Java/AndroidManifest under `app/src/main`,
 *    `plugin-sdk/src/main` or `plugins/llm/src/main` references
 *    `androidx.security.crypto`, `EncryptedSharedPreferences` or `MasterKeys`;
 *  - the repo's encrypted-prefs lane stays exclusively AndroidKeyStore-based
 *    (`WebDavCredentialStore`), the recommended replacement, untouched.
 *
 * NOTE: CVE-2024-37150 is a Deno npm-registry bug, NOT an androidx.security CVE —
 * it is deliberately not asserted here.
 */
class SecurityCryptoAbsenceTest {

    @Test
    fun `app build file no longer pulls security-crypto`() {
        val buildFile = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("repo root must contain app/build.gradle.kts", buildFile.isFile)
        val text = buildFile.readText()
        assertFalse(
            "build.gradle.kts must not reference libs.security.crypto (removed in phase-112)",
            text.contains("security.crypto") || text.contains("security-crypto")
        )
    }

    @Test
    fun `version catalog carries no securityCrypto version or library`() {
        val catalog = File(repoRoot(), "gradle/libs.versions.toml")
        assertTrue("repo root must contain gradle/libs.versions.toml", catalog.isFile)
        val text = catalog.readText()
        assertFalse(
            "catalog must not declare the securityCrypto version (removed in phase-112)",
            text.contains("securityCrypto")
        )
        assertFalse(
            "catalog must not declare the security-crypto library (removed in phase-112)",
            text.contains("security-crypto")
        )
        assertFalse(
            "catalog must not pin androidx.security at all (removed in phase-112)",
            text.contains("androidx.security")
        )
    }

    @Test
    fun `source tree has zero references to the deprecated crypto API`() {
        val forbidden = listOf(
            "androidx.security.crypto",
            "androidx.security:security",
            "EncryptedSharedPreferences",
            "MasterKeys"
        )
        val offenders = StringBuilder()
        sourceDirs().forEach { scanDir ->
            scanDir.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
                .forEach { file ->
                    val text = file.readText()
                    forbidden.forEach { token ->
                        if (text.contains(token)) {
                            offenders.append("\n  ").append(relative(scanDir, file))
                                .append(" -> \"").append(token).append("\"")
                        }
                    }
                }
        }
        assertTrue(
            "Deprecated security-crypto references must not exist (phase-112 removed them):" +
                offenders.toString(),
            offenders.isEmpty()
        )
    }

    @Test
    fun `encrypted prefs lane stays exclusively AndroidKeyStore based`() {
        // The recommended replacement lane (WebDavCredentialStore) uses the
        // AndroidKeyStore directly and never the deprecated library. Assert it
        // exists untouched, so the "use AndroidKeyStore directly" guidance has a
        // concrete, still-working referent in tree.
        val store = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavCredentialStore.kt"
        )
        assertTrue("WebDavCredentialStore must still exist", store.isFile)
        val text = store.readText()
        assertTrue(
            "WebDavCredentialStore must use AndroidKeyStore (the recommended lane)",
            text.contains("AndroidKeyStore")
        )
        assertFalse(
            "WebDavCredentialStore must not adopt the deprecated security-crypto",
            text.contains("EncryptedSharedPreferences") || text.contains("security-crypto")
        )
    }

    private fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).removePrefix(File.separator)

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
            return dir
        }

        private fun sourceDirs(): List<File> {
            val root = repoRoot()
            return listOf(
                File(root, "app/src/main"),
                File(root, "plugin-sdk/src/main"),
                File(root, "plugins/llm/src/main")
            ).filter { it.isDirectory }
        }
    }
}