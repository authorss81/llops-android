# Phase 214 — Stroke Smoothing v2 (pressure/tilt + historical batch + velocity-adaptive)

Status: **DONE** (2026-08-26). No schema change, no new dependencies, `.github/workflows/` untouched, base-APK-size rule intact.

## 1. What shipped

Four capture-pipeline upgrades behind the existing `stroke_stabilizer_enabled` gate, plus one commit-time geometry pass:

| # | Task | Where |
|---|------|-------|
| 1 | Historical (coalesced) batch ingestion | `services/StrokeInputBatcher.kt` (new) + `AnnotationCanvas.kt` passive bridge + `onDrag` drain |
| 2 | Pressure/tilt low-pass (smooth **then** remap) | `StabilizerFilter` scalar channels + `StrokeSmoothingPolicy.pressureWindowSize` |
| 3 | Velocity-adaptive alpha | `StrokeSmoothingPolicy.adaptiveAlpha` + `StabilizerFilter.next(...)` full-channel overload |
| 4 | One-Euro model behind `StreamFilter` | `services/StreamFilters.kt` (new) + `StrokeStabilizer.selectModel` + `stroke_stabilizer_model_key` pref |
| 5 | Commit-time Chaikin fairing (hairline only) | `services/StrokeFairingPolicy.kt` (new) + canvas commit path after RDP |
| 6 | Tension dial + model chips UI | `CanvasSettingsBottomSheet` + `stroke_stabilizer_prediction_percent` pref |

## 2. Capture chain — before vs after

**Before (pre-214):** each dispatched `MotionEvent` contributed ONE sample (coalesced history never read — 120–240 Hz digitizers downsampled to dispatch rate before smoothing ran); only x/y were EWMA-filtered with a static per-stroke alpha `2/(w+1)`; pressure/tilt/timestamp passed through raw (`AnnotationCanvas.kt` old `onDrag`); prediction constant fixed at `0.15`.

**After:**
1. The passive `pointerInteropFilter` bridge pushes every `getHistoricalX/Y/Pressure/getHistoricalAxisValue(AXIS_TILT)` sample of each ACTION_MOVE, then the current sample, into a lock-free SPSC ring (`StrokeInputBatcher`, window-space coordinates). `historySize == 0` ⇒ exactly one sample per event (pre-214 behaviour, pinned). UP/CANCEL flush the ring.
2. `onDrag` drains FIFO and feeds every sample through the SAME pipeline as live input: page-bounds gate → shared `ingestPointerSample` → stabilizer. A monotonic timestamp gate (`StrokeBatchPolicy.isStale`) rejects replays; the gate + ring reset at every stroke boundary (start/end/cancel). Freehand ingests the WHOLE batch; eraser/eyedropper/shape tools consume the NEWEST sample only (`applyEraser`/`sampleColorAt` rebuild per call — cost containment, deliberate).
3. Pressure (+tilt) ride their own narrower-band EWMA channel — window mapped from the main window onto **2..6** (`pressureWindowSize`: w=2→2, w=8→4, w=12→6) — and the `PressureCurveHelper.remapPressure` call moved AFTER smoothing, so gamma curves never amplify un-smoothed jitter. Stabilizer-disabled path keeps the exact legacy raw-pressure remap.
4. Per-sample velocity (`BrushStrokeMath.segmentVelocity` vs the previous accepted point) adapts the EWMA alpha: `t = clamp(v/6)`; target lerps **0.12..0.55**; result clamped into `[max(0.12, base·0.5) .. min(0.55, base·2.5)]` around the tuned base `2/(w+1)`. Window 8 (the default) spans exactly the spec'd 0.12..0.55 band; window 2 never damps below ⅓ (raw-feeling brushes stay raw), window 12 never jumps past ~2× (smooth brushes stay smooth). No timing pair yet ⇒ static base alpha ⇒ byte-identical to pre-214.
5. Model selection: `stroke_stabilizer_model_key` pref (`ewma` | `one_euro`, sanitized both ways, unknown ⇒ EWMA) swaps the filter at stroke start via `StrokeStabilizer.selectModel` (no-op when unchanged — never discards warm-up). `OneEuroStreamFilter` implements the same `StrokeStreamFilter` interface: `minCutoff = 1.2 Hz` (spec constant), beta derived from the effective window (0.004 @ w=2 → 0.03 @ w=12) so strongly-smoothed presets compensate more at speed; pressure/tilt use half-beta scalar channels; null/degenerate timestamps fall back to a 60 Hz nominal period.
6. Tension dial: `stroke_stabilizer_prediction_percent` (0..35, default 15 = the legacy `0.15` constant, sanitized both ways) feeds the existing `retune` at stroke start. Both new inputs reach the gesture closures through `rememberUpdatedState` — a settings change never restarts `pointerInput` (which would cancel an in-flight drag).
7. Commit-time fairing: after RDP, `StrokeFairingPolicy.shouldFair` gates ONE Chaikin pass (interior vertices cut ¾/¼, endpoints exact, size exactly `2n−2`) to hairline ink only: `PEN/FOUNTAIN_PEN/PENCIL/FINELINER` at ≤3 px width, hairline-band epsilon, >8 surviving points. Shape-snapped strokes never reach it. `chaikinOnce` refuses to run when the doubled result would exceed `StrokeGeometryPolicy.MAX_POINTS_PER_STROKE`, and the canvas re-enforces `capLoadedPoints` AFTER fairing — caps enforced after any fairing, never before. Pressure/tilt/timestamps interpolate onto the new points.

