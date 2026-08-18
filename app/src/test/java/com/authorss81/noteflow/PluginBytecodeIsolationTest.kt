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
import org.junit.Assert.assertFalse
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
    fun `the scoped loader refuses raw egress and process-exec classes - R2-B1N-03`() {
        // Phase-144: java.net.* / javax.net.* xor aggregate the network the
        // plugin must NOT own, and Runtime/ProcessBuilder the process exec it
        // must NOT own. Non-app-namespace delegation used to re-export these to
        // plugin code, making the static scan the ONLY barrier against
        // string-built Class.forName("java.net." + ...) — now the loader chain
        // itself refuses them, no matter how the caller spells the name.
        val scoped = PluginFrameworkClassLoader(parentLoader)
        val forbidden = listOf(
            "java.net.Socket",
            "java.net.URL",
            "java.net.HttpURLConnection",
            "java.net.InetAddress",
            "javax.net.SocketFactory",
            "javax.net.ssl.SSLSocket",
            "javax.net.ssl.HttpsURLConnection",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder"
        )
        for (name in forbidden) {
            try {
                scoped.loadClass(name)
                fail("loadClass('$name') must be refused by the plugin sandbox (R2-B1N-03)")
            } catch (expected: ClassNotFoundException) {
                // refused, as required
            }
        }
        // The sandbox must NOT break the rest of the JDK a benign plugin uses.
        for (name in listOf(
            "java.lang.String", "java.lang.Integer", "java.lang.Math",
            "java.util.List", "java.util.Locale", "java.io.Reader"
        )) {
            scoped.loadClass(name) // must not throw
        }
    }

    @Test
    fun `egress refusal is name-based and catches string-built reflection - R2-B1N-03`() {
        val scoped = PluginFrameworkClassLoader(parentLoader)
        // The runtime gate keys on the resolved NAME, so a plugin that builds
        // "java.net." + "Socket" via concatenation and reflects with
        // Class.forName still lands on java.net.Socket and is refused.
        for (built in listOf(
            "java.net.Socket",
            "java." + "net." + "Socket",
            "java.net" + ".URL",
            "java.lang" + ".Runtime"
        )) {
            assertTrue(
                "isEgressForbidden('$built') must be true",
                PluginFrameworkClassLoader.isEgressForbidden(built)
            )
            try {
                Class.forName(built, false, scoped)
                fail("Class.forName('$built') must be refused")
            } catch (expected: ClassNotFoundException) {
                // refused, as required
            }
        }
        // Benign java.lang/java.util/java.io types are outside the egress set.
        assertFalse(PluginFrameworkClassLoader.isEgressForbidden("java.lang.String"))
        assertFalse(PluginFrameworkClassLoader.isEgressForbidden("java.util.List"))
        assertFalse(PluginFrameworkClassLoader.isEgressForbidden("java.io.Reader"))
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

    @Test
    fun `the static scan rejects a dot-form network egress Class forName literal`() {
        // Phase-46 review: slash-form type references were caught, but a
        // reflection literal `Class.forName("java.net.HttpURLConnection")`
        // compiles to the DOT form — the old exact-slash set never matched it.
        val artifact = signedArtifact(HostileForNameNetworkPlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Rejected
        )
        val reason = (scan as ArtifactStaticScan.Result.Rejected).reason
        assertTrue("reason=$reason", reason.contains("network"))
    }

    @Test
    fun `the static scan rejects a dot-form string-built VaultKeyHolder reference`() {
        val artifact = signedArtifact(HostileForNameVaultPlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Rejected
        )
        val reason = (scan as ArtifactStaticScan.Result.Rejected).reason
        assertTrue("reason=$reason", reason.contains("services") || reason.contains("VaultKeyHolder"))
    }

    @Test
    fun `the static scan rejects process-execution classes`() {
        // Phase-46 review: ProcessBuilder/Runtime.exec were previously out of
        // scope — now refused at the class-name level (slash AND dot forms).
        val pb = signedArtifact(HostileProcessBuilderPlugin::class.java.name)
        val scanPb = ArtifactStaticScan().scan(pb.file)
        assertTrue(
            "ProcessBuilder -> ${(scanPb as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scanPb is ArtifactStaticScan.Result.Rejected
        )
        assertTrue("reason=${(scanPb as ArtifactStaticScan.Result.Rejected).reason}", (scanPb as ArtifactStaticScan.Result.Rejected).reason.contains("process"))

        val rt = signedArtifact(HostileRuntimeExecPlugin::class.java.name)
        val scanRt = ArtifactStaticScan().scan(rt.file)
        assertTrue(
            "Runtime.exec -> ${(scanRt as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scanRt is ArtifactStaticScan.Result.Rejected
        )
        assertTrue("reason=${(scanRt as ArtifactStaticScan.Result.Rejected).reason}", (scanRt as ArtifactStaticScan.Result.Rejected).reason.contains("process"))
    }

    @Test
    fun `the static scan rejects string-fragmented egress and exec class names - R2-B1N-03`() {
        // Phase-144: `Class.forName("java.net." + "Sock" + "et")` never matches
        // an exact token, but the artifact MUST still ship the dot-form
        // package-prefix fragment ("java.net.") — the scan now refuses any
        // parsed string/type carrying one at a word boundary.
        val netJar = File(tmp.root, "frag-net.jar")
        JarOutputStream(netJar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(
                TestDexBuilder.build(
                    strings = listOf("java.net.", "Socket"),
                    types = listOf(0, 1)
                )
            )
            out.closeEntry()
        }
        val netScan = ArtifactStaticScan().scan(netJar)
        assertTrue(
            "fragmented java.net. must be rejected -> ${(netScan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            netScan is ArtifactStaticScan.Result.Rejected
        )
        assertTrue(
            "reason=${(netScan as ArtifactStaticScan.Result.Rejected).reason}",
            (netScan as ArtifactStaticScan.Result.Rejected).reason.contains("fragments")
        )

        val rtJar = File(tmp.root, "frag-rt.jar")
        JarOutputStream(rtJar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(
                TestDexBuilder.build(
                    strings = listOf("java.lang.", "Runtime", "Lcom/authorss81/noteflow/plugins/NoteflowPlugin;"),
                    types = listOf(2)
                )
            )
            out.closeEntry()
        }
        val rtScan = ArtifactStaticScan().scan(rtJar)
        assertTrue(
            "fragmented java.lang. must be rejected -> ${(rtScan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            rtScan is ArtifactStaticScan.Result.Rejected
        )
        assertTrue(
            "reason=${(rtScan as ArtifactStaticScan.Result.Rejected).reason}",
            (rtScan as ArtifactStaticScan.Result.Rejected).reason.contains("fragments")
        )
    }

    @Test
    fun `the static scan does not false-positive on benign dot-form fragments - R2-B1N-03`() {
        // Benign strings that merely CONTAIN a dot between letters around the
        // fragment shape (e.g. "kotlin.jvm." is fine; a slash-form type
        // descriptor java/lang/String carries no dot form; a word-embedded
        // "notjava.net.foo" is not a boundary match) still pass.
        val jar = File(tmp.root, "frag-benign.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(
                TestDexBuilder.build(
                    strings = listOf(
                        "Lcom/authorss81/noteflow/plugins/NoteflowPlugin;",
                        "Ljava/lang/String;",
                        "example.com/notjava.lang.other",
                        "myjava.network.config",
                        "kotlin.jvm.JvmInline"
                    ),
                    types = listOf(0, 1)
                )
            )
            out.closeEntry()
        }
        val scan = ArtifactStaticScan().scan(jar)
        assertTrue(
            "benign dex artifact must pass -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Pass
        )
    }

    @Test
    fun `the static scan does not over-reject benign identifiers resembling a sensitive class`() {
        // Phase-46 review: whole-token matching means a benign plugin's own
        // compound identifiers (method names etc.) are NOT false-rejected.
        val artifact = signedArtifact(BenignLookalikePlugin::class.java.name)
        val scan = ArtifactStaticScan().scan(artifact.file)
        assertTrue(
            "benign lookalike plugin must pass the scan -> ${(scan as? ArtifactStaticScan.Result.Rejected)?.reason}",
            scan is ArtifactStaticScan.Result.Pass
        )
    }

    // ------------------------------------------------------------------ //
    // 2a. The plugins.* host surface must never expose a vault handle (F3)  //
    // ------------------------------------------------------------------ //

    @Test
    fun `no vault-handle types are referenced by code in the resolvable plugin surface`() {
        // Phase-46 review: the sandbox trusts the whole `plugins.*` namespace
        // (that is what a downloadable artifact resolves against). A future
        // host class under `plugins.*` that holds a VaultKeyHolder /
        // SecurityService / NoteflowDatabase / SettingsManager / NoteRepository
        // handle would become artifact-reachable. This pins the invariant:
        // plugin-host CODE (comments + string literals stripped, e.g. the scan's
        // own pattern tables and KDoc mentions) never references those types.
        val root = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/plugins")
        assertTrue("plugins host source root missing: $root", root.isDirectory)
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val code = stripCommentsAndStrings(file.readText())
                ArtifactStaticScan.containsSensitiveToken(code)?.let {
                    violations += "${file.relativeTo(root)} -> $it"
                }
            }
        assertTrue(
            "plugin-host code under plugins.* must not reference vault handles:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
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

    private val ksSeq = java.util.concurrent.atomic.AtomicLong(0)

    private fun signedArtifact(pluginClassName: String): TestArtifactBuilder.SignedArtifact {
        // Per-artifact keystore name: two signedArtifact calls in ONE test share
        // tmp.root, and keytool refuses to re-create an existing alias.
        val name = "isolation-signer-${ksSeq.incrementAndGet()}"
        val ks = TestArtifactBuilder.newKeystore(tmp.root, name)
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

/** A plugin that reaches for a raw socket REFLECTIVELY — a dot-form
 *  `Class.forName("java.net.HttpURLConnection")` literal, which the pre-review
 *  slash-form-only scan could not see. (Compile-time direct reference would
 *  emit the slash CONSTANT_Class entry; the literal is the evasion.) */
internal class HostileForNameNetworkPlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("UNUSED_VARIABLE")
    override fun transformText(text: String): String {
        val conn = Class.forName("java.net.HttpURLConnection")
        val unused = conn
        return text
    }

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile Reflective Network Egress",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that reflects its way to raw sockets.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
}

/** A plugin that string-builds a vault type for reflection. */
internal class HostileForNameVaultPlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("UNUSED_VARIABLE")
    override fun transformText(text: String): String {
        val prefix = "com.authorss81.noteflow.services."
        val clazz = Class.forName(prefix + "VaultKeyHolder")
        val unused = clazz
        return text
    }

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile Reflective Vault",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that reflects its way to the vault DEK.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
}

