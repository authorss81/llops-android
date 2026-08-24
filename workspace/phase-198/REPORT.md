# Phase 198 — Isolate Active-Stroke Recomposition + Viewport Culling Polish [PERF 2.1+2.5]

**Date:** 2026-08-24 · **Status:** DONE (code + tests + `gradle :app:assembleDebug` green;
full `:app:testDebugUnitTest` 2636 tests / 3 failures, all three reproduced pre-existing —
see §7)

## 1. What shipped

Every pen sample used to invalidate the ENTIRE ink-canvas draw pass. The live preview
(`activePoints` / `activeStart` / `activeEnd`) was read inside the main canvas' draw
block — the same scope that draws paper cards, templates, page bitmaps, the per-frame
`groupBy`, every layer blit and the eraser/laser overlays — so each of the ~60–240
samples/second a stylus streams re-ran ALL of it. The eraser aim cursor had the same
storm via `eraserCursorCanvas`. On commit, a blanket `LaunchedEffect(strokes, layers,
vibrancyBoost) { layerBitmapCache.clear() }` additionally wiped EVERY page×layer raster
so the next frame re-rasterized all visible pages from vector data. And the paginated
branch iterated all `renderPageCount` page indices per frame, paying band arithmetic +
`continue` for every off-screen page.

| File | Change |
|---|---|
| `services/ViewportPageWindowPolicy.kt` (NEW) | Pure-JVM closed-form visible-page window: pages are fixed-stride slabs, so `first = ceil((top−slab)/stride)`, `last = floor(bottom/stride)` resolves the inclusive range in O(1). Boundary-touching parity with the old strict-inequality skip; windows above/below the document are EMPTY (no clamp-back onto page 0); non-finite/degenerate inputs fail safe to the FULL range (over-draw, never hide ink); `pageCount<=0` → empty |
| `ui/components/AnnotationCanvas.kt` | (1) **`LiveStrokePreview`** (NEW private composable): an isolated fill-max-size canvas stacked above the main pass carrying the IDENTICAL zoom/pan `graphicsLayer` transform; draws ONLY the classic live ink (+ its symmetry mirror, through the same `drawSingleStroke`) and the relocated eraser aim cursor. (2) Main pass no longer builds a live preview except the documented **wet-tool exception** (`liveWetPreviewStroke`) — the AGSL shader must see committed strokes + preview in one saveLayer. (3) Composition gate `liveOverlayVisible` is `remember(currentTool) { derivedStateOf { … } }` — flips twice per stroke, never per sample. (4) Volatile state reaches the overlay as **provider lambdas** read only in draw scope. (5) Blanket cache-clear effect deleted → incremental per-entry content-hash invalidation. (6) Paginated loop iterates `ViewportPageWindowPolicy.visiblePageRange(...)` + hoisted horizontal guard |

## 2. Why draw-scope reads + providers instead of `snapshotFlow`/`derivedStateOf { toList() }`

The PROMPT suggested `snapshotFlow { activePoints.size }` or
`derivedStateOf { activePoints.toList() }`. Both were evaluated and REJECTED because
they move the per-sample signal the wrong way:

- **Pre-198 truth:** there was never a *composition*-phase read of `activePoints` — the
  storm was **draw-phase**: snapshot reads inside `Canvas {}` register on that layout
  node's draw only. So the fix is not "stop recomposing", it is "**shrink the node whose
  draw subscribes**".
- `snapshotFlow` collected into composition state would make each sample a *recomposi-
  tion* input — strictly worse than a draw-scope read.
- `derivedStateOf { activePoints.toList() }` allocates a full point-list COPY per sample
  (GC churn at pen rate).

What IS used from the suggestion: `derivedStateOf` for the coarse boolean
("is a stroke live" ∨ "eraser cursor shown"), keyed on `currentTool` so it can't capture
a stale tool. It recomposes the overlay subtree exactly twice per stroke (start/end);
per-sample data still flows through draw-phase reads inside the isolated node, which is
what confines the invalidation.

**Provider-lambda subtlety:** passing `activeStart`/`activeEnd`/`eraserCursorCanvas` as
value parameters would re-run `LiveStrokePreview`'s body per sample anyway (shape tools
write `activeEnd` every move; the eraser writes its cursor every move). They cross the
call boundary as `() -> T` and are dereferenced only inside `Canvas {}`.

## 3. Recomposition/redraw counts

Layout Inspector needs Android Studio + a live device/emulator and cannot run on this
Linux CI runner (no SDK interactive tooling, no Robolectric/compose-ui-test in the test
classpath — adding one is a broad-impact dependency change out of phase scope). The
counts below are therefore derived mechanically from the invalidation graph and pinned
structurally by `Phase198LiveStrokeIsolationTest`; manual one-tap verification: Android
Studio → Layout Inspector → enable recomposition counts → draw with PEN and watch
`annotation_canvas` stay flat while `live_stroke_preview` ticks.

Per pen/move SAMPLE (classic tool, e.g. PEN, 60 Hz finger / 240 Hz stylus):

