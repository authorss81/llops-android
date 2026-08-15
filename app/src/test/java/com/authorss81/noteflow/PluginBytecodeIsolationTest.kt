package com.authorss81.noteflow

import com.authorss81.testplugins.WhitelistedPlugin
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.runtime.ArtifactStaticScan
import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory
import com.authorss81.noteflow.plugins.runtime.DexStringExtractor
import com.authorss81.noteflow.plugins.runtime.FacadeHost
import com.authorss81.noteflow.plugins.runtime.FacadeResult
import com.authorss81.noteflow.plugins.runtime.PluginArtifact
import com.authorss81.noteflow.plugins.runtime.PluginArtifactResolver
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginFrameworkClassLoader
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.runtime.RuntimePluginLoader
import com.authorss81.noteflow.plugins.runtime.SignatureVerifiedPluginRuntime
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B1-AUTH-01 (phase-46): downloadable-plugin bytecode is no longer executed with
 * the app classloader as parent. Two independent gates close it:
 *
 * 1. **[ArtifactStaticScan]** rejects at verify time (install, every load
 *    re-verify, update, rollback — all funnel through
 *    `ArtifactSignatureVerifier.verify`) any artifact whose bytecode or raw
 *    content references the app's private packages, the secret-bearing classes
 *    (`VaultKeyHolder`, `EncryptionService`, `NoteflowDatabase`,
 *    `SettingsManager`, `NoteRepository`) or raw java.net egress primitives.
 * 2. **[PluginFrameworkClassLoader]** (production parent of the plugin DEX)
 *    refuses at resolution time, per class name, every
 *    `com.authorss81.noteflow.*` class OUTSIDE the sanctioned `plugins.*`
 *    framework surface — so even bytecode the static scan could not see (or
 *    reflection such as `Class.forName("...")`) can never fabricate a handle to
 *    the DEK/vault.
 *
 * Pure JVM: test artifacts are signed jars over hostile/benign plugin classes
 * (the same [TestArtifactBuilder] machinery the Phase-23/24 tests use).
 */
class PluginBytecodeIsolationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parentLoader: ClassLoader =
        TestDownloadablePlugin::class.java.classLoader ?: javaClass.classLoader

    private val fixtureId = TestDownloadablePlugin.TEST_PLUGIN_ID

    // ------------------------------------------------------------------ //
    // 1. The scoped framework loader                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun `the scoped framework loader refuses every app-private package`() {
        val scoped = PluginFrameworkClassLoader(parentLoader)
        val forbidden = listOf(
            "com.authorss81.noteflow.services.VaultKeyHolder",
            "com.authorss81.noteflow.services.SecurityService",
            "com.authorss81.noteflow.data.db.NoteflowDatabase",
            "com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel",
            "com.authorss81.noteflow.utils.ConstantTime",
            "com.authorss81.noteflow.MainActivity"
        )
        for (name in forbidden) {
            try {
                scoped.loadClass(name)
                fail("loadClass('$name') must be refused by the plugin sandbox")
            } catch (expected: ClassNotFoundException) {
                // refused, as required
            }
        }
        // The framework surface stays resolvable (compiled-in interfaces the
        // artifacts are built against).
        scoped.loadClass("com.authorss81.noteflow.plugins.NoteflowPlugin")
        scoped.loadClass("com.authorss81.noteflow.plugins.runtime.PluginContext")
        // And platform/JDK/third-party classes still resolve through the parent
        // (org.junit.T / java.util.* load fine in the test worker; a fresh
        // kotlin.collections.* loadClass fails in this worker's loader chain,
        // so kotlin classes are not asserted here — the delegation rule is the
        // same for every non-app namespace).
        scoped.loadClass("java.lang.String")
        scoped.loadClass("java.util.List")
        scoped.loadClass("org.junit.Test")
    }

    @Test
    fun `reflection reach-through is blocked the same way`() {
        val scoped = PluginFrameworkClassLoader(parentLoader)
        try {
            Class.forName("com.authorss81.noteflow.services.VaultKeyHolder", false, scoped)
            fail("Class.forName must not bypass the plugin sandbox")
        } catch (expected: ClassNotFoundException) {
            // refused, as required
        }
        val framework =
            Class.forName("com.authorss81.noteflow.plugins.runtime.PluginContextFactory", false, scoped)
        assertTrue(framework.simpleName == "PluginContextFactory")
    }

    @Test
    fun `a jar's own classes plus the framework surface load through the scoped loader`() {
        val scoped = PluginFrameworkClassLoader(parentLoader)
        val artifact = signedArtifact(TestDownloadablePlugin::class.java.name)
        val loader = URLClassLoader(arrayOf(artifact.file.toURI().toURL()), scoped)
        val clazz = loader.loadClass(TestDownloadablePlugin::class.java.name)
        assertTrue(NoteflowPlugin::class.java.isAssignableFrom(clazz))
    }

    // ------------------------------------------------------------------ //
    // 2. Static scan: install/verify-time content gate                     //
    // ------------------------------------------------------------------ //

    @Test
    fun `the static scan rejects an artifact that mentions app-private packages`() {
        val artifact = signedArtifact(HostileVaultPlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Rejected
        )
        val reason = (scan as ArtifactStaticScan.Result.Rejected).reason
        assertTrue("reason=$reason", reason.contains("services") || reason.contains("VaultKeyHolder"))
    }

    @Test
    fun `the static scan rejects raw network egress bytecode`() {
        val artifact = signedArtifact(HostileNetworkPlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Rejected
        )
        val reason = (scan as ArtifactStaticScan.Result.Rejected).reason
        assertTrue("reason=$reason", reason.contains("network"))
    }

    @Test
    fun `the static scan accepts a whitelisted capability plugin`() {
        val artifact = signedArtifact(TestDownloadablePlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "benign plugin must pass the scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Pass
        )
    }

    @Test
    fun `the static scan catches an app-private reference smuggled only as a resource`() {
        val jar = File(tmp.root, "smuggle.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("payload/profile.dat"))
            out.write("user=someone\nref=com.authorss81.noteflow.services.VaultKeyHolder\n".toByteArray())
            out.closeEntry()
        }
        val scan = ArtifactStaticScan().scan(jar)
        assertTrue(scan is ArtifactStaticScan.Result.Rejected)
    }

    @Test
    fun `the static scan parses a dexified plugin - dex string table`() {
        val dex = TestDexBuilder.build(
            strings = listOf(
                "Lcom/authorss81/noteflow/plugins/NoteflowPlugin;",
                "Lcom/authorss81/noteflow/services/VaultKeyHolder;",
                "Ljava/net/HttpURLConnection;",
                "com.authorss81.noteflow.data.NoteRepository"
            ),
            types = listOf(0, 1, 2)
        )
        val extracted = DexStringExtractor.extract(dex)
        assertTrue(
            "extracted must contain the dex strings, got $extracted",
            extracted.any { it.contains("plugins/NoteflowPlugin") } &&
                extracted.any { it.contains("services/VaultKeyHolder") } &&
                extracted.any { it.contains("net/HttpURLConnection") } &&
                extracted.any { it.contains("NoteRepository") }
        )
        // A readable jar carrying this dex is rejected by the scan.
        val jar = File(tmp.root, "dex-hostile.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(dex)
            out.closeEntry()
        }
        val scan = ArtifactStaticScan().scan(jar)
        assertTrue(
            "dex artifact -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Rejected
        )
    }

    @Test
    fun `the static scan accepts a clean dexified plugin`() {
        val dex = TestDexBuilder.build(
            strings = listOf(
                "Lcom/authorss81/noteflow/plugins/NoteflowPlugin;",
                "Ljava/lang/String;",
                "com.authorss81.noteflow.plugins.runtime.PluginContext"
            ),
            types = listOf(0, 2)
        )
        val jar = File(tmp.root, "dex-clean.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(dex)
            out.closeEntry()
        }
        val scan = ArtifactStaticScan().scan(jar)
        assertTrue(
            "clean dex artifact -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Pass
        )
    }

    // ------------------------------------------------------------------ //
    // 3. Load-time sandbox (classloader) - hostile artifact                //
    // ------------------------------------------------------------------ //

    @Test
    fun `a plugin touching VaultKeyHolder fails to load under the scoped parent`() {
        val artifact = signedArtifact(HostileVaultPlugin::class.java.name)
        val entry = remoteEntryFor(artifact)
        // Bypass verify() on purpose: this test exercises the CLASSloader
        // sandbox alone (the static scan is a separate, earlier gate).
        val loader = RuntimePluginLoader(
            classLoaderFactory = scopedFactory(),
            contextFactory = PluginContextFactory.capabilityAware(RecordingHost()),
            parentClassLoader = parentLoader
        )
        val result = loader.load(entry, artifact.file.absolutePath)
        assertTrue(
            "hostile plugin must fail to materialize under the sandbox, got $result",
            result is RuntimeOutcome.Failed
        )
        assertTrue((result as RuntimeOutcome.Failed).message.contains("could not"))
    }

    @Test
    fun `the same hostile artifact genuinely runs without the sandbox - proving the boundary matters`() {
        // Control: the hostile bytecode is valid and WOULD exfiltrate the DEK
        // under the pre-fix parent (app classloader). This is the "before".
        val artifact = signedArtifact(HostileVaultPlugin::class.java.name)
        val entry = remoteEntryFor(artifact)
        val loader = RuntimePluginLoader(
            classLoaderFactory = rawFactory(),
            contextFactory = PluginContextFactory.capabilityAware(RecordingHost()),
            parentClassLoader = parentLoader
        )
        val result = loader.load(entry, artifact.file.absolutePath)
        assertTrue("control (no sandbox) must load the hostile plugin -> $result", result is RuntimeOutcome.Success)
        val plugin = (result as RuntimeOutcome.Success).value.plugin
        assertEquals(entry.id, plugin.manifest.id)
        assertTrue(plugin is TextTransformPlugin)
    }

    @Test
    fun `the full runtime refuses a hostile artifact before any code is materialized`() {
        val artifact = signedArtifact(HostileVaultPlugin::class.java.name)
        val entry = remoteEntryFor(artifact)
        val rt = runtimeFor(entry, artifact.file)
        // verify() alone already refuses it (the install gate).
        val verification = rt.verify(
            PluginArtifact(entry, artifact.file.absolutePath, entry.sha256.orEmpty(), entry.pinnedCertHash.orEmpty())
        )
        assertTrue(verification is RuntimeOutcome.Failed)
        assertTrue(
            "reason=${(verification as RuntimeOutcome.Failed).message}",
            verification.message.contains("static security scan")
        )
        // load() re-verifies, so it refuses too — no bytecode ever runs.
        val loaded = rt.load(entry)
        assertTrue(loaded is RuntimeOutcome.Failed)
        assertTrue((loaded as RuntimeOutcome.Failed).message.contains("static security scan"))
    }

    // ------------------------------------------------------------------ //
    // 4. Whitelisted-capability plugin still loads + runs under the fix    //
    // ------------------------------------------------------------------ //

    @Test
    fun `a whitelisted capability plugin loads and executes under the sandbox`() {
        // WhitelistedPlugin lives OUTSIDE the app's private namespace (like a
        // real plugin), so the sandbox lets it — and its own inner classes —
        // resolve through the parent; hostile app-package references it makes
        // would still be refused (covered by the hostile tests above).
        val artifact = signedArtifact(WhitelistedPlugin::class.java.name)
        val entry = remoteEntryFor(artifact)
        val rt = runtimeFor(entry, artifact.file)

        val loaded = rt.load(entry)
        assertTrue("load -> ${(loaded as? RuntimeOutcome.Failed)?.message}", loaded is RuntimeOutcome.Success)
        val loadedPlugin = (loaded as RuntimeOutcome.Success).value
        assertEquals(entry.id, loadedPlugin.plugin.id)
        assertTrue(loadedPlugin.plugin is TextTransformPlugin)
        assertEquals("white:HELLO", (loadedPlugin.plugin as TextTransformPlugin).transformText("hello"))
    }

    // ------------------------------------------------------------------ //
    // 5. Wiring pins (source-level) — a refactor cannot drop a gate         //
    // ------------------------------------------------------------------ //

    @Test
    fun `production class-loader factory ships the scoped framework loader`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/AppClassLoaderFactory.kt")
        assertTrue(
            "AppClassLoaderFactory must wrap the plugin DEX parent in PluginFrameworkClassLoader",
            source.contains("PluginFrameworkClassLoader(parent)")
        )
    }

    @Test
    fun `artifacts pass through the static scan at the verify gate`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/ArtifactSignatureVerifier.kt")
        val verifyBlock = source.substringAfter("fun verify(", "END")
        assertTrue("verify() must run ArtifactStaticScan", verifyBlock.contains("ArtifactStaticScan()"))
        assertTrue(
            "verify() must refuse scanned artifacts loudly",
            verifyBlock.contains("plugin static security scan")
        )
    }

    // ------------------------------------------------------------------ //
    // fixtures                                                             //
    // ------------------------------------------------------------------ //

    private fun signedArtifact(pluginClassName: String): TestArtifactBuilder.SignedArtifact {
        val ks = TestArtifactBuilder.newKeystore(tmp.root, "isolation-signer")
        return TestArtifactBuilder.build(tmp.root, ks, pluginClassName = pluginClassName)
    }

    private fun remoteEntryFor(artifact: TestArtifactBuilder.SignedArtifact) = PluginEntry(
        id = fixtureId,
        name = "Isolation Test Plugin",
        description = "Isolation test plugin.",
        version = PluginVersion(1, 0, 0),
        capabilities = setOf(PluginCapability.TextTransform),
        category = "Text",
        downloadUrl = "https://plugins.example.com/isolation.apk",
        sha256 = artifact.sha256Hex,
        pinnedCertHash = artifact.pinnedCertHash,
        source = PluginEntrySource.REMOTE
    )

    private fun runtimeFor(entry: PluginEntry, file: File) = SignatureVerifiedPluginRuntime(
        artifactResolver = PluginArtifactResolver { e -> if (e.id == entry.id && file.isFile) file else null },
        classLoaderFactory = scopedFactory(),
        contextFactory = PluginContextFactory.capabilityAware(RecordingHost()),
        parentClassLoader = parentLoader
    )

    private fun scopedFactory(): ClassLoaderFactory = ClassLoaderFactory { artifactPath, parent ->
        URLClassLoader(arrayOf(File(artifactPath).toURI().toURL()), PluginFrameworkClassLoader(parent))
    }

    private fun rawFactory(): ClassLoaderFactory = ClassLoaderFactory { artifactPath, parent ->
        URLClassLoader(arrayOf(File(artifactPath).toURI().toURL()), parent)
    }

    private fun readSource(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

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

/** Minimal host (capability facade) for the isolation tests — records nothing. */
private class RecordingHost : FacadeHost {
    override fun insertText(text: String) = FacadeResult.Granted(Unit)
    override fun showResult(title: String, body: String) = FacadeResult.Granted(Unit)
    override fun httpGet(url: String) = FacadeResult.Granted("body")
    override fun readSelection() = FacadeResult.Granted("selection")
    override fun requestModelDownload(sizeBytes: Long) = FacadeResult.Granted(Unit)
}

// ----------------------------------------------------------------------
// Hostile (test-only) plugin bytecode — compiled into the TEST source set,
// so it never ships in the APK. These are the "attacker" artifacts.
// ----------------------------------------------------------------------

/** A plugin that, like the B1-AUTH-01 exploit, reaches for the vault DEK in
 *  its constructor. Under the pre-fix parent this bytecode actually executes
 *  and reads `VaultKeyHolder.dek` (null in tests; the real DEK in production). */
internal class HostileVaultPlugin : NoteflowPlugin, TextTransformPlugin {

    init {
        @Suppress("UNUSED_VARIABLE")
        val stolen = com.authorss81.noteflow.services.VaultKeyHolder.dek
    }

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile Vault Stealer",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that reaches for the vault DEK.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
    override fun transformText(text: String): String = text
}

/** A plugin that holds its own HTTP connection — the raw network egress the
 *  capability facade is meant to channel exclusively. */
internal class HostileNetworkPlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("unused")
    private var egress: java.net.HttpURLConnection? = null

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile Network Egress",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that opens its own sockets.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
    override fun transformText(text: String): String = text
}

