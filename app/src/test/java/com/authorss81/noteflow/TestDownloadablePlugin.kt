package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.runtime.PluginContext
import com.authorss81.noteflow.plugins.runtime.PluginContextAware
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * A downloadable (remote) plugin packaged inside the signed test artifact.
 *
 * It implements the framework interfaces the runtime resolves through the
 * parent classloader ([NoteflowPlugin] + [TextTransformPlugin]) AND the runtime
 * load-time wiring contract ([PluginContextAware], so `setContext` can be
 * asserted). It is compiled into the TEST source set (NOT the base APK); the
 * artifact builder copies its `.class` bytes into the signed JAR.
 */
internal class TestDownloadablePlugin : NoteflowPlugin, TextTransformPlugin, PluginContextAware {

    /** The context the runtime injected via [PluginContextAware.setContext]. */
    var injectedContext: PluginContext? = null

    override val manifest = PluginManifest(
        id = TEST_PLUGIN_ID,
        name = "Remote Test Plugin",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "A downloadable test plugin.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}

    override fun transformText(text: String): String = "remote:${text.uppercase()}"

    override fun setContext(context: PluginContext) {
        injectedContext = context
    }

    companion object {
        const val TEST_PLUGIN_ID = "com.authorss81.noteflow.plugins.test.downloadable"
    }
}

/** A test class that deliberately is NOT a [NoteflowPlugin] (negative tests). */
internal class NotAPlugin

/**
 * Builds SIGNED plugin JAR artifacts for the pure-JVM runtime tests.
 *
 * A throwaway keystore is generated with the JDK's `keytool` binary (present in
 * every JDK distribution), the JAR is signed with the public
 * [JarSigner] (`jdk.jartool`), and the artifact's SHA-256 + certificate pin are
 * computed the same way production computes them. Tests may request a SECOND
 * keystore to prove a different key is rejected.
 */
internal object TestArtifactBuilder {

    data class SignedArtifact(
        val file: File,
        val sha256Hex: String,
        val pinnedCertHash: String,
        val cert: java.security.cert.X509Certificate
    )

    data class Keystore(
        val file: File,
        val alias: String,
        val password: CharArray
    ) {
        fun privateKeyEntry(): KeyStore.PrivateKeyEntry {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { ks.load(it, password) }
            return ks.getEntry(alias, KeyStore.PasswordProtection(password)) as KeyStore.PrivateKeyEntry
        }
    }

    /** Generate a fresh signing keystore (independent per call). */
    fun newKeystore(workDir: File, name: String): Keystore {
        val ksFile = File(workDir, "$name.p12")
        val password = "noteflow-test-pass"
        val keytool = keytoolBinary()
        val cmd = listOf(
            keytool,
            "-genkeypair", "-alias", "plugin",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-validity", "3650",
            "-dname", "CN=Noteflow Plugin Test, O=Noteflow, C=XX",
            "-keystore", ksFile.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-noprompt"
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        check(ksFile.isFile) { "keytool produced no keystore: $output" }
        return Keystore(ksFile, "plugin", password.toCharArray())
    }

    /**
     * Build a signed artifact whose JAR holds [pluginClassName]'s class bytes
     * plus the `META-INF/plugin-entry.properties` descriptor declaring
     * [pluginId]/[pluginClassName].
     *
     * @param sign true to JAR-sign the artifact (the verifier REQUIRES a
     *   signature; false builds an unsigned jar for the "not signed" test).
     * @param descriptorId the id written into the descriptor (null omits the
     *   descriptor entirely for the missing-descriptor test).
     */
    /**
     * Monotonic counter so artifact filenames never collide even when two
     * `System.nanoTime()` calls land in the same coarse clock tick — a collision
     * used to let a later `build` silently OVERWRITE an earlier artifact,
     * corrupting digest-based tests (e.g. the "hash mismatch" update test
     * intermittently resolving to a match and succeeding).
     */
    private val buildSeq = java.util.concurrent.atomic.AtomicLong(0)

    fun build(
        workDir: File,
        keystore: Keystore,
        pluginClassName: String = TestDownloadablePlugin::class.java.name,
        pluginId: String = TestDownloadablePlugin.TEST_PLUGIN_ID,
        descriptorId: String? = pluginId,
        sign: Boolean = true
    ): SignedArtifact {
        val seq = buildSeq.incrementAndGet()
        val unsigned = File(workDir, "unsigned-$seq-${System.nanoTime()}.jar")
        writeUnsignedJar(unsigned, pluginClassName, descriptorId)

        val artifactFile = File(workDir, "artifact-$seq-${System.nanoTime()}.jar")
        if (sign) {
            signWithJarsigner(unsigned, artifactFile, keystore)
        } else {
            unsigned.copyTo(artifactFile)
        }
        unsigned.delete()
        val sha256 = com.authorss81.noteflow.plugins.runtime.PluginDigest.sha256Hex(artifactFile)!!
        val cert = keystore.privateKeyEntry().certificateChain.first() as java.security.cert.X509Certificate
        val pin = "sha256/" + com.authorss81.noteflow.plugins.runtime.PinnedCertHash.base64Sha256(cert)
        return SignedArtifact(artifactFile, sha256, pin, cert)
    }

    /**
     * Sign [unsigned] with the JDK's `jarsigner` tool (same JVM family, so the
     * `--provider`/keystore interplay is stable). The `jdk.security.jarsigner`
     * API is in the `jdk.jartool` module which is not on the unit-test compile
     * classpath, so the CLI tool (present in every JDK distribution) is used.
     */
    private fun signWithJarsigner(unsigned: File, signed: File, keystore: Keystore) {
        val jarsigner = File(System.getProperty("java.home"), "bin/jarsigner")
        val cmd = listOf(
            jarsigner.absolutePath,
            "-keystore", keystore.file.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", String(keystore.password),
            "-keypass", String(keystore.password),
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-signedjar", signed.absolutePath,
            unsigned.absolutePath,
            keystore.alias
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "jarsigner failed: $output" }
    }

    private fun writeUnsignedJar(file: File, pluginClassName: String, descriptorId: String?) {
        JarOutputStream(FileOutputStream(file)).use { jar ->
            if (descriptorId != null) {
                jar.putNextEntry(ZipEntry("META-INF/plugin-entry.properties"))
                jar.write(
                    ("plugin.id=$descriptorId\nplugin.class=$pluginClassName\n").toByteArray(Charsets.UTF_8)
                )
                jar.closeEntry()
            }
            val classPath = pluginClassName.replace('.', '/') + ".class"
            jar.putNextEntry(ZipEntry(classPath))
            val resource = TestArtifactBuilder::class.java.classLoader
                .getResourceAsStream(classPath)
                ?: error("class file resource not found: $classPath")
            resource.use { it.copyTo(jar) }
            jar.closeEntry()
        }
    }

    private fun keytoolBinary(): String {
        val home = System.getProperty("java.home")
        val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        return File(home, "bin/keytool$suffix").absolutePath
    }
}
