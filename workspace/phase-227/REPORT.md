# Phase 227 — Paper deckled edge + tunable texture strength + layered PSD blend fidelity + transparent/deckled PNG/PSD exports

**Date**: 2026-08-27
**Status**: DONE

## Summary

Shipped four related surface-finish / export-fidelity features with zero schema,
migration, or base-APK dependency changes. All deckle geometry and texture
strength live in pure-JVM policies shared verbatim between the Compose canvas and
the exporters so the editor, transparent PNG, and every PSD layer clip to identical
geometry.

## What changed

### 1. Deckled paper edge (`services/PaperEdgePolicy.kt`, new — pure JVM)
- `enum PaperEdge { RECT, ROUNDED, DECKLED }` with `fromKey`/`persistenceKey`/`sanitizeKey`.
- Default key `"rounded"` = 8dp-radius round-rect = the pre-227 card (byte-identical behavior).
- `RECT` = radius 0; `DECKLED` = wavy hand-cut paper edge.
- Deckle is pure vector (no bitmap): `deckleNodes(x, y, w, h, ampPx, seed)` generates a
  bounded 4-sided node polygon with per-edge ripple (edge-node proportions >= 4/edge),
  corner fade, deterministic per-family seed, 16..100 nodes for a 1080dp page.
- `smoothedDeckleMidpoints(nodes)` returns a midpoint polyline that both the Compose canvas
  (`deckledSheetPath`) and the exporters (`DeckleExportHelper.sheetPath`) consume, so
  editor ↔ export pixels align.
- `wavyOffsetAt` bounded within +/-peak (3px), deterministic, family-dependent (dark vs light paper).

### 2. Tunable texture strength (`services/PaperTextureStrengthPolicy.kt`, new — pure JVM)
- MIN 0 / MAX 100 / DEFAULT 50.
- `grainDrawAlpha = 0.02 + strength/100 * 0.05` (0.02..0.07).
- `grainScale = grainDrawAlpha / grainDrawAlpha(50)`  → 1.0 at default, 0.444 at 0, 1.556 at 100.
- `shaderStrength = fraction`; `shaderGain = fraction/fraction(50)` → 1.0 at 50, 0 at 0, 2.0 at 100.
- Default 50 anchors grainScale == shaderGain == 1.0 → pre-227 grain is byte-identical for untouched installs.

### 3. Canvas wiring (`AnnotationCanvas.kt`)
- New params `paperEdgeKey` (default `PaperEdgePolicy.DEFAULT_KEY`) and `paperTextureStrength`
  (default `PaperTextureStrengthPolicy.DEFAULT`); `remember` blocks derive `paperEdge` +
  `grainScale`.
- `drawPaperCard` rewritten with a DECKLED branch (clipPath `deckledSheetPath` +
  `drawDeckleSheetShadow` blurred stroke under the fill) vs ROUNDED (radius 8) vs RECT (0).
- New `drawPaperGrain(grainBrush, x, y, w, h, radius, grainScale)` helper — one tiled pass at
  alpha `scale.coerceIn(0f,1f)` plus an additive boost pass when scale > 1.
- `paperTextureStrength` threaded: `drawCompositedLayersStrokes` → `drawWetLayerPass` →
  `wetMixingEffect.update`, where `paperGrain = (brushParams.paperGrain * shaderGain).coerceIn(0f,1f)`.

### 4. Settings (`SettingsManager.kt`)
- `paperEdgeKey` (sanitized, default `"rounded"` = pre-227 card).
- `paperTextureStrength` (clamped, default 50).

### 5. PSD blend fidelity (`PsdExportPolicy.kt`, `PsdExportService.kt`)
- `PsdExportPolicy.psdBlendSignature(blendMode)` maps every `LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES`
  to a 4-char PSD blend key (MULTIPLY→"mul ", SCREEN→"scrn", OVERLAY→"over", DARKEN→"dark",
  LIGHTEN→"lite", COLOR_DODGE→"ldiv", COLOR_BURN→"idiv", HARD_LIGHT→"hLit", SOFT_LIGHT→"sLit",
  DIFFERENCE→"diff", EXCLUSION→"smud", else→"norm").
- `PsdLayer` gained `blendSignature: String = "norm"`; `layerRecordBytes` writes the signature at
  PSD record offsets 46-49 (after the "8BIM" at 42-45).