/**
 * Builds a minimal, structurally valid DEX file containing [strings] and the
 * [types] (as indices into [strings]), mirroring the header/string/type layout
 * [DexStringExtractor] reads. Used to prove the scan parses *dexfied* plugin
 * artifacts, not just `.class` jars.
 */
internal object TestDexBuilder {

    fun build(strings: List<String>, types: List<Int>): ByteArray {
        val headerSize = 0x70
        val stringIdsOff = headerSize
        val typeIdsOff = stringIdsOff + strings.size * 4
        // string_data items come after the type_ids table; `string_ids` entries
        // store FILE-absolute offsets into that region.
        val dataStart = typeIdsOff + types.size * 4
        val stringData = mutableListOf<Byte>()

        fun uleb(value: Int): List<Byte> {
            var v = value
            val out = mutableListOf<Byte>()
            do {
                var b = (v and 0x7F)
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out += b.toByte()
            } while (v != 0)
            return out
        }

        val stringOffsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            stringOffsets[i] = dataStart + stringData.size
            stringData += uleb(s.length)
            stringData += s.toByteArray().toList()
            stringData += 0x00.toByte()
        }

        val total = dataStart + stringData.size
        val file = ByteArray(total)
        byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00).copyInto(file, 0) // "dex\n035\0"
        putU4(file, 0x38, strings.size)
        putU4(file, 0x3C, stringIdsOff)
        putU4(file, 0x40, types.size)
        putU4(file, 0x44, typeIdsOff)
        strings.forEachIndexed { i, _ -> putU4(file, stringIdsOff + i * 4, stringOffsets[i]) }
        types.forEachIndexed { i, idx -> putU4(file, typeIdsOff + i * 4, idx) }
        stringData.forEachIndexed { i, b -> file[dataStart + i] = b }
        return file
    }

    private fun putU4(bytes: ByteArray, off: Int, value: Int) {
        bytes[off] = (value and 0xFF).toByte()
        bytes[off + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[off + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}