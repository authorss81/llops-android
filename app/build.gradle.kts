import java.util.Properties

// Phase 170 (Phase-32-NEW-01 MEDIUM): lingua 1.2.2 ships ALL 75 `language-models/<iso>/**`
// n-gram dirs as plain JAR resources (loaded via `getResourceAsStream("/language-models/...")`),
// even though `LanguageDetectionCore.SUPPORTED` only compiles a 24-language subset. The 51
// unused dirs are stripped at packaging time (see `packaging.resources.excludes` below), which
// shrinks the base APK's language-models payload from ~80 MB packed to the 24 used languages
// (~35 MB packed). The codes below = lingua's full 75-language corpus minus SUPPORTED (24),
// verified byte-for-byte against the `com.github.pemistahl:lingua:1.2.2` JAR in the Gradle cache
// (each dir holds `{uni,bi,tri,quadri,five}grams.json`; non-Latin scripts ship unigrams only).
// Source-pinned against `LanguageDetectionCore.SUPPORTED` by `Phase170LinguaTrimTest` so the
// exclude list and the detection subset cannot drift apart.
private val LINGUA_UNUSED_LANGUAGE_ISOS = listOf(
    "af", "az", "be", "bg", "bn", "bs", "ca", "cy", "eo", "et", "eu", "fa",
    "ga", "gu", "he", "hr", "hy", "id", "is", "ka", "kk", "la", "lg", "lt",
    "lv", "mi", "mk", "mn", "mr", "ms", "nn", "pa", "sk", "sl", "sn", "so",
    "sq", "sr", "st", "sw", "ta", "te", "th", "tl", "tn", "ts", "ur", "vi",
    "xh", "yo", "zu"
)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Phase 195: JVM screenshot-render suite. TEST-ONLY — creates the
    // `recordPaparazziDebug`/`verifyPaparazziDebug` tasks and injects the
    // `app.cash.paparazzi:paparazzi` lib into the unit-test configuration only.
    // It adds zero bytes to the base APK (no runtime/compile dependency).
    alias(libs.plugins.paparazzi)
    // Phase 199 (PERF 2.2): baseline-profile CONSUMER — merges profiles from
    // `:baselineprofile` generation runs (and src/main/baseline-prof.txt)
    // into every release build's assets/dexopt/baseline.prof. The plugin adds
    // no runtime code of its own; profile INSTALLATION is done by
    // androidx.profileinstaller (dependency below).
    alias(libs.plugins.androidx.baselineprofile)
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

    // B1-PLAT-1 (phase-57): the release APK may ONLY be signed by a real,
    // externally-supplied release keystore. There is NO debug-keystore fallback
    // and NO in-repo base64 keystore blob. When `KEYSTORE_FILE`/credentials are
    // unset the signing config stays empty (storeFile = null), and AGP's
    // `:app:validateSigningRelease` (wired below as the release signingConfig)
    // fails the release build loudly instead of emitting a debug-signed "release"
    // APK. `gradle assembleDebug` is untouched — the debug variant keeps AGP's
    // auto-generated debug keystore.
    signingConfigs {
        create("releaseConfig") {
            // Phase 171 (Phase-32-NEW-03 INFO): force APK Signature Scheme v3 ON.
            // Root cause is NOT a config flag disabling v3 — AGP 8.7.3 only enables
            // v3 automatically when `minSdk >= 28`, and this app floors at minSdk 26
            // (below), so every prior release built v2-only and had NO in-place
            // signing-key-rotation capability. `enableV3Signing = true` overrides
            // that threshold while leaving v2 on (v2 remains the fallback scheme a
            // pre-Android-9 device understands) and v1/v4 untouched by design:
            // v4 only accelerates Android 11+ incremental installs and is not
            // required; bumping minSdk to 28 would be a user-approval-level change
            // and is intentionally OUT of scope here.
            enableV3Signing = true
            val ksFilePath = System.getenv("KEYSTORE_FILE")
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")
            val ksAlias = System.getenv("KEY_ALIAS")
            val ksKeyPass = System.getenv("KEY_PASSWORD")

            // All-or-nothing: only a real, complete, existing keystore qualifies.
            // If any of the four variables is missing/blank, or the file is absent,
            // the config stays EMPTY (storeFile = null) and the release build is
            // refused — never a debug-signed "release" APK. Paths are resolved
            // against the REPO ROOT (so `./release.keystore` = `rootDir/release.keystore`).
            if (ksFilePath != null &&
                !ksPassword.isNullOrBlank() &&
                !ksAlias.isNullOrBlank() &&
                !ksKeyPass.isNullOrBlank() &&
                rootProject.file(ksFilePath).exists()
            ) {
                storeFile = rootProject.file(ksFilePath)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPass
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
            // Phase 199 (PERF 2.3): strip resources that R8 proved unreachable.
            // Requires minify (above). Verified safe: no getIdentifier()/dynamic
            // resource lookup exists in the app source; fonts are static
            // R.font.* references (theme/Fonts.kt); stickers are emoji glyphs
            // drawn via the platform font (StickerCatalog — zero image assets);
            // manifest-referenced xml (file_paths, data_extraction_rules,
            // widget info) is kept automatically. The Phase-170 lingua
            // packaging excludes below are INDEPENDENT of this flag (packaging
            // excludes vs shrinker) and stay authoritative for java resources.
            isShrinkResources = true
            // B1-PLAT-1: the release variant is ALWAYS bound to `releaseConfig`.
            // When its storeFile is null (KEYSTORE_FILE/credentials unset), AGP's
            // `:app:validateSigningRelease` task throws "Keystore file not set for
            // signing config 'releaseConfig'" and the build FAILS — no silent
            // fallback to the debug keystore. See `signingConfigs.releaseConfig`.
            signingConfig = signingConfigs.getByName("releaseConfig")
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
    // strict 16KB/4KB page alignment (SQLCipher .so). Phase 176 measured the
    // alternative: extractNativeLibs=false STORES every .so uncompressed, growing
    // each download (~+3.8 MB arm64 / +13.7 MB universal) while re-exposing the
    // 16KB-page dlopen crash — so extraction stays ON. Operative control note:
    // the explicit `android:extractNativeLibs="true"` in AndroidManifest.xml
    // OVERRIDES this DSL flag when they disagree (phase-176 evidence: flipping
    // ONLY `useLegacyPackaging` to false changed nothing in the payload; both
    // sites are kept = true so there is no disagreement to hide).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // Phase 170 (Phase-32-NEW-01): strip the 51 lingua language-model dirs that
        // LanguageDetectionCore never loads. AGP's java-resource merge honors these
        // globs in `MergeJavaResourcesDelegate` (`ParsedPackagingOptions.getAction` ->
        // EXCLUDE), so `language-models/<iso>/**` prunes each unused dir from the
        // assembled APK while the 24 used languages stay loadable by the detector.
        resources {
            excludes += LINGUA_UNUSED_LANGUAGE_ISOS.map { "language-models/$it/**" }
            // Phase 176 (R2-KS-27 LOW + R2-KS-24 INFO): exclude debug/dev artifacts
            // from the release APK payload. Verified origins (BEFORE build evidence,
            // workspace/phase-176/REPORT.md):
            //  - DebugProbesKt.bin — root-level resource bundled inside
            //    kotlinx-coroutines-core-jvm 1.8.1 (a REQUIRED runtime dep; NOT from a
            //    removable debug dependency). Stripping it removes the coroutine-debug
            //    agent attach path + its debug logging hooks from production.
            //  - kotlin-tooling-metadata.json — INJECTED AT BUILD TIME by the Kotlin
            //    Gradle plugin (describes Gradle/KGP versions); never used at runtime.
            //  - firebase-*.properties — defense-in-depth only: post-phase-175 the base
            //    APK has NO Firebase deps (verified empty in the BEFORE payload), so this
            //    glob is a guard against a future dep accidentally re-shipping them.
            excludes += listOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "firebase-*.properties")
        }
    }

    // Phase 170 (Phase-32-NEW-02 LOW): release-only ABI-split APKs, so a device only
    // downloads/native-loads its own `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`
    // (each ABI = ~6 native libs, ~12-16 MB packed today). The legacy `splits` DSL is
    // NOT build-type-aware, so ABI splitting is activated ONLY when the requested task
    // list contains a release-variant task; a plain `gradle assembleDebug` keeps
    // producing the single monolithic debug APK exactly as before. `isUniversalApk =
    // true` keeps one full-fat APK (all 4 ABIs, emitted as `app-universal-release.apk`)
    // for sideloading/emulators — see `docs/RELEASE.md` for the revised artifact list.
    // Every split APK is signed by the same `releaseConfig` (B1-PLAT-1 fail-closed
    // signing is untouched).
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.substringAfterLast(':').lowercase().contains("release") }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
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

