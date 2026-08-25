// R2-b2b2-DEP-04 (phase-146 + review): the one-way mavenCentral() allow-list applies
// to plugin resolution AND dependency resolution so a poisoned Central index can
// inject neither an unknown plugin nor an unknown dependency group. pluginManagement
// is pre-evaluated and cannot reference script-level declarations, so the 56 literal
// `includeGroupByRegex("...")` lines are written out TWICE in this file (pluginManagement
// and dependencyResolutionManagement) — the source-pinning tests
// (`Phase146BuildIntegrityTest` + `B2Deps03DependencyVerificationTest`, both reading
// `CentralAllowlist` in `app/src/test/.../CentralAllowlist.kt`) scan BOTH blocks for
// every group, so an edit to either list fails the build unless the other list +
// the test data change in the same commit.

pluginManagement {
    // The pluginManagement block is pre-evaluated and CANNOT reference script-level
    // declarations, so the allow-list is written out literally here — keep it in sync
    // with the `dependencyResolutionManagement` mavenCentral block below and with
    // `CentralAllowlist` in app/src/test (the source-pinning tests scan both for
    // every group).
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            content {
                includeGroupByRegex("org\\.jetbrains.*")
                includeGroupByRegex("com\\.squareup.*")
                includeGroupByRegex("org\\.commonmark.*")
                includeGroupByRegex("org\\.jsoup.*")
                includeGroupByRegex("net\\.zetetic.*")
                includeGroupByRegex("com\\.github\\.pemistahl")
                // Phase 179: syntax highlighting (dev.snipme) — exact group only.
                includeGroupByRegex("dev\\.snipme.*")
                includeGroupByRegex("junit")
                includeGroupByRegex("org\\.hamcrest.*")
                includeGroupByRegex("org\\.junit.*")
                includeGroupByRegex("org\\.jspecify.*")
                includeGroupByRegex("org\\.checkerframework.*")
                includeGroupByRegex("com\\.google\\.errorprone.*")
                includeGroupByRegex("com\\.google\\.j2objc.*")
                includeGroupByRegex("com\\.google\\.code.*")
                includeGroupByRegex("org\\.tensorflow.*")
                includeGroupByRegex("com\\.google\\.protobuf.*")
                includeGroupByRegex("com\\.google\\.flatbuffers.*")
                includeGroupByRegex("io\\.grpc.*")
                includeGroupByRegex("io\\.netty.*")
                includeGroupByRegex("io\\.perfmark.*")
                includeGroupByRegex("org\\.xerial.*")
                includeGroupByRegex("com\\.google\\.guava.*")
                includeGroupByRegex("com\\.google\\.crypto.*")
                includeGroupByRegex("com\\.google\\.dagger.*")
                includeGroupByRegex("com\\.google\\.jimfs.*")
                includeGroupByRegex("com\\.google\\.auto.*")
                includeGroupByRegex("com\\.google\\.api.*")
                includeGroupByRegex("com\\.google\\.accompanist.*")
                includeGroupByRegex("com\\.google\\.devtools.*")
                includeGroupByRegex("com\\.google\\.android")
                includeGroupByRegex("com\\.googlecode.*")
                includeGroupByRegex("com\\.intellij.*")
                includeGroupByRegex("com\\.sun.*")
                includeGroupByRegex("jakarta.*")
                includeGroupByRegex("javax.*")
                includeGroupByRegex("commons-.*")
                includeGroupByRegex("org\\.apache.*")
                includeGroupByRegex("org\\.bouncycastle.*")
                includeGroupByRegex("org\\.codehaus.*")
                includeGroupByRegex("org\\.ow2.*")
                includeGroupByRegex("org\\.jdom.*")
                includeGroupByRegex("org\\.eclipse.*")
                includeGroupByRegex("org\\.glassfish.*")
                includeGroupByRegex("org\\.jvnet.*")
                includeGroupByRegex("org\\.sonatype.*")
                includeGroupByRegex("org\\.bitbucket.*")
                includeGroupByRegex("org\\.slf4j.*")
                includeGroupByRegex("it\\.unimi\\.dsi.*")
                includeGroupByRegex("net\\.java.*")
                includeGroupByRegex("net\\.sf.*")
                // Phase 195: Paparazzi screenshot-render suite. The marker +
                // plugin artifact resolve here (`app.cash.paparazzi`); the
                // bytebuddy/kxml2/poko groups are TEST-CLASSPATH-only but are
                // listed for PARITY with dependencyResolutionManagement
                // (Phase146BuildIntegrityTest pins that the two lists + the
                // CentralAllowlist test data can never drift).
                includeGroupByRegex("app\\.cash\\.paparazzi.*")
                includeGroupByRegex("net\\.bytebuddy.*")
                includeGroupByRegex("kxml2")
                includeGroupByRegex("dev\\.drewhamilton\\.poko.*")
            }
        }
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
        // R2-b2b2-DEP-04 (phase-146 + review): mavenCentral() is ONE-WAY filtered,
        // closing the reverse gap of the google() filter above. Central may only
        // serve the explicit allow-list below — the union of every group this
        // build's committed lockfile legitimately resolves from Central (non-google
        // groups plus the com.google.* groups that maven.google.com does NOT host:
        // guava, gson, protobuf, tink, flatbuffers, jimfs, dagger, errorprone,
        // auto.*, api.grpc, accompanist, devtools.ksp marker, ...). A poisoned
        // Central index can no longer inject an unknown group (nor an
        // androidx.*/com.google.* version that google() doesn't host — those now
        // fail fast instead of silently falling back to Central). Adding a new
        // library MUST add its group to this allow-list in the same commit — and to
        // the pluginManagement mavenCentral allow-list above (plugin resolution) +
        // `CentralAllowlist` in app/src/test (the source-pinning tests scan all
        // three). Derived empirically (see workspace/phase-146/REPORT.md) from
        // the resolved group set on 2026-08-18.
        mavenCentral {
            content {
                // Kotlin stdlib + coroutines + annotations.
                includeGroupByRegex("org\\.jetbrains.*")
                // OkHttp / Okio (ML Kit transport in the :plugins:mlkit graph).
                // Phase 211: the `io\\.coil.*` entry was REMOVED with the dead
                // coil-compose dependency (zero source references) — no module
                // of this build resolves io.coil-kt anymore.
                includeGroupByRegex("com\\.squareup.*")
                // Markdown (commonmark) + HTML extraction (jsoup).
                includeGroupByRegex("org\\.commonmark.*")
                includeGroupByRegex("org\\.jsoup.*")
                // SQLCipher.
                includeGroupByRegex("net\\.zetetic.*")
                // Lingua (com.github.pemistahl) — exact group only, not the whole
                // com.github.* namespace (review: no other com.github group resolves).
                includeGroupByRegex("com\\.github\\.pemistahl")
                // Phase 179: syntax highlighting (dev.snipme) — exact group only.
                includeGroupByRegex("dev\\.snipme.*")
                // Test runners + annotations.
                includeGroupByRegex("junit")
                includeGroupByRegex("org\\.hamcrest.*")
                includeGroupByRegex("org\\.junit.*")
                includeGroupByRegex("org\\.jspecify.*")
                // Nullability / static-analysis annotations (guava + androidx transitives).
                includeGroupByRegex("org\\.checkerframework.*")
                includeGroupByRegex("com\\.google\\.errorprone.*")
                includeGroupByRegex("com\\.google\\.j2objc.*")
                includeGroupByRegex("com\\.google\\.code.*")
                // ML Kit / LiteRT transitives NOT hosted on maven.google.com.
                includeGroupByRegex("org\\.tensorflow.*")
                includeGroupByRegex("com\\.google\\.protobuf.*")
                includeGroupByRegex("com\\.google\\.flatbuffers.*")
                includeGroupByRegex("io\\.grpc.*")
                includeGroupByRegex("io\\.netty.*")
                includeGroupByRegex("io\\.perfmark.*")
                includeGroupByRegex("org\\.xerial.*")
                // guava + friends (NOT hosted on maven.google.com).
                includeGroupByRegex("com\\.google\\.guava.*")
                includeGroupByRegex("com\\.google\\.crypto.*")
                includeGroupByRegex("com\\.google\\.dagger.*")
                includeGroupByRegex("com\\.google\\.jimfs.*")
                includeGroupByRegex("com\\.google\\.auto.*")
                includeGroupByRegex("com\\.google\\.api.*")
                includeGroupByRegex("com\\.google\\.accompanist.*")
                includeGroupByRegex("com\\.google\\.devtools.*")
                includeGroupByRegex("com\\.google\\.android")
                includeGroupByRegex("com\\.googlecode.*")
                // AGP/Kotlin/KSP tooling transitives that resolve via Central.
                includeGroupByRegex("com\\.intellij.*")
                includeGroupByRegex("com\\.sun.*")
                includeGroupByRegex("jakarta.*")
                includeGroupByRegex("javax.*")
                includeGroupByRegex("commons-.*")
                includeGroupByRegex("org\\.apache.*")
                includeGroupByRegex("org\\.bouncycastle.*")
                includeGroupByRegex("org\\.codehaus.*")
                includeGroupByRegex("org\\.ow2.*")
                includeGroupByRegex("org\\.jdom.*")
                includeGroupByRegex("org\\.eclipse.*")
                includeGroupByRegex("org\\.glassfish.*")
                includeGroupByRegex("org\\.jvnet.*")
                includeGroupByRegex("org\\.sonatype.*")
                includeGroupByRegex("org\\.bitbucket.*")
                includeGroupByRegex("org\\.slf4j.*")
                includeGroupByRegex("it\\.unimi\\.dsi.*")
                includeGroupByRegex("net\\.java.*")
                includeGroupByRegex("net\\.sf.*")
                // Phase 195: Paparazzi screenshot-render suite transitive groups
                // that resolve on the UNIT-TEST classpath only (never the base
                // APK): `app.cash.paparazzi` (paparazzi + paparazzi-agent),
                // `net.bytebuddy` (byte-buddy agent), the `kxml2` XML pull
                // parser (layoutlib), and `dev.drewhamilton.poko` annotations.
                includeGroupByRegex("app\\.cash\\.paparazzi.*")
                includeGroupByRegex("net\\.bytebuddy.*")
                includeGroupByRegex("kxml2")
                includeGroupByRegex("dev\\.drewhamilton\\.poko.*")
            }
        }
    }
}

