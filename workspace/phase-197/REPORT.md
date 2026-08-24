# Phase 197 — Per-Brush Stroke Stabilizer Tuning + Smoothing Slider [PERF 1.2]

**Date:** 2026-08-24 · **Status:** DONE (code + tests + `assembleDebug` green; full suite green except the documented pre-existing failures)

## 1. What shipped

The stroke stabilizer no longer runs every brush and every input source with one fixed
tuning (`windowSize=8`, `prediction=0.15f`). Each brush preset now carries its own
`smoothing` fraction, a persisted user strength slider (0–100%) trims it, and finger input
automatically smooths more than stylus.

| File | Change |
|---|---|
| `services/StrokeSmoothingPolicy.kt` (NEW) | Pure-JVM decision table: smoothing→window mapping (2..12), slider trim semantics, finger/stylus adjustment, sanitization. `DEFAULT_SMOOTHING=0.6f` maps to window **8** — exact pre-197 parity |
| `services/StrokeStabilizer.kt` | `StrokeStabilizer.retune()` + `StabilizerFilter.retune(windowSize, prediction)` — live re-tune between strokes; fields became `private var`; EWMA alpha is derived per-call so retune takes effect from the next sample. Defaults untouched |
| `services/BrushPreset.kt` | New `smoothing: Float = DEFAULT_SMOOTHING` field on `BrushPreset` + per-preset values + `validatePresetSmoothing()` |
| `services/BrushPresetFileCodec.kt` | `.inkbrush` bundles carry `smoothing` as an OPTIONAL key: encode always writes it, decode reads it fail-safe (`sanitizeSmoothing`) — pre-197 bundles without the key decode unchanged |
| `services/SettingsManager.kt` | `strokeStabilizerStrengthPercent` pref (`stroke_stabilizer_strength_percent`, default 100), sanitized on read AND write |
| `ui/components/AnnotationCanvas.kt` | Passive `getToolType(0)` capture in the existing `pointerInteropFilter` → `lastInputIsStylus`; `stabilizerStrengthPercent` param; retune at every drag START from preset+slider+input; new keys on the drag `pointerInput` |
| `ui/screens/EditorScreen.kt` | State + pass-through to the canvas; "Smoothing strength" slider in `CanvasSettingsBottomSheet` (0–100%, 5% steps), visible while the stabilizer is on, writes state + prefs (immediate apply) |

## 2. The tuning model

```
base     = windowForSmoothing(preset.smoothing ?: 0.6)          // 2..12
baseline = base (+2 if pointer is FINGER)                        // input-source fold-in
window   = clamp(2 + round((baseline − 2) × slider%), 2, 12)    // user trim
```

- **Per-brush character:** smoothing 0..1 → window `2 + round(s·10)`. Pencil 0.15→4,
  fountain pen / calligraphy-chalk 0.30→5, highlighter 0.45→7, oil 0.60→8,
  marker 0.70→9, watercolor 0.90→11. **Fast calligraphy is tighter than marker**
  (prompt requirement); eraser carries a neutral 0.5 but never routes through the
  stabilizer.
- **Slider semantics:** 100% = "the smoothing this brush was designed with" (= pre-197
  behavior for stylus/no-preset), 0% = raw input ALWAYS (the finger boost folds into the
  baseline BEFORE the trim, so 0% can never be overridden back up). Intermediate values
  interpolate linearly.
- **Input source:** STYLUS/ERASER tool types are the design baseline; FINGER (and any
  unknown type — fail toward more smoothing) widens the baseline by +2 windows because
  fat-finger jitter is worse than digitizer jitter.
- **Legacy parity golden:** no preset + slider 100% + stylus ⇒ window **8**, prediction
  **0.15f** — byte-identical tuning to pre-197 (pinned by test).
- Prediction stays the pre-197 constant; only the window adapts.

Preset values were chosen by tool physics: precise/fast tools low, broad/forgiving high.

## 3. Wiring notes

- The pinned `val stabilizerFilter = remember { StrokeStabilizer.create() }` line is kept
  BYTE-IDENTICAL (phase-196 source pin preserved); re-tuning happens at each drag start via
  the new `retune()`, immediately after the existing `reset()`, BEFORE the first point —
  so even the first filtered sample of a stroke uses that stroke's tuning.
- `activeBrushPresetId`, `importedBrushPresets`, and `stabilizerStrengthPercent` joined the
  drag detector's `pointerInput` keys so the handler can never act on stale captures (same
  pattern as the existing `stabilizerEnabled` key).
- Tool-type capture lives in the SAME passive `pointerInteropFilter` as pressure/tilt/
  timestamp (still returns false, consumes nothing; the phase-196 predictor record()
  bridge is untouched).
- Slider changes apply to the NEXT stroke — the settings sheet blocks canvas gestures while
  open, so there is no mid-stroke retune path at all. Persistence is immediate
  (state write + prefs write in one callback).

## 4. Before/after stroke samples (deterministic harness)

