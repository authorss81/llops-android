# Phase 150 report — canvas memory & render budgets (R2-b2b4-DOS-02 + R2-b2b4-DOS-03 + R2-b2b5-FEA-04)

Status: **DONE** — all three findings closed. Verified `gradle :app:testDebugUnitTest`
= 2052 app tests (2051 green; 1 PRE-EXISTING `Phase148UiFailureTextScrubTest` UNC-path
failure — unrelated baseline, reproduced on a clean stash by phase-149) +
`gradle assembleDebug` green (debug APK `app-debug.apk`, first `packageDebug` hit a
transient Gradle-daemon race against the concurrent test run; clean re-run succeeded).

## Findings fixed

### R2-b2b4-DOS-02 (MEDIUM) — live layer count is UNBOUNDED and each visible layer holds a full-page ARGB bitmap for the whole session

**Before** (from `docs/security-report-round2.md:870-877`):
- `EditorScreen.onAddLayer` had no maximum; `LayerDao.getLayersForPage` returned EVERY
  `layers` row (no `LIMIT`); the whole-DB vault restore imported any layer count into
  the live canvas.
- The renderer cached ONE full-page ARGB_8888 bitmap per visible layer (keyed
  `${pageIdx}_${layer.id}_${symmetryMode}_v${vibrancyBoost}`) in an unbounded
  `mutableMapOf`, retained until a strokes/layers change or disposal — resident for the
  whole open session, never evicted.
- The only cap in the stack was `PsdExportPolicy.MAX_EXPORT_LAYER_COUNT = 16` on the
  EXPORT path.

**Exploit / Reproducer:** restore a crafted backup spreading strokes across 40 layers on
a 1080×2400 page → nine ~10.4 MB page bitmaps → ~416 MB native on page open → OOM /
process kill with no further interaction. 2048×2732 tablets → ~22 MB/layer (~880 MB).
Multiple pages with stroke content multiply it higher.

**After (phase-150):**

- **Single source of truth** — new pure-JVM `services/LayerRenderBudgetPolicy.kt`:
  `MAX_LIVE_LAYER_COUNT` = 16 (deliberately the SAME number as the phase-82 export cap,
  so the editor shows exactly what a PSD export can carry), `MAX_RESIDENT_BITMAP_BYTES` =
  64 MB, `byteSize` accounting, the gate math (`layerLimitReached`/`mayAddLayer`/
  `isLayerCountCapped`/`omittedLayerCount`), the pure cap model
  (`capToLiveLimit`: top by `zOrder`, ties by list order = the SQL's `rowid DESC`,
  emitted ascending), the SINGLE ordering SQL literals
  (`BOUNDED_TOP_LAYERS_ROOM_SQL` for Room, `KEEP_HIGHEST_Z_LAYERS_RAW_SQL` for the raw
  restore sanitizer), and both non-alarming notices (`layerLimitNotice()`,
  `layersCappedNotice()`).
- **DAOs** (`Daos.kt`): `LayerDao.getTopLayersForPageBounded(pageId, limit)` uses the
  policy's `@Query` literal (`ORDER BY zOrder DESC, rowid DESC LIMIT :limit`) +
  `countLayersForPage`.
- **Repository** (`NoteRepository.kt`): `getLayersForPage` now materializes ONLY the
  top-16 read (sorted ascending for the editor path; the default-layer creation for a
  genuinely empty page is untouched); `getLayerCountForPage` surfaces the raw count.
- **ViewModel** (`NoteflowViewModel.kt`): `layerCappedNotifiedPages` (one-time per page
  per session, cleared at lock beside `geometryCappedNotifiedPages`) +
  `maybeNotifyLayersCapped`; `loadEditorCanvasPage` compares the raw count against the
  retained list and raises the notice when layers were held back.
- **Editor** (`EditorScreen.kt`): `onAddLayer` and `onDuplicateLayer` fail CLOSED at the
  cap with `viewModel.showSnackbar(LayerRenderBudgetPolicy.layerLimitNotice(), isLong =
  true)` + `return` — never a silent limit (AGENTS.md hardware-reality rule).
- **Resident raster LRU** — new `ui/components/LayerBitmapLruCache.kt` (access-order
  `LinkedHashMap`, byte counter, `put` evicts coldest back to `BitmapPool` under the
  64 MB budget, `clear` releases on invalidation/unmount). Replaced the unbounded
  `layerBitmapCache` map; both `drawCompositedLayersStrokes` materialization paths go
  through `get`/`put`.