Prediction (motion-predicted tail) remains ephemeral preview-only (`dropPredictedTail` discipline untouched); the persisted tension percent is a tuning parameter, never geometry. `CanvasCommitListPolicy` untouched (still the single emission source); exactly one `RamerDouglasPeucker.simplify` call site survives (phase-201 pin re-verified).

## 3. UI

`CanvasSettingsBottomSheet` → Stroke Stabilizer section (visible when enabled):
- existing "Smoothing strength" slider (unchanged);
- new **"Tension (lag compensation)"** slider 0–35 % (15 % = pre-214 default), helper "Higher = snappier follow-through · Lower = rounder, calmer lines";
- new model chips **"Classic" / "Adaptive (One-Euro)"** in a horizontally scrollable row (phase-166 compact-screen discipline), helper **"Affects strokes drawn after the change"** — the honest apply-timing contract shared by all three dials.
All three persist instantly via `SettingsManager` (sanitized on read AND write) and apply at the NEXT stroke start.

## 4. Legacy parity (verified, not claimed)

- Stabilizer OFF: byte-identical capture (raw coords, `remapPressure(lastPressure)`, per-sample tilt conversion identical to the old bridge state). The only change is MORE samples on batching hardware (task 1 is orthogonal to the toggle).
- `StabilizerFilter.next(x, y)` / `StrokeStabilizer.smooth()` / `create()` defaults: same float ops in the same order — pinned bit-for-bit against the full-channel overload with null velocity (`VelocityAdaptiveAlphaTest`).
- EWMA round-trip after `selectModel(one_euro → ewma)`: bit-identical streams vs a never-switched facade (`OneEuroParityTest`).
- Non-batching devices: `historicalCount(0) == 0` + single current-sample offer ⇒ one consumed point per event (pinned).

## 5. Deliberate pin updates (not silent breakage)

- `Phase197StrokeSmoothingTest.legacy stabilizer entry points remain intact` — the capture call is now the full-channel overload; pin rewritten to assert the new call shape + raw-pressure argument.
- `Phase196MotionPredictionTest.existing stabilizer path is untouched` — same call-site change; pin rewritten, prediction-tail pipeline unchanged.

## 6. Tests

