plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    // Phase 199 (PERF 2.2): registers this module as the baseline-profile
    // PRODUCER for `targetProjectPath` (:app, the consumer). The plugin adds
    // the `generateBaselineProfile`/`connectedNonMinifiedReleaseAndroidTest`
    // task wiring on :app — it contributes NO code to either APK.
    alias(libs.plugins.androidx.baselineprofile)
}

// Phase 199 (PERF 2.2): baseline-profile producer module.
//
// Purpose
// ------
// Runs androidx macrobenchmark scenarios against a NON-minified release build
// of :app on a connected device/emulator and records which classes/methods
// execute during:
//   1. cold app start  (Application → MainActivity → vault unlock → home),
//   2. open a note     (home list → editor),
//   3. first ink stroke(canvas warm-up).
// The recorded profile is merged into every subsequent `:app` release build
// as assets/dexopt/baseline.prof (AOT-compiled at install time; API 33+
// reads it natively during install, older devices install it via the
// bundled androidx.profileinstaller).
//
// Running it (REQUIRES a connected device or emulator — never runs in CI):
//
//   gradle :app:generateBaselineProfile
//
// The generated/updated rules land in :app's baselineProfiles output and are
// committed by the maintainer together with the release that benefits from
// them. This module is NOT wired into assembleDebug/testDebugUnitTest paths;
// its dependencies (benchmark-macro-junit4, uiautomator) exist only here.

android {
    namespace = "com.authorss81.noteflow.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The producer plugin builds a `nonMinifiedRelease` variant of :app to
    // run against (profiles must map to deobfuscated names, then are
    // remapped for the minified build). Nothing else is customized.
    targetProjectPath = ":app"

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

baselineProfile {
    // Real devices AND emulators are both valid producers; the maintainer
    // picks whichever is attached when running generateBaselineProfile.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}