/** A plugin that spawns a subprocess via ProcessBuilder — the previously
 *  out-of-scope exec vector, now refused at the class-name level. */
internal class HostileProcessBuilderPlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("UNUSED_VARIABLE")
    override fun transformText(text: String): String {
        val pb = java.lang.ProcessBuilder("sh")
        val unused = pb
        return text
    }

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile ProcessBuilder",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that spawns a subprocess.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
}

/** A plugin that shells out via Runtime.getRuntime().exec — same class-name
 *  gate as [HostileProcessBuilderPlugin]. */
internal class HostileRuntimeExecPlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("UNUSED_VARIABLE")
    override fun transformText(text: String): String {
        val proc = Runtime.getRuntime().exec("sh")
        val unused = proc
        return text
    }

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Hostile Runtime exec",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Attack artifact that shells out through Runtime.exec.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
}

/** A BENIGN plugin whose own identifiers merely RESEMBLE the sensitive class
 *  names — the whole-token matching must not false-reject it. */
internal class BenignLookalikePlugin : NoteflowPlugin, TextTransformPlugin {

    @Suppress("UNUSED_VARIABLE")
    override fun transformText(text: String): String {
        val noteCount = getNoteRepositoryCount()
        val securityCached = isSecurityServiceCached()
        val unused = noteCount to securityCached
        return text
    }

