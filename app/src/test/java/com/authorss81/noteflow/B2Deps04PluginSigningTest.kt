package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2-DEPS-04 (phase-76): the downloadable-plugin signing identity is no longer
 * derived from a public default or an ephemeral, build-bred keystore.
 *
 * Before: `plugins/llm/build.gradle.kts` hard-coded a default signing password,
 * and whenever `PLUGIN_SIGNING_KEYSTORE_B64` was unset it ran the JDK
 * keystore-generation CLI to mint a brand-new self-signed JKS into
 * `build/plugin-signing/` every build, so every local build produced a fresh
 * signing identity whose pin no app could ever trust — and the metadata comment
 * claimed a non-existent `:app:generateLlmPluginSeed` consumed the pin.
 *
 * After: the signing tasks FAIL LOUDLY unless `PLUGIN_SIGNING_KEYSTORE_B64` +
 * `PLUGIN_SIGNING_STORE_PASS` are both present (the secret store), there is no
 * keystore-generation fallback and no default credential anywhere, and the REAL
 * task `:app:generateLlmPluginSeed` derives the app-compiled pin only from the
 * SIGNED artifact metadata — so the pin can only ever match the one real CI key
 * identity. The committed `GeneratedLlmPluginPin.kt` is folded into
 * `CompileTimePluginPins.RELEASES` via `llmPluginSeedRelease` (null = fail
 * closed, no release pinned yet).
 *
 * Pure-JVM source-pin tests (repo precedent: `B1Plat01ReleaseSigningTest`).
 * The banned needle literals are composed at runtime so this file never
 * re-introduces the leaked bytes it guards against.
 */
class B2Deps04PluginSigningTest {

    private val passwordConstantName = "DEFAULT" + "_KEY_PASSWORD"
    private val defaultPassword = "inkflow" + ".2026.plugins"
    private val keytoolCli = "key" + "tool"

    @Test
    fun `plugin signing has no hardcoded default password`() {
        val text = pluginBuildFileText()
        assertFalse(
            "the committed default password must be gone (B2-DEPS-04): " +
                "found '${firstMatch(text, listOf(passwordConstantName, defaultPassword))}'",
            text.contains(passwordConstantName) || text.contains(defaultPassword)
        )
    }

    @Test
    fun `plugin signing has no ephemeral keytool fallback`() {
        val text = pluginBuildFileText()
        assertFalse(
            "the build must never mint a signing keystore itself (B2-DEPS-04): " +
                "found '${if (text.contains(keytoolCli)) "keytool" else "-genkeypair"}'",
            text.contains(keytoolCli) || text.contains("-genkeypair")
        )
        assertFalse(
            "the 10,950-day self-signing epoch must be gone (B2-DEPS-04)",
            text.contains("10950")
        )
    }

    @Test
    fun `plugin signing requires the secret store environment`() {
        val text = pluginBuildFileText()
        assertTrue(
            "the keystore must come only from PLUGIN_SIGNING_KEYSTORE_B64 (B2-DEPS-04)",
            text.contains("PLUGIN_SIGNING_KEYSTORE_B64")
        )
        assertTrue(
            "the store password must come only from PLUGIN_SIGNING_STORE_PASS (B2-DEPS-04)",
            text.contains("PLUGIN_SIGNING_STORE_PASS")
        )
        assertTrue(
            "an unset PLUGIN_SIGNING_KEYSTORE_B64 must throw a GradleException (fail loud)",
            text.contains("requirePluginSigningKeystoreB64") &&
                text.contains("throw GradleException")
        )
        assertTrue(
            "an unset PLUGIN_SIGNING_STORE_PASS must throw a GradleException (fail loud)",
            text.contains("requirePluginSigningStorePass") &&
                text.contains("throw GradleException")
        )
        assertTrue(
            "PLUGIN_SIGNING_KEY_PASS is optional but must never fall back to a committed constant " +
                "(B2-DEPS-04)",
            text.contains("PLUGIN_SIGNING_KEY_PASS") &&
                text.contains("?: requirePluginSigningStorePass()")
        )
        assertFalse(
            "no jarsigner invocation may reference a default password (B2-DEPS-04)",
            text.contains("?: $passwordConstantName")
        )
    }

    @Test
    fun `plugin signing tasks fail loudly via the task-graph gate`() {
        val text = pluginBuildFileText()
        assertTrue(
            "signing tasks must be gated fail-loud before any build work (phase-57 precedent)",
            text.contains("gradle.taskGraph.whenReady")
        )
        val gateSlice = text.substringAfter("PLUGIN_SIGNING_TASK_NAMES")
            .substringBefore("val pluginSigningKeystore")
        listOf("signPlugin", "verifyPluginSignature", "pluginMetadata").forEach { taskName ->
            assertTrue(
                "PLUGIN_SIGNING_TASK_NAMES must include '$taskName' so requesting it without the env fails loud",
                gateSlice.contains(taskName)
            )
        }
        assertTrue(
            "the gate must refuse when either env var is missing",
            gateSlice.contains("!keystoreSet") || gateSlice.contains("!storePassSet")
        )
        assertTrue(
            "the gate must throw a descriptive GradleException (B2-DEPS-04)",
            gateSlice.contains("throw GradleException") && gateSlice.contains("B2-DEPS-04")
        )
    }

