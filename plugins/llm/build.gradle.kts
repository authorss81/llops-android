import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 29: the downloadable LOCAL-LLM plugin module.
//
// This module is deliberately NOT a dependency of `:app` — building it produces
// the signature-verified downloadable artifact that users install from the
// Plugin Store (HTTPS download → pinned-cert + SHA-256 verify → DexClassLoader
// load). The MediaPipe `tasks-genai` engine + its native `.so` libraries live
// HERE (in the artifact), never in the base APK. See
// `docs/plugin-architecture.md` + `docs/PLUGINS.md`.
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

tasks.register<Jar>("packagePlugin") {
    group = "plugin-artifact"
    description = "Builds the unsigned downloadable LLM plugin jar (the store's signature-verified artifact source)."
    dependsOn("compileReleaseKotlin")

    archiveFileName.set("llm-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("plugin-artifact"))

    // 1. This module's own compiled classes.
    from(layout.buildDirectory.dir("tmp/kotlin-classes/release"))

    // 2 + 3. Engine classes + native libs, pulled from the raw AAR (lazily —
    // CopySpec sources must be registered at configuration time, so the AAR is
    // resolved via providers evaluated when the copy runs). The AAR's
    // classes.jar is nested, so it is exploded once to a temp file.
    from(provider {
        val aar = genaiAar.get().files.single { it.name.endsWith(".aar") }
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
    }

    // Native libs: `jni/<abi>/*.so` in the AAR become `lib/<abi>/*.so` in the
    // artifact (the runtime resource path NativeLibraryBundle reads).
    from(provider { genaiAar.get().files.single { it.name.endsWith(".aar") } }) {
        include("jni/**")
        eachFile { path = "lib/" + path.removePrefix("jni/") }
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

tasks.register("printPluginMetadata") {
    group = "plugin-artifact"
    description = "Prints sha256 + pinned-cert metadata for the packaged plugin jar (requires packagePlugin to have run)."
    dependsOn("packagePlugin")
    doLast {
        val jar = layout.buildDirectory.file("plugin-artifact/llm-plugin.jar").get().asFile
        if (!jar.exists()) {
            throw GradleException("plugin jar not found — run :plugins:llm:packagePlugin first")
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(jar.readBytes())
            .joinToString("") { "%02x".format(it) }
        println("LLM plugin artifact: ${jar.absolutePath}")
        println("  sha256 = $sha256")
        println("  size   = ${jar.length()} bytes")
        println("  catalog downloadUrl must serve this jar; pinnedCertHash comes from the signing certificate.")
    }
}
