# Phase 211: Release Hygiene — Shrink the Ink Keep, Drop Dead Deps, Delete Dead Font, Compose Metrics, Gradle Flags [BUILD]

**Goal:** Remaining APK/build wins after phases 170/175/176/199. All items verified against source; every removal must be proven safe by build + tests.

1. **Blanket keep blocks ALL Ink shrinking** — `app/proguard-rules.pro:12` `-keep class androidx.ink.** { *; }`: all 5 ink artifacts ship fully unshrunk/unobfuscated (est. 0.2-0.6 MB compressed). Also stale `-keep com.google.protobuf.**` (`:57-58`) — protobuf left the base APK in phase-29/175.
   **Fix:** delete both rules (AndroidX consumer rules cover Parcelable/CREATOR); rebuild and diff `app/build/outputs/mapping/usage.txt`; run the phase-195 Paparazzi suite + canvas unit tests; re-add only narrowly-scoped keeps proven necessary.

2. **Three declared-but-unused dependencies** (zero source references, grep-verified): `navigation.compose` (`app/build.gradle.kts:299`, catalog `:91`), `coil.compose` (`:332`, catalog `:93`) + its sole-purpose `io\.coil.*` allow-list regex (`settings.gradle.kts:29`), `material3.windowsizeclass` (`:297`, catalog `:99`; app rolls its own `WindowSizeCategory` `MainActivity.kt:83`).
   **Fix:** remove deps + catalog entries + allow-list regex + regenerate `gradle/verification-metadata.xml`. Release bytes ~0 (R8 already strips) but smaller debug APK, faster minify, smaller verification surface.

3. **Dead font asset 221 KB** — `res/font/lora_italic.ttf` referenced ONLY by `AppFonts.SerifItalic` (`theme/Fonts.kt:66-70`), itself never used.
   **Fix:** delete val + file (serif italic = `FontStyle.Italic` synthesis on lora.ttf if ever wanted).

4. **Compose compiler observability missing** — no `composeCompiler {}` block anywhere despite the exact instability-prone shapes present (EditorScreen.kt 320 KB / AnnotationCanvas.kt 314 KB sources).
   **Fix:** add metrics/reports destinations (non-default variant only); audit HomeScreen/EditorScreen stability reports once and fix top unstable params IF trivially safe — otherwise document findings in REPORT.md for a follow-up.

5. **Gradle flags** (`gradle.properties:1-17`): add `org.gradle.parallel=true` + `org.gradle.caching=true` (safe); set `android.nonFinalResIds=true` (no `getIdentifier()` usage, verified `app/build.gradle.kts:112-113` comment); trial `org.gradle.configuration-cache=true` LAST with explicit validation of the three configuration-time hazards: signing gate `taskGraph.whenReady` (`app/build.gradle.kts:254-270`), splits DSL reading taskNames (`:192`), Paparazzi plugin. If CC fails CI, revert just that flag and document.

## DoD
Release assembles green with keystore gate intact; unit tests green (count unchanged or +); usage.txt diff attached in `workspace/phase-211/REPORT.md`; APK size before/after recorded; any re-added keep rule justified line-by-line.
