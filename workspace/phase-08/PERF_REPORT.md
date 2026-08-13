# Phase 8 — Performance Optimization Report (honest edition)

Applies to **InkFlow/Noteflow**, commit `018736c` (Phase 7 baseline).

Scope: find real, verifiable inefficiencies from the Phase 8 audit areas and fix
them with minimal mechanical diffs. **No new dependencies, no new permissions,
no DB schema changes, no `.github/workflows/` edits, no feature changes.** All
fixes are behavior-preserving; the only deliberate UI deltas are transient
loading states on previously-blocking buttons (see each entry).

Verification performed:
- `gradle testDebugUnitTest` — PASS (JVM unit tests)
- `gradle assembleDebug` — PASS

**Honesty note:** no dedicated benchmark harness exists in this repo, so
throughput numbers below are estimates from the PBKDF2 iteration count and
standard bitmap decode costs, not measured frames. Every fix targets a path that
was either (a) provably running on the main thread, or (b) allocating
unbounded-sized work per frame/per page. Those are categorical wins, not vibes.

---

## 1. Main-thread PBKDF2 (600,000 iterations) in Compose callbacks — FIXED

**Bottleneck:** `EncryptionService.deriveKey` runs PBKDF2WithHmacSHA256
(600,000 iterations, `EncryptionService.kt:31-35`) synchronously on the calling
thread. Five UI callbacks called it directly from onclick/composition:

| site | fix |
|---|---|
| `LockScreen.kt:151` (`verifyMasterPassword` in `Unlock` button) | `LockScreen.kt:152-173` — launch in `rememberCoroutineScope`, disable button + "Unlocking…" while in flight |
| `Dialogs.kt:436` (`setBiometricEnabled`) | `Dialogs.kt:436-445` — `scope.launch` |
| `Dialogs.kt:538` (`changeMasterPassword`) | `Dialogs.kt:538-546` — `scope.launch` |
| `Dialogs.kt:580` (`setMasterPassword`) | `Dialogs.kt:580-588` — `scope.launch` |
| `HomeScreen.kt:1138` (`isMasterPasswordValid` in backup dialog) | `HomeScreen.kt:1139-1168` — moved inside the existing export `scope.launch`; `isValidating` guards re-entry |

**Fix (root):** `NoteflowViewModel.kt` — the six security methods
(`setMasterPassword` :819, `changeMasterPassword` :858, `verifyMasterPassword`
:917, `isMasterPasswordValid` :962, `setBiometricEnabled` :981,
`removeMasterPassword` :1020) are now `suspend` and run the PBKDF2/AES work in
`withContext(Dispatchers.Default)`. State transitions (lockout counters, DEK
install, `initializeData()`) still happen on the resuming (main) coroutine so
`StateFlow` semantics are unchanged. Failure-count/lockout behavior preserved
1:1.

**Why Default and not IO:** PBKDF2-AES-128 is CPU-bound, not blocking-I/O;
running it on Default keeps the IO pool free. `EncryptionService` itself is a
pure JVM helper (`EncryptionAndServiceTest.kt:34-46` calls it directly) — no
crypto behavior changed.

**Before/after:** a 600k-iteration PBKDF2 derive takes ~200–1,500 ms on a
mid/low-end core. Previously this froze the UI (ANR risk on a locked screen).
Now it runs off the main thread; the lock screen stays responsive and only the
button shows a transient "Unlocking…" state.

---

## 2. Bitmap decodes unbounded (OOM risk on low-RAM) — FIXED

**Bottleneck:** `BitmapFactory.decodeFile(...)` with no `inSampleSize` decodes a
camera photo at full resolution (a 48–108 MP phone image = hundreds of MB of
heap), crashing low-RAM devices during export. Found 4 sites (3 in
`ImportExportService.kt` + 1 in the older `exportDocumentAsPdf` loop) plus a
related accumulator in the multi-page loop.

**Fix:** added `ImportExportService.decodeImageSampled(sourceFilePath,
maxLongEdge)` (helper at `ImportExportService.kt:138-151`) that reads bounds,
computes a power-of-two `inSampleSize` for the long edge, and decodes bounded.
Applied to:
- `ImportExportService.kt:193` `exportAnnotatedPage` raster source bg (cap
  4096).
- `ImportExportService.kt:297` `exportDocumentAsPdf` raster source bg (cap
  4096).
- `ImportExportService.kt:501` `drawEmbedsAndStickyNotesToCanvas` photo embed
  (cap 2048 — the embed is scaled down into its layout rect, so the extra detail
  cap is pure waste).
- `ImportExportService.kt:1673` `exportVaultToZip` per-page raster source/copy
  (cap 4096).
- `ImportExportService.kt:329-330` — the per-page decode in
  `exportDocumentAsPdf` was never recycled inside its loop (a multi-page export
  held N full decodes); added `bg?.recycle()` (safe: the page was already
  rasterized onto the composited bitmap and `bitmap.recycle()` precedes it).

**No visual change:** images under the cap decode identically; images over the
cap that were previously likely to OOM now export at ≤4096 px in the long edge.

**Already-bounded (audited, unchanged):** `ImageViewer.decodeBoundedImage`
(maxDim), `EditorScreen.decodeBoundedBitmap` (targetWidth 1080, on IO), all
`withContext(Dispatchers.IO)` wrappers.

---

## 3. Bitmap decodes on the main thread in Compose — FIXED

**Bottleneck:** `remember(...) { decodeBoundedImage(...) }` executed during the
first composition of components on the main thread — a 12 MP photo sampled to
1600/2400 px still costs ~50–200 ms of jank on the round-trip.