| Surface | Pre-198 | Post-198 |
|---|---|---|
| Main canvas draw-block executions | 1/sample (~60–240/s) | **0** |
| Live-preview node draw executions | (same node, folded into the above) | 1/sample — paper/template/blit-free |
| Committed-layer rasterization during live stroke | 0 (hash-gated) | **0 (unchanged)** |
| Committed-layer raster BLITs per sample | every visible page | **0** |
| Overlay recompositions | n/a | **0/sample** (2 per stroke: gate flip) |
| Wet tool (WATERCOLOR/OIL_PAINT/SMUDGE/SPLATTER/INK_WASH/GOUACHE/PALETTE_KNIFE) main-pass redraws | 1/sample | 1/sample (**documented exception**, dirty-rect scoped shader pass; isolating it would change the AGSL mix input or double-composite committed layers) |

Per stroke COMMIT:

| Surface | Pre-198 | Post-198 |
|---|---|---|
| Layer rasters re-rasterized next frame | **every visible page×layer** (blanket clear) | only entries whose page+layer content hash changed |
| Partial-ERASER move (rewrites stroke list per sample) | clear-all + full re-raster per move | only the edited page's entry |

Paginated long document (e.g. 200 pages, viewport on pages 62–63): loop iterations per
frame 200 → **2** (O(total) → O(visible)); drawing work unchanged (the old loop already
skipped off-screen slabs, so this is iteration overhead + parity-verified semantics).

## 4. Incremental layer-cache invalidation (step 2)

The blanket `clear()` was redundant with the existing lazy gates:
`cache.hash != strokesHash || cache.hash == 0` compares the CONTENT hashCode of the
page's strokes against the cached raster's stamp, keyed by
`pageIdx_layerId_symmetryMode_vibrancy`. Deleting the effect means:

- a commit only re-rasterizes entries whose content actually changed, lazily at next
  draw — other visible pages keep blitting their valid rasters;
- vibrancy/symmetry changes change the KEY, orphaning old entries which the LRU releases
  back to `BitmapPool` under the resident-byte budget (phase-150 mechanism, untouched);
- equal-content list instances (parent rebuilds the list without content change) keep
  their raster instead of being force-re-rendered;
- safety precondition pinned by test: `Stroke`/`PointF` are structural `data class`es,
  so the hash gate compares real geometry, not identities;
- unmount hygiene unchanged (`DisposableEffect(Unit)` still clears everything).

## 5. Viewport culling (step 3)

Verified the phase-50 slab culling semantics, then strengthened the mechanism: the
pre-198 loop was O(visible) WORK but still O(total) ITERATIONS. Pages here are
fixed-stride slabs, so `ViewportPageWindowPolicy.visiblePageRange` computes the window
in closed form and the loop header became `for (pageIdx in visiblePageWindow)`.
Parity is pinned EXHAUSTIVELY (`ViewportPageWindowPolicyTest.exhaustive sweep…`):
~600 window positions × 6 heights compared against the original per-page predicate,
including gap-only viewports (empty), boundary-touching bands (both flanking pages),
fully-above/below-document windows (empty — the naive clamp would have wrongly pulled
page 0 back in), and degenerate inputs (fail-safe over-draw). The horizontal band guard
moved OUT of the loop (never depended on the page index).

## 6. Verification

- `gradle :app:assembleDebug` — green.
- `gradle :app:testDebugUnitTest` — **2636 tests, 3 failures, all pre-existing**:
  - `Phase148UiFailureTextScrubTest` (documented UNC-path failure, AGENTS.md),
  - `PaparazziSmokeTest` ×2 (layoutlib environment; reproduced identically on a clean
    stash of this branch's changes before acceptance).
- New tests (24):
  - `ViewportPageWindowPolicyTest` (12) — closed-form behavior + legacy-predicate sweep.
  - `Phase198LiveStrokeIsolationTest` (12) — source pins: isolated overlay node +
    testTag, `derivedStateOf` gate keyed on tool, provider-not-value volatile params,
    draw-scope-only reads, exactly two remaining `id="preview"` constructions
    (wet-only + overlay), wet gate behind `isWetRenderedTool`, all three page branches
    wet-only, multi-color uniform no longer touches dry live points, eraser cursor
    relocated after the overlay tag, blanket-clear effect gone + DisposableEffect kept,
    data-class hash precondition, closed-form window wired + iterate-and-skip loop gone.
  - `B2Dos01StrokeGeometryTest` culling pin updated to the new mechanism (same intent:
    visible-window derivation from pan/zoom, closed-form policy, window-only loop,
    horizontal guard retained).
- No DB schema change, no migration, no new dependencies, `.github/workflows/`
  untouched, base-APK-size rule intact. Public `AnnotationCanvas` API unchanged —
  EditorScreen call sites untouched.

## 7. Known limitations (honest)

1. **Wet tools keep the per-sample main-pass redraw** (§3 table). Moving them would
   require either drawing committed strokes twice (alpha compounding on non-opaque
   layers) or changing the AGSL mix input (visual regression). Their pass is already
   dirty-rect scoped (phase-04 audit item 3), and they are a minority path.
2. **Layout Inspector counts are not machine-collected here** — CI runner has no device.
   Structural pins + the invalidation-graph analysis stand in; manual verification takes
   one minute (§3).
3. The overlay adds one small canvas NODE while a stroke is live (removed by the gate at
   stroke end); idle cost is zero.