// B2-DEPS-03 (phase-75) + R2-b2b2-DEP-04 (phase-146): dependency verification is
// enabled by the committed `gradle/verification-metadata.xml` lockfile — Gradle
// auto-enables STRICT checksum + PGP signature verification for every resolved
// artifact (jars, POMs, ksp/AGP/Kotlin/compose build plugins) the moment that
// file is present, with NO settings-level DSL to toggle (the
// `dependencyVerification {}` settings block does not exist in Gradle 8.13). A
// build attempting to resolve an unlisted/unmatching artifact fails loudly
// instead of silently compiling a compromised/MITM'd download into the signed
// APK.
//
// Lockfile-regeneration provenance (R2-b2b2-DEP-04): the lockfile is
// (re)generated deliberately, never blindly, from a CLEAN, verified mirror:
//
//   gradle --write-verification-metadata sha256,pgp --export-keys testDebugUnitTest assembleDebug
//
// then the diff (trusted-keys, per-artifact pgp signatures, sha256 checksums,
// gradle/verification-keyring.gpg/.keys) is reviewed before commit. Signature
// verification is PGP-over-the-resolved graph: every signed artifact must be
// signed by a key in the committed `<trusted-keys>` block, and its sha256 must
// still match (both are checked — a signature alone is not enough). Artifacts
// without a published `.asc` fall back to checksum-only.
//
// R2-b2b2-DEP-03 (phase-146) — stale build-graph-only entries are DOCUMENTED,
// not deleted: pre-phase-175 the `:app` graph treated `okhttp-3.0.0` +
// `okio-1.6.0` as POM-only losing candidates of `com.google.mlkit:translate`
// (confirmed by `gradle :app:dependencyInsight --dependency okhttp` →
// "3.0.0 -> 4.12.0") whose jars never resolved. Phase 175 moved ML Kit out of
// the base APK into the `:plugins:mlkit` downloadable-plugin module, and THERE
// `mlkit:translate` resolves okhttp-3.0.0 (and okio-1.6.0) as ACTIVE runtime
// deps — their jars are now pinned + verified in the lockfile (dependency
// verification stays on; the entries are never dropped). The lockfile-bounded
// phrase below ("their jars NEVER resolve") applies to the OLD :app-only graph
// only. `guava-27.0.1-android`, `grpc-1.57.0`, `netty-4.1.93`,
// `flatbuffers-1.12.0` resolve only inside the `:plugins:llm` tasks-genai graph
// and never ship (packaged jar includes only `com/google/mediapipe/**` +
// `lib/<abi>/*.so`, see plugins/llm/build.gradle.kts:90-122); they are tracked
// for the next tasks-genai bump in workspace/phase-146/REPORT.md.

rootProject.name = "InkFlow"
include(":app")
include(":plugin-sdk")
// Phase 199 (PERF 2.2): macrobenchmark module that PRODUCES the baseline
// profile (cold start → open note → first stroke) consumed by :app release
// builds. Device-only: `gradle :app:generateBaselineProfile` with a connected
// device/emulator. Its benchmark/uiautomator deps never enter any APK.
include(":baselineprofile")
// Phase 29: standalone local-LLM plugin module. NOT a dependency of :app — it
// produces the downloadable, signature-verified artifact for the Plugin Store.
include(":plugins:llm")
// Phase 175: standalone ML Kit OCR + translation plugin module. Also NOT a
// dependency of :app — it produces the downloadable artifact that carries ML Kit
// out of the base APK (R2-KS-21). Its ML Kit / gms runtime deps resolve from the
// SAME allow-listed google() + mavenCentral() groups the base app already uses
// (com.google.mlkit ships from maven.google.com; guava/protobuf/flatbuffers/grpc/
// netty from the mavenCentral allow-list entries already present above).
include(":plugins:mlkit")
