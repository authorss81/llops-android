# Phase 224 — Timelapse Replay (stroke timestamp → MP4)

## Goal
One-tap **timelapse** replay of any page — strokes already timestamped, just play them back and export `MP4` via platform `MediaCodec`.

## Context — verified anchors
- **Timestamps exist:** `PointF.timestampMs` `StrokeModels.kt:98`, `Stroke.timestampMs` `141`, `StrokeEntity.timestampMs:51` `LASER` uses `System.currentTimeMillis` else voice `elapsedMs` `AnnotationCanvas.kt:1468-1483` candidate build; `NoteRepository.kt:1055` `getStrokesForPage` preserves order.
- **Rendering:** `drawSingleStroke` `4208+` + `drawCompositedLayersStrokes 3652` already draws any `List<Stroke>` — replay just feeds prefix `take(k)` per timestamp.
- **Export pattern:** `PsdExportService` already writes layered export; no `MediaCodec` usage yet (grep 0), so no prior `MediaMuxer` setup.
- **No heavy deps:** `libs.versions.toml` has no `ffmpeg`, must use platform `MediaCodec + MediaMuxer` (API 26+ safe).

## Tasks
1. **Replay composable:** `ui/components/TimelapsePlayer.kt` — `Slider` scrub 0..`strokes.size`, `LaunchedEffect(play)` `withFrameNanos` stepping `nextTime = strokes[playIdx].timestampMs` (fallback `idx*120ms` when null); draws via `CanvasDrawScope` reuse of `drawSingleStroke` path (raster to `Bitmap` per frame scaled `0.5×` for speed).
2. **Export:** `services/TimelapseExporter.kt` — `MediaCodec.createEncoderByType(MIMETYPE_VIDEO_AVC)` + `MediaMuxer(path, MUXER_OUTPUT_MPEG_4)`, `720p` `30fps` `2Mbps`, `presentationTimeUs = elapsed* speedFactor (30× timelapse)`, `drainEncoder` loop like `PsdExportService` pattern; output to `cacheDir/timelapse_<page>.mp4` → SAF `ACTION_CREATE_DOCUMENT` (reuse `SaFExporter.kt:161` pattern).
3. UI: page overflow "Timelapse" → `Dialog` with preview canvas + `Play/Pause` + `Export MP4` (`FilledTonalButton`). Progress `LinearProgressIndicator`.
4. Guard `StrokeGeometryPolicy MAX_POINTS_PER_PAGE 200k` — cap timelapse at 2000 strokes or downsample via `StrokeSimplifyPolicy` already.

## Constraints
- No schema, no new native deps (only `android.media.*`); no `ffmpeg`. Keep bitmap per frame `720p ARGB_8888` pooled via `BitmapPool` and recycled immediately.
- DoD: `assembleDebug` + `testDebugUnitTest` green; `TimelapseExporterTest` pure JVM (timestamp → frame index, speed factor); Paparazzi `TimelapsePlayer` frame PNG.

