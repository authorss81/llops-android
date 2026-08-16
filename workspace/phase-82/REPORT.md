# Phase 82 — B2-DOS-06 (MEDIUM) — Multi-layer PSD export materializes N full-page ARGB bitmaps plus N per-layer channel buffers simultaneously → OOM on many-layer pages

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-06, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before** — the layered PSD export had three independent unbounded-memory stems, all hit at once:

- `ImportExportService.kt:2455-2461` (`exportPageToPsd`) — `layers.sortedBy { it.zOrder }.forEachIndexed { … }`
  created ONE full-page 1080×1528 `ARGB_8888` Bitmap (~6.6 MB) PER layer and kept them ALL alive
  in `psdLayers` until the file was written. The layer count was UNBOUNDED (the Layers panel adds
  layers freely; a restored vault can carry arbitrary `layers` rows via `restoreFromZip`).
- `PsdExportService.kt:119-190` (`buildLayerDataSection`) — every layer's 4 uncompressed channels
  were accumulated into a fresh per-layer `ByteArrayOutputStream` and stored as full-size
  ByteArrays in `layerPixelBlocks.add(chanBos.toByteArray())` (`:183`/`:190`) — ~6.6 MB × layer,
  ALL held simultaneously — plus a per-layer `IntArray(width*height)` (`:124`).
- `PsdExportService.kt:79-89` — the composite raster additionally did `compositeBitmap.getPixels`
  into a second full-size `IntArray`, and the whole layer-and-mask section was materialized as one
  `ByteArray` (`buildLayerDataSection` → `bos.toByteArray()`).

Exploit scenario (from the finding): a ~25-layer note exported as PSD →
25 × 6.6 MB bitmaps + 25 × 6.6 MB channel blocks + composite ≈ **~350 MB peak heap** before any
bytes hit the file → OOM/ANR on 1–2 GB devices, recurring on every export.

**After** — two layered fixes close the path:

1. **Layer-count cap BEFORE any bitmap is created** — new pure-JVM decision table
   `services/PsdExportPolicy.kt` (the single layer-budget source): `MAX_EXPORT_LAYER_COUNT = 16`,
   `capLayerCount(layerCount)` / `omittedLayerCount(layerCount)` / `isLayerCountCapped(layerCount)`,
   and `noticeMessage(exported, omitted)` (the one-time non-alarming wording). 
   `ImportExportService.exportPageToPsd` (`:2465-2467`) now clamps the zOrder-sorted layer list
   with `sorted.take(exportedDataLayers)` BEFORE any `createBitmap` runs, so a 200-layer crafted
   vault can never raise the bitmap count beyond 16. The function returns the new
   `PsdExportService.PsdExportOutcome(file, exportedLayerCount, omittedLayerCount)`
   (`:2483`; failure `:2491`). `EditorScreen.kt:1412-1420` shows the policy's notice — "PSD
   export included the bottom N of M layers — K omitted (max 16)" — via
   `PsdExportPolicy.noticeMessage` when `outcome.wasLayerCapped`, so the cap is never silent
   (AGENTS.md hardware-reality rule: no silent degradation).

2. **Channel data streams straight to the destination stream with a single reused IntArray** —
   `PsdExportService.exportLayersToPsd` now writes the layer-and-mask section streaming:
   - the layer INFO records (count + per-layer records incl. channel-size table, blend
     signature, extra-data/name block) go into a tiny bounded buffer `buildLayerRecords`
     (`:85`, `:227`) — a few KB for 16 layers, the ONLY section buffer ever in heap;
   - the per-layer channel PIXEL data is written one channel at a time straight to the
     destination `DataOutputStream` via `writeLayerChannelData` (`:93`, `:249`) →
     `writeChannelPixels` (`:271`), dropping the pre-fix `layerPixelBlocks` full-size
     per-layer channel ByteArray accumulation entirely;
   - ONE `IntArray(width * height)` (`:92`) is reused for every layer's `getPixels` AND the
     composite pass (`:108-115`) — the per-layer IntArray and the second composite IntArray
     are gone.
   - The section length written into the PSD header is computed up front by the pure-JVM
     helpers `channelSizeFor` (`:134`), `channelDataLength` (`:141`), `layerSectionLength`
     (`:150`) exactly as the pre-fix materialized section would have been (`recordsBytes + 4 ×
     channelSize × layerCount`) — the on-disk layout stays **byte-identical** to before.

Peak heap for a 25-layer note drops from ~350 MB to ~112 MB bounded (16 × 6.6 MB bitmaps + one
6.6 MB buffer + tiny records) on every export; a restored malicious vault can no longer raise it.

## File:line evidence (commit after)

