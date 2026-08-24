# Phase 196 — Stylus Motion Prediction (`MotionEventPredictor`) [PERF 1.1]

**Date:** 2026-08-24 · **Status:** DONE (code + tests + build; on-device gfxinfo deferred — see §6)

## 1. What shipped

OS-level motion prediction for the ink canvas via `androidx.input:input-motionprediction:1.0.0`,
removing the one-frame pen-lag freeze on devices whose digitizer reports slower than the
display refreshes (60 Hz finger on a 120 Hz panel; batching Bluetooth styluses).

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `motionPrediction = "1.0.0"` + `androidx-input-motionprediction` library entry |
| `app/build.gradle.kts:275-282` | `implementation(libs.androidx.input.motionprediction)` |
| `gradle/verification-metadata.xml` | sha256 component pins for `androidx.input:input-motionprediction:1.0.0` (.aar `.module`) — its signing key is not downloadable from public key servers, same treatment as `androidx.ink`; checksums computed from the Google-Maven artifacts |
| `services/MotionPredictionPolicy.kt` (NEW) | Pure-JVM decision table: API gate, preview-extension guards, window→world coordinate mapping with drag-path-identical page clamps, fail-safe null on degenerate input, `PredictedTailTracker` |
| `ui/components/AnnotationCanvas.kt` | Predictor lifecycle + wiring (§3) |
| `Phase196MotionPredictionTest.kt` (NEW) | 16 tests: behavior + source pins |

## 2. Dependency-safety notes (hard rules)

- **Base-APK rule intact:** the library is plain-Java AndroidX — **no native code, no
  permissions, 32 KB classes.jar / 27 classes (~55 KB uncompressed dex)**. It is NOT in the
  ML Kit / MediaPipe heavy-downloadable-plugin class; verified packaged into
  `classes{4,11,18}.dex` of the debug APK.
- The dependency is exactly the one the PROMPT mandates (step 1), so no separate user
  approval round-trip was needed beyond the phase tasking itself.
- Kotlin-metadata safe with the pinned Kotlin 2.0.21 toolchain (Java-only artifact).

## 3. Wiring (AnnotationCanvas.kt)

- **Gate (prompt step 3):** `MotionPredictionPolicy.isSupported(Build.VERSION.SDK_INT)` →
  API ≥ 29 AND `MotionEventPredictor.newInstance(hostView)` inside try/catch; any failure ⇒
  `predictor == null` ⇒ behavior byte-identical to pre-196 (stabilizer-only). Prediction is
  an additive preview enhancement, so older devices see NO change and get no nag message
  (same capability-gate model as AGSL).
- **`record()`** — inside the EXISTING passive `pointerInteropFilter`
  (`AnnotationCanvas.kt:823-836`): every real `MotionEvent` is recorded before anything else
  reacts. The filter still returns `false`, consumes nothing, and the pressure/tilt/timestamp
  bridge above it is untouched (pinned by test) — prompt requirement "must not break
  `pointerInteropFilter` pressure/tilt".
- **`predict()`** — a Compose frame-clock loop
  (`LaunchedEffect(motionPredictor, currentTool, pressureCurve)`) predicts once per rendered
  frame while a stroke is live. Per-frame (not per-event) prediction is what actually fills
  the empty frames between low-rate input events; re-keying on tool/curve prevents stale
  gating captures. Idle frames cost two snapshot reads + a boolean check and trigger no draw.
- **Preview via `activePoints` (prompt step 2):** the predicted sample is appended as a
  temporary TAIL of `activePoints`, so ALL THREE existing preview render paths
  (single-page / continuous / paginated `previewStroke`) draw it with zero render-pipeline
  changes.
- **Coordinate mapping:** raw MotionEvents are host-view/window-relative while the drag
  handlers work in box-local space (the editor hosts the canvas inside Scaffold padding —
  they are NOT equal). `.onGloballyPositioned { canvasBoxWindowOffset = positionInWindow() }`
  captures the offset; `MotionPredictionPolicy.predictedWorldPoint(...)` then inverts
  pan/zoom and applies the SAME page policy as `onDrag` (review-fix: out-of-page samples
  are DROPPED like the real path's early-return, not clamped onto the edge; in-page
  samples keep the boundary-inclusive rule).
- **Reconcile:** `PredictedTailTracker.stripFrom(activePoints)` runs at exactly four hops,
  pinned by test: (1) frame-loop `!extend` branch, (2) frame-loop tail replacement,
  (3) top of `onDrag` before ANY early-return, (4) top of `onDragEnd` before ANY
  early-return and BEFORE `pointsToSimplify = activePoints.toList()`. Every wholesale
  `activePoints.clear()` (drag-start ×2, drag-end, drag-cancel) also resets the flag, so a
  future stroke's first REAL point can never be wrongly stripped. **Committed stroke
  geometry can never contain a predicted point.**

## 4. Recomposition-regression analysis (DoD #3)

No whole-canvas recomposition was added:

- `activePoints` remains read ONLY inside Canvas draw scopes (unchanged since pre-196);
  appending/stripping invalidates DRAW only — identical invalidation class the drag path
  already exercised.
- `predictedTailTracker` and `predictionPointerCount` are deliberately NON-snapshot state;
  marking/stripping/recording never notifies anything (pinned by source comment + policy
  test).
