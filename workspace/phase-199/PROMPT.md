# Phase 199: Baseline Profile + R8 fullMode + shrinkResources [PERF 2.2+2.3]

**Goal:** 20-30% cold start + APK size win with near-zero runtime change.

**Context:** No `:baselineprofile` module `app/build.gradle.kts:178` `compileArtProfile` disabled (AGP crash). Only `isMinifyEnabled=true` `:104`, no `shrinkResources`, no `fullMode`.

**Steps:**
1. Add `:baselineprofile` module, `androidx.benchmark:macro-benchmark`, `profileinstaller`, generate profile for cold start → open note → first stroke.
2. Add `android.enableR8.fullMode=true` `gradle.properties`, `isShrinkResources=true` release `app/build.gradle.kts:104`.
3. Verify `docs/fonts`, `EmptyStateArt`, `StickerCatalog` resources shrink, lingua 51 dirs already excluded.

**DoD:** Baseline profile generated, `assembleRelease` + `test` green, cold start `StartupTimingMetric` before/after in REPORT.md, APK size before/after.
