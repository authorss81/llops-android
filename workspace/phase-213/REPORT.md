# Phase 213 — Brush Shading & Drop Shadows (real-world paper elevation)

**Date:** 2026-08-26 · **Type:** feature (render-only) · **Risk:** low
**DoD:** `gradle assembleDebug` green · `gradle testDebugUnitTest` green (3 documented pre-existing failures, unchanged baseline) · `visual-qa/screenshots` before/after (light+dark paper) with 0→1 shadow progression · no schema change · no new deps · AGSL shaders bit-exact untouched.

## 1. What shipped

Per-stroke **soft drop shadows** ("Paper elevation") so ink lifts off the paper like a professional drawing app. Render-only and preset-driven: zero Room/schema churn, zero new dependencies (`BlurMaskFilter` only), zero change to any stored byte — old strokes render identically when the feature is off or the plan is null.

| # | Piece | File:line | Notes |
|---|-------|-----------|-------|
| 1 | Decision table | `services/BrushShadowPolicy.kt` (new, ~250 lines) | Pure JVM. `enabled(lowEnd)` (pinned tier table — production tier resolution lives in EditorScreen); `shouldApply(tool)` skips ERASER/LASER/TEXT/SMUDGE/STICKER/DOTTED/SELECT/PAN/EYEDROPPER; `shadowAlpha` 0.20 light / 0.12 dark; `offset(widthPx, pxPerDp)` = `(w·0.35, w·0.40)` clamped 1..6 dp (density fail-safe 1.0); `blurRadius(w)` = `w·0.6` clamped 2..12 px; HIGHLIGHTER alpha × 0.5; `plan(...)` → null = zero draw contribution (one-slot last-plan memo absorbs the live-preview per-frame allocation); `gpuCarrierPreferred(sdkInt, lowEnd)` tier table (see §4). |
| 2 | Pooled renderer | `ui/components/StrokeShadowRenderer.kt` (new, 234 lines) | ONE process-lifetime `Paint` + reusable `Path`; the single `BlurMaskFilter(…, Blur.NORMAL)` construction site re-fires only when the 0.5px-quantized radius changes (never per segment/draw). Shadow tint follows the PAPER: near-black on light stock, white lift on dark stock. Geometry mirrors the main branches exactly (midpoint-quadratic freehand smoothing; verbatim vertex math for LINE/RECTANGLE/ELLIPSE/TRIANGLE/STAR/PENTAGON/HEXAGON/ARROW incl. the 24 px / 30° arrow head). Thread contract: UI-thread canvas draws only. |
| 3 | Underlay wiring | `AnnotationCanvas.kt:4252-4276` (`drawSingleStroke`) | `BrushShadowPolicy.plan(tool, stroke.width, isDarkPaper, shadowEnabled, density)` → `StrokeShadowRenderer.drawStrokeShadow(...)` BEFORE both render paths (the androidx.ink advanced path included, which returns early below), so every tool lifts consistently. Param `shadowEnabled: Boolean = false` keeps all pre-existing call sites compiling unchanged. |
| 4 | Gate + threading | `AnnotationCanvas.kt:811`, `:2077/:2128/:2272` (3 draw passes), LiveStrokePreview sig + both preview calls, `drawWetLayerPass` sig + `drawStrokes()` + idle fallback | Composition-level gate `val strokeShadowEnabled = paperElevationEnabled` threaded through every chain that reaches `drawSingleStroke` — committed rasters, wet pass, live preview (the preview casts the same shadow the committed stroke will → WYSIWYG at lift-off). |
| 5 | Raster invalidation | `AnnotationCanvas.kt:3768`, `:3896` | Both layer-raster cache keys gained `_s{0|1}`: `"${pageIdx}_${layer}_v${vibrancy}_s${flag}"`. Toggling the setting (or a future tier change) orphans old rasters via the existing LRU instead of serving stale flat/shadowed ink. Draw cost ≈ 2× path while rasterizing; steady-state frames stay bitmap-blits. |
| 6 | Setting | `SettingsManager.kt:156-162` (`paper_elevation_enabled`, default **ON**) | Prefs only. State in EditorScreen (`EditorScreen.kt:495`) → AnnotationCanvas param (`:2182`) → Canvas & Paper Options switch row "Paper Elevation" (`:4796/:5240-5259`, icon Layers). |
| 7 | Honest low-end degradation | `EditorScreen.kt:694-708` | In the existing device-tier effect: LOW_END + setting on + latch unset ⇒ auto-off ONCE + non-alarming snackbar ("…You can re-enable them in canvas settings.") + `SettingsManager.lowEndPaperElevationWarningShown` (`SettingsManager.kt:591-594`). The renderer reads ONLY the user flag — deliberately NO second tier gate inside AnnotationCanvas, because a hidden gate would make a deliberate re-enable silently ineffective (AGENTS.md "never silent degradation"; pinned by test). |

