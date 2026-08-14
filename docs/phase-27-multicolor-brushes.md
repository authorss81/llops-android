# Multi-Color Brushes, Sharp Edges & True Eyedropper — Phase 27

Phase 27 ships three real, reachable, unit-tested canvas improvements:

1. **Rainbow / Gradient / Shimmer brush modes** — per-point color derived at
   render time from the stroke's stored `colorMode` + `colorSeed` (+ optional
   gradient end color), never stored per point.
2. **Sharp-edge fix** — every brush guarantees a real ≥1.5px anti-aliased
   penumbra (previously hard brushes could get a zero-width feather band), and
   CALLIGRAPHIC/CHISEL_MARKER joins are drawn as a clean ribbon with no notch.
3. **True eyedropper** — color picking now samples the actual composited page
   pixel under the tap (with stroke-alpha compensation) instead of a guessed
   average.

No new dependencies, no new permissions, no DB schema change.

## What changed

### Render-time multi-color modes (vector path + AGSL both supported)

- `StrokeColorMode` enum (`StrokeModels.kt`): `SOLID`, `RAINBOW`, `GRADIENT`,
  `SHIMMER`, with `persistenceKey` + `fromKey` (unknown → SOLID).
- `Stroke` gains `colorMode`, `colorSeed`, `gradientToColorInt` — all defaulted,
  so every pre-phase-27 stroke and every non-opt-in call site stays bit-identical.
- `BrushColorModeMath` (new, pure-JVM, test-covered):
  - `hsvToArgb`/`argbToHsv` — in-gamut HSV round-trip (rounded channels).
  - `rainbowColorAt` — full wheel sweep, s=1, value lifted to ≥0.5, seamless
    progress 0↔1, per-seed start-hue rotation.
  - `gradientColorAt` — linear blend base→gradientEnd, preserves base alpha.
  - `complementaryArgb` — 180°-away hue fallback when no gradient end stored.
  - `shimmerColorAt` — deterministic white sheen sweep over the base value.
  - `colorForProgress` / `strokeProgress` — dispatch + arc-length progress.
- Rendering (`AnnotationCanvas.drawSingleStroke`):
  - PEN/HIGHLIGHTER/MARKER/DOTTED/FINELINER: multi-color renders dense
    round-capped per-segment line loops (per-point sweep).
  - LINE: `Brush.linearGradient` from start color to end color.
  - CALLIGRAPHIC/CHISEL_MARKER: new `drawRibbonStroke` (quad overlap extension
    + interior vertex cap circles + round end caps) with per-point color.
  - NEON: glow layers share the derived `neonColor`.
  - Rect shapes: `derivedColorAt(0.5f)` fill color.
  - Textured tools (PENCIL/AIRBRUSH/OIL_PAINT/WATERCOLOR/SPLATTER/CHARCOAL/
    OIL_PASTEL/INK_WASH/GOUACHE/DRY_BRUSH/PALETTE_KNIFE): sample the mid-stroke
    color `derivedColorAt(0.5f)` — **honest limitation**: the single-color
    `BitmapShader` cannot carry a per-point hue sweep, so textured tools get one
    color per stroke; per-segment sweeps only apply to line-based tools.
- AGSL wet-preview (`AgslShaders` + preview color threading): the live
  wet-mixing preview now uses the derived per-point color when the current mode
  is multi-color (last-point progress), so the preview matches the committed
  stroke.

### Sharp-edge / join fix

- `edgeFeather` (Kotlin) and the AGSL wet-mixing falloff were both using
  `max(hardness, 1 - bandWidth/r)`, which leaves `bandStart == 1` (zero band)
  for hard brushes near radius 1.5. Both now use
  `min(hardness, 1f - bandWidth/r)` — forcing a real ≥1.5px penumbra for hard
  brushes while preserving the soft-brush wide band exactly. The two MUST stay
  in sync; the shader comment documents the mirror.
