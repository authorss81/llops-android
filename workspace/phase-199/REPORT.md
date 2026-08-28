# Phase 199 — Baseline Profile + R8 fullMode + shrinkResources (PERF 2.2+2.3)

## Status: `DONE-WITH-DEFERRALS` (honest scope)

The build-toolchain wiring shipped and is pinned by tests. The **measured** wins
(profile generation, StartupTimingMetric before/after, APK-size delta) are
**deferred — they require a connected device/emulator that CI does not have.**
The original PROMPT DoD asked for those numbers; this report documents exactly
what exists and what does not.

## What actually shipped

1. **`:baselineprofile` producer module** (`baselineprofile/`, registered in
   `settings.gradle.kts`): `com.android.test` + `androidx.baselineprofile`
   plugins, targeting `:app`. Two macrobenchmark scenarios
   (`StartupBaselineProfile` cold start incl. A/B `CompilationMode` variants +
   `StartupProfileGenerator`; `NoteOpenAndStrokeBaselineProfile` quick-capture →
   editor → first stroke). Scenario constants mirror the host contract
   (verified: `WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE`
   = `WidgetLaunchPolicy.kt:19`; consumed at `MainActivity.kt:499-502`
   (LaunchedEffect gate) + `:1060` (`quickCaptureRequested = true`); uiautomator
   wait target `"Stroke Width"` is a real contentDescription,
   `EditorScreen.kt:3799` (and its mirror at `:3958`)). Benchmark/uiautomator/ext-junit
   deps live ONLY in this module — never in any APK.
2. **Consumer side**: `androidx.baselineprofile` Gradle plugin on `:app`;
   `profileinstaller` 1.4.1 explicitly pinned (~tens of KB; base-APK rule
   intact); stable-line pins documented in `gradle/libs.versions.toml`.
3. **R8 full mode** (`gradle.properties` `android.enableR8.fullMode=true`) with
   scoped Gson keeps for every reflective-DTO package (see test below) plus the
   existing data.model/data.db rules.
4. **`isShrinkResources = true`** on release (minify already on).
5. **Dependency-verification completion (REVIEW FIX)** — see below.

## Review fixes (2026-08-24, this commit)

Findings from the phase review, all addressed:

- **F1 (HIGH)** — `.done` was set with no REPORT/metrics/generated profile.
  This file is the honest record. Deferred items listed below.
- **F2 (MEDIUM)** — the AGP `compileArtProfile` stopgap had been deleted on an
  unproven assumption. Restored as a GUARDED disable
  (`app/build.gradle.kts`, `hasCommittedBaselineProfiles`): ArtProfile tasks are
  disabled ONLY while no real profile exists in-tree
  (`src/main/baseline-prof.txt` or `src/main/baselineProfiles/*.txt`), and lift
  automatically the moment one is committed. Rationale: the original crash
  ("String index out of range: 62", AGP 8.7.3, GH Actions runner) was never
  proven fixed, `.github/workflows/android.yml` SKIPS llops-bot pushes
  (`if: github.actor != 'llops-bot'`), and today's tree has no profile — i.e.
  exactly the degenerate input that used to crash.
- **F3 (MEDIUM)** — plugin-sdk consumer rules were app-global, not jar-scoped:
  `-keep class com.authorss81.noteflow.plugins.** { *; }` also pinned the HOST
  app's own plugins.* subpackages (runtime internals + weather/dictionary/
  websearch), undoing phase-199's own member-name-only keeps. Rewrote to a
  single-star root-package keep + exact-name runtime keeps
  (`PluginContext`/`PluginEntry`/`PluginVersion`), corrected the false comment
  about consumer-rule scoping, and replaced the bogus `AppClassLoaderFactory`
  citation with the real loader files (`plugins/runtime/
  PluginFrameworkClassLoader.kt`, `RuntimePluginLoader.kt`).
- **F4 (LOW)** — docs updated: ARCHITECTURE.md "Implemented in phase-199",
  phase-status rows, stale truth-table wording ("baseline profiles NOT wired").
- **F5 (LOW)** — new pure-JVM pinning suite `Phase199ReleaseShrinkTest` (7
  tests): fullMode+shrinkResources on; guarded ArtProfile stopgap present;
  EVERY Gson-touching app source mapped to its fullMode keep rule with an
  exhaustive-discovery guard (a new Gson file fails the suite until mapped);
  consumer-rule scope pinned against both leak directions AND cross-checked
  against the SDK's actual packages/classes; baseline-profile toolchain wiring
  pinned.
- **NEW CRITICAL DEFECT FOUND DURING REVIEW-FIX VERIFICATION** — phase-199
  added the `com.android.test` / `androidx.baselineprofile` plugins but never
  resolved them against `gradle/verification-metadata.xml`: **every Gradle
  invocation failed dependency verification at configuration time** (missing
  entries for `com.android.test.gradle.plugin-8.7.3.pom`,
  `androidx.baselineprofile.gradle.plugin-1.3.4.pom`,
  `benchmark-baseline-profile-gradle-plugin-1.3.4.jar/.module`,
  `profileinstaller-1.4.1.aar/.module`). No phase-199 build ever ran green —
  consistent with F1's missing evidence. Fixed by pinning each artifact's real
  sha256 from dl.google.com (same-origin as Gradle resolves), replacing the
  broken pgp-only marker entry with a checksum entry, and adding
  `<trusting group="androidx.baselineprofile"/>` next to the existing
  benchmark trust line. `gradle help`, `testDebugUnitTest` and `assembleDebug`
  now resolve clean.

