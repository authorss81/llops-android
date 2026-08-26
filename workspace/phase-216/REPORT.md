# Phase 216 — Selection Actions

## Summary
Implemented clipboard copy/cut, duplicate, delete, shape-aware hit-test, and drag-to-translate for lasso-selected strokes/shapes on the canvas.

## Changes

### New: `services/StrokeSelectionActionPolicy.kt` (pure-JVM)
- **Clipboard serialize/deserialize** via `EncryptionService` with MIME `inkflow-strokes`
- **Duplicate**: fresh UUIDs + `DUPLICATE_OFFSET_PX` (12f) offset
- **Delete**: removes selected strokes from the active list
- **Hit-test**: RECTANGLE (axis-aligned containment), ELLIPSE (`((x-cx)/rx)²+((y-cy)/ry)²≤1.08²`), LINE/ARROW (`distanceToSegment ≤ LINE_HIT_TOLERANCE_PX`), freehand (point/segment proximity)
- **Translate**: moves selected strokes by delta, recomputes `pdfPage` for cross-page moves, calls `recomputeBounds`

### Modified: `ui/screens/EditorScreen.kt`
- `copySelectedStrokes()` / `cutSelectedStrokes()` / `duplicateSelectedStrokes()` / `deleteSelectedStrokes()` — all route through single `handleStrokesChange` undo entry
- `translateSelectedStrokes(dx, dy)` — delegates to `StrokeSelectionActionPolicy.translate`
- `ClipboardGuard.recordCopy()` called on clipboard writes

### Modified: `ui/components/AnnotationCanvas.kt`
- New `onSelectionTranslate: (dx: Float, dy: Float) -> Unit` parameter
- Translate-drag gesture: touch inside selection bounds → accumulate delta → commit on drag-end; touch outside → start new lasso; cancel resets state
- `finishLassoSelection` gained `allStrokes` parameter (fix for forward-reference to `activeStrokeList`)
- Pre-existing Phase 215 fixes: `LassoTrailPolicy` FQN qualification in `StrokeSelectionOverlay`, `selection_count_a11y` string resource added

### New: `app/src/main/res/values/strings.xml`
- Added `<string name="selection_count_a11y">%1$d strokes selected</string>` (was missing, referenced by EditorScreen a11y announcement)

## Tests
- `StrokeSelectionActionPolicyTest` — 41 tests (clipboard round-trip, duplicate offset, delete, hit-test per shape type, translate + cross-page, recomputeBounds, source pins)
- `Phase216SelectionWiringTest` — 24 source-pin tests (EditorScreen wiring, AnnotationCanvas wiring, policy existence/purity, encryption gate, CanvasCommitListPolicy)

## Verification
- `gradle assembleDebug` — GREEN
- `gradle testDebugUnitTest` — 65 new tests pass, 0 failures

## Known Limitations
- Axis-aligned hit-test only for shapes; rotated shapes need `Stroke.rotationDegrees` + DB migration (deferred per AGENTS.md major-arch-change rule)
- `translateSelectedStrokes` uses hardcoded `pageStride=1592f, pageHeight=1528f` (needs future parametrization)