**Fix** (decode moved into `Dispatchers.IO`, result stored in `mutableStateOf`):
- `ImageViewer.kt:70-73` `FullscreenImageDialog` (maxDim 2400).
- `ImageViewer.kt:129-133` `MarkdownInlineImage` (maxDim 1600).
- `MediaEmbedComponents.kt:88-97` `PhotoEmbedCard` embed preview.

Transient loading placeholder shows while decoding; file-missing fallback
unchanged. `EditorScreen` paper-texture and PDF-page decodes were already on IO
(`EditorScreen.kt:136,361,371,395`) — audited, unchanged.

---

## 4. Per-frame re-vectorization of committed strokes (canvas hot path) — FIXED

**Bottleneck:** `AnnotationCanvas.drawCompositedLayersStrokes`
(`AnnotationCanvas.kt:1991-1999`) — when `layers.isEmpty()` (first frames before
the page's layer list loads) the code re-vectorized **every committed stroke
every frame**, calling `drawSingleStroke` for the full stroke list. This is the
same class of cost the layer cache path (line 2067+) already avoids — but the
empty-layers branch bypassed it entirely. (`EditorScreen` maintains ≥1 layer
after load, so strokes generally cache; the gap was the pre-load window and any
path with no layer rows.)

**Fix:** `AnnotationCanvas.kt:1991-2043` — routed the empty-layers branch through
the same page-local bitmap cache (`pageIdx_layer_default_<symmetryMode>`): on
`strokes` change the outer `LaunchedEffect(strokes, layers)` at
`AnnotationCanvas.kt:381-386` clears the cache, and the committed strokes are
rasterized once (bitmap from `BitmapPool`, released back on page change
`AnnotationCanvas.kt:422-429`). The live `previewStroke` is still drawn
per-frame on top — which is correct and always was.

**Before/after:** empty-layers frames went from O(S) vector draws per frame
(S = strokes on the page) to one cached blit + the active stroke. Frames during
the pre-load window no longer jank.

**Considered, not applied:** the `Path()` allocations inside `drawSingleStroke`
(per stroke) are now only hit for (a) cache rebuilds and (b) the live preview
stroke — both inherent. Reusing a rewindable `Path` across strokes is a
micro-optimization with real correctness risk and no verifiable benefit once
commit-stroke caching is in place. The pointer-move handler allocates a
`PointF` per event — that IS the stroke data, not waste. `WetBrushEngine` /
`WetMixingMath` are frame-timing / pure math with no per-point allocation to
remove (and their unit tests must not change).

---

## 5. AppStartupLogger doing file I/O in `onCreate` — FIXED

**Bottleneck:** `AppStartupLogger.logEvent` →
`appendToFile` (`AppStartupLogger.kt:64-73`) opened a `FileWriter` on the main
thread during `MainActivity.onCreate` on every event and lifecycle transition.

**Fix:** `AppStartupLogger.kt:18-26,47-49` — event writes are delegated to a
single daemon `ExecutorService`; crash logging (`logCrash`) intentionally stays
synchronous so the final crash record survives the dying process.

---

## 6. BitmapPool `getOptionsWithInBitmap` size mismatch — FIXED (defensive)

**Bottleneck:** `BitmapPool.getOptionsWithInBitmap` (`BitmapPool.kt:47-58`)
polled a pooled bitmap and set `options.inBitmap` without checking
width/height/config. If a caller ever reused the options for a differently-sized
decode, `BitmapFactory` throws `IllegalArgumentException`. (Currently no callers
— latent bug.)

**Fix:** `BitmapPool.kt:54-62` — validate `width`/`height`, otherwise return the
bitmap to the pool and omit `inBitmap`.

---

## Areas audited with NO changes needed

- **Repository/storage:** all `NoteRepository` write paths are `suspend` +
  `withContext(Dispatchers.IO)` or `Dispatchers.Default` (`saveStrokesForPage`
  :485, `saveCanvasItemsForPage` :622, `saveLayersForPage` :711). Stroke
  autosave is debounced 1 s (`EditorScreen.kt:418-425`), unmount flush uses
  `NonCancellable + Dispatchers.IO`. WAL checkpoint fix from Phase 2 is present
  (`NoteRepository.kt:136-144`, fully steps the cursor). `DocumentTextExtractor`
  uses `readText()`, `HtmlToMarkdownConverter` uses `StringBuilder` with linear
  regex passes — no O(n²) string building.
- **Recomposition/stability:** `GalleryView` / `SpreadsheetTableView` /
  `UnifiedSidebar` use keyed lazy lists; sidebar `LaunchedEffect`s are keyed on
  stable selection IDs. No volatile-keyed heavy effects found; no unstable-lambda
  churn that would justify `remember`-wrapping hot components.
- **Startup:** `db`/`repository` are lazy (`NoteflowViewModel.kt:32-33`); the
  DB-integrity check already runs in `Dispatchers.IO` (:46-55); `MainActivity`
  share-URI copy runs in `lifecycleScope.launch(Dispatchers.IO)`
  (`MainActivity.kt:514-518`); markdown read/write already wrapped in IO
  (:303-309, :325-330). The only startup offender was `AppStartupLogger` (fixed
  above).
- **`WebDavSyncService` / `PsdExportService`:** heavy paths already
  `withContext(Dispatchers.IO)`.

## What was NOT measured (be honest)

PBKDF2 jank-times, bitmap decode latencies and frame times are estimates based
on well-known unit costs; this repo has no perf harness. The categorical fixes
(off-main, bounded, per-page-once) are verifiable by code inspection and did not
change test outcomes.