# Phase 200 — Linear Color Mixing + Paper Texture + Eraser AA Parity [PERF 3.2+3.3+3.5]

**Date:** 2026-08-24 · **Status:** DONE (code + tests + `gradle assembleDebug` green;
full `gradle testDebugUnitTest` 2674 tests / 4 failures, ALL reproduced on a clean
stash — see §7)

## 1. What shipped

Three render-quality fixes, each behind its pure-JVM policy so the math is unit-tested
before it reaches the GPU:

| # | PROMPT step | Files |
|---|---|---|
| 1 | Wet-mix math in `ColorSpaces.LinearSrgb` via a `ColorSpace` param | `services/WetMixingMath.kt` (rewritten), `ui/components/AgslShaders.kt` (shader mirror) |
| 2 | Tileable paper-grain noise `BitmapShader` under ink, cached statically, near-zero cost | `services/PaperGrainPolicy.kt` (NEW), `ui/components/PaperGrainTileCache.kt` (NEW), `ui/components/AnnotationCanvas.kt` (wiring) |
| 3 | Eraser AA parity with the ink falloff | `services/EraserGeometryPolicy.kt` (extended), `AnnotationCanvas.kt` `LiveStrokePreview` |

### 1.1 Linear-light wet mixing (PERF 3.2)

The wet-mix reference mixed raw **gamma-encoded** sRGB channels. Gamma encoding
compresses highlights, so any product/average taken on encoded values
under-represents light: overlapping bright complementary washes collapsed into dark
desaturated browns ("mud"). The absorbance product (Beer–Lambert,
`1-(1-base)(1-brush)`) is only physically correct in linear light.

- `WetMixingMath.pigmentMixRgb(...)` gains a **`mixSpace: ColorSpace =
  ColorSpaces.LinearSrgb`** parameter (the PROMPT's "via `ColorSpace` param"):
  each sRGB-encoded channel passes the standard piecewise EOTF
  (`srgbToLinear`, knee 0.04045 / ×12.92 / ^2.4) before the per-channel
  `pigmentMix`, and the result is re-encoded with the exact inverse
  (`linearToSrgb`) because the canvas surface stays sRGB-encoded.
- **`ColorSpaces.Srgb` reproduces the pre-200 gamma behaviour bit-for-bit** — kept as
  the parity escape hatch and pinned by test. Any other space (e.g. `CieLab`) fails
  safe to LINEAR, because the AGSL mirror can only express the two supported
  encodings (documented in the KDoc).
- `sourceOverAlpha` is **untouched** — alpha is not gamma-encoded; changing it would
  be a blending regression (pinned by a source-slice test).
- **Shader mirror** (`WET_MIXING_SHADER`): `srgbToLinear3`/`linearToSrgb3` GLSL
  helpers (same piecewise constants) wrap ONLY the pigment branch:
  `linBase = srgbToLinear3(base.rgb)`, `linBrush = srgbToLinear3(vibBrushColor)`,
  product in linear, `mixedRgb = linearToSrgb3(mixedLin)`. The old gamma-space
  product line is gone (asserted absent by test). Impasto lighting, wet-edge
  darkening, vibrancy and the alpha accumulation keep their pre-200 sRGB-space
  behaviour — deliberately, to bound the visual diff to the mix itself.
- The scalar `pigmentMix(base, brush, factor)` keeps its signature; its KDoc now
  states it expects channels **already encoded in the working mix space**.

### 1.2 Paper grain under ink (PERF 3.3)

`drawPaperCard` painted a perfectly flat tint — plastic next to real cold-press
stock. Now a tileable noise tile is generated **once per process per paper family**
and drawn as **one REPEAT-tiled `BitmapShader` round-rect** over the fill, strictly
UNDER the template / background / reference-image / ink passes:

- `PaperGrainPolicy` (pure JVM): 192 px tile, deterministic integer-hash value noise
  folded through `floorMod(·, TILE_SIZE_PX)` so column 0 == column 192 and the REPEAT
  wrap seam is invisible **by construction** (pinned by test); alpha envelope =
  faint sheet tooth (`TOOTH_STRENGTH` 0.35) + quadratic fiber flecks in the top 8 %
  noise band (`FLECK_THRESHOLD` 0.92), capped at 0.05 (light paper, graphite
  `0xFF2B2B2B`) / 0.07 (dark paper, cool-white `0xFFE8EDF4`); NaN/∞ fail safe to 0.
- `PaperGrainTileCache` (static object): access-order `LinkedHashMap` LRU capped at
  `MAX_CACHED_TILES = 4` (worst case `maxResidentBytes()` ≈ 590 KB, asserted
  ≤ 4 MB); brushes (`BitmapShader(REPEAT, REPEAT)` wrapped in `ShaderBrush`) are
  cached alongside so the draw path allocates nothing. Eviction drops references
  WITHOUT `Bitmap.recycle()` — a recorded display list may still reference the
  pixels (documented; source-pinned).
- **Low-end fallback (AGENTS.md hardware rule):** `DeviceTier.LOW_END` devices skip
  the grain entirely (`PaperGrainPolicy.enabled(false)` → brushFor returns null →
  zero extra draw, zero generation cost). Cosmetic-only overlay, so the degradation
  is a no-op on the draw path, not a lost feature.
- Wired at ALL THREE `drawPaperCard` call sites (single page / continuous / page-wise)
  via a `grainBrush: Brush? = null` parameter — default keeps every other caller and
  preview compiling unchanged.

### 1.3 Eraser aim-cursor AA parity (PERF 3.5)

The PARTIAL eraser's cut is geometric (strokes are re-split and re-rendered through
the same ink renderer, so the *cut* already has ink quality), but the pre-200 aim
cursor was a **hard-edged flat fill + crisp ring** — visually harsher than every
brush stroke. The cursor now renders its fill through the SAME guaranteed-AA rule
the ink falloff uses:

- `EraserGeometryPolicy.cursorFeatherBand(radiusPx)` = `min(MIN_FEATHER_PX, max(r,1)/2)`
  — exactly the `BrushColorModeMath`/AGSL band rule (≥1.5 px penumbra, capped at
  half the radius so tiny disks keep a real edge).
- `cursorFillAlphaAt(nd, radiusPx)` = **exactly** `BrushColorModeMath.edgeFeather(nd,
  hardness = 1f, radiusPx)` — pinned to 0 tolerance across nd ∈ [0,1] × 10 radii.
- `LiveStrokePreview` PARTIAL branch: the flat `drawCircle` is replaced by a
  `Brush.radialGradient` with `CURSOR_FEATHER_STOP_COUNT + 1 = 13` stops sampled from
  that curve (linear interpolation between stops approximates the hermite to sub-1 %
  alpha error); the crisp guide ring stays (`CURSOR_RING_ALPHA` 0.6, width 2 px) for
  aim precision. `CURSOR_FILL_ALPHA` 0.22 keeps the pre-200 look inside the band.
  The phase-198 structural pin expression
  `EraserGeometryPolicy.previewRadius(currentWidth, currentWidth)` is preserved.
- The STROKE-mode highlight (whole-stroke hit preview) is unchanged — it is a
  selection affordance, not a brush edge.

## 2. PROMPT-context corrections (honesty notes)

- The PROMPT context claimed `EraserGeometryPolicy.kt` uses `PorterDuff.CLEAR` — it
  never did (the eraser is geometry-based; the only `PorterDuff.Mode.CLEAR` sites in
  the canvas are the layer-cache `drawColor` resets, which are full-bitmap clears,
  not aliased edge draws). Step 3 was therefore implemented against the REAL aliased
  surface: the hard-edged aim cursor.
- The PROMPT context named `InkCardPaperPolicy.kt` (the phase-187 GALLERY card
  policy) as the flat-tint site; the canvas paper fill actually lives in
  `drawPaperCard`. The grain was wired there (the canvas), not into gallery cards —
  "premium paper feel" belongs to the drawing surface. Gallery cards are untouched.
- "Cached static `LayerBitmapLruCache`" — `LayerBitmapLruCache`'s eviction protector
  parses LAYER keys (`<page>_<layer>_…`) to keep the active page resident, which is
  meaningless for the two grain families. The tile cache is a standalone static LRU
  with the same access-order structure and an explicit byte budget instead
  (rationale in the class KDoc).

## 3. DoD — visual before/after

No emulator/device exists on this runner, so on-device screenshots are impossible
here (same constraint honestly recorded in phases 196–198). Equivalent ground truth:
`workspace/phase-200/before-after.png` (888×838) rendered by a JVM driver
(`workspace/phase-200/RenderBeforeAfter.java`) that calls the **real compiled
classes** (`WetMixingMath`, `PaperGrainPolicy`, `EraserGeometryPolicy` from
`app/build/tmp/kotlin-classes/debug`) — the exact functions the shader/draw path
mirrors:

- **Wet mix**: cyan wash + overlapping soft red deposit — BEFORE (gamma) is a muddy
  desaturated brown-red; AFTER (linear) is a luminous saturated red over clean teal.
- **Paper grain**: both tile families at 3.5× alpha gain for print visibility.
- **Eraser cursor**: hard rim vs feathered edge, with 1-D alpha-profile strips.

On-device verification recipe: open a note → watercolor brush → draw cyan, overlay
red at ~50 % (compare mud), toggle a dark paper colour in Canvas Settings (grain
visible on both families), select the PARTIAL eraser and observe the cursor rim.

