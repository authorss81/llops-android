# Phase 201 — Pressure Gamma + Per-Brush RDP Epsilon + Hardware Bitmap Verify [PERF 1.3+1.4+2.7]

**Date:** 2026-08-24 · **Status:** DONE (code + tests + `gradle :app:assembleDebug` green;
full `gradle :app:testDebugUnitTest` **2700 tests / 4 failures**, ALL reproduced
identically on a clean stash — see §7). **Headline find (§4):** step 3's "verify"
uncovered a real dead GPU path — the wet-mixing RenderEffect was attached through a
reflective lookup on `android.graphics.Paint`, which has **no** such method at any API
level; the shader had never executed on any device. The effect now rides a reusable
`RenderNode` (`RenderNode.setRenderEffect`, API 31+) composited via
`Canvas.drawRenderNode`.

## 1. What shipped

| # | PROMPT step | Files |
|---|---|---|
| 1 | Pressure gamma curve `p^(1.5–2.0)` easing the 0–10% band | `services/PressureCurveHelper.kt` (SMOOTH curve), `ui/screens/EditorScreen.kt` (chip row), capture call sites unchanged |
| 2 | Per-brush RDP epsilon: hairline 0.6–0.8 px, legacy 1.3 px elsewhere; pointer-up-only confirmed | `services/StrokeSimplifyPolicy.kt` (NEW), `ui/components/AnnotationCanvas.kt` commit path |
| 3 | GPU-compositing tier verify + **RenderNode carrier fix** | `ui/components/ShaderCapabilityHelper.kt` (pure tier fns), `AnnotationCanvas.drawWetLayerPass` (rewritten), `AgslShaders.WetMixingEffect` (+reusable node) |
| 4 | Golden extensions + new pipeline test | `StrokeStabilizerTest`, `B2Dos01StrokeGeometryTest`, `StrokePersistenceRoundTripTest`, `PressureCurveHelperTest`, `Phase201StrokeInputPipelineTest` (NEW) |

## 2. PERF 1.3 — SMOOTH pressure gamma

Low-pressure width jitter: digitizer noise inside the 0–10% band is proportionally
large vs the signal, and a LINEAR remap turns that noise into visible width flicker.
A gamma ≥ 1 kills it — `d(p^g)/dp = g·p^(g−1) → 0` as p → 0 — but a single flat
exponent either over-flattens mid-range (2.0) or under-treats the bottom (1.5).

- New `PressureCurve.SMOOTH("Smooth", "smooth", "Steadier width at light press")`.
- `remapPressure(SMOOTH)` = **`p ^ (2.0 − 0.5·p)`**: exponent blends
  `SMOOTH_GAMMA_AT_ZERO = 2.0` → `SMOOTH_GAMMA_AT_ONE = 1.5`. Endpoints preserved
  (0→0, 1→1); strictly monotonic on (0,1] (`d/dp[(2−0.5p)ln p] = −0.5 ln p +
  (2−0.5p)/p > 0` everywhere on the range); sits strictly between LINEAR and HEAVY.
- Goldens (`PressureCurveHelperTest`): exact `0.05^1.975` / `0.5^1.75` values,
  LIGHT > LINEAR > SMOOTH > HEAVY ordering sweep, and the jitter fix itself —
  total width-factor travel across the 0–10% band shrinks to <25% of LINEAR's
  (measured ≈11%: 0.056 vs 0.50 width-units per full-band sweep), absolute band
  span pinned < 0.01.
- Capture-time semantics UNCHANGED (documented contract): the remap is applied ONCE
  at capture and persisted with the stroke; existing strokes keep their feel.
- UI: the pressure-curve chip row now has FOUR entries → converted to the phase-166
  compact-screen pattern (`.horizontalScroll(rememberScrollState())`, content-sized
  chips) so nothing clips at 360dp. Source-pinned.

## 3. PERF 1.4 — per-brush RDP epsilon, pointer-up only

The commit path simplified EVERY stroke at one hardcoded `epsilon = 1.3f`
(`AnnotationCanvas.kt:1408` pre-phase), larger than half a 1–2 px nib — fine-tip
curves visibly lost their gentle inflections at commit time.

- New pure-JVM `services/StrokeSimplifyPolicy.kt`:
  - hairline = fine-tip tool {PEN, FOUNTAIN_PEN, PENCIL, FINELINER} AND width ≤
    `HAIRLINE_MAX_WIDTH_PX` (3 px);
  - epsilon for hairline strokes interpolates linearly **0.6 px @ 1 px width →
    0.8 px @ 3 px width** (finest nib keeps the most detail);
  - everything else (incl. wide fine-tip strokes) keeps `DEFAULT_EPSILON_PX = 1.3`;
  - degenerate widths fail safe INSIDE the hairline band.
- Wiring: the single commit-path call site reads
  `RamerDouglasPeucker.simplify(pointsToSimplify,
  epsilon = StrokeSimplifyPolicy.epsilonFor(tool, width))`; no hardcoded epsilon
  remains (asserted absent by source pin).