// Phase 199 review fix (review finding 2): wire the CONSUMER to the PRODUCER.
// Without this `:app:generateBaselineProfile` was a marker task (the plugin's
// GenerateBaselineProfileTask has no @TaskAction); the real work happens in
// `:baselineprofile:collectNonMinifiedReleaseBaselineProfile` (device test →
// profile collection), which then feeds merge/copy into src/main/baselineProfiles
// via `:app:mergeReleaseBaselineProfile` + `:app:copyReleaseBaselineProfileIntoSrc`.
// This block links the app's release baseline profile to the producer module, so
// `gradle :app:generateBaselineProfile --no-configuration-cache` (see REPORT for
// the AGP config-cache caveat) plans the producer tasks:
// :baselineprofile:connectedNonMinifiedReleaseAndroidTest →
// :baselineprofile:collectNonMinifiedReleaseBaselineProfile → app merge/copy.
baselineProfile {
    from(project(":baselineprofile"))
}

// Phase 199 review fix (review finding 2, first round): GUARDED restoration
// of the AGP 8.7.3 compileArtProfile stopgap. The phase-199 commit deleted the
// old unconditional disable on the assumption that wiring the baseline-profile
// toolchain also fixed the underlying crash ("String index out of range: 62"
// when compiling an art profile on the GitHub Actions runner) — but nothing
// ever proved that. Note `.github/workflows/android.yml` SKIPS llops-bot
// pushes (`if: github.actor != 'llops-bot'`, line 28), so the automated
// phase pipeline never exercises a signed `assembleRelease` under
// fullMode+shrinkResources; as of review time no run of
// `minifyReleaseWithR8`+`assembleRelease` with the phase-199 flags has
// produced a signed APK. Right now NO baseline profile is committed in-tree
// (none was generated — needs a connected device), so profile compilation
// would run on exactly the kind of empty/degenerate input that used to crash.
//
// The disable is therefore CONDITIONAL: it lifts automatically as soon as a
// real committed profile exists, so once a maintainer lands one via
// `gradle :app:generateBaselineProfile`, release builds compile it again and
// any recurrence of the AGP crash surfaces as an explicit CI failure pointing
// at the bug — never as a silent skip of a shipped profile.
//
// Caveat (documented in workspace/phase-199/REPORT.md): the check runs at
// CONFIGURATION time, so within the single invocation that first WRITES
// profiles (`generateBaselineProfile`), the final re-package step may still
// see no in-tree files and skip compilation. Run the release build once more
// AFTER generation so the freshly committed profile is compiled in.
val hasCommittedBaselineProfiles: Boolean =
    file("src/main/baseline-prof.txt").isFile ||
        (file("src/main/baselineProfiles").isDirectory &&
            (file("src/main/baselineProfiles").listFiles()
                ?.any { it.name.endsWith(".txt") } == true))