- Flattened composite honors per-layer opacity + blend via new `blendPaint(layer)` (API 29+
  `BlendMode` table, PorterDuff fallback <29; NORMAL / full-opacity = SRC_OVER, byte-identical to pre-227).

### 6. Exports (`ImportExportService.kt`, `DeckleExportHelper.kt` new)
- `exportAnnotatedPage` gained `transparentBackground: Boolean = false` — skips the white fill for
  PNG/WEBP (ignored for PDF / background-image pages, which are opaque anyway).
- Deckled clip applied (only when `transparentBackground && !exportAsPdf && deckledEnabled`) via a
  `canvas.save()/clipPath()/restore()` around the render using `DeckleExportHelper.sheetPath`.
- `exportPageToPsd` now passes real per-entity `opacity = (opacity*255).toInt().coerceIn(0,255)` and
  `blendSignature = PsdExportPolicy.psdBlendSignature(blendMode)`; deckle clips every PSD layer
  (Background, per-entity, merged-preview, flattened composite) when deckled.
- `DeckleExportHelper.seed` uses the light-paper family seed; exports pass `width/360f` as pxPerDp
  for consistent ~+-2-3dp tooth on a 1080px nominal page.

### 7. UI (`EditorScreen.kt`)
- `CanvasSettingsBottomSheet` gained a "Paper Edge" FilterChip row (Square / Rounded / Deckled) and a
  "Paper Texture" 0-100 slider with descriptive text + `%` label (Grain icon), persisted via settings.
- New overflow menu item "Export Page as Transparent PNG" (after "Export Page as PNG").

## Tests
- `PaperEdgePolicyTest` (pure JVM): keys/enum sanitize, wave bounded +/-peak + deterministic +
  family-dependent, non-finite → 0, amplitude density scaling, `deckleNodes` bounded/closed/
  corners-sharp/deterministic/16..100 nodes, corner-fade vs mid-edge ripple, midpoints polygon.
- `PaperTextureStrengthPolicyTest` (pure JVM): clamp, fraction, prompt lerp, grainScale unity @50 /
  envelope, shaderStrength, shaderGain 0..2.
- `Phase227PsdEdgeBlendTest` (pure JVM): every renderer mode → unique 4-char key, corrupt → "norm",
  case-insensitive, record layout offsets 46-49 + length invariance, source pins for
  canvas/settings/imports/editor wiring, PSD opacity + blend + deckle fusing.
- `Phase200CanvasRenderParityTest` grain pin updated for the new `drawPaperGrain` helper.

## Verification
- `gradle :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` ✅
- `gradle :app:assembleDebug` ✅ green (44s, 57 tasks).
- `gradle :app:testDebugUnitTest` ✅ 3420 tests, 14-15 pre-existing-or-environment failures
  (10-11 Paparazzi sandbox, 1 Phase148 UNC, 2 B2Ui2Clipboard) — 0 new.
- Phase-227 filtered run (PaperEdgePolicyTest, PaperTextureStrengthPolicyTest,
  Phase227PsdEdgeBlendTest, B2Dos06PsdExportLayerCapTest, PaperGrainPolicyTest) ✅ BUILD SUCCESSFUL.

## Visual DoD
`visual-qa/screenshots/phase-227/deckled-edge-texture.png` (1200x760, 40 KB) produced by
`workspace/phase-227/RenderDeckledEdge.java` — a Java2D driver that compiles and runs the REAL
`PaperEdgePolicy` / `PaperTextureStrengthPolicy` classes (via `kotlin.Pair.getFirst/getSecond`) against
`app/build/tmp/kotlin-classes/debug` + kotlin-stdlib jars, drawing a deckled card at strength 50/0/100
to show the ripple + texture envelope. (Paparazzi snapshots are broken on this runner — same
`UninitializedPropertyAccessException` layoutlib cause as prior phases.)

## Constraints honored
- No schema, no migration, no new base-APK deps; base-APK-size rule intact.
- Default settings (rounded edge + strength 50) reproduce pre-227 pixels byte-for-byte.
- Deckled/photographic export never silently degrades; low-end behavior unchanged (grain already
  gated by `ShaderCapabilityHelper` / LOW_END).
- Pure-JVM testable policies; no bitmap edge raster.