## 4. Verification

| Check | Result |
|---|---|
| `gradle :app:testDebugUnitTest` (targeted: WetMixingMathTest, PaperGrainPolicyTest, Phase200EraserCursorAAParityTest, Phase200CanvasRenderParityTest) | 34 / 34 green |
| `gradle testDebugUnitTest` (full) | **2674 completed / 4 failed — all four reproduced on a clean stash** (§7) |
| `gradle assembleDebug` | **BUILD SUCCESSFUL** |
| Shader ↔ Kotlin mirror | source-pinned (`Phase200CanvasRenderParityTest`): both sides carry the piecewise EOTF; old gamma product absent from shader; `sourceOverAlpha` slice contains no "linear" |
| Blending regression | alpha accumulation untouched (test); `ColorSpaces.Srgb` path byte-equal to the pre-200 formula (test); impasto/wet-edge/vibrancy math unchanged |

## 5. Tests added/updated

- `WetMixingMathTest` — 3 legacy tests kept (all still valid under the new default;
  the complementary test's assertions hold in linear space: R = 0.9 exactly,
  G/B ≈ 0.5845) **+ 5 new**: piecewise-EOTF known values + full-range round trip +
  out-of-range fail-safe; default==LinearSrgb and ≠ Srgb on mid-tones; SRGB-space
  legacy bit-parity; endpoint identities in any space (factor 0 → base, black base +
  full deposit → brush); **chroma-preservation anti-mud property** (linear result
  keeps more channel spread than gamma for bright complements) + goldens; unknown
  space fails safe to linear.
- `PaperGrainPolicyTest` (9) — determinism, structural tileability at the wrap seam,
  full-range noise + family separation, alpha envelope incl. NaN/∞, fleck-vs-tooth
  dominance + monotonic curve, low-end gate, cache-key stability, byte budget,
  speckle tints per family.
- `Phase200EraserCursorAAParityTest` (7) — band ≥ MIN_FEATHER_PX for usable radii and
  ≤ half-radius for tiny disks (0 px disk → 0.5 px band, matching `edgeFeather`'s
  floored radius), EXACT equality with `edgeFeather(·, 1f, ·)`, opaque center /
  transparent rim, monotonic non-increasing falloff, band strictly inside (0,1) +
  stop count, pre-200 look constants.
- `Phase200CanvasRenderParityTest` (10 source pins) — shader linearization order +
  old-product absence; `ColorSpace` param default; `sourceOverAlpha` slice purity;
  `drawPaperCard` grain param + fill-before-grain order; exactly 3 call sites wired;
  low-end gate wiring; tile-cache LRU structure + REPEAT + no-`recycle()`; eraser
  branch uses the sampled feather gradient (phase-198 pin preserved, old flat fill
  absent); eraser policy constants + `edgeFeather` routing.

## 6. Performance accounting (no device on CI — static, like 196–198)

- Wet mix: +2 piecewise `pow`-based half3 conversions per covered pixel, ONLY inside
  the brush radius (early-out unchanged); AGSL `pow`/`mix`/`step` are single-cycle
  GPU primitives — no new textures, uniforms, or passes. Vector fallback (pre-33 /
  low-end / GPU off) shares the same change through `WetMixingMath` (non-hot path:
  reference/tested, not per-pixel).
- Paper grain: ONE extra `drawRoundRect(brush)` per page card per frame; tile
  generation is one `IntArray` fill + `setPixels` (~1 ms) ONCE per family per
  process; resident ≤ 2 tiles in practice (light+dark), hard-capped at 4
  (~590 KB). LOW_END: zero cost (gate).
- Eraser cursor: 13-entry stop array + one gradient brush allocated per cursor draw
  (only while the eraser hovers/drags, inside the phase-198 isolated node) — no
  composition-phase reads added, no invalidation-surface change.

## 7. Pre-existing failures (reproduced on a clean stash)

`git stash push` → same 3 test classes fail identically on untouched HEAD:
`Phase148UiFailureTextScrubTest` (documented UNC-path), `PaparazziSmokeTest`
rendersLightTheme/rendersDarkTheme (runner layoutlib env, documented since
phase-195/196), plus `WikiLinkParserCacheUnitTest` timing flake in the full run
(documented; passes in isolation). **Zero failures introduced by phase-200.**

## 8. Constraints honored

No schema change · no new dependencies (compose `colorspace` classes were already on
the classpath) · `.github/workflows/` untouched · base-APK-size rule intact · no
keys/passwords/content logged · low-end fallback per AGENTS.md hardware rule ·
changes left uncommitted for maintainer review per repo workflow.