tasks.configureEach {
    if (!hasCommittedBaselineProfiles &&
        name.startsWith("compile") &&
        name.endsWith("ArtProfile")
    ) {
        enabled = false
    }
}

// B1-PLAT-1 (phase-57) fail-fast gate: whenever a task that produces a SIGNED
// RELEASE artifact is requested while the release keystore is not configured,
// abort before R8/minify burns minutes. Only release signing/packaging task names
// trigger it (assembleRelease, bundleRelease, packageRelease, installRelease,
// validateSigningRelease) so quality-only release tasks like `lintRelease` stay
// runnable without a keystore. Debug builds never carry these task names and are
// unaffected. AGP's `:app:validateSigningRelease` is the second, always-on
// backstop even if a caller somehow bypasses this early gate.
gradle.taskGraph.whenReady {
    if (allTasks.any { task ->
            task.project == project && task.name in RELEASE_SIGNING_TASK_NAMES
        }
    ) {
        val releaseSigning = android.signingConfigs.getByName("releaseConfig")
        if (releaseSigning.storeFile == null || releaseSigning.storeFile?.exists() != true) {
            throw GradleException(
                "Release build refused: no release keystore configured (B1-PLAT-1). " +
                    "Set KEYSTORE_FILE (existing keystore), KEYSTORE_PASSWORD, KEY_ALIAS and " +
                    "KEY_PASSWORD, then run the release task again. The debug-keystore/repo-blob " +
                    "fallbacks were removed — a release APK can no longer be produced signed with " +
                    "the well-known Android debug key. See docs/RELEASE.md."
            )
        }
    }
}
private val RELEASE_SIGNING_TASK_NAMES = setOf(
    "assembleRelease", "bundleRelease", "packageRelease", "installRelease",
    "validateSigningRelease"
)

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Phase 211 (release hygiene): Compose compiler observability. The project has
// exactly the instability-prone shapes the stability report exists for
// (EditorScreen.kt ~320 KB / AnnotationCanvas.kt ~314 KB sources, god-ViewModel
// params), yet no composeCompiler {} block existed anywhere. Metrics/report
// emission is OPT-IN via `-Pinkflow.composeMetrics` so ordinary builds are
// byte-for-byte unchanged (the flag is deliberately NOT a default-on variant
// switch); run:
//
//   gradle :app:compileDebugKotlin -Pinkflow.composeMetrics --rerun
//
// then read build/compose-compiler-reports/*-composables.csv (stability +
// skippability per composable) and build/compose-compiler-metrics/*.txt.

