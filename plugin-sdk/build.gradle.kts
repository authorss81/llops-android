plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 29: the compile-time PLUGIN FRAMEWORK surface, extracted from the base
// app so a downloadable (remote) plugin module can be compiled against the SAME
// interface types the base app resolves at runtime (class identity matters: the
// app's classloader is the parent of every plugin DexClassLoader, so the plugin
// artifact must NOT bundle its own copies of these types).
//
// This module deliberately depends ONLY on the Android platform + Kotlin
// stdlib. No app packages, no Room, no Compose. Moving a type here therefore
// never drags the base app's domain into a plugin artifact.
android {
    namespace = "com.authorss81.noteflow.plugins"
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
