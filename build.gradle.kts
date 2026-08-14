plugins {
    alias(libs.plugins.android.application) apply false
    // Phase 29: plugin-sdk + downloadable plugin modules are Android libraries;
    // both AGP markers must be declared at the root so the classpath version is
    // known when each submodule applies its marker.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