## 2. Design decisions

- **Shadow under the advanced androidx.ink path too.** The pre-pass runs before the early-returning Ink branch, so advanced-brush strokes get the same elevation as fallback-path strokes.
- **Theme awareness per prompt:** dark paper lifts with WHITE 0.12 alpha (a black shadow is invisible on `#1E293B`); light paper uses BLACK 0.20. Verified visually (§3).
- **Centerline ribbon for textured/stamp tools.** PENCIL/AIRBRUSH/WATERCOLOR etc. get one smoothed centerline outline blurred under their stamp pass — the cheap, uniform approximation the prompt's "offset points similarly" allows, without doubling expensive stamp loops.
- **DOTTED in the skip set** (beyond the prompt minimum): discrete dots over a continuous blurred band would read as a phantom stripe between dots. *(Review fix 2026-08-26: the shipped `SKIPPED_TOOLS` had omitted DOTTED even though this decision was written here — the code now matches the design, pinned by `BrushShadowPolicyTest`.)*
- **Zoom behavior:** committed ink rasterizes shadows into the page-space `LayerBitmapLruCache` bitmaps, so COMMITTED shadows scale with zoom exactly like stroke widths by construction. The live preview consumes the same world-space values directly under the gesture transform, where BlurMaskFilter radius-vs-canvas-transform behavior is pipeline-dependent (device-space blur would stay fixed on screen) — flagged for on-device confirmation rather than claimed as verified.
- **Pooled-paint style discipline:** the shared `Paint` is set STROKE at every non-dot geometry branch; only the single-point tap-dot branch switches it to FILL_AND_STROKE. *(Review fix 2026-08-26: without the explicit reset a tap-dot leak made every later shape shadow draw FILLED — pinned by `Phase213BrushShadowTest`.)*

## 3. DoD visual artifact

`visual-qa/screenshots/phase-213/before-after-light-dark.png` (1060×391), rendered by `workspace/phase-213/RenderShadowProgression.java` against the REAL compiled `BrushShadowPolicy` class from `app/build/tmp/kotlin-classes/debug` — same precedent as phase-200 (`workspace/phase-200/before-after.png`).

Paparazzi could not be used: `PaparazziSmokeTest` fails on this runner's layoutlib environment (`Renderer.configureBuildDriveProperties` NoSuchElementException — pre-existing since phase-208, reproduced again in the review-fix full-suite run). The driver therefore reproduces the exact shipped pipeline math: shadow layer = curve stroked at width w, offset by `plan.getOffsetX/Y()`, tinted black/white at `plan.getAlpha()`, true separable Gaussian of radius `plan.getBlurRadiusPx()` (BlurMaskFilter.Blur.NORMAL equivalent), composited UNDER un-blurred ink. **Honest scope caveat (review finding): this artifact validates the POLICY MATH only, not the Android renderer — a pooled-state bug like the review-fixed paint.style leak would not reproduce here; on-device visual QA remains the real acceptance gate.** Content:

- LIGHT PAPER row: widths 3/8/16 px — BEFORE flat navy ink vs AFTER with soft down-right black shadow (alpha 0.20).
- DARK PAPER row: same widths — AFTER shows the subtle WHITE 0.12 lift under `#F8FAFC` ink.
- SHADOW STRENGTH PROGRESSION band: strength 0 → 0.25 → 0.5 → 0.75 → 1 at pen w=10 — clean monotonic 0..1 ramp.

On-device recipe: open a note → draw with Pen/Marker → toggle "Paper Elevation" in Canvas & Paper Options → switch paper color to a dark value in the same sheet and observe the white lift.

## 4. GPU carrier tier (prompt task 3) — evaluated, vector path shipped for ALL tiers

`BrushShadowPolicy.gpuCarrierPreferred(sdkInt, lowEnd)` pins the API-31+/non-low-end tier table, but phase 213 deliberately routes every tier through `BlurMaskFilter`:

1. Committed ink rasterizes into the software `LayerBitmapLruCache` bitmaps — a `RenderNode`/`RenderEffect` carrier cannot run inside them, so BlurMaskFilter must produce the committed look anyway and a carrier would create a two-tier visual mismatch.
2. A shared carrier claimed N times per frame re-creates the exact hazard phase-201 fixed for the wet pass (`Canvas.drawRenderNode` keeps a LIVE reference — re-recording retroactively rewrites earlier composites of the same node); per-stroke nodes would allocate on the hot path.
3. On hardware-accelerated canvases Skia already executes small-radius (≤12 px) BlurMaskFilter blurs on the GPU — the vector path IS GPU-composited where it matters; the live preview (the only per-frame consumer) draws exactly one shadow per frame in its isolated phase-198 node.

The task was explicitly optional-if-feasible; feasibility analysis above says the carrier adds risk without a measurable win. Follow-up scope (documented, not built): a carrier scoped to the single live-preview stroke only.

