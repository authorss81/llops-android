# Phase 150: Canvas memory & render budgets — bound live layers, layer-bitmap cache, minimap work, and dynamic page count [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b4-DOS-02, R2-b2b4-DOS-03, R2-b2b5-FEA-04) and
`docs/phase-status.md` + `docs/ARCHITECTURE.md`. This phase bounds the canvas
render path's memory and per-frame work on crafted/restored geometry.

## Source findings (all OPEN — MEDIUM, LOW, LOW)

1. **R2-b2b4-DOS-02** (MEDIUM) — Live-canvas layer count is UNBOUNDED and each
   visible layer with strokes materializes a full-page ARGB bitmap resident for
   the page's lifetime: `EditorScreen.onAddLayer` (`EditorScreen.kt:566-585`)
   has no maximum; `LayerDao.getLayersForPage` (`Daos.kt:273-274`) returns ALL
   rows; renderer caches ONE full-page bitmap per visible layer keyed
   `${pageIdx}_${layer.id}_${symmetryMode}_v${vibrancyBoost}`
   (`AnnotationCanvas.kt:2552-2570`, `BitmapPool.acquire` `:2567`), retained
   until a strokes/layers change (`:455-460`) or disposal (`:522-528`). Only the
   EXPORT path has a cap (`PsdExportPolicy.kt:29,35-44` — 16 layers). A crafted
   backup with 40 layers × 1080×2400 pages → ~416 MB native → OOM on open.
2. **R2-b2b4-DOS-03** (LOW) — Minimap HUD re-walks EVERY stroke/point and issues
   a `drawLine` per sample of a fixed 4× stride on the main thread
   (`AnnotationCanvas.kt:1789-1815`) — ~50k draw-commands per frame at the
   phase-50 geometry cap (~200k points/page).
3. **R2-b2b5-FEA-04** (LOW) — `dynamicPageCount` is unbounded by stroke
   coordinate VALUE (length caps only): `AnnotationCanvas.kt:950-952` computes
   `(maxY / (pageHeightPx + pageGapPx)) + 1` from raw `pt.y` with no upper
   clamp; a crafted `y:1e9` point → ~628k pages iterated every draw frame
   (`:1412-1415`, full stroke-list filter per non-culled page `:1479`).

## The fix (where & how)

- **R2-b2b4-DOS-02:** Cap live layers per page at the same 16 as the export path
  (fail-closed with the existing non-alarming message + settings re-enable
  pattern per the hardware-reality rule), and bound the resident layer-bitmap
  map with a fixed byte/LRU budget (reuse/extend the `LayerBitmapCache` +
  `BitmapPool` size) instead of one never-evicted bitmap per layer.
- **R2-b2b4-DOS-03:** Give the minimap a fixed work budget (sample to a few
  hundred polyline points by deriving stride from total geometry) or render the
  thumbnail from the already-cached layer/page bitmap instead of re-walking
  stroke geometry every frame.
- **R2-b2b5-FEA-04:** Clamp `maxStrokeY`/`calculatedPages` to a world-size
  ceiling, and hoist the per-page stroke filter into a precomputed
  `Map<Int, List<Stroke>>`.

## Verification

- New/updated pure-JVM unit tests + source pins: layer-cap enforcement on
  addLayer + restore path at 16; the layer-bitmap LRU budget bound; minimap
  work-budget/derived-stride; page-count clamp on a crafted `y:1e9` stroke.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-150/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- A crafted backup cannot OOM the canvas or spin the minimap; on-screen layer
  count matches the export cap of 16 with an honest non-alarming notice.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep reduce-motion /
  low-end rules (minimap off by default on low-end devices) intact.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.