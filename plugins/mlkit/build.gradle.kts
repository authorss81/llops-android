import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.jar.JarFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 175 (R2-KS-21): the downloadable ML Kit OCR + translation plugin module.
//
// This module is deliberately NOT a dependency of `:app` — building it produces
// the SIGNED, downloadable artifact users install from the Plugin Store (HTTPS
// download → pinned-cert + SHA-256 verify → DexClassLoader load). Every ML Kit
// payload that used to ride in the base APK (libmlkit_google_ocr_pipeline.so,
// libtranslate_jni.so, assets/mlkit-google-ocr-models/**, translate model
// metadata) lives HERE, in the artifact — never in the base APK. See
// `docs/plugin-architecture.md` + `docs/PLUGINS.md`.
//
// The module compiles ONLY against `plugin-sdk` (the shared framework surface —
// including the OCR/translation serving interfaces moved there in phase-175)
// plus the ML Kit engines + coroutines it carries itself; it never imports app
// packages.
android {
    namespace = "com.authorss81.noteflow.mlkit"
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
    // The shared framework surface (NoteflowPlugin, OcrPlugin, TranslationPlugin,
    // PluginContext…).
    implementation(project(":plugin-sdk"))
    // The engines the plugin carries inside its artifact.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    // R2-KS-21 / dependency-verification: ML Kit 16.x/17.x POMs pin OLD androidx
    // transitive versions (activity 1.0.0, lifecycle-livedata 2.0.0, core 1.9.0
    // …) that the BASE-APK graph overrides via the Compose BOM — so our module's
    // classpath would resolve unverified artifacts the app never resolves.
    // Constrain the module graph to the SAME androidx versions the app resolves
    // (and `gradle/verification-metadata.xml` already pins) so everything the
    // module resolves is verified and shared with the app's runtime surface.
    constraints {
        implementation("androidx.activity:activity:1.9.3")
        implementation("androidx.annotation:annotation:1.8.1")
        implementation("androidx.annotation:annotation-experimental:1.4.1")
        implementation("androidx.arch.core:core-common:2.2.0")
        implementation("androidx.arch.core:core-runtime:2.2.0")
        implementation("androidx.collection:collection:1.4.4")
        implementation("androidx.core:core:1.15.0")
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.emoji2:emoji2:1.3.0")
        implementation("androidx.emoji2:emoji2-views-helper:1.3.0")
        implementation("androidx.fragment:fragment:1.5.7")
        implementation("androidx.lifecycle:lifecycle-common:2.8.7")
        implementation("androidx.lifecycle:lifecycle-livedata:2.8.7")
        implementation("androidx.lifecycle:lifecycle-livedata-core:2.8.7")
        implementation("androidx.lifecycle:lifecycle-process:2.8.7")
        implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
        implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
        implementation("androidx.savedstate:savedstate:1.2.1")
        implementation("androidx.startup:startup-runtime:1.1.1")
    }
}

// --- Downloadable artifact packaging -----------------------------------------
//
// Resolve the RAW ML Kit AARs (AGP's variant transforms would otherwise swallow
// `jni/` native libs + `assets/` + `res/` model files) so the artifact carries:
//   1. this module's compiled Kotlin classes (the plugin + its engines);
//   2. the engine's `com.google.mlkit/**`, `com.google.android.gms/**`, gms
//      transitives + their third-party runtime table (guava/protobuf/grpc/netty/
//      …) — classes that must NOT ship in the base APK (R2-KS-21);
//   3. the native `.so` libraries under `lib/<abi>/` (extracted + preloaded at
//      runtime by `PluginPayloadLoader`, which reads them as jar resources);
//   4. the bundled Latin OCR models + translation metadata under `assets/`
//      (extracted to app-private files at runtime — the ML Kit native engine
//      resolves them from the path AndroidAssetUtil seeds with the cache dir);
//   5. the `META-INF/plugin-entry.properties` descriptor the Phase-23 loader
//      reads (`plugin.id` + `plugin.class`).
val mlkitAars = configurations.register("mlkitAars")
dependencies.add("mlkitAars", libs.mlkit.text.recognition)
dependencies.add("mlkitAars", libs.mlkit.translate)

// R2-KS-21 / dependency-verification: the raw-AAR graph (transitive AND leaf)
// must not pull OLD androidx versions the lockfile has never seen. Force the
// SAME androidx versions the app resolves (and `gradle/verification-metadata.xml`
// already pins) so every resolved artifact is verified. ML Kit/gms artifacts are
// already in the lockfile.
mlkitAars.configure {
    resolutionStrategy.eachDependency {
        val requested = "${requested.group}:${requested.name}"
        val forced = ANDROIDX_VERIFIED_VERSIONS[requested]
        if (forced != null) useVersion(forced)
    }
}

