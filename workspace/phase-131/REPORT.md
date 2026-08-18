# Phase 131 — Project metadata & LLM plugin build-script alignment

**Status:** DONE
**Date:** 2026-08-18
**Branch/base:** `main` @ `355a7a9` (phase-130)

## Summary

Two deliverables, both proven against the system-Gradle build environment
(Gradle 8.13, no wrapper, Temurin JDK 21 — the CI configuration):

1. **`metadata.json` created at the repo root** — the single committed source of
   truth for project identity, version, SDK/build configuration and the plugin
   capability surface. A new pure-JVM parse+validate helper
   (`services/ProjectMetadata.kt`) plus a 10-test alignment suite keep it in
   sync with `app/build.gradle.kts`, `settings.gradle.kts`,
   `gradle/libs.versions.toml`, `PluginCapability.ALL` and
   `PluginRegistry.defaultPlugins()` — any drift fails the build.
2. **`plugins/llm` build script aligned to the catalog** — its last raw
   dependency coordinate (`junit:junit:4.13.2`) is now catalog-managed
   (`libs.junit`), the module builds standalone under system Gradle (unsigned
   `packagePlugin` **and** the full B2-DEPS-04 signed
   `signPlugin → verifyPluginSignature → pluginMetadata` pipeline, proven with a
   throwaway `/tmp` keystore then reverted), and the base APK still does NOT
   embed the MediaPipe engine (downloadable-plugin rule re-verified at the
   binary level).

## What was checked

### Metadata
- No `metadata.json` existed anywhere in the repo (glob-verified). Created one.

### `plugins/llm/build.gradle.kts` (command-invocation compatibility)
- Task names: `packagePlugin`, `signPlugin`, `verifyPluginSignature`,
  `pluginMetadata` all exist and run under system Gradle 8.13
  (`gradle :plugins:llm:packagePlugin` → BUILD SUCCESSFUL).
- No wrapper assumptions — the module uses only standard Gradle APIs
  (`providers.environmentVariable`, `project.exec`, `layout.buildDirectory`).
- Dependencies: `libs.plugins.android.library`, `libs.plugins.kotlin.android`,
  `libs.mediapipe.tasks.genai`, `libs.kotlinx.coroutines.android`,
  `project(":plugin-sdk")` were already catalog/project-resolvable. The one raw
  coordinate `testImplementation("junit:junit:4.13.2")` was catalog-ized.
- The B2-DEPS-04 signing gate is intact and fail-closed: `gradle :plugins:llm:signPlugin`
  without `PLUGIN_SIGNING_KEYSTORE_B64`/`PLUGIN_SIGNING_STORE_PASS` → loud
  `GradleException` (re-verified 2026-08-18).
- Module wiring: `include(":plugins:llm")` in `settings.gradle.kts`; `:app` does
  NOT depend on it; base APK contains zero `com/google/mediapipe/**` classes and
  zero MediaPipe native libs (unzip-verified).

## What was fixed (file:line)

