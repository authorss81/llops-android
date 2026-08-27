# Phase 228 — Fix All Failing Tests + Green APK/CI (6h, no regressions)

## Goal
Make `gradle testDebugUnitTest` **100% green** (0 failures) and **both APK builds green** (`assembleDebug` `assembleRelease`) with **full Android CI green** — without breaking any currently passing test or feature.

## Context — verified anchors
- **Current lane:** `git ls-files workspace/phase-*/.done` 222/224, `phase-118/.blocked` historic, `phase-227/PROMPT.md` pending paper deckled + export. After `5b9ee7d` (`phase-226` selection transform) many new suites landed (`BlenderScatterPolicyTest`, `StrokeSelectionActionPolicyTest:439`, `SelectionTransformPolicyTest:247`, `FillToleranceTest:229` etc.) — a flaky `Phase148` `WikiLinkParserCache` wave seen in `phase-214/215` logs, plus recent `setup-android` `packages` comma fix `43286dc` that must stay green.
- **CI:** `.github/workflows/llops.yml` `select-phase` + `run-phase 120min:120` + `review 120min:298` all run `gradle/actions/setup-gradle@v6` `8.13` + `setup-android@v4` `packages: 'platforms;android-36 build-tools;36.0.0 ndk;27.0.12077973 cmake;3.22.1'` `145` + `opencode 1.15.12`. `AGENTS.md` hard rule: no workflow edits, no schema without approval.
- **Builds:** `app/build.gradle.kts:15` `com.aistudio.inkflow.app.bkxjrz`, `gradle/libs.versions.toml` `agp 8.7.3`, `kotlin 2.0.21`, `composeBom 2024.12.01`, `R8 fullMode` + `shrinkResources` `phase-199`.

## Tasks
1. **Discover:** `gradle testDebugUnitTest --continue` (or `--rerun-tasks`) → collect every `FAILED`/`*Test` class + `gradle assembleDebug` / `assembleRelease` errors. Triage into (a) real product bug (b) flaky timeout (c) stale golden/Paparazzi snapshot.
2. **Fix without breaking:** for each failure, fix **product code** or **test determinism** (never `@Ignore`), keep `grep -r "Phase\d+.*Test" app/src/test` count non-decreasing. Isolate via pure-JVM `Policy` where possible (`services/*Policy.kt` pattern).
3. **Verify green:** `gradle testDebugUnitTest` **all green** (re-run with `--rerun-tasks` once), `gradle assembleDebug` green, `gradle assembleRelease` green (fail-closed `RELEASE_KEYSTORE_B64` warning expected when local, but task must not `FAILED`), `gradle lintDebug` 0 errors (warnings OK).
4. **CI parity:** ensure `.github/workflows` local `act` shape passes: `gradle build` (assemble + test) green. No new deps that break `verification-metadata.xml`.

## Constraints
- No Room schema, no `.github/workflows/` edits, no new heavy native deps. Keep `Apache-2.0` `LICENSE:1`.
- Keep `Phase-199` baselineprofile + `R8` + `baselineprofile` producer intact; do not add `androidTest` emulator deps.

## DoD
`gradle testDebugUnitTest` 0 failures (attach `test-results` summary in `REPORT.md`), `assembleDebug` + `assembleRelease` green, `lintDebug` 0 errors, `git diff --stat` lists only test/product fixes (no workflow edits), `workspace/phase-228/REPORT.md` table `Test | Was | Fix (file:line) | Verified`.
