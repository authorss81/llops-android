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

// B2-DEPS-03 (phase-75): every resolved artifact — including build plugins (AGP,
// Kotlin, KSP, compose) — must match the checked-in hashes in
// gradle/verification-metadata.xml. The generated lockfile is committed; a build
// attempting to resolve an unlisted/unmatching artifact fails loudly instead of
// silently compiling a compromised/MITM'd download into the signed APK.
dependencyVerification {
    verify = "all"
}

rootProject.name = "InkFlow"
include(":app")
include(":plugin-sdk")
// Phase 29: standalone local-LLM plugin module. NOT a dependency of :app — it
// produces the downloadable, signature-verified artifact for the Plugin Store.
include(":plugins:llm")
