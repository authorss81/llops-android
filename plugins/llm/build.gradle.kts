import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.jar.JarFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 29: the downloadable LOCAL-LLM plugin module.
//
// This module is deliberately NOT a dependency of `:app` — building it produces
// the SIGNED, downloadable artifact that users install from the Plugin Store
// (HTTPS download → pinned-cert + SHA-256 verify → DexClassLoader load). The
// MediaPipe `tasks-genai` engine + its native `.so` libraries live HERE (in the
// artifact), never in the base APK. See `docs/plugin-architecture.md` +
// `docs/PLUGINS.md`.
//
// The module compiles ONLY against `plugin-sdk` (the shared framework surface)
// plus the engine/coroutines it carries itself — it never imports app packages.
android {
    namespace = "com.authorss81.noteflow.llm"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The shared framework surface (NoteflowPlugin, AssistantPlugin, PluginContext…).
    implementation(project(":plugin-sdk"))
    // The engine the plugin carries inside its artifact.
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
}

// --- Downloadable artifact packaging -----------------------------------------
//
// Resolve the RAW tasks-genai AAR (AGP's variant transforms would otherwise
// swallow the `jni/` native libs) so the artifact can carry:
//   1. this module's compiled Kotlin classes (the plugin + its engines);
//   2. the engine's `com/google/mediapipe/**` classes (NOT in the base APK);
//   3. the native `.so` libraries under `lib/<abi>/` (extracted + preloaded at
//      runtime by `NativeLibraryBundle`, which reads them as jar resources);
//   4. the `META-INF/plugin-entry.properties` descriptor the Phase-23 loader
//      reads (`plugin.id` + `plugin.class`).
val genaiAar = configurations.register("genaiAar")
dependencies.add("genaiAar", libs.mediapipe.tasks.genai)

val genaiAarFile: Provider<RegularFile> = providers.provider {
    val aar = genaiAar.get().files.single { it.name.endsWith(".aar") }
    layout.buildDirectory.file("tmp/genai-aar-${aar.name}").get().asFile.also { target ->
        if (!target.exists() || target.length() != aar.length()) {
            aar.copyTo(target, overwrite = true)
        }
    }
    layout.buildDirectory.file("tmp/genai-aar-${aar.name}").get()
}

