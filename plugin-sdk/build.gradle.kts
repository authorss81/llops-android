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

        // Phase 199 (PERF 2.3): the shared plugin ABI (FrameworkPlugin,
        // PluginCapability, PluginEntry, PluginVersion, PluginContext, …) must
        // keep its binary names in every R8-minified consumer — the host APK's
        // classloader is the PARENT of each downloadable plugin's DexClassLoader,
        // so renamed SDK types would break plugin linkage at runtime. The rules
        // ship WITH the module (consumer-rules.pro) so they can never drift from
        // the surface they protect.
        consumerProguardFiles("consumer-rules.pro")
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
