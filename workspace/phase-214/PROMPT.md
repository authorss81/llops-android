# Phase 214 — Stroke Smoothing v2 (pressure/tilt + historical batch + velocity-adaptive)

## Goal
Professional-grade **stroke smoothening** beyond single EWMA x/y — eliminate width/angle jitter, reclaim coalesced MotionEvent samples, and adapt damping to drawing speed. Must keep undo/commit/lock safety and legacy parity.

## Context — verified anchors
- **Capture chain today:** `AnnotationCanvas.kt:916-936` `pointerInteropFilter` reads `pressure/tilt/eventTime/toolType` + `motionPredictor.record` (passive, consumes nothing); `1146` `detectDragGestures` is the smoothing path; per-move `1341-1363` remaps pressure via `PressureCurveHelper.remapPressure` (`services/PressureCurveHelper.kt:36-73`) then **only x,y** EWMA: `stabilizerFilter.next(x,y)` (`services/StrokeStabilizer.kt:88-112` `alpha=2/(w+1)`, `out=EWMA + velocity*0.15`); `pressure/tilt/timestamp` pass unsmoothed (`AnnotationCanvas.kt:1361-1363`).
- **Stabilizer policy:** `services/StrokeSmoothingPolicy.kt:30-99` — `window 2..12` (`2+round(s*10)`), `BrushPreset.smoothing` per-brush (`BrushPreset.kt:27` defaults 0.15-0.90), slider `stroke_stabilizer_strength_percent 0..100` + `FINGER_EXTRA_WINDOW=2`, applied at next stroke start `AnnotationCanvas.kt:1270-1282` via `retune(window)`. `PREDICTION=0.15` constant — no tension/damping.
- **Missing coverage:** historical batch `getHistoricalX/Y/Pressure` never read (each MotionEvent → one sample, coalesced points lost); pressure/tilt never low-passed (light-touch width jitters); alpha static (slow writing over-smoothed, fast under-smoothed); no One-Euro/Kalman, no spline fairing; `RamerDouglasPeucker:13-92` decimation `epsilon 0.6-1.3` (`services/StrokeSimplifyPolicy.kt:28-47` hairline-aware) runs only at commit `AnnotationCanvas.kt:1501-1506`.
- **Prediction:** `services/MotionPredictionPolicy.kt:37-166` tail-only preview `PredictedTailTracker`, stripped before commit (`dropPredictedTail()` `:1409`) — not smoothing, masks 1-frame lag.
- **Render smooth:** `PEN` quadratic Bezier `Path` `AnnotationCanvas.kt:4314-4353` is draw-time only.

## Tasks
1. **Historical batch ingestion:** in `pointerInteropFilter` loop `0 until historySize` push `getHistoricalX/Y/Pressure/AXIS_TILT` synthetic `PointF`s into a lock-free queue consumed by `onDrag` before EWMA. Achieves 2-3× temporal resolution on batching devices, zero latency. Pin that `historySize==0` still yields single latest sample.
2. **Pressure/tilt low-pass:** fold `pressure` (+ `tilt`) through same adaptive cutoff as x/y before `PointF` creation. Order: **smooth pressure THEN `remapPressure`** to avoid gamma-amplified jitter (`PressureCurveHelper.kt:14-22` contract). Separate `pressureWindow 2..6` allowed if pressure jitter dominates tilt.
3. **Velocity-adaptive alpha:** per `onDrag` compute `vel=BrushStrokeMath.segmentVelocity(prev,curr)` and lerp `alpha = 0.12..0.55` over `vel/6` clamped by `effectiveWindowSize`. Slow→stable, fast→responsive. Golden tests.
4. **(Optional behind policy) One-Euro adaptive filter** behind `StreamFilter` interface (`EWMAFilter` legacy parity + `OneEuroFilter(minCutoff=1.2, beta)`) selected via `BrushPreset.smoothingInterpretation` or `stabilizerModelKey` pref; `StrokeSmoothingPolicy` maps slider 0-100 → cutoff/tension preserving `effectiveWindowSize` migration. Fallback `LOW_END` skips spline fairing if `frameTimeEmaMs>20`.
5. **Commit-time fairing (pointer-up only, cheap):** after RDP, if `points>8` && hairline epsilon, single Chaikin pass or already-present RDP → cubic fit, behind `StrokeGeometryPolicy` caps (`MAX_POINTS_PER_STROKE 20000`). Purely geometry — no DB change, no ` CanvasCommitListPolicy` change beyond point content.
6. **UI/flag gating:** extend `CanvasSettingsBottomSheet` tension concept (maps to `prediction 0.0..0.35`) if trivial; persist via `SettingsManager` sanitized; apply at next stroke start via existing `retune`. Document "affects strokes drawn after the change".

## Constraints
- Schema untouched; prediction stays ephemeral never persisted; `CanvasCommitListPolicy` single source; geometry caps enforce after any fairing.
- DoD: `gradle assembleDebug` + `testDebugUnitTest` green; new tests: `HistoryBatchTest` (3 vs 1 per coalesced event), `PressureSmoothingTest` RMS -40% on 5Hz sinusoid, velocity goldens, One-Euro parity vs EWMA fallback. REPORT.md shows before/after stroke PNG crops (slow diagonal + light-pressure width) + bench ms on low-end.
