# Phase 239 — Fix Phase 235 (Instrumented Tests) Properly [FIX]

## Status: DONE

## Goal
Fix the 4-hour timeout failure of `phase-235` (instrumented Android tests) by
addressing the three root causes identified in the phase-239 prompt:
1. **x86_64 emulator boot failure** (no HW acceleration on GitHub runners)
2. **Edit-tool JSON parse errors** in the phase-235 agent run
3. **Dependency verification failure** of the `androidTest` runtime classpath
   (`com.squareup:javawriter` + 13 others in `gradle/verification-metadata.xml`)

## Summary of the decision
The phase-239 PROMPT's stated mechanism — move `createComposeRule()` Compose UI
tests into `app/src/test/` so they run in `gradle testDebugUnitTest` "on the
JVM, no AVD" — is **technically infeasible without Robolectric**.

`androidx.compose.ui:ui-test-junit4` requires a live Android runtime
(`InstrumentationRegistry`, a real `Looper`/`Context`/`window`, a Compose
host). Local unit tests run against the mockable android.jar with
`isReturnDefaultValues = true` (see `app/build.gradle.kts:199-208`), which
deliberately does NOT provide a functioning Android environment. The project
explicitly avoids Robolectric ("without pulling in Robolectric",
`app/build.gradle.kts:202`). Robolectric would itself require new
`verification-metadata.xml` entries — an explicit phase-239 hard constraint.

So the only technically-sound way to ship critical-path regression coverage
that satisfies **all** phase-239 constraints simultaneously (JVM
`testDebugUnitTest`, no emulator, no new verification entries, no
`app/src/androidTest/`, no `llops.yml` edit) is to write **pure-JVM logic
tests** over the real classes that back each critical-path UI feature. This
preserves phase-235's stated intent ("catches regressions in the drawing
pipeline, color picker, lasso, symmetry, markdown") at the correct layer: the
Compose gesture/layout code was never JVM-testable, but the decision math it
routes through is.

### Root-cause remediation
| Root cause | Fix |
|---|---|
| Emulator can't boot (no KVM) | No `connectedAndroidTest`/AVD used; all tests are JVM unit tests. |
| Edit-tool JSON parse errors | No `edit`-tool failures; all new content written via file writes. |
| Verification failure (`javawriter` + 13, from `espresso` via `ui-test-junit4`) | **Removed** the `androidTestImplementation(compose bom / ui-test-junit4 / test-ext-junit)` + `debugImplementation(ui-test-manifest)` deps that phase-235 added (`app/build.gradle.kts`). That kills the `:app:debugAndroidTestRuntimeClasspath` config entirely. No `verification-metadata.xml` change. |

## What changed
- **`app/build.gradle.kts`** — removed the phase-235 `androidTestImplementation`
  + `debugImplementation(ui-test-manifest)` block and replaced it with a comment
  documenting the phase-239 resolution. No new dependency was added: the 5 test
  files use the already-verified `junit` (`testImplementation(libs.junit)`,
  `app/build.gradle.kts:414`).
- **5 new test files** (in `app/src/test/kotlin/com/authorss81/noteflow/`, all
  pure JVM, run in `gradle testDebugUnitTest`):

