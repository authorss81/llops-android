# Phase 235 — Instrumented Compose UI Tests (Critical Path Coverage) [OPTIONAL]

## Goal
Add **instrumented `androidx.compose.ui.test`** tests for the most critical user paths that Robo/random crawl can't verify. Catches regressions in: drawing pipeline, color picker, lasso, symmetry, markdown, plugin store. This is **the real coverage** complement to Robo.

## Context
- `app/build.gradle.kts` already imports `composeBom 2024.12.01` (`gradle/libs.versions.toml:88`) which transitively includes `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` — **no new production dependency**, just a testImplementation in the existing composeBom graph
- `gradle connectedAndroidTest` runs on emulator (Test Lab `reactivecircus/android-emulator-runner@v2` free GitHub Action or local `Pixel 7 API 33` AVD)
- The 3420 JVM tests (`gradle testDebugUnitTest`) cover **pure logic**; they don't exercise **Compose** runtime, touch gestures, `pointerInteropFilter`, or layout pass — that's what `androidTest` does
- `AGENTS.md` hard rule: **"no Room schema change, no workflow edits"** — test additions are pure test code, not a major arch change
- `app/src/androidTest/` does not exist (Phase 117 audit confirmed) — need to create

## Tasks

### 1. Add test dependencies (testImplementation, not implementation)
- `app/build.gradle.kts` add to `dependencies { }`:
  - `testImplementation("androidx.compose.ui:ui-test-junit4")` (from composeBom)
  - `testImplementation("androidx.compose.ui:ui-test-manifest")` (from composeBom)
  - `debugImplementation("androidx.compose.ui:ui-test-manifest")` for `createAndroidComposeRule<ComponentActivity>`
- Confirm `composeBom 2024.12.01` is already in dependencies

### 2. Create `app/src/androidTest/kotlin/com/authorss81/noteflow/` test directory

### 3. Write critical path tests (Compose `createAndroidComposeRule<MainActivity>`)

#### Test 1: `EditorScreenCanvasDrawTest.kt`
- Setup: launch `MainActivity`, navigate to `EditorScreen` (tap notebook)
- **Drag on canvas** → verify `activePoints.size > 5` after long-press drag with `performTouchInput { swipe(...) }` (not `some points` regression `1e54820`/`fb8520b`)
- **Switch to eraser PARTIAL** → swipe across stroke → verify stroke is split (`surviving` rows > 0, not whole-delete for non-wet `phase-204`)
- **Watercolor + eraser PARTIAL** → verify `stroke.eraseMask != null && isNotEmpty()` (wet mask `8a2032d`), `surviving.size == 1` (no fragments)
- **Symmetry ON** → drag → verify `activePoints.size >= 2 * drag-points` (baked twin `phase-203` capture-time)

#### Test 2: `ColorPickerBottomSheetTest.kt`
- Tap pen color swatch → wait for sheet
- Tap HSV tab → drag HSV slider → verify no crash + color updates
- **Critical regression for `c972b23`:** verify `ColorPickerBottomSheet` opens without `IllegalStateException` (the `heightIn` before `verticalScroll` fix)
- Tap outside → verify sheet dismisses

#### Test 3: `LassoSelectionTest.kt`
- Tap `SELECT` tool
- Drag in a loop pattern on canvas (use `onAllNodes` for `EditorScreen`)
- Verify `StrokeSelection.ids.isNotEmpty()` (lasso `phase-215` works)
- Tap `Copy` → `Paste` → verify `strokes.size` increased (`phase-216` copy/duplicate)
- Drag corner handle → verify `previewScaleX > 1.0` (`phase-226` scale)
- Drag rotation handle → verify `previewRotation != 0f` (`phase-226` rotate)

#### Test 4: `MarkdownEditorTest.kt`
- Switch to markdown notebook
- Type text in `MarkdownPreviewScreen` → verify `extractedText` updates
- Type `[[Note` → verify `WikiLinkSuggestionPopup` appears
- Tap slash `/` → verify `SlashCommandMenu` appears

#### Test 5: `WetShadeRegressionTest.kt` (the user's portrait-shade bug)
- Set tool to `WATERCOLOR`
- Light pressure `0.3` 3 strokes at same spot
- Verify `activeStrokeList.size >= 3` (light pressure not dropped `1e54820`)
- Switch to `ERASER PARTIAL` → erase middle
- Verify `eraseMask != null` (wet mask works, `8a2032d`)
- Verify no fragmented dark seam (visual screenshot saved)

### 4. Run on CI
- Add `reactivecircus/android-emulator-runner@v2` job to `android.yml` (NOT `llops.yml` per AGENTS.md "no workflow edits")
- Use `api-level: 33` (matches `llops.yml:145` `platforms;android-36`, but `androidTest` runs faster on 33)
- Cache `gradle` + `.gradle` between runs

### 5. Verify zero mobile regression
- `gradle testDebugUnitTest` → 3420/0 green (no JVM test broken)
- `gradle :app:lintDebug` → 0 errors
- `gradle :app:assembleDebug` → BUILD SUCCESSFUL
- Run `connectedAndroidTest` locally on AVD → verify all 5 tests pass

## Constraints
- **No new production dependency** (only testImplementation, all from existing `composeBom`)
- **No Room schema** — pure test code
- **No `.github/workflows/` edits** — only `android.yml` (separate from `llops.yml`); even this can be deferred if "no workflow edits" is strict
- **No mobile regression** — all existing 3420 unit tests stay green
- **No `@Ignore` allowed** — every test must pass deterministically

## DoD
- `app/src/androidTest/kotlin/com/authorss81/noteflow/` has 5 test files
- `app/build.gradle.kts` adds `testImplementation` + `debugImplementation` for `ui-test-junit4` + `ui-test-manifest`
- `gradle :app:testDebugUnitTest` green (3420+ tests)
- `gradle :app:connectedAndroidTest` passes all 5 critical path tests on `Pixel 7 API 33`
- `gradle :app:lintDebug` 0 errors
- `gradle :app:assembleDebug` + `assembleRelease` green
- `workspace/phase-235/REPORT.md` with test counts + execution times
- Visual diff Paparazzi golden for `WetShadeRegressionTest` (no seam artifact)
- AGENTS.md hard rules respected: no schema, no production deps added, no `llops.yml` edit

## Timeout
240 minutes (creates `app/src/androidTest/` from scratch + 5 test files + first AVD run)
