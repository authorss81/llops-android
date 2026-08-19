# Phase 176: Release packaging hygiene — exclude debug/dev artifacts + retain R8 mapping (R2-KS-27 LOW + R2-KS-24 INFO) [NOT STARTED]

You are working on **InkFlow/Noteflow**. Kali round-2 static analysis (`docs/kali-report-round2.md`) found two low-severity RELEASE-PACKAGING hygiene issues with no owning phase yet:

- **R2-KS-27 (LOW)** — dev/build artifacts shipped in the release APK payload:
  `unknown/DebugProbesKt.bin` (coroutines debug probes → extra debug logging),
  `unknown/kotlin-tooling-metadata.json`, `unknown/firebase-*.properties`
  (see `app/build.gradle.kts` debug-dependency scope + packaging resource excludes).
- **R2-KS-24 (INFO)** — R8 `-printmapping` `mapping.txt` is not retained for
  release builds (`ls app/build/outputs/mapping/release/` was empty on the runner),
  so obfuscated symbols from the Kali/forensic pass can't be back-mapped.

Neither is covered by an existing phase: phase-170 covers the lingua packs + ABI
splits; phase-171 covers signing/pin; phase-175 moves ML Kit OCR/translate out of
the base APK. This phase is purely RELEASE PACKAGING (one area, two rows).

Read `docs/ARCHITECTURE.md`, `docs/RELEASE.md`, and `docs/phase-status.md` first.

## Context — current state (verify with file:line before editing)

- `app/build.gradle.kts` release build config: `isMinifyEnabled = true` (:70),
  the fail-closed signing block (:133-153), the R8 `proguard-rules.pro` (already
  retains the plugin/Runtime reflection entries + `-keepparameternames`), and the
  `packaging { resources.excludes += ... }` block (check whether the debug-probe /
  kotlin-tooling / firebase-*.properties excludes are present — phase-166-era code
  already excludes some `.properties` and META-INF noise).
- The coroutines `DebugProbesKt.bin` comes from the on-device "debugAgent"
  (`kotlinx-coroutines-debug` being pulled into the release classpath) — confirm
  the origin in the dependency closure before excluding.
- The release artifact is produced by `gradle assembleRelease` with the
  `RELEASE_KEYSTORE_B64` / `KEYSTORE_FILE` env (fail-closed B1-PLAT-01); without it
  the build FAILS CLOSED — create a throwaway keytool keystore mirroring
  `workspace/phase-159/REPORT.md` to run the release build locally if needed.

## What to do

1. Exclude the dev/debug artifacts from the **release** packaging:
   `DebugProbesKt.bin`, `kotlin-tooling-metadata.json`, `firebase-*.properties`
   (only what actually ships — verify each one exists in the payload first).
   Prefer `packaging.resources.excludes` over source changes; the only acceptable
   source-level change is removing a dependency that ONLY serves debugging (and
   prove it has no runtime use).
2. Keep R8 mapping retention on: `-printmapping` (or `isPrintMapping` default) writing
   `app/build/outputs/mapping/release/mapping.txt` — verify the file is PRODUCED by
   the release build and is idempotent across two clean release builds.
3. Document in `docs/RELEASE.md` where the mapping artifact lives and that a future
   CI/forensic pass should archive it (do NOT edit `.github/workflows/` to add the
   archive upload — note it as a deferred CI task needing user approval).
4. Do NOT remove `okhttp3/` or `org/` payload dirs (R2-KS-27 lists them as bloat,
   but they are real runtime deps — leave them; the ACTION items are the debug
   artifacts only).
5. Decide and document `extractNativeLibs` (R2-KS noise): keep the current value;
   justify it with before/after APK size in the REPORT (changing it is optional and
   only if it saves size with no perf/install downside — verify with `apksigner` +
   install-shape notes).

## Definition of done

- `unzip -l` of the release APK (build with the temporary keystore + R8 on) shows
  NO `DebugProbesKt.bin`, NO `kotlin-tooling-metadata.json`, NO `firebase-*.properties` —
  with BEFORE/AFTER listings in the REPORT.
- `app/build/outputs/mapping/release/mapping.txt` exists and round-trips at least one
  obfuscated symbol present in the phase-160 report (e.g. `w2/C3694b0`) back to its
  deobfuscated name.
- `gradle assembleDebug` green; `gradle testDebugUnitTest` green except the 1
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure (untouched).
- `apksigner verify` passes on the rebuilt release APK (same 1-signer scheme as
  phase-159). No behavior change, no new deps, no schema change.
- `workspace/phase-176/REPORT.md` with the exclusion diff, mapping evidence,
  extractNativeLibs decision, and the deferred-CI note.
- Commit + push.

## Constraints

- Do NOT edit `.github/workflows/` (mapping archival is explicitly deferred/noted).
- No new dependencies. No schema change. No app-logic changes — this is packaging only.
- The mapping.txt and keystore are secrets-adjacent: never commit the keystore or
  mapping into the repo; document retention on the runner.
- Diagnostic not elegance: prove the before → after payload difference with real
  `unzip -l` output.