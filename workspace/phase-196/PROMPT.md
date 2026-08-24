# Phase 196: Stylus Motion Prediction — MotionEventPredictor [PERF 1.1]

**Goal:** Add OS-level motion prediction to eliminate 1-frame pen lag on 120Hz devices.

**Context:** `AnnotationCanvas.kt:699-706` uses `pointerInteropFilter` + `services/StrokeStabilizer.kt:36` (EWMA 8 / prediction 0.15f) but no `androidx.input.motionprediction.MotionEventPredictor`. This is the biggest perceived-latency win.

**Steps:**
1. Add dependency `androidx.input:input-motionprediction:1.0.0` to `gradle/libs.versions.toml` + `app/build.gradle.kts`.
2. In `AnnotationCanvas.kt:699` init `MotionEventPredictor.newInstance(canvasView)`; on `onTouchEvent` feed real `MotionEvent` → `predictor.record()` → `predictor.predict()`; draw predicted point for in-progress segment via `activePoints` preview, reconcile on next real event. Must not break `pointerInteropFilter` pressure/tilt.
3. Gate: fallback if `Predictor` unavailable (API <29) → existing stabilizer only.
4. Verify `StrokeStabilizerTest` still green, no lag on finger vs stylus.

**DoD:** `gradle assembleDebug` green, `testDebugUnitTest` green (1 pre-existing failure allowed), no whole-canvas recomposition regression, `gfxinfo` jank before/after in REPORT.md.
