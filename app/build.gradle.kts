import java.util.Base64

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

    signingConfigs {
        create("releaseConfig") {
            val ksFilePath = System.getenv("KEYSTORE_FILE")
            val debugKs = file("${rootDir}/debug.keystore")
            val base64Ks = file("${rootDir}/debug.keystore.base64")

            if (ksFilePath != null && file(ksFilePath).exists()) {
                storeFile = file(ksFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            } else {
                if (!debugKs.exists() && base64Ks.exists()) {
                    try {
                        val decodedBytes = Base64.getDecoder().decode(base64Ks.readText().trim())
                        debugKs.writeBytes(decodedBytes)
                    } catch (_: Exception) {}
                }
                if (debugKs.exists()) {
                    storeFile = debugKs
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
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
            val relConfig = signingConfigs.getByName("releaseConfig")
            if (relConfig.storeFile != null && relConfig.storeFile?.exists() == true) {
                signingConfig = relConfig
            } else {
                // No release keystore: fall back to AGP's built-in debug config
                // (auto-generated keystore) so CI can still assemble a release APK.
                signingConfig = signingConfigs.getByName("debug")
            }
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
    implementation(libs.security.crypto)
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
