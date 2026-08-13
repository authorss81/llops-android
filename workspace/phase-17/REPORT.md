# Phase 17 — State Correction (2026-08-13)

## Status: BLOCKED — NOT IMPLEMENTED

Phase 17 was falsely marked `.done` on commit `942b5ed`. That commit added only an
empty `workspace/phase-17/.done` marker and contained **zero** implementation of the
phase's stated goal.

## Intended scope (per PROMPT.md)
Real, opt-in **libmypaint** brush engine: vendor upstream source into
`app/src/main/cpp/`, build via NDK/CMake, thin JNI layer (`brush_new/load`,
`stroke_begin`, `stroke_to`, `stroke_end`, `render_tile`, `brush_list`), a Kotlin
`LibMyPaintEngine` behind the same interface as the AGSL engine, tiered fallback
(libmypaint high-end / AGSL API 33+ / vector API<33), settings toggle "Pro brush
engine (libmypaint)", 5+ bundled `.myb` brushes, pure-JVM unit tests, engine docs.

## None of it was delivered
- No `app/src/main/cpp/` directory, no `CMakeLists.txt`, no C/C++/JNI source.
- No `externalNativeBuild`/`ndkVersion` wiring in `app/build.gradle`.
- No `LibMyPaintEngine` Kotlin service, no brush catalog, no tier-selection logic.
- No unit tests; no docs update.

## Why block, not implement-on-the-spot
1. **Major architectural change** — adding NDK/CMake/JNI + a native third-party
   library tier is a major architectural change; AGENTS.md requires explicit user
   approval before writing that code.
2. **Cannot verify here** — builds are CI-only per AGENTS.md (`gradle assembleDebug`
   in GitHub Actions). Implementing a large unverifiable native codebase risks
   breaking the CI build, which phase-17 itself requires to pass.
3. **AGENTS.md honesty mandate** — the earlier libmypaint C++ stub was deleted in
   Phase 03 as a dead/fake feature; a genuine replacement must be real and verified,
   not another marker-only stub.

## Resolution
- Removed the false `workspace/phase-17/.done`.
- Added `workspace/phase-17/.blocked` so the pipeline surfaces this phase as needing
  manual intervention (surfaced as a warning/error + GitHub issue every tick) rather
  than silently advancing past it or retrying it.
- This file documents the reason.

## To un-block
A human must decide one of:
- Approve and properly implement the real libmypaint NDK engine (verify via CI
  `gradle assembleDebug` + `gradle testDebugUnitTest`), then
  `rm workspace/phase-17/.blocked` and add `workspace/phase-17/.done`; or
- Formally cancel phase-17 (update PROMPT.md / plan), then
  `rm workspace/phase-17/.blocked` and remove the phase from the run.