New suites (all green):
- `HistoryBatchTest` (9): the DoD **3-vs-1** coalesced case, `historySize==0` single-latest-sample pin, FIFO order, overflow sheds-oldest, `clear()` at boundaries, monotonic gate, + 2 wiring pins (historical axes in the bridge; drain-into-shared-pipeline ordering).
- `PressureSmoothingTest` (8): DoD **RMS −40 % on the 5 Hz sinusoid** (measured −47 %, see §7), tilt channel damping, dedicated 2..6 pressure-window goldens + monotonicity, channel-alpha golden (0.4 one-step response vs main-window 2/9), disabled-path parity, enabled-path convergence to `remap(smoothed)`, smooth-then-remap source-order pin. A statistical "smooth-then-remap beats remap-then-smooth" dominance test was deliberately NOT written — the SMOOTH gamma is compressive at low pressure, so neither order universally dominates in width space; the contract is pinned structurally instead (comment in the suite explains why).
- `VelocityAdaptiveAlphaTest` (9): goldens 0.12/0.55/0.335 @ window 8, monotonic ramp, window-2 floor ⅓, window-12 cap `2/13·2.5`, null/NaN ⇒ static base, behavioural settle-speed golden (fast writing converges ≥25 % sooner), bit-parity vs legacy `next(x,y)`.
- `OneEuroParityTest` (8): key sanitization fail-safe (incl. path-traversal string), facade model reporting, bit-identical EWMA after model round-trip, no-op reselect keeps warm-up, constants + beta mapping goldens, exact steady-state convergence, standstill jitter collapse (<25 % spread), fast-ramp lag < 75 % of static window-8 EWMA, SettingsManager pref pins.
- `Phase214StrokeSmoothingV2Test` (8): fairing gate matrix, endpoint preservation + `2n−2` size, turn-angle reduction, channel interpolation (pressure/tilt/timestamp), cap-refusal, degenerate inputs, commit-order source pins (simplify → shouldFair → chaikinOnce → capLoadedPoints, single simplify call site, no fairing on the snapped branch), stroke-start model/tension wiring pins, sheet UI pins, tension policy goldens.

**Total: 42 new `@Test` methods.** Updated: `Phase197StrokeSmoothingTest` (1 pin), `Phase196MotionPredictionTest` (1 pin) — see §5.

Full suite: `gradle testDebugUnitTest` = **3197 completed / 3 failed, ALL pre-existing environmental** (Phase148 UNC-path documented in AGENTS.md; PaparazziSmokeTest ×2 layoutlib) — identical to the phase-213 baseline. `gradle assembleDebug` green.

## 7. DoD evidence

**Visual** (`workspace/phase-214/before-after-smoothing.png`, generated by `workspace/phase-214/RenderSmoothingComparison.java` from the REAL compiled policy classes — Paparazzi is env-broken on the runner, phase-200/213 precedent):
- Row 1 — slow diagonal, ±2.2 px digitizer jitter, 4× coalesced input: BEFORE (one-sample-per-event, static alpha) visibly wobblier; AFTER (full batch + adaptive alpha) straighter and calmer.
- Row 2 — light pressure (0.30 ± 0.15): BEFORE shows thick-thin width lumps; AFTER noticeably more uniform width (pressure channel + smooth-then-remap).
Honest scope caveat: the harness validates the POLICY MATH pipeline (same production classes the canvas calls), not the on-device Compose render; on-device confirmation of the live preview remains future device-lab work, same class of caveat as phase-213 §5b-5.

**RMS:** `PressureSmoothingTest` measures the 5 Hz sinusoid deviation: raw ≈ 0.115 → smoothed ≈ 0.061 = **−47 %** (DoD gate −40 %).

**Bench** (desktop JVM proxy for low-end — NOT a device number, stated honestly): legacy ≈ 510–750 ns per dispatched event; v2 ≈ 440–670 ns per physical sample (≈ 2.6 µs per 4-sample coalesced event). Even at 240 Hz × 4-batch the smoothing cost is ~0.003 % of a 16 ms frame budget; the dominant per-sample costs remain unchanged (PointF alloc + wet-engine gate).

**Policy goldens** (printed by the harness): `adaptiveAlpha(w=8, v=0)=0.1200, v=3 ⇒ 0.3350, v≥6 ⇒ 0.5500`; `pressureWindow(w=8)=4`.

## 8. Constraints audit

- Schema untouched (prefs only). ✔
- Prediction tail stays ephemeral; never persisted. ✔
- `CanvasCommitListPolicy` untouched — single emission source. ✔
- Geometry caps enforce AFTER fairing (`capLoadedPoints` post-pass + in-policy refusal). ✔
- No new dependencies; no new permissions; `INTERNET` untouched. ✔
- Base-APK size: three small pure-JVM files + UI rows; no native code. ✔
- Never logs keys/content; nothing new logs at all. ✔
