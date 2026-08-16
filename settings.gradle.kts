pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // B2-DEPS-03 (phase-75): mirror the pluginManagement content filters — the
        // google() repo may only serve `com.android.*`, `com.google.*`, `androidx.*`
        // groups, so a polluted google index can never publish a fake org.jetbrains/
        // io.coil-kt/... artifact into the build graph.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// B2-DEPS-03 (phase-75): dependency verification is enabled by the committed
// `gradle/verification-metadata.xml` lockfile — Gradle auto-enables STRICT
// checksum verification for every resolved artifact (jars, POMs, ksp/AGP/Kotlin/
// compose build plugins) the moment that file is present, with NO settings-level
// DSL to toggle (the `dependencyVerification {}` settings block does not exist in
// Gradle 8.13). A build attempting to resolve an unlisted/unmatching artifact
// fails loudly instead of silently compiling a compromised/MITM'd download into
// the signed APK. The lockfile is (re)generated deliberately, never blindly:
// `gradle --write-verification-metadata sha256 testDebugUnitTest assembleDebug`
// then the diff is reviewed before commit.

rootProject.name = "InkFlow"
include(":app")
include(":plugin-sdk")
// Phase 29: standalone local-LLM plugin module. NOT a dependency of :app — it
// produces the downloadable, signature-verified artifact for the Plugin Store.
include(":plugins:llm")
