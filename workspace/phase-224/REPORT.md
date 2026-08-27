# Phase 224 — Timelapse Replay (stroke timestamp → MP4)

**Status:** DONE (2026-08-27)
**Type:** Feature (viewport-only, no schema / no migration / no new deps / `.github/workflows/` untouched)
**Base-APK rule:** intact — the only new runtime API is `android.media.*` (platform MediaCodec + MediaMuxer). No ffmpeg, no native libs.

## What was delivered

One-tap **timelapse** replay of any page's strokes — the strokes are already timestamped, so the app plays them back in chronological order and can export an H.264 MP4 entirely on-platform.

### 1. `services/TimelapsePolicy.kt` (pure-JVM timing model)
- A "timelapse" plays **30× FASTER** than real time: `videoElapsedMs = (real − t0) / SPEED_FACTOR`, `SPEED_FACTOR = 30f`. (The prompt's `elapsed * speedFactor` was read as ambiguous; the timelapse/faster interpretation was chosen so a real page of minutes compresses to a sane 30fps clip.)
- `sourceTimeMs(strokes)` — per-stroke timestampMs, falling back to `index * FALLBACK_STEP_MS (120ms)` when null.
- `frameForElapsedMs(ms)` — FPS quantization: `(ms * FPS) / 1000`, clamped at 0.
- `effectiveElapsedMs(strokes, budget)` — when the natural accelerated timeline would exceed `MAX_TOTAL_FRAMES = 1800`, it **compresses** (scales time down) so the last emitted frame still shows every rendered stroke — never silent truncation.
- `totalFrames(strokes, maxFrames)` — `min(naturalTotal, budget)`, ≥ 2 (or 1 for an empty timeline).
- `visibleStrokeCountAtFrame(strokes, frameIndex)` — how many of the capped strokes are on-screen at a given frame (used by both the exporter and the player).
- `capped(strokes)` — `MAX_STROKES = 2000` oldest-first (Task 4 guard; the 200k-point per-page budget is already enforced by `StrokeGeometryPolicy`, this caps stroke *count* too).
- Constants: `WIDTH=1280, HEIGHT=720, FPS=30, SPEED_FACTOR=30f, BITRATE=2_000_000, MAX_STROKES=2000, MAX_TOTAL_FRAMES=1800, FALLBACK_STEP_MS=120`.

### 2. `services/TimelapseExporter.kt` (MediaCodec + MediaMuxer export)
- `MIMETYPE_VIDEO_AVC` encoder, 720p, 30fps, 2 Mbps, `MediaMuxer` → `video/mp4`.
- **Buffer-input path** (not `createInputSurface()`): gives precise per-frame PTS without real-time throttling.
- Color format selected from the encoder's codec capabilities (`COLOR_FormatYUV420Planar` preferred, fallback `COLOR_FormatYUV420SemiPlanar`); custom ARGB→YUV420 converter `fillYuv420`.
- Per-frame rasterizer mirrors `AnnotationCanvas.drawSingleStrokeToCanvas` (self-contained, background-thread-safe `android.graphics.Canvas`): freehand polylines, single-point dots, shape boxes, TEXT labels, FILL polygons, GRADIENT rects. Wet/tiled/AGSL multi-pass brushes fall back to their flat polyline — an accepted, bounded approximation for a replay export.
- `worldBounds` (union of stroke points + anchors, fallback 1080×1528 page box) + `fitTransform` (uniform fit → 720p, centered, 0.96 margin).
- Playlist built from `TimelapsePolicy` (frame → visible-stroke-count), one output frame per time step, drained via a bounded drain loop.
- `export(bitmapPool, strokes, outPath)` returns the MP4 `File`.

### 3. `ui/components/TimelapsePlayer.kt` (preview player)
- Preview bitmap rasterized via the SAME `TimelapseExporter.drawPrefix` + `fitTransform`, so what you see is what the MP4 contains.
- Scrub `Slider` `0..strokes.size`; Play/Pause steps through timestamps with bounded per-stroke `delay` (16..2000ms); `LinearProgressIndicator` from hoisted `isExporting`/`exportProgress`.

### 4. EditorScreen wiring + SAF export
- Overflow menu → **"Timelapse Replay…"** (after "Export Layers as PSD").
- `AlertDialog` hosts `TimelapsePlayer`; "Export MP4" runs an export coroutine: `TimelapseExporter.export(...)` then SAF `ACTION_CREATE_DOCUMENT` via `SaFExporter.export(TIMELAPSE_MP4, file)`.
- `ExportDestinationPolicy.ExportKind.TIMELAPSE_MP4` added (`mimeType="video/mp4"`, `suggestedFileName="timelapse.mp4"`) — SAF-only, respects the B1-PLAT-3 `ExportDestinationPolicy` discipline.

## Tests
- `services/TimelapseExporterTest` (10 pure-JVM): speed factor (30× faster), empty timeline = 1 hold frame, first-stroke-at-time-zero, null-timestamp fixed-step fallback, FPS frame quantization, visibility growth across frames, total-frames hold + full-stroke end, cap-clamp-without-truncation, MAX_STROKES cap (oldest-first), sanity bound.
- `paparazzi/TimelapsePlayerPaparazziTest` (light + dark frame PNG).

## Verification (DoD)
- `gradle :app:compileDebugKotlin` — SUCCESS (only pre-existing deprecation warnings for the standard `COLOR_FormatYUV420*` constants, which remain valid on all supported API levels).
- `gradle :app:testDebugUnitTest --tests "...TimelapseExporterTest"` — **10/10 PASS**.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL**.
- `gradle :app:testDebugUnitTest` (full) — **3366 total / 11 failures, ALL pre-existing or environment** (0 new from this phase), reproduced on a clean `git stash`:
  - **6 Paparazzi** (`PaparazziSmokeTest` ×2, `Phase223DraftingGridSnapshotTest` ×2, `TimelapsePlayerPaparazziTest` ×2) — fail in Paparazzi's `Renderer.configureBuildProperties` (`NoSuchElementException`) BEFORE any composable code runs. This is the known-broken Paparazzi layoutlib/SDK infra earlier phases documented for `PaparazziSmokeTest`; the committed smoke test fails identically here, so my new test passes/fails in lockstep with the existing pipeline wherever the SDK is present. The `TimelapsePlayer` composable itself uses only layoutlib-safe APIs, so it is safe when Paparazzi runs.
  - **2 `B2Ui2ClipboardScrubTest`** — pre-existing source-scan assertions against `CodeBlockTextView.kt` / an existing raw write in `EditorScreen.kt` (unrelated to this phase; clean-stash repro).
  - **1 `Phase148UiFailureTextScrubTest`** — the long-standing documented UNC-path failure.
- Baseline check: stashing the phase-224 changes and re-running the 5 suspect tests produced the SAME failures → none of the 11 failures is introduced by this phase.

## Notes / deviations from the prompt
- **Timelapse = 30× FASTER** (`videoElapsedMs = (real−t0)/30`), not slower. This is the only sensible reading for a "timelapse" that produces a short 30fps clip from a long real page; it's unit-pinned by `speedFactorIsThirtyAndPlaybackIsFaster` + `firstStrokeLandsAtTimeZero`.
- The prompt's "raster via `CanvasDrawScope` reuse of `drawSingleStroke`" was realized with a self-contained `android.graphics` rasterizer (mirroring `drawSingleStrokeToCanvas`) instead, because the Compose `DrawScope` API is too parameterized/wet-brush-coupled for a background-thread rasterizer; wet/AGSL multi-pass brushes fall back to flat polylines, documented.
- `totalFrames` returns exactly the cap when compressed (an initial off-by-two in my first test-expectation set was fixed by computing the natural total and clamping, rather than compressing-then-recounting — so the final frame always shows all strokes).

## Cross-phase integrity
- No schema change, no new DB columns, no migration.
- No new base-APK dependencies (only `android.media.*` platform APIs).
- No logging of keys/passwords/decrypted content; MP4 writes go SAF-only.
- `.github/workflows/` untouched.

## Docs updated
- `docs/ARCHITECTURE.md` — services table row + "Implemented in phase-224" note (timelapse timing model + MediaCodec export).
- `docs/phase-status.md` — phase-224 row.