Prompt constraint honored: **AGSL shader uniforms untouched** — zero `uShadow*` anywhere (pinned by `Phase213BrushShadowTest`); `AgslShaders.kt` bit-exact zero-pigment passthrough region unmodified.

## 5. Verification

- New suites: `services/BrushShadowPolicyTest` (**19 tests** — gates incl. the DOTTED skip, alpha constants incl. highlighter halving, offset factors + 1..6 dp clamp + density fail-safes, blur factor + 2..12 px clamp + NaN/negative fail-safes, null-plan matrix, coherent golden plan, GPU tier table) + `Phase213BrushShadowTest` (**9 source pins** — policy→renderer wiring before both paths, threading through committed/wet/live chains (whitespace-tolerant matching), pooled-renderer allocation pins (exactly 1 Paint / 1 Path / 1 BlurMaskFilter site) + paint-style leak pin (exactly 1 FILL_AND_STROKE site / exactly 4 STROKE sites), `_s` flag in BOTH cache keys, default-ON pref, editor + sheet wiring, honest low-end auto-off + no hidden canvas tier gate, no `uShadow`). **28/28 green.**
- Full: `gradle :app:testDebugUnitTest` = **3152 completed / 3 failed**, all three pre-existing and documented on clean HEAD since phase-212 (`Phase148UiFailureTextScrubTest` UNC-path scrub env failure; `PaparazziSmokeTest` ×2 layoutlib env failures — reproduced again immediately before this phase's changes).
- `gradle :app:assembleDebug` green.
- No Room schema change, no migration, no new dependencies, `.github/workflows/` untouched, base-APK-size rule intact (two small pure-Kotlin files + wiring).

## 5b. Review fixes (2026-08-26)

All nine review findings dispositioned:

1. **DOTTED missing from skip set (MEDIUM)** — FIXED: added to `SKIPPED_TOOLS` with the phantom-stripe rationale; pinned by a new dedicated `BrushShadowPolicyTest` test (`shouldApply(DOTTED)` false + `plan(...)` null). The §2 claim is now true in code.
2. **Shared-paint style leak (MEDIUM)** — FIXED: `buildGeometry` forces `paint.style = STROKE` on both the shape branch and the bare start/end branch; only the single-point tap-dot branch sets FILL_AND_STROKE. Pinned: exactly 1 FILL_AND_STROKE site / exactly 4 STROKE sites in `StrokeShadowRenderer`.
3. **Visual DoD is simulation-level (LOW-MED)** — DOCUMENTED: §3 now carries an explicit scope caveat (math-only artifact; on-device QA is the real acceptance gate); Paparazzi re-confirmed env-broken in the review-fix full-suite run.
4. **Test counts misstated 19+7 → actual 18+8 (LOW)** — CORRECTED here and in `docs/ARCHITECTURE.md` + `docs/phase-status.md`; post-fix totals are 19 + 9 = 28 and were verified against executed JUnit XML.
5. **Zoom-scaling claim overstated (LOW)** — REWRITTEN (§2): committed-path consistency holds by construction (page-space rasters); preview-path blur-vs-transform behavior flagged for on-device confirmation instead of claimed as fact.
6. **Per-frame ShadowPlan allocation (LOW)** — FIXED: one-slot last-plan memo inside `BrushShadowPolicy.plan` (UI-thread-only contract, same as the renderer); live-preview frames reuse the identical plan instance for the in-progress stroke.
7. **Dead surface + misleading comment (INFO)** — FIXED: `AnnotationCanvas.kt` comment no longer implies EditorScreen calls `BrushShadowPolicy.enabled`; `enabled()` KDoc now states it is a pinned tier table reserved for a future renderer-side gate.
8. **Whitespace-sensitive source pins (INFO)** — FIXED: multi-line literal assertions normalized to whitespace-collapsed matching so re-indentation cannot break them.
9. **Exports stay flat (INFO)** — NOT ACTIONED (unchanged known limitation, see §6): threading shadows into `ImportExportService.drawSingleStrokeToCanvas` remains follow-up scope.

Verification of the fixes themselves: filtered rerun of both suites green (28/28, confirmed via JUnit XML), then full `gradle :app:testDebugUnitTest` on the fixed tree = same 3 pre-existing environmental failures and nothing else; `assembleDebug` green.

## 6. Known limitations (documented, not actioned)

- **Exports stay flat**: PNG/WebP/PDF/PSD export uses `ImportExportService.drawSingleStrokeToCanvas` (its own simplified renderer); threading shadows there is a follow-up so exported files keep matching pre-213 consumers until then.
- The shadow under CALLIGRAPHIC/CHISEL_MARKER ribbons is the round-cap centerline outline, not the angled nib silhouette (blur makes the difference sub-pixel at these alphas).
- `gpuCarrierPreferred` is currently consumed by tests only (see §4).
