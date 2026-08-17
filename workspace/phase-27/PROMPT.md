# Phase 27: Brush rendering — fix sharp edges, add rainbow & unique colors, fix color picking [DONE]
You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an AGSL GPU wet-mixing engine + vector fallback, and brush styles added
in Phase 18 (charcoal, oil pastel, ink wash, gouache, dry brush, palette knife),
plus a vibrant color system and palette from Phase 19.

This phase fixes a visual defect and expands color expressiveness.

## 1. Fix sharp/aliased stroke edges
- Some brush strokes render with **sharp, jagged edges** (visible at certain
  sizes/zooms, especially in the vector fallback and at hard `uHardness`).
- Find the exact cause (stroke tessellation, missing anti-aliasing on the
  polyline joins/caps, shader step edges, `strokeContainsPoint`/dirty-rect
  margins) and fix it so ALL brush types render smooth edges at all widths and
  zoom levels.
- Verify: line joins (`ROUND`), caps, alpha feathering at falloff edges, and the
  vector-fallback antialiasing path (API < 33). Pure-JVM tests for any geometry
  math you add (e.g. an edge-feather/falloff function; join/cap raster tests).
- The fix must not regress performance (respect `WetBrushEngine.currentQuality`
  degradation).

## 2. Rainbow & unique multi-color brushes
- Add **rainbow brush** (and similar unique color effects): the stroke color
  cycles through hues along the stroke path (per-point hue from position/elapsed,
  seamless, vibrant). Implement as a real brush style (extend `StrokeTool` or a
  color-mode flag on existing tools) working on BOTH AGSL and vector fallback.
- Also add at least one more unique effect color mode, e.g. **gradient** (two
  picked colors blended along the stroke) and **shimmer/iridescent** (hue varies
  subtly per stroke-seed with metallic sheen). Each must be distinct, reachable
  in the color picker, and unit-testable (pure-JVM hue-cycling/gradient math:
  given progress [0..1] and seed → valid in-gamut color).
- Ensure these color modes persist correctly (the per-point color is derived at
  render time from the stroke's seed/mode — do NOT store a color per point;
  store the mode + seed so persistence is unchanged and round-trips).

## 3. Fix color picking (eyedropper + palette) to work properly
- Audit the eyedropper (`sampleColorAt` + `onColorSampled`) and
  `ColorPickerBottomSheet`: sampled color must exactly match the rendered pixel
  (account for vibrancy boost and paper color), must appear in recent/palette,
  and must be applied to the current brush. Fix sampling offsets under pan/zoom
  (screen→canvas coordinate mapping), alpha handling, and dark-paper cases.
- The HSV sliders must round-trip (drag → color → reopen shows the same value)
  and never produce out-of-gamut colors.
- Rainbow/gradient modes must appear as selectable options in the color picker,
  not hidden.

## Definition of done
- `gradle assembleDebug` succeeds; `gradle testDebugUnitTest` passes with new
  tests: edge-feather/falloff math, hue-cycling + gradient + shimmer math
  (in-gamut, deterministic per seed), eyedropper coordinate mapping, HSV
  round-trip.
- Sharp edges eliminated on all brush types (documented before/after).
- Rainbow/gradient/shimmer render on AGSL AND vector fallback, persist, and are
  selectable in the picker.
- Eyedropper picks the exact rendered color (verified under zoom/pan) and applies
  it correctly.
- No performance regression at default quality.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema (color modes persist as mode+seed on the stroke —
  extend the stroke model safely if needed, with a migration-safe note).
- Do NOT edit `.github/workflows/`.
- Respect API 26+ / low-end: derived color math is cheap; vector fallback must
  show the same color modes as AGSL.
- Be honest: if a mode cannot render correctly on both paths this phase, ship it
  on the path that works and say exactly what is deferred.
