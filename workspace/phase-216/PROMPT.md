# Phase 216 — Lasso Copy/Duplicate + Shape Select & Move

## Goal
With lasso selection from phase 215, add **clipboard copy, duplication, deletion, and shape-aware move/translate** for any selected strokes/shapes. Shapes remain `Stroke` rows (LINE/RECTANGLE/ELLIPSE/ARROW etc.) but get precise geometric hit-testing and unified drag translation.

## Context — verified anchors
- **Foundation:** `StrokeModels.kt:141-163` strokes + `Entities.kt:51-74` encrypted `pointsJson`; `CanvasCommitListPolicy.emittedList` `services/CanvasCommitListPolicy.kt:33-44` single source building full vault list on commit (must be the only commit path to avoid 205 resurrection bug). Undo `EditorScreen.kt:469-483,862-870` full-list stack.
- **Shapes as strokes:** `services/ShapeRecognitionHelper.kt:13-222` `trySnapShape(threshold 0.82)` snap LINE/ELLIPSE/RECTANGLE/ARROW to 2/37/5/5 points (`AnnotationCanvas.kt:1488-1494` gate excludes LASER/WATERCOLOR/OIL_PAINT/SMUDGE/SPLATTER/DOTTED/NEON/CHARCOAL/OIL_PASTEL/DRY_BRUSH/PALETTE_KNIFE). Explicit shape pens `RECTANGLE/LINE/ARROW/ELLIPSE/TRIANGLE/STAR/PENTAGON/HEXAGON` use `start/end`. No `selectedStrokeId` or stroke-move logic exists (grep `strokeContainsPoint` only in eraser).
- **Second engine:** `plugins/inktos/InkToShapeGeometry.kt:49-353` + `InkToShapePlugin` via `PluginManager` (`EditorScreen.kt:1396-1426`, `NoteflowViewModel.kt:777-785`) — mirrors snap but with `4πA/p²` etc. `convertLatestStrokeToShape()` replaces last freehand stroke via `convertedStrokeIds` guard.
- **Existing moves:** embeds/stickies `AnnotationCanvas.kt:5040-5995` `DraggableStickyNoteCard/DraggableMediaEmbedCard` body drag `dragAmount/zoomScale` clamped `0..pageWidth`, `pageTopY..pageTopY+pageHeight`; `CanvasItemRotationMath.rotationFromHandleDrag` `services/CanvasItemRotationMath.kt:102-126`.
- **Clipboard precedent:** `services/ClipboardGuard.kt:37-109` `recordCopy + scrubIfOwnCopy(60s)` + lock unconditional `scrubUnconditionally()` (phase-139); code-block card `MediaEmbedComponents.kt:353-357` `clipboardManager.setText(AnnotatedString(codeText))`.
- **Layer/paging:** `Stroke.layerId`, `getPageFromCanvasY` `516,543`.

## Tasks
1. **Clipboard for strokes:**
   - Copy: `ClipboardGuard.recordCopy()` + system `ClipboardManager.setPrimaryClip(ClipData.newPlainText("inkflow-strokes", EncryptionService.serializeStrokes(selectedStrokes).toString()))` (reuse `EncryptionService.serializeStrokes` from `NoteRepository.saveStrokesForPage`). Paste offset = center viewport Y `centerViewportY/pageStride` (like `attachVoiceRecording` `EditorScreen.kt:1041`).
   - Cut = Copy + Delete single undo step; paste produces fresh UUIDs. Scrub policy: transient clipboard content regarded as own-copy; lock scrubs unconditionally regardless of guard window.
   - Source-pin: all strokes `saveStrokesForPage` still goes through `VaultWriteGate.requireKey` + `encryptField` (phase-49).

2. **Duplicate (single-page):** `handleStrokesChange(strokes + selectedStrokes.map{ it.copy(id=UUID.randomUUID(), points= offsetCopy(dx=12/zoom, dy=12/zoom)) })` — reuse `onDuplicateLayer` UUID pattern. Single undo entry.

3. **Delete:** `handleStrokesChange(strokes.filterNot{ it.id in selectedIds })`. Clear selection after.

4. **Shape-aware hit & move:**
   - RECTANGLE: `containsInRotatedRect(hit, bounds, rotation)` pattern (reuse `CanvasItemRotationMath`); else axis-aligned `minX/maxX/minY/maxY` from `points`.
   - ELLIPSE: `((x-cx)/rx)²+((y-cy)/ry)² <= 1+margin` (reuse `InkToShapeGeometry.ellipseFitDeviation` spirit).
   - LINE/ARROW: projection + `perpendicularDeviation < threshold`.
   - Translate: selected strokes `points.map{ p.copy(x+=dx/zoom,y+=dy/zoom)}` + `start/end` same, `pdfPage= getPageFromCanvasY(newY)` for cross-page drag. On `onDragEnd` emit `newStrokes` via single `handleStrokesChange` → one `emittedList` call, one autosave debounced, one undo entry.
   - `rotationDegrees` on Stroke is **deferred** — keep axis-aligned v1; document that rotated-shape support needs a `Stroke.rotationDegrees` field + approved migration per AGENTS.md major-arch-change rule.

5. **Handles (selection chrome):** overlay on `selectedBounds` rect — 1 translate drag surface + optional corner ResizeHandles + RotationHandle reusing `DraggableMediaEmbedCard` handles but **visible at rest** (`ResizeHandleVisibilityPolicy` override `visibleAtRest=true`, alpha 1) to fix phase-193 discoverability complaint. Handles reuse `CanvasItemRotationMath`.

## Constraints
- No schema migration this phase, no workflow edits, no new heavy deps. Honor low-end `LayerBitmapLruCache` reuse — no per-move bitmap recreate beyond normal commit path. Undo single coalesced entry per op.
- DoD: `assembleDebug` + `testDebugUnitTest` green; new tests: clipboard round-trip, duplicate offset, hit per shape type, translate + cross-page pdfPage recompute, guard that `saveStrokesForPage` encryption gate still enforced. REPORT.md shows lasso→copy→paste + lasso→move walkthrough with file:line.
