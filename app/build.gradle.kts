import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.authorss81.noteflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.inkflow.app.bkxjrz"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // B1-PLAT-1 (phase-57): the release APK may ONLY be signed by a real,
    // externally-supplied release keystore. There is NO debug-keystore fallback
    // and NO in-repo base64 keystore blob. When `KEYSTORE_FILE`/credentials are
    // unset the signing config stays empty (storeFile = null), and AGP's
    // `:app:validateSigningRelease` (wired below as the release signingConfig)
    // fails the release build loudly instead of emitting a debug-signed "release"
    // APK. `gradle assembleDebug` is untouched — the debug variant keeps AGP's
    // auto-generated debug keystore.
    signingConfigs {
        create("releaseConfig") {
            val ksFilePath = System.getenv("KEYSTORE_FILE")
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")
            val ksAlias = System.getenv("KEY_ALIAS")
            val ksKeyPass = System.getenv("KEY_PASSWORD")

            // All-or-nothing: only a real, complete, existing keystore qualifies.
            // If any of the four variables is missing/blank, or the file is absent,
            // the config stays EMPTY (storeFile = null) and the release build is
            // refused — never a debug-signed "release" APK. Paths are resolved
            // against the REPO ROOT (so `./release.keystore` = `rootDir/release.keystore`).
            if (ksFilePath != null &&
                !ksPassword.isNullOrBlank() &&
                !ksAlias.isNullOrBlank() &&
                !ksKeyPass.isNullOrBlank() &&
                rootProject.file(ksFilePath).exists()
            ) {
                storeFile = rootProject.file(ksFilePath)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        debug {
            // No custom signingConfig: AGP's built-in "debug" config is used,
            // which AUTO-GENERATES ~/.android/debug.keystore on first build.
            // (A custom signingConfig pointing at a missing keystore fails
            // :app:validateSigningDebug, so we must NOT override it here.)
        }
        release {
            isMinifyEnabled = true
            // B1-PLAT-1: the release variant is ALWAYS bound to `releaseConfig`.
            // When its storeFile is null (KEYSTORE_FILE/credentials unset), AGP's
            // `:app:validateSigningRelease` task throws "Keystore file not set for
            // signing config 'releaseConfig'" and the build FAILS — no silent
            // fallback to the debug keystore. See `signingConfigs.releaseConfig`.
            signingConfig = signingConfigs.getByName("releaseConfig")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Phase 31 Part C2: force native-lib EXTRACTION at install time. The previous
    // `android:extractNativeLibs="false"` (memory-map .so straight from the APK)
    // causes dlopen/UnsatisfiedLinkError cold-start crashes on SDK 36 devices with
    // strict 16KB/4KB page alignment (SQLCipher .so). useLegacyPackaging=true makes
    // AGP emit extractNativeLibs=true in the merged manifest, so AGP can never
    // re-inject the false value behind our back.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Phase 31: the NoteflowViewModel construction test drives plugin availability
    // checks + AndroidPluginLogger (android.util.Log) against the "mockable" android.jar.
    // Returning default values (instead of throwing "Method ... not mocked") keeps the
    // required pure-JVM construction test green without pulling in Robolectric.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Stopgap: AGP 8.7.3's profile compiler crashes with "String index out of range: 62"
// on the GitHub Actions runner (Gradle 9.6.1). Baseline profiles are NOT wired
// (ROADMAP Phase 21.3 / 32.9) and the old baseline-prof.txt was a dead file that has
// now been deleted — skip profile compilation until release engineering wires them.
tasks.configureEach {
    if (name.startsWith("compile") && name.endsWith("ArtProfile")) {
        enabled = false
    }
}

// B1-PLAT-1 (phase-57) fail-fast gate: whenever a task that produces a SIGNED
// RELEASE artifact is requested while the release keystore is not configured,
// abort before R8/minify burns minutes. Only release signing/packaging task names
// trigger it (assembleRelease, bundleRelease, packageRelease, installRelease,
// validateSigningRelease) so quality-only release tasks like `lintRelease` stay
// runnable without a keystore. Debug builds never carry these task names and are
// unaffected. AGP's `:app:validateSigningRelease` is the second, always-on
// backstop even if a caller somehow bypasses this early gate.
gradle.taskGraph.whenReady {
    if (allTasks.any { task ->
            task.project == project && task.name in RELEASE_SIGNING_TASK_NAMES
        }
    ) {
        val releaseSigning = android.signingConfigs.getByName("releaseConfig")
        if (releaseSigning.storeFile == null || releaseSigning.storeFile?.exists() != true) {
            throw GradleException(
                "Release build refused: no release keystore configured (B1-PLAT-1). " +
                    "Set KEYSTORE_FILE (existing keystore), KEYSTORE_PASSWORD, KEY_ALIAS and " +
                    "KEY_PASSWORD, then run the release task again. The debug-keystore/repo-blob " +
                    "fallbacks were removed — a release APK can no longer be produced signed with " +
                    "the well-known Android debug key. See docs/RELEASE.md."
            )
        }
    }
}
private val RELEASE_SIGNING_TASK_NAMES = setOf(
    "assembleRelease", "bundleRelease", "packageRelease", "installRelease",
    "validateSigningRelease"
)

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Phase 29: shared plugin framework surface (NoteflowPlugin, PluginCapability,
    // PluginContext, PluginEntry, PluginVersion…) compiled once so downloadable
    // plugin artifacts (plugins/llm) share class identity with the base app.
    implementation(project(":plugin-sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher (full-database at-rest encryption)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)

    // Jetpack Ink API
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.strokes)

    // Coil & Utilities
    implementation(libs.coil.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.gson)

    // Markdown rendering
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)

    // Phase 12: on-device, offline OCR (ML Kit text-recognition, bundled model).
    // Runs offline with no API key and no INTERNET. See plugins/ocr/OnDeviceOcrPlugin.
    implementation(libs.mlkit.text.recognition)

    // Phase 16: on-device translation (ML Kit translate — keyless, offline-first,
    // models download on explicit user action).
    implementation(libs.mlkit.translate)

    // Phase 29: the MediaPipe tasks-genai local-LLM engine is deliberately NOT
    // in the base APK. It ships as the downloadable, signature-verified
    // `plugins/llm` artifact (Plugin Store) so users who never use the AI
    // assistant don't carry its ~50 MB of native libraries.

    // Phase 15: productivity & knowledge plugin pack (pure-JVM cores).
    implementation(libs.lingua)
    implementation(libs.jsoup)

    // Phase 31: JVM unit tests. kotlinx-coroutines-test lets the construction test
    // install a Main dispatcher so NoteflowViewModel's eager stateIn(viewModelScope,..)
    // flows can be built outside Android. junit is the existing test runner.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
}

// --- Downloadable-LLM-plugin seed ------------------------------------------
//
// B2-DEPS-04 (phase-76): `:app:generateLlmPluginSeed` — the REAL implementation
// of the task the old `plugins/llm/build.gradle.kts` metadata comment referenced
// but never defined. Maintainers' publishing flow:
//
//   PLUGIN_SIGNING_KEYSTORE_B64=<base64 JKS> PLUGIN_SIGNING_STORE_PASS=<pass> \
//     gradle :app:generateLlmPluginSeed
//
// The task depends on `:plugins:llm:pluginMetadata`, which FAILS LOUDLY unless
// the real plugin-signing keystore env vars are present (the B2-DEPS-04 gate in
// `plugins/llm/build.gradle.kts`), so the seed can never be derived from an
// ephemeral/build-bred keystore — it can only ever match the ONE real CI signing
// identity. The task validates the metadata values and rewrites the committed
// `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedLlmPluginPin.kt`;
// committing the resulting diff (with an app bump) is how a genuine release's
// pin lands in `CompileTimePluginPins.RELEASES` via `llmPluginSeedRelease`.
// It is deliberately NOT wired into `assembleDebug`, so debug builds never need
// the plugin-signing env.
tasks.register<DefaultTask>("generateLlmPluginSeed") {
    group = "plugin-artifact"
    description = "Regenerates src/main/.../GeneratedLlmPluginPin.kt from the SIGNED :plugins:llm artifact metadata " +
        "(requires PLUGIN_SIGNING_KEYSTORE_B64 + PLUGIN_SIGNING_STORE_PASS)."
    dependsOn(":plugins:llm:pluginMetadata")
    doLast {
        val llmProject = rootProject.findProject(":plugins:llm")
            ?: throw GradleException("LLM plugin seed refused: ':plugins:llm' is not part of this build.")
        val metadataFile = llmProject.layout.buildDirectory
            .file("plugin-artifact/plugin-metadata.properties")
            .get().asFile
        if (!metadataFile.isFile) {
            throw GradleException(
                "LLM plugin seed refused: plugin-metadata.properties not found at ${metadataFile.absolutePath}. " +
                    "The seed derives from the SIGNED :plugins:llm artifact; run with the plugin-signing env set."
            )
        }
        val props = Properties().apply {
            metadataFile.inputStream().use { load(it) }
        }
        val artifact = props.getProperty("artifact").orEmpty().trim()
        val sha256 = props.getProperty("sha256").orEmpty().trim()
        val pinnedCertHash = props.getProperty("pinnedCertHash").orEmpty().trim()
        val version = props.getProperty("version").orEmpty().trim()
        if (artifact.isBlank() || sha256.isBlank() || pinnedCertHash.isBlank() || version.isBlank()) {
            throw GradleException(
                "LLM plugin seed refused: plugin-metadata.properties is incomplete " +
                    "(artifact='$artifact', hasSha256=${sha256.isNotBlank()}, " +
                    "hasPinnedCertHash=${pinnedCertHash.isNotBlank()}, version='$version')."
            )
        }
        if (!Regex("^[0-9a-f]{64}$").matches(sha256)) {
            throw GradleException("LLM plugin seed refused: metadata sha256 '$sha256' is not a 64-char lowercase hex digest.")
        }
        if (!Regex("^sha256/[A-Za-z0-9+/=]+$").matches(pinnedCertHash)) {
            throw GradleException("LLM plugin seed refused: metadata pinnedCertHash '$pinnedCertHash' is not a 'sha256/<base64>' pin.")
        }
        val versionParts = version.split('.').map { it.toIntOrNull() }
        if (versionParts.size != 3 || versionParts.any { it == null }) {
            throw GradleException("LLM plugin seed refused: metadata version '$version' is not 'major.minor.patch'.")
        }
        val (major, minor, patch) = versionParts.map { it!! }

        val seedDir = project.layout.projectDirectory
            .dir("src/main/kotlin/com/authorss81/noteflow/plugins/runtime")
        val seedFile = seedDir.file("GeneratedLlmPluginPin.kt").asFile
        seedFile.parentFile.mkdirs()
        seedFile.writeText(
            """
            |package com.authorss81.noteflow.plugins.runtime
            |
            |/**
            | * AUTO-GENERATED by `:app:generateLlmPluginSeed` — DO NOT EDIT BY HAND.
            | *
            | * The application-compiled pin of the ONE real, CI-signed LLM plugin
            | * release (B2-DEPS-04). The generator requires the real signing keystore
            | * (`PLUGIN_SIGNING_KEYSTORE_B64` + `PLUGIN_SIGNING_STORE_PASS`, from the
            | * secret store) because it depends on `:plugins:llm:pluginMetadata`,
            | * which fails loudly without them — so this value can only ever match
            | * the real CI signing identity, never a build-bred keystore.
            | * `CompileTimePluginPins.RELEASES` folds this release into the
            | * app-compiled pin table, so the plugin-update chain accepts ONLY an
            | * artifact signed by this exact key. When this value is null no LLM
            | * plugin release is pinned yet and the chain refuses every offer,
            | * fail-closed (B1-NET-03).
            | */
            |internal val llmPluginSeedRelease: PinnedPluginRelease? = PinnedPluginRelease(
            |    id = "com.authorss81.noteflow.plugins.llm",
            |    version = PluginVersion($major, $minor, $patch),
            |    sha256 = "$sha256",
            |    pinnedCertHash = "$pinnedCertHash",
            |)
            |""".trimMargin()
        )
        println("LLM plugin seed regenerated: ${seedFile.absolutePath}")
        println("  id             = com.authorss81.noteflow.plugins.llm")
        println("  version        = $version")
        println("  sha256         = $sha256")
        println("  pinnedCertHash = $pinnedCertHash")
        println("Commit the resulting diff to ship this pin in the app build.")
    }
}