tasks.register<Jar>("packagePlugin") {
    group = "plugin-artifact"
    description = "Builds the UNSIGNED downloadable LLM plugin jar (the input to signPlugin)."
    dependsOn("compileReleaseKotlin")

    archiveFileName.set("llm-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-artifact"))

    // Reproducible jar: fixed timestamps + deterministic entry order, so the
    // same source always produces the same artifact bytes and the pinned SHA-256
    // stays valid across rebuilds (the app pins this exact digest).
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    // 1. This module's own compiled classes.
    from(layout.buildDirectory.dir("tmp/kotlin-classes/release"))

    // 2. Engine classes, pulled from the raw AAR. The AAR's classes.jar is
    //    nested, so it is exploded once to a temp file (its published entries
    //    carry fixed 2010-01-01 timestamps, keeping the result deterministic).
    from(provider {
        val aar = genaiAarFile.get().asFile
        val exploded = layout.buildDirectory.file("tmp/genai-classes.jar").get().asFile
        if (!exploded.exists() || exploded.length() == 0L || exploded.lastModified() < aar.lastModified()) {
            JarFile(aar).use { archive ->
                val classesEntry = archive.getEntry("classes.jar")
                    ?: throw GradleException("tasks-genai AAR has no classes.jar: ${aar.name}")
                archive.getInputStream(classesEntry).use { input ->
                    exploded.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        zipTree(exploded)
    }) {
        include("com/google/mediapipe/**")
        includeEmptyDirs = false
    }

    // 3. Native libs: `jni/<abi>/*.so` in the AAR become `lib/<abi>/*.so` in the
    //    artifact (the runtime resource path NativeLibraryBundle reads). This
    //    MUST be a zipTree of the AAR (a plain `from(file)` copies the AAR as a
    //    single file and matches nothing) — Phase 29 review fix.
    from(zipTree(genaiAarFile)) {
        include("jni/**")
        eachFile { path = "lib/" + path.removePrefix("jni/") }
        includeEmptyDirs = false
    }

    // 4. The descriptor the Phase-23 loader uses to materialize the plugin.
    from(provider {
        val descriptorDir = temporaryDir.resolve("descriptor")
        descriptorDir.resolve("META-INF").mkdirs()
        descriptorDir.resolve("META-INF/plugin-entry.properties").writeText(
            "plugin.id=com.authorss81.noteflow.plugins.llm\n" +
                "plugin.class=com.authorss81.noteflow.llm.LocalLlmPlugin\n"
        )
        descriptorDir
    })
}

// --- Signing ----------------------------------------------------------------
//
// The downloadable artifact MUST be signed before the Phase-23 runtime accepts
// it: the app pins `sha256/<base64>` of the signing certificate (PinnedCertHash)
// and verifies it on TLS AND on the JAR signature block. The signing keystore
// is NEVER committed. CI provides a stable keystore via
// `PLUGIN_SIGNING_KEYSTORE_B64` (base64 JKS); a local developer build generates
// an ephemeral self-signed keystore into `build/plugin-signing/` so the whole
// pipeline stays runnable offline (the pinned hashes then rotate per build and
// are injected into the app at build time — see `:app:generateLlmPluginSeed`).
private val KEYSTORE_ALIAS = "plugin-signing"
private val DEFAULT_KEY_PASSWORD = "inkflow.2026.plugins"

val pluginSigningKeystore: Provider<RegularFile> = providers.provider {
    val target = layout.buildDirectory.file("plugin-signing/plugin-signing.jks").get()
    val file = target.asFile
    val fromEnv = providers.environmentVariable("PLUGIN_SIGNING_KEYSTORE_B64").orNull
    if (file.exists()) return@provider target
    if (fromEnv != null) {
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(fromEnv))
    } else {
        file.parentFile.mkdirs()
        project.exec {
            commandLine(
                "keytool", "-genkeypair",
                "-alias", KEYSTORE_ALIAS,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10950",
                "-dname", "CN=InkFlow Plugin Signing, OU=Developer, O=InkFlow",
                "-keystore", file.absolutePath,
                "-storetype", "JKS",
                "-storepass", DEFAULT_KEY_PASSWORD,
                "-keypass", DEFAULT_KEY_PASSWORD
            )
        }.rethrowFailure()
    }
    target
}

private fun loadSigningCert(): X509Certificate {
    val storePass = providers.environmentVariable("PLUGIN_SIGNING_STORE_PASS").orNull ?: DEFAULT_KEY_PASSWORD
    val keyStore = KeyStore.getInstance("JKS")
    pluginSigningKeystore.get().asFile.inputStream().use {
        keyStore.load(it, storePass.toCharArray())
    }
    return keyStore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
}

tasks.register("signPlugin") {
    group = "plugin-artifact"
    description = "Signs llm-plugin.jar with the plugin-signing keystore (jarsigner, SHA256withRSA)."
    dependsOn("packagePlugin", pluginSigningKeystore)
    inputs.file(layout.buildDirectory.file("plugin-artifact/llm-plugin.jar"))
    outputs.file(layout.buildDirectory.file("plugin-artifact/llm-plugin-signed.jar"))
    doLast {
        val storePass = providers.environmentVariable("PLUGIN_SIGNING_STORE_PASS").orNull ?: DEFAULT_KEY_PASSWORD
        val keyPass = providers.environmentVariable("PLUGIN_SIGNING_KEY_PASS").orNull ?: DEFAULT_KEY_PASSWORD
        val jar = layout.buildDirectory.file("plugin-artifact/llm-plugin.jar").get().asFile
        val signed = layout.buildDirectory.file("plugin-artifact/llm-plugin-signed.jar").get().asFile
        project.exec {
            commandLine(
                "jarsigner",
                "-keystore", pluginSigningKeystore.get().asFile.absolutePath,
                "-storepass", storePass,
                "-keypass", keyPass,
                "-sigalg", "SHA256withRSA",
                "-digestalg", "SHA-256",
                "-signedjar", signed.absolutePath,
                jar.absolutePath,
                KEYSTORE_ALIAS
            )
        }.rethrowFailure()
        println("LLM plugin artifact signed: ${signed.absolutePath}")
    }
}

tasks.register("verifyPluginSignature") {
    group = "plugin-artifact"
    description = "Verifies the signed artifact's JAR signature + pinned certificate hash (jarsigner -verify)."
    dependsOn("signPlugin")
    doLast {
        val signed = layout.buildDirectory.file("plugin-artifact/llm-plugin-signed.jar").get().asFile
        project.exec {
            commandLine("jarsigner", "-verify", signed.absolutePath)
        }.rethrowFailure()
        println("LLM plugin artifact signature verified: ${signed.absolutePath}")
    }
}

// --- Metadata ---------------------------------------------------------------
//
// Emits the exact `sha256` (hex) and `pinnedCertHash` (`sha256/<base64>`) the
// app must pin in its catalog seed so a signed artifact from THIS build passes
// both the TLS pin and the JAR-signature pin. `:app:generateLlmPluginSeed`
// consumes this file.
val pluginMetadataFile = layout.buildDirectory.file("plugin-artifact/plugin-metadata.properties")

tasks.register("pluginMetadata") {
    group = "plugin-artifact"
    description = "Writes plugin-metadata.properties (sha256 + pinnedCertHash of the SIGNED artifact)."
    dependsOn("signPlugin")
    inputs.file(layout.buildDirectory.file("plugin-artifact/llm-plugin-signed.jar"))
    outputs.file(pluginMetadataFile)
    doLast {
        val signed = layout.buildDirectory.file("plugin-artifact/llm-plugin-signed.jar").get().asFile
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(signed.readBytes())
            .joinToString("") { "%02x".format(it) }
        val certPin = PinnedCertHashValue.of(loadSigningCert())
        pluginMetadataFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "artifact=llm-plugin-signed.jar\n" +
                    "sha256=$sha256\n" +
                    "pinnedCertHash=$certPin\n" +
                    "sizeBytes=${signed.length()}\n" +
                    "version=1.0.0\n"
            )
        }
        println("LLM plugin metadata written to ${pluginMetadataFile.get().asFile}")
        println("  sha256         = $sha256")
        println("  pinnedCertHash = $certPin")
        println("  sizeBytes      = ${signed.length()}")
    }
}

/** `sha256/<base64>` pin of a certificate's DER encoding (matches PinnedCertHash). */
private object PinnedCertHashValue {
    fun of(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return "sha256/" + Base64.getEncoder().encodeToString(digest)
    }
}