| File:line | Change |
|---|---|
| `metadata.json` (new, repo root) | Project metadata: `name=InkFlow`, `namespace=com.authorss81.noteflow`, `applicationId=com.aistudio.inkflow.app.bkxjrz`, `version{versionCode:2, versionName:"1.0.0", envOverrides VERSION_CODE/VERSION_NAME}`, `android{compileSdk:36, minSdk:26, targetSdk:36}`, `build{gradleVersion 8.13, usesGradleWrapper:false, agp 8.7.3, kotlin 2.0.21, ksp 2.0.21-1.0.25, jvmTarget 17, modules app/plugin-sdk/plugins:llm}`, capability buckets (18 compile-time-served / assistant downloadable / file_transfer unserved — the exact `PluginCapability.ALL` partition) and the LLM downloadable-plugin record (id, class, module, `inBaseApk:false`, engine, `pinnedReleaseVersion:null`, B2-DEPS-04 signing env vars). |
| `app/src/main/kotlin/com/authorss81/noteflow/services/ProjectMetadata.kt` (new) | Pure-JVM Gson parser + `validate()` (structural fail-closed checks + cross-check of every capability key against `PluginCapability.ALL`, no-gap/dup/unknown detection, and the base-APK rule `inBaseApk` must be false). |
| `app/src/test/java/com/authorss81/noteflow/Phase131MetadataAlignmentTest.kt` (new) | 10 tests: metadata validates clean; name/namespace/applicationId vs build files; version + env overrides; SDK levels; toolchain + module wiring; capability surface vs `PluginCapability.ALL` + `PluginRegistry.defaultPlugins()`; LLM record vs `plugins/llm/build.gradle.kts` + seed pin; parser/validator negative cases. |
| `gradle/libs.versions.toml:32-34` | `junit = "4.13.2"` version entry (comment + Phase 131 rationale). |
| `gradle/libs.versions.toml:88` | `junit = { group = "junit", name = "junit", version.ref = "junit" }` library entry. |
| `app/build.gradle.kts:225` | `testImplementation("junit:junit:4.13.2")` → `testImplementation(libs.junit)`. |
| `plugins/llm/build.gradle.kts:50` | `testImplementation("junit:junit:4.13.2")` → `testImplementation(libs.junit)`. |

Resolved coordinates are byte-identical to before, so
`gradle/verification-metadata.xml` needed no update (proven by the strict-verification build passing).

## Verification (system Gradle 8.13, no wrapper)

- `gradle :plugins:llm:packagePlugin` → **BUILD SUCCESSFUL**. Artifact
  `plugins/llm/build/plugin-artifact/llm-plugin.jar` (21,460,605 B) contains the
  plugin classes, `com/google/mediapipe/**` (56 classes), 4 native `.so`, and
  `META-INF/plugin-entry.properties`
  (`plugin.id=com.authorss81.noteflow.plugins.llm`,
  `plugin.class=com.authorss81.noteflow.llm.LocalLlmPlugin`).
- Positive signed path (throwaway `/tmp` keystore, deleted after): `signPlugin`
  → `verifyPluginSignature` (`jar verified`) → `pluginMetadata` emitted
  `sha256=6de6c855…` + `pinnedCertHash=sha256/iPLwN05LwSqcLYfjbzOEaEee88OcM9E+FTNewJ8tpkM=`
  + `version=1.0.0` (the exact B2-DEPS-04 contract). No keystore material was
  committed; the generated pin was NOT committed (fail-closed `null` seed kept).
- Fail-closed gate: `gradle :plugins:llm:signPlugin` without the env → loud
  `LLM plugin signing refused (B2-DEPS-04)…` `GradleException`.
- Base APK (debug, 173,945,158 B, SHA-256 `7c1d7366…`) → **0** `mediapipe`
  classes + **0** MediaPipe native libs (`unzip -l` verified).
- `gradle testDebugUnitTest` (forced `--rerun-tasks`) → **BUILD SUCCESSFUL**.
  1853 tests, **0 failures, 0 errors, 0 skipped** (app + plugins:llm).
- `gradle assembleDebug` → **BUILD SUCCESSFUL** (90 tasks).
- `B2Deps04PluginSigningTest` (the pre-existing llm-build source pins) still
  green; `B1Plat01ReleaseSigningTest` untouched and green.

One transient single-task test failure was observed mid-run and did not
reproduce on the forced rerun (the documented pre-existing flake class in this
repo; the final 1853/0/0 run is authoritative).

## Constraints honored

- No DB schema change. `.github/workflows/` untouched. No new dependencies (junit
  4.13.2 was already resolved/verified; only its declaration moved into the
  catalog). No keys/decrypted content logged. `allowBackup=false`, ClipboardGuard,
  FLAG_SECURE untouched. LLM stays a downloadable plugin (`inBaseApk:false`, base
  APK binary-verified free of the engine).