## Second review pass (2026-08-28, this commit)

Second round of REVIEW FINDINGS on the same phase-199 scope, all addressed:

- **F1 (HIGH)** — `gradle :baselineprofile:collectNonMinifiedReleaseBaselineProfile`
  still failed dependency verification for the producer module's OWN resolutions
  (`:baselineprofile:nonMinifiedReleaseCompileClasspath` + `RuntimeClasspath`):
  15 metadata files + 10 `.aar` binaries were unverified (benchmark-macro-junit4/
  -macro/-common 1.3.4, annotation 1.7.0 + 1.7.0-beta01 + annotation-jvm 1.7.0,
  test core 1.6.1 / monitor 1.7.1 / ext-junit 1.2.1 / services-storage 1.5.0 /
  uiautomator 2.3.0, tracing-perfetto{,-binary,-handshake} 1.0.0 from Google,
  kotlinx-coroutines-core 1.3.4 pom from MavenRepo). The 04d9901 review fix had
  only pinned the plugin jar/marker/pom entries actually needed to CONFIGURE the
  graph; the producer's compile+runtime classpaths resolve real artifacts and
  were never exercised. Fixed in `gradle/verification-metadata.xml`: sha256 pins
  computed from the artifacts Gradle itself cached (checksum-neutral, no flag
  weakened); the phase-added unverifiable pgp-only pin for the coroutines 1.3.4
  pom was replaced with its sha256. `:baselineprofile:collectNonMinifiedRelease
  BaselineProfile --dry-run` now resolves clean.
- **F2 (MEDIUM)** — `:app:generateBaselineProfile` was NOT connected to
  `:baselineprofile` (no `baselineProfile { from(project(":baselineprofile")) }`),
  so it was a marker task whose real work never ran. Fixed in `app/build.gradle.kts`.
  `:app:generateBaselineProfile --dry-run` now plans `:baselineprofile:
  connectedNonMinifiedReleaseAndroidTest` + `:baselineprofile:
  collectNonMinifiedReleaseBaselineProfile` → `:app:mergeReleaseBaselineProfile`
  → `:app:copyReleaseBaselineProfileIntoSrc`.
- **F3 (LOW)** — corrected the stale "CI runs assembleRelease on every push"
  rationale in both `app/build.gradle.kts` and this REPORT: `.github/workflows/
  android.yml` actually SKIPS llops-bot pushes, so no verified signed
  `assembleRelease` under fullMode+shrinkResources has ever run on CI.
- **F4 (LOW)** — REPORT's file:line anchors refreshed:
  `MainActivity.kt:1015/:474-477` → `:499-502/:1060`;
  `EditorScreen.kt:3189` → `:3799` (+ mirror `:3958`).
- **F5 (INFO)** — shrinkResources/`getIdentifier()`/font-refs/R8-fullMode safety
  already verified in the first pass; no additional code change needed.

Known AGP caveat (NOT introduced by this commit): with the repo's global
`org.gradle.configuration-cache=true`, a `:baselineprofile`-involving invocation
errors while serializing `CheckAarMetadataTask.disallowedAsarArtifacts`/
`checkTestedAppObfuscation.mappingFile` (`ResolutionBackedFileCollection`/
`DefaultArtifactCollection` — an AGP-vs-config-cache limitation). Run profile
generation with `--no-configuration-cache`, as `docs/ARCHITECTURE.md` notes.

## Verification performed

- `gradle :app:testDebugUnitTest :app:assembleDebug` — **2643 tests, 3
  failures**, all three pre-existing/environmental and identical to the
  phase-196/198 clean-tree baseline: `Phase148UiFailureTextScrubTest`
  UNC-path ×1, `PaparazziSmokeTest` layoutlib ×2. Debug APK produced.
- `Phase199ReleaseShrinkTest` 7/7 green.
- `gradle :baselineprofile:tasks` — producer module configures; Baseline
  Profile tasks registered (`collectNonMinifiedReleaseBaselineProfile`, …).
- `:baselineprofile:collectNonMinifiedReleaseBaselineProfile --dry-run` +
  `:app:generateBaselineProfile --dry-run` — **green** (dependencies resolve
  against the completed lockfile; F2 wiring plans the producer chain).
- Dependency verification: plain (non-write-mode) configuration of root +
  `:app` unit-test/debug paths green end-to-end.

## Deferred (requires device/emulator — same class as phase-196/198 gfxinfo)

- `gradle :app:generateBaselineProfile --no-configuration-cache` run on a
  device + committing the generated profile (then the guarded stopgap lifts
  automatically; run one more release build so the profile compiles in).
- `StartupTimingMetric` before/after cold-start numbers.
- APK size before/after (shrinkResources delta).

## Verification-metadata additions note (review finding 7)

All new lockfile entries belong to the phase-199 toolchain graph itself
(plugin markers + benchmark-baseline-profile plugin + profileinstaller). The
phase-199 commit's keyring/key edits (TFLite signing key etc.) came from
regeneration side effects of resolving previously-unresolved configurations;
they remain group/version-scoped and unchanged by this review fix.