/** androidx versions the app graph resolves + the dependency-verification
 *  lockfile already pins; force these on every configuration that resolves the
 *  ML Kit AAR graph so nothing unverified slips in (R2-KS-21). */
private val ANDROIDX_VERIFIED_VERSIONS: Map<String, String> = mapOf(
    "androidx.activity:activity" to "1.9.3",
    "androidx.annotation:annotation" to "1.8.1",
    "androidx.annotation:annotation-experimental" to "1.4.1",
    "androidx.arch.core:core-common" to "2.2.0",
    "androidx.arch.core:core-runtime" to "2.2.0",
    "androidx.collection:collection" to "1.4.4",
    "androidx.core:core" to "1.15.0",
    "androidx.core:core-ktx" to "1.15.0",
    "androidx.emoji2:emoji2" to "1.3.0",
    "androidx.emoji2:emoji2-views-helper" to "1.3.0",
    "androidx.fragment:fragment" to "1.5.7",
    "androidx.lifecycle:lifecycle-common" to "2.8.7",
    "androidx.lifecycle:lifecycle-livedata" to "2.8.7",
    "androidx.lifecycle:lifecycle-livedata-core" to "2.8.7",
    "androidx.lifecycle:lifecycle-process" to "2.8.7",
    "androidx.lifecycle:lifecycle-runtime" to "2.8.7",
    "androidx.lifecycle:lifecycle-viewmodel" to "2.8.7",
    "androidx.lifecycle:lifecycle-viewmodel-savedstate" to "2.8.7",
    "androidx.savedstate:savedstate" to "1.2.1",
    "androidx.startup:startup-runtime" to "1.1.1"
)

/** The ML Kit + gms ENGINE AARs the artifact carries (excludes androidx — their
 *  classes/asserts/natives are the host's, never duplicated). */
val mlkitAarFiles: Provider<List<File>> = providers.provider {
    mlkitAars.get().files.filter { it.name.endsWith(".aar") }.sortedBy { it.name }
}

/** Explode each raw AAR's nested `classes.jar` to a stable temp file. AARs
 *  without a `classes.jar` entry are skipped (their classes are the host's, e.g.
 *  androidx/AndroidX runtime classes the parent-first classloader provides). */
val explodedClassJars: Provider<List<File>> = providers.provider {
    mlkitAarFiles.get().mapNotNull { aar ->
        val exploded = layout.buildDirectory.file("tmp/classes/${aar.name}.classes.jar").get().asFile
        val needsRebuild = !exploded.exists() || exploded.length() == 0L || exploded.lastModified() < aar.lastModified()
        if (needsRebuild) {
            exploded.parentFile.mkdirs()
            var found = false
            JarFile(aar).use { archive ->
                val classesEntry = archive.getEntry("classes.jar") ?: return@mapNotNull null
                found = true
                archive.getInputStream(classesEntry).use { input ->
                    exploded.outputStream().use { output -> input.copyTo(output) }
                }
            }
            exploded
        } else {
            exploded.takeIf { it.isFile }
        }
    }
}

/** Top-level class prefixes the artifact must carry (ML Kit + its gms/runtime
 *  table). Anything outside these prefixes comes from the host app's classloader
 *  (androidx, kotlin, java) — parent-first, never duplicated. */
private val PACKAGED_CLASS_PREFIXES = listOf(
    "com/google/mlkit/",
    "com/google/android/gms/",
    "com/google/android/libraries/",
    "com/google/android/datatransport/",
    "com/google/firebase/",
    // gms/guava/runtime third-party table (resolved as jars, not AARs).
    "com/google/protobuf/",
    "com/google/flatbuffers/",
    "com/google/common/",
    "com/google/thirdparty/",
    "com/google/auto/",
    "com/google/errorprone/",
    "com/google/j2objc/",
    "com/google/rpc/",
    "com/google/longrunning/",
    "com/google/api/",
    "com/google/http/",
    "com/google/type/",
    "io/grpc/",
    "io/netty/",
    "org/xerial/"
)

