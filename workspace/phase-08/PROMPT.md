# Phase 8: Performance optimization (make it feel fast, keep it honest) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. The app now WORKS and is honest (Phases 2–7). This phase is about
**performance and responsiveness**: find real, verifiable inefficiencies and fix
them. No new features. No visual changes. Build must stay green.

The app targets API 26+, including low-end 2-core devices (per the project's
compatibility policy). Optimizations must respect the existing capability/tier
helpers (`DeviceCompatibilityManager`, `ShaderCapabilityHelper`).

## Verified problem areas (from the perf audit — fix each)

### 1. Main-thread work (jank)
Search the app for blocking work on the main thread. Known suspects to verify:
- `NoteflowViewModel`/`MainActivity` loading pages or decrypting on the main
  thread at startup or on navigation.
- Any `db.` DAO calls or `EncryptionService` calls invoked synchronously from
  UI (Compose) callbacks instead of a coroutine/`Dispatchers.IO`.
- `AnnotationCanvas.kt` doing bitmap allocation or pixel reads during draw.
- `ImportExportService` / `PsdExportService` / `WebDavSyncService` heavy work
  done inline in a UI callback rather than a worker.

For every confirmed main-thread offender, move the work to `Dispatchers.IO` (or a
bounded coroutine scope), or make it lazy. Verify with a grep/call-site audit
recorded in a findings list — do not guess.

### 2. Per-frame allocation in the canvas hot path
`AnnotationCanvas.kt` pointer/stroke path processes points per frame. Look for:
- Allocations inside the pointer-move handler (new `Path`, `List`, `FloatArray`,
  `RectF` per event) that could be pooled or reused.
- `WetBrushEngine` / `WetMixingMath` allocating per stroke point.
- String/formatting work inside draw.

Fix by reusing buffers (a small object pool or pre-allocated working objects).
Keep behavior identical. Pure-math helpers that were made unit-testable in
earlier phases must stay testable.

### 3. Bitmap memory (OOM risk on low-RAM devices)
Audit `ImageViewer.kt`, `LayerBitmapCache.kt`, `BitmapPool.kt`, gallery/media
components:
- Confirm image decode is bounded (`inSampleSize`/`decodeBounded`), never
  full-resolution `BitmapFactory.decodeFile` for large images.
- Confirm `LayerBitmapCache`/`BitmapPool` have real capacity limits and are
  released on config change / note close (no unbounded caching).
- Confirm no full-canvas `Bitmap.createBitmap` per stroke without reuse.

Fix leaks/oversized allocs; keep existing behavior identical.

### 4. Recomposition / stability in Compose
Audit the main screens (`HomeScreen.kt`, `EditorScreen.kt`, `UnifiedSidebar.kt`,
`GalleryView.kt`, `SpreadsheetTableView.kt`):
- Any `LaunchedEffect` keyed on volatile state that re-runs heavy work on every
  recomposition.
- Unstable lambdas/params causing large subtrees to recompose (add `remember`/
  stable holders where it clearly helps — do NOT over-annotate or chase
  micro-optimizations that hurt readability).
- Lists without keys/`LazyColumn` misuse causing full relayouts.

Fix the clear, high-value ones only. No speculative rewrites.

### 5. Startup time
- `MainActivity` / `NoteflowViewModel` init: confirm vault unlock, DB open, and
  `EncryptionService` key derivation (PBKDF2 600k) are NOT on the main thread.
- Move expensive one-time init off the critical path (lazy VM, delayed heavy
  init, async unlock) without changing behavior.
- Confirm `AppStartupLogger.kt` isn't doing expensive work itself.

### 6. Storage / I/O
- `NoteRepository` save/insert paths: confirm writes go through Room off-thread
  and are batched where cheap (no per-stroke transaction churn).
- `DocumentTextExtractor` / `HtmlToMarkdownConverter`: confirm no accidental
  O(n²) string concatenation; use `StringBuilder`.
- WAL checkpoint: verify the fix from Phase 2 is still present (backups include
  latest edits).

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes (existing tests unchanged except where an
  optimization legitimately requires a test update — say so explicitly).
- Every change is a perf fix with a `file:line` justification, NOT a rewrite.
- A `workspace/phase-08/PERF_REPORT.md` is written listing: the confirmed
  bottleneck, the `file:line`, the fix, and (where measurable in a JVM test)
  the before/after.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Do NOT change user-visible behavior or visuals. No feature changes.
- No speculative micro-optimizations: if you cannot articulate a real benefit,
  leave the code alone and note it as "considered, not applied".
- Low-end device policy: optimizations must not assume fast hardware; they must
  HELP slow devices (this is the point).
- Be honest: `PERF_REPORT.md` must not overstate gains that were not measured.