| File | Phase-235 scenario | Backing logic pinned (file:anchor) |
|---|---|---|
| `Phase239EditorScreenCanvasDrawTest` | canvas draw: point accumulation, PARTIAL eraser split, wet mask, symmetry twin | `WetCanvasEngine.markPaintDeposited`, `BrushStrokeMath` (wetness peaks, light pressure, velocity/bristle), `EraserGeometryPolicy` (round partial mask), `SymmetryCommitPolicy.bakedTwin`+`SymmetryHelper` |
| `Phase239ColorPickerBottomSheetTest` | HSV slider + harmony swatches + picker opens w/o crash (`c972b23`) | `ColorHarmonyHelper` (RGB↔HSL round-trip, harmony hue rotation), `PaletteMath` (`familyFor`/`hsvOf` — Phase-21 hue-band fix), `ColorFamily` |
| `Phase239LassoSelectionTest` | SELECT loop select, Copy/Paste grows set, scale handle >1, rotate handle != 0 | `LassoPolicy` (classifyDrag, winding contains), `StrokeSelectionActionPolicy` (duplicate, clipboard round-trip), `SelectionTransformPolicy` (corner scale clamp, rotate/`rotatedBounds`) |
| `Phase239MarkdownEditorTest` | typed `[[Note` popup, markdown text/block handling | `WikiSuggestionPolicy` (locateQuery, suggest ranking, snippet), `WikiLinkParser.extractWikiLinks`, `MarkdownBlockTokenizer` (block types, checkbox toggle, round-trip) |
| `Phase239WetShadeRegressionTest` | light-pressure watercolor not dropped, PARTIAL-eraser mask, no dark seam | `BrushStrokeMath` (light pressure pigment), `WetCanvasEngine`, `WetMixingMath` (sourceOverAlpha monotonic, pigment-mix endpoint exactness/clamp), `EraserGeometryPolicy` |

The 5 files carry **52 test methods** (single-scenario decompositions, not just 5).

## Verification (all on this JVM, no emulator)
- `gradle :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **3545 tests / 0
  failures / 0 errors / 0 skipped** (baseline 3493 + 52 new).
- `gradle :app:assembleDebug` → BUILD SUCCESSFUL (46s).
- `gradle :app:assembleRelease` → BUILD SUCCESSFUL (R8 minify + shrinkResources
  + real release keystore signing, 8m38s; only `compileReleaseArtProfile` SKIPPED,
  which is the separate baseline-profile producer concern).
- `gradle :app:lintDebug` → BUILD SUCCESSFUL, **0 errors** (109 pre-existing
  deprecation warnings, untouched).

### DoD checklist
- [x] 5 new test files in `app/src/test/kotlin/com/authorss81/noteflow/`
- [x] `gradle :app:testDebugUnitTest` green (3545 total, incl. 52 new)
- [x] `gradle :app:assembleDebug` + `assembleRelease` green
- [x] `gradle :app:lintDebug` 0 errors (109 pre-existing warnings)
- [x] `gradle/verification-metadata.xml` UNCHANGED (`git diff` empty)
- [x] `app/src/androidTest/` does NOT exist
- [x] `workspace/phase-239/REPORT.md` (this file)

### Constraints honored
- No emulator (`connectedAndroidTest` not run).
- No new `verification-metadata.xml` entries (in fact the failing deps were removed).
- No schema change.
- No `.github/workflows/` edit (`llops.yml` and `android.yml` untouched).
- No `app/src/androidTest/` directory.
- No `@Ignore`d tests (all 52 pass deterministically — no flaky/timing/threading deps).

## Deviation from the PROMPT's literal Step 1/2 (with justification)
The PROMPT asked to add `testImplementation("androidx.compose.ui:ui-test-junit4")`
and call `createComposeRule()` in JVM tests. As reasoned above and verified
empirically in this phase (the compiled Compose path never even reaches a green
state without Robolectric), that literal path cannot produce green `testDebugUnitTest`
on a plain JVM — it would fail the DoD's green-tests requirement. Writing the same
critical-path scenarios against the real pure-JVM backing classes is the only
approach that meets every stated hard constraint AND the green-tests DoD. This is
documented here so a future phase does not re-introduce instrumentation/Robolectric
as a "fix" that the pipeline cannot run.

## Before / After
- Before: phase-235 timed out at 4h; `:app:processDebugAndroidTestManifest` failed
  dependency verification (`javawriter` + 13); x86_64 emulator couldn't boot.
- After: 52 new pure-JVM critical-path regression tests, all green in
  `testDebugUnitTest`; debug+release builds green; lint 0 errors; no verification
  metadata change; no emulator; no androidTest.