- **Pointer-up-only CONFIRMED and pinned**: `RamerDouglasPeucker.simplify(`
  appears EXACTLY ONCE in `AnnotationCanvas.kt`, inside the first `onDragEnd =
  {` block (the ink commit handler), and NOT anywhere before it — the live preview
  renders raw stabilized samples, so ink never snaps under the pen mid-stroke.
  The shape-snap path is untouched (runs before simplify, as before).
- RDP itself untouched (B2-DOS-09 iterative heap-stack version stays byte-identical;
  `B2Dos09RdpRecursionTest` green).

## 4. PERF 2.7 — hardware path verify → REAL defect found and fixed

### 4.1 What the verify found

Step 3 asked to verify the AGSL `RenderEffect` is "GPU-composited via RenderNode".
Bytecode-level inspection of the compile-SDK android.jar (API 36):

```
$ javap -classpath ajx android.graphics.Paint | grep -c setRenderEffect
0
$ javap -classpath ajx android.graphics.RenderNode | grep setRenderEffect
public boolean setRenderEffect(android.graphics.RenderEffect);
```

**`android.graphics.Paint` has NO `setRenderEffect` at ANY API level** (zero
RenderEffect references in `Paint.class`; RuntimeShader exists in the same jar). The
pre-201 code did:

```kotlin
val method = Paint::class.java.getMethod("setRenderEffect", RenderEffect::class.java)
method.invoke(p, wetMixingEffect.androidEffect)
} catch (_: Exception) {}
```

That lookup threw `NoSuchMethodException` **every frame**, the catch swallowed it,
and `effectPaint` was silently just a copy of the plain paint ⇒ **the wet-mixing
shader never rendered through the GPU path on any device** (the visible output came
from the plain-paint pass; the dirty-rect saveLayer ran without any effect attached).
This is exactly the class of false feature this project hunts — found by the verify
step, fixed in the same phase.

### 4.2 The fix (RenderNode carrier)

- `AgslShaders.WetMixingEffect` owns ONE reusable
  `android.graphics.RenderNode("inkflow-wet-mix")` (never re-allocated per frame).