tasks.register<Jar>("packagePlugin") {
    group = "plugin-artifact"
    description = "Builds the UNSIGNED downloadable ML Kit plugin jar (the input to signPlugin)."
    dependsOn("compileReleaseKotlin")

    archiveFileName.set("mlkit-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-artifact"))

    // Reproducible jar: fixed timestamps + deterministic entry order, so the
    // same source always produces the same artifact bytes and the pinned SHA-256
    // stays valid across rebuilds (the app pins this exact digest).
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    // 1. This module's own compiled classes.
    from(layout.buildDirectory.dir("tmp/kotlin-classes/release"))

    // 2. Engine classes, pulled from the raw AARs' nested classes.jar (exploded
    //    once) + the raw third-party jars — the ML Kit / gms runtime table that
    //    must NOT ship in the base APK.
    from(provider {
        explodedClassJars.get().map { zipTree(it) } +
            mlkitAars.get().files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }.map { zipTree(it) }
    }) {
        include(PACKAGED_CLASS_PREFIXES.map { "$it**" })
        includeEmptyDirs = false
    }

    // 3. Native libs: `jni/<abi>/*.so` in each AAR become `lib/<abi>/*.so` in
    //    the artifact (the runtime resource path PluginPayloadLoader reads).
    from(provider { mlkitAarFiles.get().map { zipTree(it) } }) {
        include("jni/**")
        eachFile { path = "lib/" + path.removePrefix("jni/") }
        includeEmptyDirs = false
    }

    // 4. Bundled models + translation metadata as `assets/…` (extracted to
    //    app-private files at runtime — never baked into the base APK).
    from(provider { mlkitAarFiles.get().map { zipTree(it) } }) {
        include("assets/mlkit-google-ocr-models/**")
        includeEmptyDirs = false
    }
    from(provider { mlkitAarFiles.get().map { zipTree(it) } }) {
        include("res/raw/translate_models_metadata.json", "res/xml/rapid_response_client_defaults.xml")
        eachFile { path = "assets/" + path.substringAfterLast('/') }
        includeEmptyDirs = false
    }

    // 5. The descriptor the Phase-23 loader uses to materialize the plugin.
    from(provider {
        val descriptorDir = temporaryDir.resolve("descriptor")
        descriptorDir.resolve("META-INF").mkdirs()
        descriptorDir.resolve("META-INF/plugin-entry.properties").writeText(
            "plugin.id=com.authorss81.noteflow.plugins.mlkit\n" +
                "plugin.class=com.authorss81.noteflow.mlkit.OnDeviceMlKitPlugin\n"
        )
        descriptorDir
    })
}

// --- Signing ----------------------------------------------------------------
//
// Mirror of `plugins/llm/build.gradle.kts` — the downloadable artifact MUST be
// signed before the Phase-23 runtime accepts it. The signing identity is the ONE
// real, operator-held plugin-signing keystore (B2-DEPS-04), provisioned ONLY
// from the secret store; when the env vars are unset the signing tasks FAIL
// LOUDLY (no ephemeral self-signed fallback, no default credential).
private val KEYSTORE_ALIAS = "plugin-signing"

private fun requirePluginSigningKeystoreB64(): String {
    val b64 = providers.environmentVariable("PLUGIN_SIGNING_KEYSTORE_B64").orNull
        ?.takeIf { it.isNotBlank() }
    return b64 ?: throw GradleException(
        "ML Kit plugin signing refused (B2-DEPS-04): PLUGIN_SIGNING_KEYSTORE_B64 is unset. " +
            "The downloadable artifact must be signed by the ONE real plugin-signing " +
            "keystore provisioned from the secret store."
    )
}

private fun requirePluginSigningStorePass(): String {
    val pass = providers.environmentVariable("PLUGIN_SIGNING_STORE_PASS").orNull
        ?.takeIf { it.isNotBlank() }
    return pass ?: throw GradleException(
        "ML Kit plugin signing refused (B2-DEPS-04): PLUGIN_SIGNING_STORE_PASS is unset."
    )
}

private fun pluginSigningKeyPass(): String =
    providers.environmentVariable("PLUGIN_SIGNING_KEY_PASS").orNull
        ?.takeIf { it.isNotBlank() }
        ?: requirePluginSigningStorePass()

private val PLUGIN_SIGNING_TASK_NAMES = setOf(
    "signPlugin", "verifyPluginSignature", "pluginMetadata"
)

gradle.taskGraph.whenReady {
    if (allTasks.any { task -> task.project == project && task.name in PLUGIN_SIGNING_TASK_NAMES }) {
        val keystoreSet =
            providers.environmentVariable("PLUGIN_SIGNING_KEYSTORE_B64").orNull?.isNotBlank() == true
        val storePassSet =
            providers.environmentVariable("PLUGIN_SIGNING_STORE_PASS").orNull?.isNotBlank() == true
        if (!keystoreSet || !storePassSet) {
            throw GradleException(
                "ML Kit plugin signing refused (B2-DEPS-04): PLUGIN_SIGNING_KEYSTORE_B64 and " +
                    "PLUGIN_SIGNING_STORE_PASS must both be set from the secret store. " +
                    "There is no ephemeral self-signed keystore fallback and no default " +
                    "password in this build. (keystore set=$keystoreSet, store pass set=$storePassSet)"
            )
        }
    }
}