composeCompiler {
    if (project.hasProperty("inkflow.composeMetrics")) {
        metricsDestination = layout.buildDirectory.dir("compose-compiler-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-compiler-reports")
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
    // Phase 238: responsive layout (tablet / floating-window / landscape). The
    // window-size-class artifact is pinned by the SAME composeBom 2024.12.01 as
    // `material3` above — a tiny (~10 KB) pure-Kotlin row, no new version. It
    // replaces the phase-211 belt-and-braces story where the app rolled its own
    // width-only `WindowSizeCategory` enum in MainActivity (that enum is deleted
    // in phase-238; the official class also carries the HEIGHT size class the
    // homegrown one lacked, which the landscape-phone and floating-window fixes
    // need).
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons)
    // Phase 211: navigation-compose REMOVED — declared since project genesis
    // but NEVER used: the app navigates via MainActivity's mutableStateOf
    // screen switch (no NavHost/rememberNavController anywhere, grep-verified).

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

    // Phase 196: OS-level stylus/touch motion prediction for the ink canvas.
    // Pure-Java AndroidX (no native code, no permissions, ~tens of KB of dex) —
    // NOT in the heavy downloadable-plugin class (ML Kit / MediaPipe); the
    // base-APK size constraint is unaffected. Gated to API 29+ at runtime by
    // MotionPredictionPolicy; older devices keep the existing stabilizer path.
    implementation(libs.androidx.input.motionprediction)

    // Phase 199 (PERF 2.2): installs assets/dexopt/baseline.prof on API < 33
    // at first run (API 33+ consumes it natively at install time). Tiny pure-
    // AndroidX lib; was already present transitively (1.3.1 via androidx.
    // activity) — pinned explicitly so the cold-start install path is a
    // declared, versioned contract rather than a transitive accident.
    implementation(libs.androidx.profileinstaller)

    // Utilities
    // Phase 211: coil-compose REMOVED — zero source references (no
    // AsyncImage/Coil call site anywhere; grep-verified). Images render via
    // BitmapFactory/Compose primitives, so the loader was dead weight.
    implementation(libs.androidx.biometric)
    implementation(libs.gson)

    // Markdown rendering
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)

    // Phase 12/16 on-device OCR + translation were REMOVED from the base APK in
    // Phase 175 (R2-KS-21): the ML Kit engines + native `.so` libs + bundled
    // Latin OCR models ship ONLY in the downloadable, signature-verified
    // `plugins/mlkit` artifact (Plugin Store) — see those modules; the base APK
    // rejects OCR/Translation requests with NO_PLUGIN_INSTALLED until installed.

    // Phase 29: the MediaPipe tasks-genai local-LLM engine is deliberately NOT
    // in the base APK. It ships as the downloadable, signature-verified
    // `plugins/llm` artifact (Plugin Store) so users who never use the AI
    // assistant don't carry its ~50 MB of native libraries.

    // Phase 15: productivity & knowledge plugin pack (pure-JVM cores).
    implementation(libs.lingua)
    implementation(libs.jsoup)

    // Phase 179: syntax highlighting for fenced code blocks in the markdown
    // renderers (dev.snipme:highlights, pure Kotlin, jvm variant — base APK
    // stays lean, no native libs, no new permissions).
    implementation(libs.highlights)

    // Phase 31: JVM unit tests. kotlinx-coroutines-test lets the construction test
    // install a Main dispatcher so NoteflowViewModel's eager stateIn(viewModelScope,..)
    // flows can be built outside Android. junit is the existing test runner.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Phase 239: phase-235's INSTRUMENTED Compose tests (androidTest) were
    // removed — they are impossible on this pipeline. (1) `connectedAndroidTest`
    // needs an AVD/kernel with HW acceleration, which GitHub runners lack, and
    // (2) resolving `espresso` (a `ui-test-junit4` transitive) for the
    // `debugAndroidTestRuntimeClasspath` failed dependency verification
    // (`com.squareup:javawriter` + 13 more) on every run. The same critical-path
    // coverage instead lives in `app/src/test/.../Phase239*` as PURE-JVM tests of
    // the backing logic classes (WetCanvasEngine / LassoPolicy / PaletteMath /
    // SymmetryCommitPolicy / WikiLinkParser / SelectionTransformPolicy), which
    // run in `gradle testDebugUnitTest` on the JVM — no emulator, no new verified
    // dependency (just the existing junit), no Robolectric (the project keeps
    // `isReturnDefaultValues = true` specifically to avoid pulling it in).
    //
    // No androidTestImplementation / debugImplementation(ui-test-manifest) here:
    // `createComposeRule` cannot run on the plain JVM, so keeping those configs
    // would be dead weight that re-broken the verification classpath.
}