    @Test
    fun `app defines the generateLlmPluginSeed task (not a dangling claim)`() {
        val text = appBuildFileText()
        assertTrue(
            "the seed task must actually exist (B2-DEPS-04): the old build only mentioned it in a comment",
            text.contains("tasks.register<DefaultTask>(\"generateLlmPluginSeed\")")
        )
        assertTrue(
            "the seed task must consume the SIGNED :plugins:llm artifact metadata",
            text.contains("dependsOn(\":plugins:llm:pluginMetadata\")")
        )
        assertTrue(
            "the seed task must refuse missing metadata loudly",
            text.contains("plugin-metadata.properties not found")
        )
        assertTrue(
            "the seed task must validate sha256 as 64-char lowercase hex",
            text.contains("^[0-9a-f]{64}$")
        )
        assertTrue(
            "the seed task must validate pinnedCertHash as sha256/<base64>",
            text.contains("^sha256/[A-Za-z0-9+/=]+$")
        )
        assertTrue(
            "the seed task must emit the compiled pin file",
            text.contains("GeneratedLlmPluginPin.kt")
        )
    }

    @Test
    fun `the compile-time pin table folds the llm seed release`() {
        val text = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/CompileTimePluginPinStore.kt"
        ).readText()
        assertTrue(
            "CompileTimePluginPins.RELEASES must fold in llmPluginSeedRelease (B2-DEPS-04)",
            text.contains("llmPluginSeedRelease")
        )
        assertTrue(
            "RELEASES must build the table from the seed via buildReleaseTable",
            text.contains("buildReleaseTable(") && text.contains("listOfNotNull(llmPluginSeedRelease")
        )
    }

    @Test
    fun `the compile-time pin table folds the mlkit seed release`() {
        val text = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/CompileTimePluginPinStore.kt"
        ).readText()
        assertTrue(
            "CompileTimePluginPins.RELEASES must fold in mlKitPluginSeedRelease (R2-KS-21)",
            text.contains("mlKitPluginSeedRelease")
        )
        assertTrue(
            "RELEASES must build the table from all seeds via buildReleaseTable",
            text.contains("listOfNotNull(llmPluginSeedRelease, mlKitPluginSeedRelease)")
        )
    }

    @Test
    fun `committed llm seed is null or a valid pinned release (never garbage)`() {
        val seedFile = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedLlmPluginPin.kt"
        )
        assertTrue("GeneratedLlmPluginPin.kt must be committed for the app to compile", seedFile.isFile)
        val text = seedFile.readText()
        assertTrue("seed must declare llmPluginSeedRelease", text.contains("llmPluginSeedRelease"))
        if (text.contains("llmPluginSeedRelease: PinnedPluginRelease? = null")) {
            // Fail-closed initial state: nothing pinned, the update chain refuses every offer.
            return
        }
        assertTrue(
            "a non-null seed must construct a PinnedPluginRelease (B2-DEPS-04)",
            text.contains("PinnedPluginRelease(")
        )
        assertTrue(
            "a non-null seed must carry the llm plugin id",
            text.contains("com.authorss81.noteflow.plugins.llm")
        )
        assertTrue("a non-null seed must carry a PluginVersion", text.contains("PluginVersion("))
        val sha256Match = Regex("sha256\\s*=\\s*\"[0-9a-f]{64}\"").containsMatchIn(text)
        assertTrue("a non-null seed sha256 must be a 64-char lowercase hex digest", sha256Match)
        val pinMatch = Regex("pinnedCertHash\\s*=\\s*\"sha256/[A-Za-z0-9+/=]+\"").containsMatchIn(text)
        assertTrue("a non-null seed pinnedCertHash must be a sha256/<base64> pin", pinMatch)
    }

    @Test
    fun `the promoted default password appears nowhere in the repo code or config`() {
        val root = repoRoot()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in PRUNED_DIRS }
            .filter { it.isFile && it.extension in TEXT_EXTENSIONS }
            .forEach { file ->
                assertFalse(
                    "the promoted default signing password must never appear in tracked source/config " +
                        "(B2-DEPS-04): ${relativeToRepo(file)}",
                    file.readText().contains(defaultPassword)
                )
            }
    }

    @Test
    fun `no plugin keystore material is committed under the repo`() {
        val root = repoRoot()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in PRUNED_DIRS }
            .filter { it.isFile && (it.name.endsWith(".jks") || it.name.endsWith(".keystore")) }
            .forEach { file ->
                assertFalse(
                    "keystore material must never be committed (B2-DEPS-04): ${relativeToRepo(file)}",
                    true
                )
            }
    }

    private fun pluginBuildFileText(): String {
        val buildFile = File(repoRoot(), "plugins/llm/build.gradle.kts")
        assertTrue("repo root must contain plugins/llm/build.gradle.kts", buildFile.isFile)
        return buildFile.readText()
    }

    private fun appBuildFileText(): String {
        val buildFile = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("repo root must contain app/build.gradle.kts", buildFile.isFile)
        return buildFile.readText()
    }

    private fun firstMatch(text: String, needles: List<String>): String =
        needles.firstOrNull { text.contains(it) } ?: "none"

    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath).removePrefix(File.separator)

    companion object {
        private val PRUNED_DIRS = setOf(
            ".git", ".gradle", ".kotlin", "build", "app/build",
            "plugins/llm/build", "logs", ".idea", ".android", ".cxx"
        )
        // `.md` is deliberately excluded: the audit docs (`docs/security-report.md`,
        // per-phase REPORT.md) must QUOTE the leaked pre-fix value as finding
        // evidence, so it legitimately appears there in the documented-finding
        // context. The code + build + config surface must never contain it again.
        private val TEXT_EXTENSIONS = setOf("kt", "kts", "toml", "gradle", "pro", "xml", "yml", "yaml", "sh")

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