val pluginSigningKeystore: Provider<RegularFile> = providers.provider {
    val fromEnv = requirePluginSigningKeystoreB64()
    val target = layout.buildDirectory.file("plugin-signing/plugin-signing.jks").get()
    val file = target.asFile
    if (!file.exists() || file.length() == 0L) {
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(fromEnv))
    }
    target
}

private fun loadSigningCert(): X509Certificate {
    val storePass = requirePluginSigningStorePass()
    val keyStore = KeyStore.getInstance("JKS")
    pluginSigningKeystore.get().asFile.inputStream().use {
        keyStore.load(it, storePass.toCharArray())
    }
    return keyStore.getCertificate(KEYSTORE_ALIAS) as X509Certificate
}

tasks.register("signPlugin") {
    group = "plugin-artifact"
    description = "Signs mlkit-plugin.jar with the plugin-signing keystore (jarsigner, SHA256withRSA)."
    dependsOn("packagePlugin")
    inputs.file(layout.buildDirectory.file("plugin-artifact/mlkit-plugin.jar"))
    outputs.file(layout.buildDirectory.file("plugin-artifact/mlkit-plugin-signed.jar"))
    doLast {
        val storePass = requirePluginSigningStorePass()
        val keyPass = pluginSigningKeyPass()
        val jar = layout.buildDirectory.file("plugin-artifact/mlkit-plugin.jar").get().asFile
        val signed = layout.buildDirectory.file("plugin-artifact/mlkit-plugin-signed.jar").get().asFile
        runExternal(
            listOf(
                "jarsigner",
                "-keystore", pluginSigningKeystore.get().asFile.absolutePath,
                "-storepass", storePass,
                "-keypass", keyPass,
                "-sigalg", "SHA256withRSA",
                "-digestalg", "SHA-256",
                "-signedjar", signed.absolutePath,
                jar.absolutePath,
                KEYSTORE_ALIAS
            ),
            "ML Kit plugin signing failed (jarsigner)"
        )
        println("ML Kit plugin artifact signed: ${signed.absolutePath}")
    }
}

tasks.register("verifyPluginSignature") {
    group = "plugin-artifact"
    description = "Verifies the signed artifact's JAR signature + pinned certificate hash (jarsigner -verify)."
    dependsOn("signPlugin")
    doLast {
        val signed = layout.buildDirectory.file("plugin-artifact/mlkit-plugin-signed.jar").get().asFile
        runExternal(
            listOf("jarsigner", "-verify", signed.absolutePath),
            "ML Kit plugin signature verification failed (jarsigner -verify)"
        )
        println("ML Kit plugin artifact signature verified: ${signed.absolutePath}")
    }
}

private fun runExternal(cmd: List<String>, failureMessage: String) {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    process.inputStream.use { it.copyTo(ByteArrayOutputStream()) }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException("$failureMessage (exit code $exitCode)")
    }
}

// --- Metadata ---------------------------------------------------------------
//
// Emits the exact `sha256` (hex) + `pinnedCertHash` (`sha256/<base64>`) the app
// must pin in its catalog seed so a signed artifact from THIS build passes both
// the TLS pin and the JAR-signature pin. A future `:app:generateMlKitPluginSeed`
// task consumes this file exactly like `:app:generateLlmPluginSeed` consumes the
// LLM module's metadata.
val pluginMetadataFile = layout.buildDirectory.file("plugin-artifact/plugin-metadata.properties")

tasks.register("pluginMetadata") {
    group = "plugin-artifact"
    description = "Writes plugin-metadata.properties (sha256 + pinnedCertHash of the SIGNED artifact)."
    dependsOn("signPlugin")
    inputs.file(layout.buildDirectory.file("plugin-artifact/mlkit-plugin-signed.jar"))
    outputs.file(pluginMetadataFile)
    doLast {
        val signed = layout.buildDirectory.file("plugin-artifact/mlkit-plugin-signed.jar").get().asFile
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(signed.readBytes())
            .joinToString("") { "%02x".format(it) }
        val certPin = PinnedCertHashValue.of(loadSigningCert())
        pluginMetadataFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "artifact=mlkit-plugin-signed.jar\n" +
                    "sha256=$sha256\n" +
                    "pinnedCertHash=$certPin\n" +
                    "sizeBytes=${signed.length()}\n" +
                    "version=1.0.0\n"
            )
        }
        println("ML Kit plugin metadata written to ${pluginMetadataFile.get().asFile}")
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