- **Restore sanitizer** (`ImportExportService.kt`): `sanitizeRestoredLayerCounts(db)`
  runs under the candidate key BEFORE re-key/field-migration (raw
  `pruneLayerPagesToLiveCap`, policy's `KEEP_HIGHEST_Z_LAYERS_RAW_SQL`, per-page
  `DELETE … id NOT IN (SELECT … ORDER BY zOrder DESC, rowid DESC LIMIT 16)`) so a crafted
  40-layer archive can't reach the live vault; `pruneStagedSnapshotLayers` trims the
  export's STAGED snapshot (never the live vault, mirroring phase-149). Strokes
  referencing a folded layer fall back onto the first retained layer — bytes are never
  lost.

### R2-b2b4-DOS-03 (LOW) — minimap HUD re-walks every stroke/point with a fixed 1-4 stride, ~50k drawLine per frame

**Before**: `AnnotationCanvas.kt:1789-1815` iterated all strokes (`step ≤ 4`) and all
points (`step ≤ 4`) with a `drawLine` per retained pair, recomputed every pan/zoom frame
— at `StrokeGeometryPolicy.MAX_POINTS_PER_PAGE` (~200k) that is ~50k draw commands.

**After**: `services/MinimapGeometryPolicy.kt` gained the work budget —
`MAX_MINIMAP_SAMPLED_STROKES` = 120, `MAX_MINIMAP_POLYLINE_SEGMENTS` = 400,
`strokeStepFor`/`pointStepFor` (ceil-div, so small documents still step at 1),
`sampledStrokeCount`, `maxLineDraws` (worst case ≤ 520). The minimap Canvas derives ONE
global point stride from the summed total (`activeStrokeList.sumOf { it.points.size }`)
and ONE stroke stride from the count; the fixed 1/2/4 `pStep` cascade is gone.

### R2-b2b5-FEA-04 (LOW) — `dynamicPageCount` unbounded by stroke coordinate VALUE

**Before**: `calculatedPages = (maxY / stride).toInt() + 1` with no upper clamp — one
crafted stroke point `{"y":1e9}` (short JSON, passes every length cap) derived ~628,141
pages; the render loop `0 until renderPageCount` was paid every frame, plus a full
`activeStrokeList.filter { it.pdfPage == pageIdx }` per non-culled page.

**After**: new pure-JVM `services/CanvasPageBudgetPolicy.kt` — `MAX_DYNAMIC_PAGES` = 2000,
`maxStrokeYCeiling(pageStride)`, `clampMaxStrokeY` (non-finite `NaN`/`±Inf` → 0; extreme
`y` clamps to the ~3.18M-px ceiling), `calculatedPagesFor`, `clampCalculatedPages`
(the derivation overflows to 2001 at the exact ceiling; the clamped render value is
exactly 2000). `AnnotationCanvas`'s continuous-mode block derives `maxY` from the
clamped Y (still covers a large `visibleBottomY`) and clamps the derived count before
`dynamicPageCount`/`SideEffect`. The per-page stroke filter is hoisted to a single
`strokesByPage = activeStrokeList.groupBy { it.pdfPage }` per draw frame, then a map
lookup per page (`strokesByPage[pageIdx] ?: emptyList()`).

## Before/after evidence (file:line)

### R2-b2b4-DOS-02
- **Before:** `EditorScreen.kt:669` `onAddLayer()` had no maximum; duplicate
  (`EditorScreen.kt:714`) unguarded. `Daos.kt:275` `LayerDao.getLayersForPage` =
  `SELECT * ... ORDER BY zOrder ASC` (no `LIMIT`). `AnnotationCanvas.kt:478`
  `layerBitmapCache = remember { mutableMapOf<...>() }` unbounded; acquire at
  `:2628`/`:2741`; the ONLY cap was the export path (`PsdExportPolicy.MAX_EXPORT_LAYER_COUNT`).
