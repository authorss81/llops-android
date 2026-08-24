# Phase 201: Pressure Gamma + RDP Epsilon per-Brush + Hardware Bitmap Verify [PERF 1.3+1.4+2.7]

**Goal:** Smooth low-pressure width jitter + fine-brush detail + GPU verification.

**Steps:**
1. `services/PressureCurveHelper.kt:38` 54 lines → gamma curve `pressure^1.5-2.0` low-end 0-10% to smooth width variation.
2. `utils/RamerDouglasPeucker.kt:29` epsilon 1.3 `AnnotationCanvas.kt:1185` → per-brush 0.6-0.8 for hairline strokes, confirm only on pointerUp not mid-stroke.
3. Verify `AgslShaders.kt:31` `RenderEffect` GPU-composited via `RenderNode` API31+, fallback for API26-30.
4. Extend `StrokeStabilizerTest`, `B2Dos01StrokeGeometryTest`, `StrokePersistenceRoundTripTest` golden values.

**DoD:** Pressure curve eased, RDP not snapping mid-stroke, hardware path verified via `dumpsys gfxinfo`, tests green.
