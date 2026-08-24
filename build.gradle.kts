plugins {
    alias(libs.plugins.android.application) apply false
    // Phase 29: plugin-sdk + downloadable plugin modules are Android libraries;
    // both AGP markers must be declared at the root so the classpath version is
    // known when each submodule applies its marker.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Phase 199 (PERF 2.2): the com.android.test plugin for the :baselineprofile
    // producer module — declared here like the other AGP markers so the
    // classpath version is pinned and the module can apply it versionless.
    alias(libs.plugins.android.test) apply false
    // Phase 199: androidx baseline-profile Gradle plugin (consumer on :app,
    // producer on :baselineprofile). Root-declared so both modules resolve the
    // SAME plugin implementation.
    alias(libs.plugins.androidx.baselineprofile) apply false
}