- `RibbonJoinMath` (new, pure-JVM): `extendBeyond` grows each quad past its
  vertices; `turnCos`/`quadContains`/vertex cap coverage guarantee that the
  overlap fully covers the join notch for turns up to 178° (test-swept).

### True eyedropper

- `EyedropperSamplingMath` (new, pure-JVM): inverse `screenToCanvas` mapping,
  `canvasToPagePixel` page-bitmap lookup, tight hit test
  (`distanceToPolyline <= width/2 + 6f`), nearest-vertex progress via
  `strokeProgress`, and source-over `composite` with `approximateStrokeAlpha`
  tool multipliers (HIGHLIGHTER 0.35, MARKER 0.42, PENCIL 0.82, AIRBRUSH 0.35,
  SPLATTER/SMUDGE 0.65).
- `AnnotationCanvas.sampleColorAt` rewritten to use the page-bitmap pixel
  (parsed paper color fallback) and the derived stroke color at the nearest
  vertex.
- Sampling a stroke resets the color mode to SOLID with a snackbar informing the
  user — the eyedropper returns a real composited color, so the multi-color
  mode must be re-engaged via the color-picker chips.

### Editor wiring

- `EditorScreen`: `currentColorMode` / `currentColorSeed` /
  `currentGradientToColor` state; `onDrawingStart` re-seeds RAINBOW/SHIMMER per
  stroke; `onColorSampled` resets to SOLID.
- `ColorPickerBottomSheet`: mode chips (SOLID/RAINBOW/GRADIENT/SHIMMER with
  colored leading swatches) + a GRADIENT-only "Gradient end" row
  (currentColor + 10 curated palette colors).
- Preview strokes (single-page, continuous, per-page) and the commit site all
  capture the current mode/seed/gradient into the `Stroke`, so what you see in
  the preview is what persists.

## Persistence

- Fields travel inside the existing `pointsJson` payload (Gson
  serialize/deserialize). No Room schema change.
- Old strokes without the fields deserialize with null mode → normalized to
  SOLID / seed 0 / null gradient via `StrokeColorMode.fromKey` in
  `NoteRepository.getStrokesForPage` (bit-identical to pre-phase-27).
- `strokeContentHash` covers the new fields so a mode change dirties the row.

## Tests

617 unit tests pass, 0 failures (`gradle testDebugUnitTest`). New:
- `BrushColorModeMathTest` — HSV round-trip, hue normalization, argb helpers,
  rainbow saturation/determinism/seed rotation/seamless wrap, gradient
  endpoints/mid-blend/alpha/gamut, complementary hue 180°, shimmer
  determinism/gamut/sheen bands, `colorForProgress` dispatch, arc-length
  progress, `edgeFeather` hard-brush penumbra ≥ MIN_FEATHER_PX and soft-brush
  band preserved.
- `EyedropperSamplingMathTest` — screen→canvas inverse (incl. divide-by-zoom and
  zero-zoom fallback), canvas→page pixel, distance-to-polyline closest-segment,
  nearestIndex, source-over composite (opaque/transparent/50% blend), stroke
  alpha multipliers.
- `RibbonJoinMathTest` — half-width/cap radius/notch leg, extendBeyond,
  segment normal, turnCos straight/hairpin/135°, vertex-cap coverage sweep over
  2–178°, quadContains.
- `StrokePersistenceRoundTripTest` — mode/seed/gradient round-trip through
  `serializeStrokes`/`deserializeStrokes`, null gradient preserved, legacy JSON
  without the new fields normalizes to SOLID/0/null, fromKey fallback.

`gradle assembleDebug` succeeds.

## Deferred / honest notes

- Textured tools render one derived color per stroke (mid-stroke sample) — a
  per-point sweep needs a custom shader per tool and is out of scope.
- The multi-color stroke is committed with its mode+seed; the exact hue at each
  point is deterministic from seed+progress, so re-rendering is stable, but a
  specific recorded hue is not recoverable as a solid color (that's why
  sampling resets to SOLID).
- Eyedropper alpha is approximated per tool (multiplier table), not per-stroke
  paint history; close enough for picking but not pixel-exact alpha.