// --- Downloadable-LLM-plugin seed ------------------------------------------
//
// B2-DEPS-04 (phase-76): `:app:generateLlmPluginSeed` — the REAL implementation
// of the task the old `plugins/llm/build.gradle.kts` metadata comment referenced
// but never defined. Maintainers' publishing flow:
//
//   PLUGIN_SIGNING_KEYSTORE_B64=<base64 JKS> PLUGIN_SIGNING_STORE_PASS=<pass> \
//     gradle :app:generateLlmPluginSeed
//
// The task depends on `:plugins:llm:pluginMetadata`, which FAILS LOUDLY unless
// the real plugin-signing keystore env vars are present (the B2-DEPS-04 gate in
// `plugins/llm/build.gradle.kts`), so the seed can never be derived from an
// ephemeral/build-bred keystore — it can only ever match the ONE real CI signing
// identity. The task validates the metadata values and rewrites the committed
// `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedLlmPluginPin.kt`;
// committing the resulting diff (with an app bump) is how a genuine release's
// pin lands in `CompileTimePluginPins.RELEASES` via `llmPluginSeedRelease`.
// It is deliberately NOT wired into `assembleDebug`, so debug builds never need
// the plugin-signing env.
tasks.register<DefaultTask>("generateLlmPluginSeed") {
    group = "plugin-artifact"
    description = "Regenerates src/main/.../GeneratedLlmPluginPin.kt from the SIGNED :plugins:llm artifact metadata " +
        "(requires PLUGIN_SIGNING_KEYSTORE_B64 + PLUGIN_SIGNING_STORE_PASS)."
    dependsOn(":plugins:llm:pluginMetadata")
    doLast {
        val llmProject = rootProject.findProject(":plugins:llm")
            ?: throw GradleException("LLM plugin seed refused: ':plugins:llm' is not part of this build.")
        val metadataFile = llmProject.layout.buildDirectory
            .file("plugin-artifact/plugin-metadata.properties")
            .get().asFile
        if (!metadataFile.isFile) {
            throw GradleException(
                "LLM plugin seed refused: plugin-metadata.properties not found at ${metadataFile.absolutePath}. " +
                    "The seed derives from the SIGNED :plugins:llm artifact; run with the plugin-signing env set."
            )
        }
        val props = Properties().apply {
            metadataFile.inputStream().use { load(it) }
        }
        val artifact = props.getProperty("artifact").orEmpty().trim()
        val sha256 = props.getProperty("sha256").orEmpty().trim()
        val pinnedCertHash = props.getProperty("pinnedCertHash").orEmpty().trim()
        val version = props.getProperty("version").orEmpty().trim()
        if (artifact.isBlank() || sha256.isBlank() || pinnedCertHash.isBlank() || version.isBlank()) {
            throw GradleException(
                "LLM plugin seed refused: plugin-metadata.properties is incomplete " +
                    "(artifact='$artifact', hasSha256=${sha256.isNotBlank()}, " +
                    "hasPinnedCertHash=${pinnedCertHash.isNotBlank()}, version='$version')."
            )
        }
        if (!Regex("^[0-9a-f]{64}$").matches(sha256)) {
            throw GradleException("LLM plugin seed refused: metadata sha256 '$sha256' is not a 64-char lowercase hex digest.")
        }
        if (!Regex("^sha256/[A-Za-z0-9+/=]+$").matches(pinnedCertHash)) {
            throw GradleException("LLM plugin seed refused: metadata pinnedCertHash '$pinnedCertHash' is not a 'sha256/<base64>' pin.")
        }
        val versionParts = version.split('.').map { it.toIntOrNull() }
        if (versionParts.size != 3 || versionParts.any { it == null }) {
            throw GradleException("LLM plugin seed refused: metadata version '$version' is not 'major.minor.patch'.")
        }
        val (major, minor, patch) = versionParts.map { it!! }

        val seedDir = project.layout.projectDirectory
            .dir("src/main/kotlin/com/authorss81/noteflow/plugins/runtime")
        val seedFile = seedDir.file("GeneratedLlmPluginPin.kt").asFile
        seedFile.parentFile.mkdirs()
        seedFile.writeText(
            """
            |package com.authorss81.noteflow.plugins.runtime
            |
            |/**
            | * AUTO-GENERATED by `:app:generateLlmPluginSeed` — DO NOT EDIT BY HAND.
            | *
            | * The application-compiled pin of the ONE real, CI-signed LLM plugin
            | * release (B2-DEPS-04). The generator requires the real signing keystore
            | * (`PLUGIN_SIGNING_KEYSTORE_B64` + `PLUGIN_SIGNING_STORE_PASS`, from the
            | * secret store) because it depends on `:plugins:llm:pluginMetadata`,
            | * which fails loudly without them — so this value can only ever match
            | * the real CI signing identity, never a build-bred keystore.
            | * `CompileTimePluginPins.RELEASES` folds this release into the
            | * app-compiled pin table, so the plugin-update chain accepts ONLY an
            | * artifact signed by this exact key. When this value is null no LLM
            | * plugin release is pinned yet and the chain refuses every offer,
            | * fail-closed (B1-NET-03).
            | */
            |internal val llmPluginSeedRelease: PinnedPluginRelease? = PinnedPluginRelease(
            |    id = "com.authorss81.noteflow.plugins.llm",
            |    version = PluginVersion($major, $minor, $patch),
            |    sha256 = "$sha256",
            |    pinnedCertHash = "$pinnedCertHash",
            |)
            |""".trimMargin()
        )
        println("LLM plugin seed regenerated: ${seedFile.absolutePath}")
        println("  id             = com.authorss81.noteflow.plugins.llm")
        println("  version        = $version")
        println("  sha256         = $sha256")
        println("  pinnedCertHash = $pinnedCertHash")
        println("Commit the resulting diff to ship this pin in the app build.")
    }
}