- **After:** `services/LayerRenderBudgetPolicy.kt` (cap 16 = export cap, 64 MB budget,
  ordering SQL). `Daos.kt:285-289` `getTopLayersForPageBounded` + `countLayersForPage`
  (policy literal). `NoteRepository.kt:1398` bounded top-16 read, `:1429` count.
  `EditorScreen.kt:677-678, 728-729` fail-closed gates + `layerLimitNotice()` snackbar.
  `NoteflowViewModel.kt:204-209` one-time notice set + `:3756-3760` loader comparison;
  `:4226` cleared at lock. `ui/components/LayerBitmapLruCache.kt` (byte-budgeted LRU →
  `BitmapPool`); `AnnotationCanvas.kt:483` + `:2584` swapped in; `:2654-2664`/`:2769-2779`
  `get`/`put`. `ImportExportService.kt:2274` candidate-key `sanitizeRestoredLayerCounts`
  (shared `pruneLayerPagesToLiveCap` `:2530`, policy SQL `:2541`), `:1360` staged-snapshot
  trim `pruneStagedSnapshotLayers` `:2571`.

### R2-b2b4-DOS-03
- **Before:** minimap `Canvas` re-walked all strokes/points with a fixed
  `step 1/2/4` + per-stroke `pStep`, one `drawLine` per retained pair, every frame
  (`AnnotationCanvas.kt:1963-1989` per the pre-fix source).
- **After:** `MinimapGeometryPolicy` budget (`MAX_MINIMAP_SAMPLED_STROKES` 120 +
  `MAX_MINIMAP_POLYLINE_SEGMENTS` 400 + `strokeStepFor`/`pointStepFor`); the minimap
  draws via the budgeted global strides (`AnnotationCanvas.kt:1993-2016`: `sumOf { it.points.size }`
  `:1993`, `strokeStepFor` `:1994`, `pointStepFor` `:1995`, loops `step strokeStep` /
  `step pointStep`). Worst case ≤ 520 `drawLine`.

### R2-b2b5-FEA-04
- **Before:** `AnnotationCanvas.kt:1002-1026` raw `maxStrokeY` → `(maxY / stride).toInt()+1`
  with no clamp (crafted `y:1e9` → ~628k pages); per-frame loop `0 until renderPageCount`
  + `activeStrokeList.filter { it.pdfPage == pageIdx }` per visible page (`:1543`).
- **After:** `services/CanvasPageBudgetPolicy.kt` (`MAX_DYNAMIC_PAGES` 2000,
  `clampMaxStrokeY` non-finite→0, `clampCalculatedPages`); wired at
  `AnnotationCanvas.kt:1025/:1029`; per-frame `strokesByPage` groupBy hoisted at `:1497`,
  page lookup `strokesByPage[pageIdx] ?: emptyList()` at `:1565`.

## New tests

`app/src/test/java/com/authorss81/noteflow/Phase150CanvasRenderBudgetTest.kt` (23 tests,
pure-JVM fake-store + source pins, mirroring `Phase149NoteVersionsRetentionTest`):

- **Layer cap / byte budget**: exact policy numbers; gate math; `capToLiveLimit`
  top-zOrder + rowid tie-break semantics + deterministic output + under-cap untouched;
  DAO SQL / restore SQL shared-ordering pins (bound params, never interpolated);
  fake-DAO bounded read = same top-16 as the model; LRU eviction simulation (12 pages →
  ~6 survivors at 1080×2400, coldest released); byte overage math; notice wording.
- **Page-count clamp**: `{"y":1e9}` clamped to the ceiling → rendered count exactly 2000
  (2001 pre-clamp); non-finite → 0/1; zero stride fails to 1; sane docs untouched.
- **Minimap budget**: 200k points → ≤ 520 drawLine worst case; stride math; small docs
  still step at 1.
- **Source pins**: DAO literal + count method + policy import; repository bounded read +
  ascending re-sort + default-layer creation; editor add/dup gates fail closed; VM
  one-time notice set + lock clear + loader comparison; `LayerBitmapLruCache` used for
  the resident map (get/put), world-Y clamp + page-count clamp wired; groupBy hoist +
  budgeted minimap strides + `pStep` gone; restore candidate-key sanitizer + staged
  snapshot prune + no live-repo touch.

## Verification

```
gradle compileDebugUnitTestKotlin   # clean
gradle :app:testDebugUnitTest       # 2052 tests: 2051 green; the ONLY failure is the
                                    #   pre-existing Phase148UiFailureTextScrubTest
                                    #   UNC-path baseline (unrelated, reproduced on a
                                    #   clean stash by phase-149)
gradle assembleDebug                # green (debug APK built; first packageDebug was a
                                    #   transient daemon race against the concurrent
                                    #   test task, re-run clean)
```