Same noisy hand path (seeded RNG, ±4.5 px jitter, intended line y = x + 40, 60 samples)
through the stabilizer at each golden window. First 12 samples:

| i | raw x,y | w2 x,y | w8 (legacy) x,y | w12 x,y |
|---|---|---|---|---|
| 0 | 2.1, 41.2 | 2.1, 41.2 | 2.1, 41.2 | 2.1, 41.2 |
| 1 | 3.2, 36.6 | 3.0, 37.7 | 2.4, 40.1 | 2.3, 40.4 |
| 2 | 0.6, 41.9 | 1.2, 41.0 | 1.9, 40.6 | 2.0, 40.8 |
| 3 | 6.6, 45.2 | 5.4, 44.1 | 3.1, 41.8 | 2.8, 41.5 |
| 4 | 5.9, 44.6 | 5.6, 44.4 | 3.7, 42.4 | 3.3, 42.0 |
| 5 | 3.7, 49.1 | 4.1, 48.0 | 3.6, 44.0 | 3.3, 43.2 |
| 6 | 2.6, 43.8 | 3.0, 44.6 | 3.4, 43.8 | 3.1, 43.2 |
| 7 | 10.1, 48.8 | 8.5, 47.9 | 5.1, 45.1 | 4.4, 44.1 |
| 8 | 4.2, 49.7 | 5.1, 49.2 | 4.7, 46.1 | 4.2, 45.0 |
| 9 | 12.9, 45.5 | 11.1, 46.3 | 6.8, 45.8 | 5.8, 45.0 |
| 10 | 11.3, 46.5 | 11.1, 46.6 | 7.8, 46.0 | 6.6, 45.2 |
| 11 | 13.6, 53.2 | 13.0, 51.6 | 9.1, 47.8 | 7.7, 46.6 |

Aggregate error over all 60 samples (perpendicular distance to the intended line, px):

| Tuning | RMS | Max deviation |
|---|---|---|
| raw (stabilizer off) | 2.82 | 6.03 |
| window 2 (slider 0% / smoothing 0) | 2.18 | 4.30 |
| **window 4 — pencil preset** | **1.47** | **3.01** |
| **window 8 — legacy default / oil @100%** | **1.00** | **2.14** |
| **window 12 — max slider+smoothing / watercolor+finger** | **0.80** | **1.71** |

Reading: the pencil's tight window 4 keeps handwriting crisp while still removing ~48% of
jitter energy vs raw; marker/watercolor-class brushes and finger input land near window
12 where jitter is reduced ~71–92%. Window 2 remains visibly responsive (settles within
≤6 samples of a step, pinned by golden test) — the "off" end of the dial.

## 5. Tests

New/extended:

- `StrokeStabilizerTest` 5→**10**: golden window 2 step response (≤6 samples), golden
  window 8 == legacy defaults EXACTLY on identical input, golden window 12 visibly slower
  (>2× settle samples, still >20 px from target after 5), monotone jitter suppression
  var(w12) < var(w8) < var(w2) < raw, and `retune()` live-retunes (plus sub-minimum clamp).
- `Phase197StrokeSmoothingTest` (NEW, **17**): policy goldens (stylus/no-preset/default =
  8; min 2 at zero smoothing OR zero slider; max 12; finger > stylus; finger capped at 12;
  slider interpolation + monotonicity across the whole dial; sanitizers; prediction const),
  pack contract (all presets in-range, calligraphy < marker, pencil ≤ calligraphy, all
  pack windows inside 2..12 for both inputs), codec contract (custom value round-trips,
  pre-197 bundle without the key decodes to the default, hostile 42.0 clamps to 1.0), and
  source pins (passive `getToolType` capture, retune behind the enabled gate reading
  preset+slider+input, full `pointerInput` key list, sheet slider + persistence lines,
  SettingsManager pref key, legacy entry points intact).

Verification:

- `gradle :app:testDebugUnitTest` — **2612 tests / 3 failures / 0 errors**, all three
  pre-existing and unrelated (identical on clean trees): `Phase148UiFailureTextScrubTest`
  (long-documented UNC-path scrub case), `PaparazziSmokeTest` ×2 (layoutlib environment
  failure documented since phase-195/196). Phase-196 baseline was 2585 tests with these
  same failures.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL**.

## 6. Constraints honored

- No DB schema change (prefs only), no new dependencies, `.github/workflows/` untouched.
- Base-APK rule intact (no new artifacts at all).
- No silent degradation: the slider is honest UI ("Higher = steadier lines, more lag"),
  and devices whose pens report as fingers simply get the (safe) finger tuning.
- Phase-196 pins untouched: `MotionEventPredictor` wiring, reconcile hops, and the exact
  `remember { StrokeStabilizer.create() }` line all still hold their pinning tests.

## 7. Risks / follow-ups

- Devices that report a passive pen as `TOOL_TYPE_FINGER` get the +2 finger boost —
  conservative direction (more smoothing), and the slider can pull it back.
- A future Brush Studio surface could expose per-preset smoothing edits (the model field
  and validation already exist); out of scope here by prompt.
