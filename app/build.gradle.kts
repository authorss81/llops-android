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
    ndkVersion = "27.0.12077973"

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

        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }
    }

    signingConfigs {
        create("debugConfig") {
            val debugKs = file("${rootDir}/debug.keystore")
            val base64Ks = file("${rootDir}/debug.keystore.base64")
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
            } else {
                // No checked-in keystore: fall back to AGP's standard debug
                // keystore, which AGP auto-generates on first build (CI-safe).
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            val relConfig = signingConfigs.getByName("releaseConfig")
            if (relConfig.storeFile != null && relConfig.storeFile?.exists() == true) {
                signingConfig = relConfig
            } else {
                signingConfig = signingConfigs.getByName("debugConfig")
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
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Stopgap: AGP 8.7.3's profile compiler crashes with "String index out of range: 62"
// on the GitHub Actions runner (Gradle 9.6.1). Baseline profiles are not wired yet
// (ROADMAP Phase 21.3) and baseline-prof.txt was a dead file — skip profile compilation
// until release engineering wires them properly.
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

    testImplementation("junit:junit:4.13.2")
}
