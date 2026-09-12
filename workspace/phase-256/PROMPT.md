# Phase 256 — Eraser precision (segments not points) + atomic undo

## Goal
Eraser misses fast-stroke gaps; every ACTION_MOVE floods undoStack (30+ snapshots/stroke → history evicted, redo cleared, undo jumps to empty).

## Evidence
- HIGH `AnnotationCanvas.kt:1750-1754`: `erasesStroke` uses `stampRadius(currentWidth, 1f)` (max) while per-sample mask uses pressure-aware radius (`EraserGeometryPolicy.kt:48-52`, 0.5x..1x). Light eraser (pressure 0.2) deletes strokes 8-12px outside true mask in STROKE mode (whole-stroke removal). Fix: gate candidates with max radius but decide removal with per-sample pressure radius.
- Missing on HEAD: `StrokeSegmenter.distSqToSegment` + `densifyPoints(maxGap=8f)` + `hitStrokeAt`/`strokeTouchedBy` testing all `points[i]->points[i+1]` segments + start/end anchors; `strokeContainsPoint(stroke, offset, extraRadius)` with thresholdSq.
- Missing on HEAD: `var eraserDidMutateDuringDrag=false`; `applyEraser` mutates `activeStrokeList` live (no `onStrokesChanged` per move); `onDragEnd`/`onDragCancel` emit `onStrokesChanged(emittedList(...))` ONCE iff mutated, then reset flag + haptic.

## Files to change
- `services/StrokeSegmenter.kt`, `ui/components/AnnotationCanvas.kt` (`erasesStroke`, `applyEraser`, `onDragStart/End/Cancel`)

## Tests
- `Phase256EraserPrecisionTest`: segment hit across 8px+ gap; light-pressure no-overdelete; one undo entry per swipe (source pin + JVM policy test).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