// --- Downloadable-ML-Kit-plugin seed ---------------------------------------
//
// R2-KS-21 (phase-175): `:app:generateMlKitPluginSeed` — the counterpart of
// `:app:generateLlmPluginSeed` for the `plugins/mlkit` artifact (on-device OCR +
// translation). Maintainers' publishing flow:
//
//   PLUGIN_SIGNING_KEYSTORE_B64=<base64 JKS> PLUGIN_SIGNING_STORE_PASS=<pass> \
//     gradle :app:generateMlKitPluginSeed
//
// The task depends on `:plugins:mlkit:pluginMetadata`, which FAILS LOUDLY unless
// the real plugin-signing keystore env vars are present (the B2-DEPS-04 gate in
// `plugins/mlkit/build.gradle.kts`), so the seed can only ever match the ONE real
// CI signing identity — never a build-bred keystore. It rewrites the committed
// `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/GeneratedMlKitPluginPin.kt`;
// committing the resulting diff (with an app bump) is how a genuine release's
// pin lands in `CompileTimePluginPins.RELEASES` via `mlKitPluginSeedRelease`.
// Deliberately NOT wired into `assembleDebug`, so debug builds never need the
// plugin-signing env.
tasks.register<DefaultTask>("generateMlKitPluginSeed") {
    group = "plugin-artifact"
    description = "Regenerates src/main/.../GeneratedMlKitPluginPin.kt from the SIGNED :plugins:mlkit artifact metadata " +
        "(requires PLUGIN_SIGNING_KEYSTORE_B64 + PLUGIN_SIGNING_STORE_PASS)."
    dependsOn(":plugins:mlkit:pluginMetadata")
    doLast {
        val mlkitProject = rootProject.findProject(":plugins:mlkit")
            ?: throw GradleException("ML Kit plugin seed refused: ':plugins:mlkit' is not part of this build.")
        val metadataFile = mlkitProject.layout.buildDirectory
            .file("plugin-artifact/plugin-metadata.properties")
            .get().asFile
        if (!metadataFile.isFile) {
            throw GradleException(
                "ML Kit plugin seed refused: plugin-metadata.properties not found at ${metadataFile.absolutePath}. " +
                    "The seed derives from the SIGNED :plugins:mlkit artifact; run with the plugin-signing env set."
            )
        }
        val props = Properties().apply {
            metadataFile.inputStream().use { load(it) }
        }
        val artifact = props.getProperty("artifact").orEmpty().trim()
        val sha256 = props.getProperty("sha256").orEmpty().trim()
        val pinnedCertHash = props.getProperty("pinnedCertHash").orEmpty().trim()
        val version = props.getProperty("version").orEmpty().trim()
        if (artifact.isBlank() || sha256.isBlank() || pinnedCertHash.isBlank() || version.isBlank()) {
            throw GradleException(
                "ML Kit plugin seed refused: plugin-metadata.properties is incomplete " +
                    "(artifact='$artifact', hasSha256=${sha256.isNotBlank()}, " +
                    "hasPinnedCertHash=${pinnedCertHash.isNotBlank()}, version='$version')."
            )
        }
        if (!Regex("^[0-9a-f]{64}$").matches(sha256)) {
            throw GradleException("ML Kit plugin seed refused: metadata sha256 '$sha256' is not a 64-char lowercase hex digest.")
        }
        if (!Regex("^sha256/[A-Za-z0-9+/=]+$").matches(pinnedCertHash)) {
            throw GradleException("ML Kit plugin seed refused: metadata pinnedCertHash '$pinnedCertHash' is not a 'sha256/<base64>' pin.")
        }
        val versionParts = version.split('.').map { it.toIntOrNull() }
        if (versionParts.size != 3 || versionParts.any { it == null }) {
            throw GradleException("ML Kit plugin seed refused: metadata version '$version' is not 'major.minor.patch'.")
        }
        val (major, minor, patch) = versionParts.map { it!! }

        val seedDir = project.layout.projectDirectory
            .dir("src/main/kotlin/com/authorss81/noteflow/plugins/runtime")
        val seedFile = seedDir.file("GeneratedMlKitPluginPin.kt").asFile
        seedFile.parentFile.mkdirs()
        seedFile.writeText(
            """
            |package com.authorss81.noteflow.plugins.runtime
            |
            |/**
            | * AUTO-GENERATED by `:app:generateMlKitPluginSeed` — DO NOT EDIT BY HAND.
            | *
            | * The application-compiled pin of the ONE real, CI-signed ML Kit plugin
            | * release (R2-KS-21). The generator requires the real signing keystore
            | * (`PLUGIN_SIGNING_KEYSTORE_B64` + `PLUGIN_SIGNING_STORE_PASS`, from the
            | * secret store) because it depends on `:plugins:mlkit:pluginMetadata`,
            | * which fails loudly without them — so this value can only ever match
            | * the real CI signing identity, never a build-bred keystore.
            | * `CompileTimePluginPins.RELEASES` folds this release into the
            | * app-compiled pin table, so the plugin-update chain accepts ONLY an
            | * artifact signed by this exact key. When this value is null no ML Kit
            | * plugin release is pinned yet and the chain refuses every offer,
            | * fail-closed (B1-NET-03).
            | */
            |internal val mlKitPluginSeedRelease: PinnedPluginRelease? = PinnedPluginRelease(
            |    id = "com.authorss81.noteflow.plugins.mlkit",
            |    version = PluginVersion($major, $minor, $patch),
            |    sha256 = "$sha256",
            |    pinnedCertHash = "$pinnedCertHash",
            |)
            |""".trimMargin()
        )
        println("ML Kit plugin seed regenerated: ${seedFile.absolutePath}")
        println("  id             = com.authorss81.noteflow.plugins.mlkit")
        println("  version        = $version")
        println("  sha256         = $sha256")
        println("  pinnedCertHash = $pinnedCertHash")
        println("Commit the resulting diff to ship this pin in the app build.")
    }
}
