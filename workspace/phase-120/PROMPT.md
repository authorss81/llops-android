# Phase 120: Rounder, smoother non-pen brush edges [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE BUG:** all brushes except pens have **sharp edges** — strokes look
jagged/pointy at stroke joins, ends, and width transitions. The pen tools
render round/smooth; the other brushes (and possibly eraser edges) need the
same roundness and smoothing treatment.

## What to do
- Locate the stroke rendering / geometry pipeline in
  `ui/components/AnnotationCanvas.kt` and the brush definitions in
  `services/BrushPreset.kt` / `data/model/StrokeModels.kt`.
- Find where pen strokes get round caps/joins and smooth width interpolation,
  and apply the **same roundness to all non-pen brushes**: round line caps,
  round joins, and smooth (non-stepped) width transitions at stroke start/end
  and at pressure changes.
- Fix any place where a brush's edge polygon is drawn with sharp corners
  (e.g. missing `StrokeCap.ROUND`/`StrokeJoin.ROUND`, or a
  polyline that should be a smooth curve).
- Keep the existing brush *character* (wet mixing, texture, etc.) — only the
  edge geometry must become rounder/smoother. Do not regress AGSL wet-mixing.

## Verification
- Pure-JVM unit tests for the geometry helpers (cap/join roundness, width
  interpolation smoothness) where the code is testable; otherwise document
  file:line proof of round caps/joins on every non-pen brush path.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- Every non-pen brush renders with round caps/joins and smooth width
  transitions — no sharp edges.
- `workspace/phase-120/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. Low-end safe: geometry math must be
  cheap (no per-frame allocations).