Source-change surface: 4 new files (`LayerRenderBudgetPolicy.kt`,
`CanvasPageBudgetPolicy.kt`, `LayerBitmapLruCache.kt`, the test), 7 edited files
(`Daos.kt`, `NoteRepository.kt`, `ImportExportService.kt`, `EditorScreen.kt`,
`NoteflowViewModel.kt`, `AnnotationCanvas.kt`, `MinimapGeometryPolicy.kt`).

## Residuals / observations (out of phase scope)

- **Phase-150 review fixes (commit `llops: phase-150 review fixes`, 2026-08-19):**
  - R1 (perf): the layer-raster LRU no longer evicts the ACTIVE page's own layer stack mid-draw
    (`LayerRenderBudgetPolicy.resolveProtectedEviction` + `pageKeyOf`, wired into
    `LayerBitmapLruCache.evictUntilWithinBudget`) — the byte budget is now a CROSS-PAGE bound and a
    legit 16-layer note is bounded by the layer cap instead of re-rasterizing every frame.
    Pure-JVM tests for the protected resolver + cross-page bound added (28 total in this class).
  - R2: `LayerRenderBudgetPolicy.capToLiveLimit` is now LIVE over the bounded DAO read in
    `NoteRepository.getLayersForPage` (`.let { capToLiveLimit(it) }`) — no longer test-only dead code.
  - R3: `pruneStagedSnapshotLayers` tolerates a missing `layers` table like the restore sanitizer
    (`shouldPropagateRestoreStripFailure`), so an old-schema vault can't abort a backup.
  - R4: the dynamic-page-count fold is no longer silent — `CanvasPageBudgetPolicy.pageCountCappedNotice()`
    raised ONCE per canvas via the new `AnnotationCanvas.onDynamicPageCountCapped` (bool derived from OWN
    stroke content past the ceiling, not pan depth) wired to an `EditorScreen` snackbar.
  - R5: a short minimap stroke whose point count is overshot by the global stride gets a single
    start→end fallback line instead of vanishing from the thumbnail (`if (!drew)`).
  - R6: `loadEditorCanvasPage` reads the raw layer COUNT before the bounded load (moot for the
    empty-page case, accurate for the notice).
  - R7: source pins added: legacy unbounded `LayerDao.getLayersForPage` has no live caller; VM
    count-before-load order; staged-prune tolerance; notice wiring.
  - Verification: `gradle :app:testDebugUnitTest` 2057 total (2056 green; the ONLY failure is the same
    pre-existing `Phase148UiFailureTextScrubTest` UNC-path baseline) + `gradle assembleDebug` green.

- The parent `EditorScreen.pdfTotalPages` state can still exceed the clamp if a user
  adds 2000+ pages by hand; the CANVAS render path is unaffected because
  `renderPageCount = dynamicPageCount` is the clamped value and `computeCanvasWorld` is
  derived from it. `isPdf` mode keeps the real PDF page count.
- `LayerDao.getLayersForPage` (the old unbounded `SELECT * … ORDER BY zOrder ASC`) is
  still declared in the DAO but no production caller references it; kept for API
  compatibility. A future cleanup may delete it.
- Duration: the two active pixels per frame scan of the geometry remains O(total points)
  once per frame (groupBy + sumOf); the per-frame draw work is what now has hard bounds.
- `.github/workflows/` untouched (phase-147 approval rule). No schema change, no
  migration, no new dependencies.

## Definition of done

- [x] `docs/security-report-round2.md` rows R2-b2b4-DOS-02 / R2-b2b4-DOS-03 / R2-b2b5-FEA-04 marked **FIXED in phase-150** with the fix-applied detail blocks.
- [x] `docs/phase-status.md` phase-150 row flipped to `DONE` with evidence.
- [x] `docs/ARCHITECTURE.md` "Implemented in phase-150" note appended to the canvas section.
- [x] New policy/`androidx.ink`-independent pure-JVM code fully unit-tested (23 tests green).
- [x] Live-layer cap == export cap (16), no silent degradation, one-time non-alarming notice + clear at lock.
- [x] Restore sanitizer under the candidate key; staged-snapshot export trim; no live-vault destructive prune.
- [x] `gradle :app:testDebugUnitTest` (2051/2052 green, 1 pre-existing baseline) + `gradle assembleDebug` green.