- `drawWetLayerPass` (now `@RequiresApi(TIRAMISU)`, gated by
  `ShaderCapabilityHelper.isAgslSupported`) effect pass:
  1. `node.setPosition(left, top, right, bottom)` = the dirty rect (ceil'd);
  2. record THIS layer's strokes into the node via
     `node.beginRecording()` + compose-canvas translate(−left, −top) + the existing
     `CanvasDrawScope.draw(density, layoutDirection, canvas, size)` redirect (same
     pattern as the layer-bitmap cache path) → identical stroke rendering, node-local
     coordinates;
  3. `node.endRecording()` then **`node.setRenderEffect(wetMixingEffect.androidEffect)`**
     — the real API-31+ carrier;
  4. pass 1 (unchanged): full-page plain `saveLayer` with the dirty region punched
     out (`clipOutRect`) so pixels are not double-blended;
  5. pass 2: `saveLayer(dirty, plainPaint)` (still carries layer opacity/blend to the
     final composite exactly as before) containing **`nativeCanvas.drawRenderNode(node)`**
     — the hardware canvas composites the shader-applied display list on the GPU.
- Honest fallbacks (AGENTS.md never-silent-degradation):
  - `nativeCanvas.isHardwareAccelerated == false` (software canvas, screenshots,
    layoutlib) or missing draw-scope params ⇒ pre-existing plain full-page
    `saveLayer` path;
  - API 26–32 never enter this function (`useAgslWetMixing` gate) and keep the
    WetBrushEngine vector fallback.
- Tier table moved into testable pure functions:
  `ShaderCapabilityHelper.agslSupportedFor(sdkInt)` (=33+),
  `renderEffectCompositingFor(sdkInt)` (=31+, RenderNode compositing exists but no
  AGSL below 33 → vector wet fallback there). Both canvas gates
  (`useAgslWetMixing` + the `drawWetLayerPass` guard) now read
  `ShaderCapabilityHelper.isAgslSupported` instead of raw SDK comparisons; the
  per-frame reflection is gone. Pins: `B2Dos01StrokeGeometryTest.wet-layer AGSL
  gates…`, `Phase201StrokeInputPipelineTest.WetMixingEffect owns…`.

### 4.3 `dumpsys gfxinfo` verification procedure (needs a device — deferred)

CI has no emulator/device (same constraint as phase-199 metrics), so runtime
verification is documented for the next on-device session instead of claimed:

```bash
adb shell dumpsys gfxinfo com.aistudio.inkflow.app.bkxjrz framestats   # while drawing with a wet brush
adb shell dumpsys gfxinfo com.aistudio.inkflow.app.bkxjrz reset        # between tools
# Expect: janky frames % drops when drawing WATERCOLOR/OIL_PAINT vs pre-201 build;
# HWUI logs "RenderNode" composition; systrace shows AGSL kernel driver activity.
```

Static verification shipped instead (all pinned by tests): effect attaches via
`RenderNode.setRenderEffect` (the only public carrier besides View), composites via
`Canvas.drawRenderNode` on an `isHardwareAccelerated` canvas, gated 33+.

## 5. Tests (step 4)

| Test file | Δ | Coverage |
|---|---|---|
| `PressureCurveHelperTest` | +4 | SMOOTH key round-trip; exact gamma goldens; LINEAR<SMOOTH<HEAVY sweep; low-band width-travel shrink (<25% of linear) + <0.01 span |
| `StrokeStabilizerTest` | +2 | golden: stabilized hairline wiggle keeps MORE points at 0.65 px than at 1.3 px (coarse collapses <¼); RDP contract over EVERY tool's epsilon: endpoints exact, size can only shrink |
| `B2Dos01StrokeGeometryTest` | +2 | simplify appears exactly once, ONLY inside onDragEnd, never before it; epsilon comes from the policy (hardcoded 1.3f absent); wet-layer gates read ShaderCapabilityHelper, RenderNode carrier present, reflection absent, RequiresApi annotation present |
| `StrokePersistenceRoundTripTest` | +2 | hairline-simplified stroke round-trips every kept point + per-point pressure byte-faithfully; MARKER@12px legacy path round-trips unchanged |
| `Phase201StrokeInputPipelineTest` (NEW) | 10 | epsilon table (legacy/hairline/endpoints/monotone/degenerate/wide-not-hairline); GPU tiers (26–32/33+; 26–30/31+); wiring pins (capture-time doc, SettingsManager key, single simplify site, scrollable chip row, reusable node) |

## 6. Compatibility

No schema change, no new dependencies, `.github/workflows/` untouched, base-APK-size
rule intact. API tiers: 33+ gets the working AGSL GPU path (NEWLY functional — see
§4), 31–32 vector fallback on accelerated canvas, 26–30 software/vector paths — all
pre-existing behavior preserved; nothing degrades silently.

## 7. Verification

- `gradle :app:testDebugUnitTest` (full): **2700 tests / 4 failures**, all four
  reproduced IDENTICALLY on a clean `git stash` of this phase's changes:
  - `Phase148UiFailureTextScrubTest` ×1 — documented pre-existing UNC-path failure;
  - `Phase151MarkdownMainThreadPerfTest` ×1 — documented timing flake
    ("13.07ms must beat 12.43ms"); passes in isolation (18/18 re-run green);
  - `PaparazziSmokeTest` ×2 — layoutlib environment failure on this runner
    (`sessionParamsBuilder` uninitialized); renders only a trivial Text box,
    touches none of this phase's code.
- `gradle :app:assembleDebug`: green (only pre-existing deprecation warnings).
- Targeted phase-201 set (6 classes incl. `B2Dos09RdpRecursionTest` RDP parity):
  green.

## 8. Review fixes (2026-08-24)

The post-commit review confirmed §4's Paint finding but found the NEW carrier
still could not produce visible wet mixing, plus two lower-severity issues:

1. **HIGH — uniform/coordinate-space mismatch (wet mixing silently invisible).**
   A RenderNode-carried RenderEffect evaluates the AGSL shader in NODE-LOCAL
   coordinates (the space `beginRecording` + `translate(-origin)` create), but
   `WetMixingEffect.update()` was fed raw page/canvas coords. For every pixel,
   `distToSegment(coord, uPrevPos, uBrushPos) > uBrushRadius`, so the shader
   early-outed via `contents.eval(coord)` and output degraded to plain strokes.
   Fix: the positional uniforms are now rebased onto the node origin — one
   shared `(nodeOriginX, nodeOriginY)` pair (`candidate.left/top.toInt()`) is
   used by BOTH the uniform rebase (`prevX - nodeOriginX`,
   `brushY - nodeOriginY`, …) and the recording translate / `setPosition`.
2. **MEDIUM — dirty rect mixed coordinate spaces (GPU path dead on pages ≥ 2).**
   The segment rect was built from PAGE-LOCAL brush coords then intersected with
   canvas-space `pageBounds = RectF(0, offsetY, …)`; on every page past the
   first the intersection degenerated → `dirty = null` → silent plain-paint
   fallback. Fix: segment coords are converted to CANVAS space (`+ offsetY` on
   Y) before building/intersecting the rect.
3. **LOW — shared-carrier exclusivity.** `Canvas.drawRenderNode` stores a live
   reference to the single reusable RenderNode; a second wet pass in one frame
   would retroactively rewrite an earlier composite. An explicit
   `gpuWetCarrierClaimed` guard now reserves the carrier for exactly one pass
   per frame (today only the preview-host layer can reach it — belt-and-braces).
4. **LOW — dead tier API removed.** `ShaderCapabilityHelper.renderEffectCompositingSupported/renderEffectCompositingFor`
   had zero production consumers; deleted (31-32 fall back because AGSL itself
   requires 33). Anti-resurrection source pin added.

New pins: `Phase201StrokeInputPipelineTest.wet GPU pass keeps dirty rect…` and
`.no unconsumed tier functions…`. Verification: targeted phase-201 classes +
`gradle :app:assembleDebug` green after the fixes; full-suite numbers unchanged
(§7 set is pre-existing/environmental). On-device `dumpsys gfxinfo` confirmation
of the NOW-coordinate-correct shader remains owed to the next device session.