    @Suppress("unused")
    private fun getNoteRepositoryCount(): Int = 0

    @Suppress("unused")
    private fun isSecurityServiceCached(): Boolean = false

    override val manifest = PluginManifest(
        id = TestDownloadablePlugin.TEST_PLUGIN_ID,
        name = "Benign Lookalike",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "A benign plugin with suspicious-looking method names.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok
    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}
}

/**
 * Strips Kotlin comments (line/block/KDoc) and string/char literals (regular,
 * triple-quoted) from [source] so a source-level invariant pin tests barely
 * the CODE a plugin-host class exposes, not its docs or message tables.
 * Whole irrelevant spans are replaced with blank output.
 */
private fun stripCommentsAndStrings(source: String): String {
    val out = StringBuilder(source.length)
    val n = source.length
    var i = 0
    while (i < n) {
        val c = source[i]
        when {
            c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                while (i < n && source[i] != '\n') i++
            }
            c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                i += 2
                var depth = 1
                while (i < n && depth > 0) {
                    if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') {
                        depth--
                        i += 2
                    } else {
                        i++
                    }
                }
            }
            c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                i += 3
                while (i < n) {
                    if (source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                        i += 3
                        break
                    }
                    i++
                }
            }
            c == '"' -> {
                i++
                while (i < n) {
                    when {
                        source[i] == '\\' -> i += 2
                        source[i] == '"' -> {
                            i++
                            break
                        }
                        else -> i++
                    }
                }
            }
            c == '\'' -> {
                i++
                while (i < n) {
                    when {
                        source[i] == '\\' -> i += 2
                        source[i] == '\'' -> {
                            i++
                            break
                        }
                        else -> i++
                    }
                }
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
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