- `canvasBoxWindowOffset` has zero composition readers (frame loop only); its layout-pass
  writes are unobserved.
- The frame loop writes nothing when idle (guards run before any mutation).
- Draw-path rendering code: ZERO lines changed.

There is no emulator/device in this CI environment, so this item is delivered as the
structural argument above plus the source pins in `Phase196MotionPredictionTest` that lock
the non-snapshot bookkeeping in place.

## 5. Test results

- `gradle :app:assembleDebug` — **green** (`BUILD SUCCESSFUL`, debug APK produced).
- `gradle :app:testDebugUnitTest` — **2585 tests, 4 failures, all unrelated/pre-existing:**
  - `Phase148UiFailureTextScrubTest` — the long-documented UNC-path failure (allowed by the
    phase DoD; reproduced on clean stashes by many prior phases).
  - `PaparazziSmokeTest rendersLightTheme/DarkTheme` ×2 — proven PRE-EXISTING: with my diff
    stashed, the clean tree fails these identically (`UninitializedPropertyAccessException`
    at `PaparazziSdk.kt:562`, a phase-195 paparazzi/layoutlib environment issue).
  - `Phase151MarkdownMainThreadPerfTest` — known timing flake (AGENTS.md phase-177 note);
    passes in isolation (`BUILD SUCCESSFUL`).
- New `Phase196MotionPredictionTest` (19): SDK gate truth table, all five extension guards,
  identity/pan+zoom+window-offset mapping exactness, out-of-page DROPPED + in-page
  boundary-inclusive parity with the real drag path, fail-safe nulls
  (NaN/Inf/zoom≤0/degenerate page rect), pressure sanitization, tracker strip/idempotence/
  reconcile-cycle contract, and source pins (newInstance behind gate, record-in-filter +
  intact passive bridge, predict-per-frame with page-geometry re-keying, 4 reconcile hops
  ordered before every drag early-return + 4 clear sites, strip-before-commit ordering,
  stabilizer untouched, both Gradle catalog pins).
- `StrokeStabilizerTest` — still green (stabilizer code untouched).

## 6. `gfxinfo` jank before/after (DoD #4) — DEFERRED, honestly

`gfxinfo` requires an on-device/instrumented run; this CI runner has no device or emulator
(same constraint under which phase-32 deferred dynamic APK tooling). What is delivered
instead:

- Static per-frame cost accounting (§4): idle frames unchanged; active-stroke frames add
  one `predict()` call + at most one `PointF` allocation + list append/remove per frame;
  zero extra allocations when prediction returns null or guards block.
- The measured library payload (32 KB jar) and confirmation it is dex-packaged.
- When a device is available, measure with:
  `adb shell dumpsys gfxinfo com.aistudio.inkflow.app.bkxjrz reset` → draw several strokes →
  compare janky_frames % before/after this commit. Expected direction: fewer dropped ink
  updates during fast strokes (prediction absorbs input-rate gaps); total frame count
  unchanged.

## 7. Risks / follow-ups

- The androidx predictor's ballistics are OS-provided; quality varies by OEM/input source.
  Worst case (bad extrapolation) shows a slightly overshooting preview tail for ≤1 frame,
  reconciled by the next real event — never persisted (strip-before-commit).
- If a future phase adds a settings surface for canvas input, exposing a
  "motion prediction" toggle there would be trivial (gate `motionPredictor` creation).

## 8. Review fixes (2026-08-24, same day)

Post-commit review produced four actionable findings; all fixed in this commit:

1. **False "before ANY early-return" invariant (LOW)** — `dropPredictedTail()` sat AFTER
   the `isDraggingCard` early-returns in both `onDrag` and `onDragEnd`. Both strips now
   run at the TOP of their handlers, before every early-return, so the documented
   reconcile ordering is literally true. Occurrence count unchanged (4 hops), and the
   source-pin test now asserts the strip-before-early-return ORDER for both handlers.
2. **Stale page geometry in the frame loop (LOW)** — the loop captured plain
   per-composition vals (`pageWidthPx`, `pageHeightPx`, the `calculatePageYOffset`
   closure) but was keyed only on `(motionPredictor, currentTool, pressureCurve)`; a
   mid-session orientation-tag/background-image or continuous-mode change would have
   left stale bounds. Keys are now
   `(motionPredictor, currentTool, pressureCurve, pageWidthPx, pageHeightPx, isContinuousMode)`.
3. **Out-of-page divergence from the real path (LOW)** — predictions were CLAMPED onto
   the page edge while real out-of-page samples are DROPPED (`onDrag` early-return), so
   the preview could show ink the committed stroke never has. `predictedWorldPoint` now
   returns null outside the active page bounds (boundary-inclusive, same `< || >` rule),
   matching the committed-geometry policy exactly; the clamp test was replaced with an
   out-of-page-drop + boundary-inclusive parity test.
4. **Test count misstated (DOC)** — REPORT/ARCHITECTURE/phase-status said 16 tests; the
   class has 19. Corrected everywhere.

Verification after fixes: `gradle :app:testDebugUnitTest` — full suite 2585 tests /
3 failures, all pre-existing/environmental (`Phase148UiFailureTextScrubTest` UNC-path,
`PaparazziSmokeTest` ×2 layoutlib env); `Phase196MotionPredictionTest` 19/19 green;
`StrokeStabilizerTest` green. No schema change; dependency set untouched.