| Site | Before | After |
|---|---|---|
| `services/ImportExportService.kt` `:2455-2461` | `layers.sortedBy { it.zOrder }.forEachIndexed { … createBitmap … }` (unbounded per-layer bitmaps) | `:2465-2467` `exportedDataLayers = PsdExportPolicy.capLayerCount(sorted.size)`; `omittedDataLayers = PsdExportPolicy.omittedLayerCount(sorted.size)`; `sorted.take(exportedDataLayers).forEachIndexed` |
| `services/ImportExportService.kt` `exportPageToPsd` return | `:2464` returned bare `File?` (no cap signal) | `:2483` returns `PsdExportService.PsdExportOutcome(file, exportedLayerCount, omittedLayerCount)`; catch `:2491` returns outcome with null file |
| `services/PsdExportService.kt` `:119-190` | `buildLayerDataSection` buffered every layer's 4 channels into `layerPixelBlocks` (full-size `chanBos.toByteArray()` per layer) + per-layer `IntArray(width*height)` (:124) + whole-section `bos.toByteArray()` | `:85-93` records in a tiny bounded `buildLayerRecords` buffer; channel pixels streamed via `writeLayerChannelData`; ONE `val pixels = IntArray(width * height)` (:92) reused for layers AND composite |
| `services/PsdExportService.kt` `:79-89` | composite used a second full-size `IntArray(pixelCount)` | `:108-115` composite `getPixels` reuses the single `pixels` buffer; `writeChannelPixels(dos, pixels, {16,8,0,24})` |
| `services/PsdExportService.kt` (new) | — | Pure-JVM layout helpers `channelSizeFor` `:134`, `channelDataLength` `:141`, `layerSectionLength` `:150`, `layerRecordBytes` `:160`, `writeChannelPixels` `:271` |
| `ui/screens/EditorScreen.kt` `:1407-1420` | `val file = ...` → single generic "PSD export failed" | reads `outcome.wasLayerCapped` → `PsdExportPolicy.noticeMessage(outcome.exportedLayerCount, outcome.omittedLayerCount)` snackbar before the SAF exporter |
| `services/PsdExportPolicy.kt` (new) | — | `MAX_EXPORT_LAYER_COUNT = 16`, `capLayerCount`, `omittedLayerCount`, `isLayerCountCapped`, `noticeMessage` |

## New/changed files

- Added `app/src/main/kotlin/com/authorss81/noteflow/services/PsdExportPolicy.kt` (pure-JVM decision table).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/services/PsdExportService.kt` (streaming section writer + single pixel buffer + pure-JVM layout helpers + `PsdExportOutcome`).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (`exportPageToPsd` capping + outcome return).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (cap notice call site).
- Added `app/src/test/java/com/authorss81/noteflow/B2Dos06PsdExportLayerCapTest.kt` (14 tests).

## Verification

- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**, 1485 tests total (1471 pre-existing + 14 new),
  **0 failures / 0 errors**.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (first invocation hit the repo's documented
  transient incremental-packaging failure; the re-run — and a follow-up — were fully green,
  90/90 UP-TO-DATE). Debug APK on disk: `app/build/outputs/apk/debug/app-debug.apk`,
  173,771,590 bytes.
- New tests `B2Dos06PsdExportLayerCapTest` (14): policy cap/clamp/omission/notice behavior;
  the streaming writer's channel-size, channel-data-length, section-length and layer-record byte
  layout (verified against an exact byte-offset parse of `layerRecordBytes`); hidden-layer flag;
  single-`IntArray`-buffer reuse; and source pins proving `layerPixelBlocks`/`chanBos` are gone,
  `IntArray(width * height)` appears exactly once, `exportPageToPsd` caps via the policy before
  rendering and returns `PsdExportOutcome`, and EditorScreen surfaces `PsdExportPolicy.noticeMessage`.

## Checksums / secrets handling

- No new secrets, keys, or passwords introduced; no logging added; `e.message` is **never** logged
  (all new code is silent except the existing `FailureLogPolicy.safeLogMessage` 2-arg `Log.e` path
  already in place at the `exportPageToPsd` catch, unchanged).
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE, WebDAV/LocalSend posture all untouched.
- No new dependencies. No Gradle/CI/workflow changes. No schema change, no migration.

## Out of scope (documented, not fixed here)

- `B2-DOS-07` (backup export builds the ENTIRE vault in one in-heap byte array) — its own phase
  (83); PSD export's file is written via `FileOutputStream` streaming, but the vault-backup zip
  path is separate.
- `B2-DOS-09` (RDP recursion StackOverflowError) / `B2-DOS-10` (`lastSavedStrokeHash` unbounded
  growth) / `Phase-32-NEW-01` (lingua n-gram pack in base APK) — separate phases.
- The composite raster itself still requires one full-page bitmap + one pixel buffer (inherent to
  rendering a merged composite); the cap bounds the layer-multiplied portion, which is the finding.
- Additional PSD-format hardening (RLE compression instead of raw, lower export resolution) is a
  product-level optimization, not required to close the finding.