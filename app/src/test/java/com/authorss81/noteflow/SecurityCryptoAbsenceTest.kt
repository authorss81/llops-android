package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 *  - `gradle/libs.versions.toml` no longer carries the version or library entry
 *    (deliberately scoped to the REMOVED artifact, NOT a blanket "androidx.security"
 *    ban — a future maintained androidx.security artifact must stay usable);
 *  - no production Kotlin/Java/AndroidManifest under any module's `src/main`
 *    (discovered dynamically, so newly-added modules are covered automatically)
 *    and no `.gradle.kts`/`.toml` build file references
 *    `androidx.security.crypto`, `androidx.security:security`,
 *    `EncryptedSharedPreferences` or `MasterKeys`;
 *  - the unit-test runtime classpath carries no security-crypto (or its Tink
 *    transitive) artifacts. Unit tests cannot read the final APK, so full
 *    APK-level absence stays a build-time/CI check (`unzip -l ... | grep -c
 *    androidx/security` = 0); this test pins the dependency-graph level, which
 *    catches a reintroduction the source scans would miss (e.g. pulled in by a
 *    module that postdates them);
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
        scanTargets().forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                if (text.contains(token)) {
                    offenders.append("\n  ").append(relativeToRepo(file))
                        .append(" -> \"").append(token).append("\"")
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
    fun `test runtime classpath carries no security-crypto or tink artifacts`() {
        // Dependency-graph-level pin. The unit-test runtime classpath includes
        // every :app implementation dependency, so if security-crypto (or the
        // Tink it would pull in) were ever re-added — even by a module that
        // postdates the source scans above — its classes become resolvable here
        // and this test fails. Full APK-level absence is asserted at build time
        // in CI via `unzip -l app-debug.apk | grep -c androidx/security` == 0.
        val loader = javaClass.classLoader
        try {
            Class.forName("androidx.security.crypto.EncryptedSharedPreferences", false, loader)
            fail("androidx.security.crypto.EncryptedSharedPreferences must NOT be on the test classpath (removed in phase-112)")
        } catch (expected: ClassNotFoundException) {
            // Absent, as required.
        }
        val tinkResolvable = try {
            Class.forName("com.google.crypto.tink.KeysetHandle", false, loader)
            true
        } catch (expected: ClassNotFoundException) {
            false
        }
        assertFalse(
            "com.google.crypto.tink must not be on the test classpath (transitive of security-crypto)",
            tinkResolvable
        )

        val classpath = System.getProperty("java.class.path") ?: ""
        val offendingJars = classpath.split(File.pathSeparator)
            .map { it.substringAfterLast(File.separator).lowercase() }
            .filter { it.contains("security-crypto") || it.contains("securitycrypto") || it.contains("tink") }
        assertTrue(
            "No security-crypto / tink artifacts may be on the unit-test classpath:" +
                offendingJars.joinToString("") { "\n  $it" },
            offendingJars.isEmpty()
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

    /**
     * All files to scan for the deprecated API: production sources from every
     * module's `src/main` (discovered dynamically so newly-added modules are
     * covered automatically) plus every build definition file (`*.gradle.kts`,
     * `*.toml`) under the repo root. Checkout/build metadata dirs are pruned.
     */
    private fun scanTargets(): List<File> = buildList {
        repoRoot().walkTopDown()
            .onEnter { dir -> dir.name !in PRUNED_DIRS }
            .filter { it.isDirectory && it.name == "main" && it.parentFile?.name == "src" }
            .forEach { srcMain ->
                srcMain.walkTopDown()
                    .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
                    .forEach { add(it) }
            }
        repoRoot().walkTopDown()
            .onEnter { dir -> dir.name !in PRUNED_DIRS }
            .filter { it.isFile && it.extension in BUILD_FILE_EXTENSIONS }
            .forEach { add(it) }
    }

    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath).removePrefix(File.separator)

    companion object {
        private val PRUNED_DIRS =
            setOf(".git", ".gradle", ".kotlin", "build", "logs", "docs", "workspace", "gradle")
        private val SOURCE_EXTENSIONS = setOf("kt", "java", "xml")
        private val BUILD_FILE_EXTENSIONS = setOf("kts", "toml")

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
    }
}