# Phase 243 — Fix Markdown editor duplication + remove two-finger canvas page rotation

Status: **DONE**
Date: 2026-08-30

Two independent pieces of work, both user-requested:

1. **Fix the live-editor text-duplication defect** (every keystroke could append
   a stale duplicate paragraph when it re-split the edited block).
2. **Remove the two-finger "canvas twist" page-rotation feature wholesale** —
   no dead code, no dangling prefs, no leftover settings UI.

## Part 1 — Markdown editor duplication

### Root cause

The duplication lived in the EDITOR, not the tokenizer. `HybridMarkdownEditor`
attached **one block at a time** to the underlying document:

- The editor keeps `doc` (the nested `MarkdownBlockTokenizer.MarkdownDocument`)
  plus `editingText` = the source of a single block window.
- On each keystroke `emitBlockEdit(prevRaw, blockIndex, newRaw)` called
  `MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newRaw)`, replacing by
  the **stale static index** captured at attach time.

Once a keystroke **re-split the edited window** (e.g. a paragraph breaks into
two blocks, or an empty run pushed over the window), the stored `blockIndex`
pointed at the WRONG slot. `replaceBlock` then replaced a neighbouring block
and the editor's own `joinBlockSources`/content push-back resurrected the old
text — visibly "duplicated" paragraphs that also ballooned the persisted note.

### Fix — anchored source-span replacement

`MarkdownBlockTokenizer.replaceContentRun(doc, startByte, endByte, newSource)`
replaces a **contiguous byte run of the live document source** and re-tokenizes
**only the affected window**:

- `lineIndexAtByte` maps the byte offset back to its current line, so the run
  is anchored to what actually exists TODAY (never a stale index).
- The window grows back across blank runs so a paragraph break / grow-apart
  merge reclassifies the correct blocks (verified exact against a fresh
  full-document re-tokenize: 19,004 random-doc probes, 0 mismatches).
- `beyond` (the untouched tail) is filtered on the SHIFTED start line
  (`b.startLine + delta > windowEnd`) so post-window blocks never get dropped
  or duplicated when the window length changes.
- `incrementalCandidates` reclassifies only the window's blocks; every other
  candidate row is preserved.

`HybridMarkdownEditor` now:

- captures `editingAnchorByte` at `onEdit` time;
- `emitBlockEdit(prevRaw, blockIndex, newRaw)` anchors via
  `doc.content.indexOf(prevRaw, editingAnchorByte)` and, as a fallback when the
  exact previous raw is not found (IME/format churn), degrades to the pinned
  block-index `replaceBlock` path the Phase-151 perf test still pins;
- `RawBlockEditor.onValueChange` passes the **field-state text** as `prev` so
  an IME burst ("abc" typed as a single composition) replaces the whole burst,
  not just its first glyph.

### Tests

`Phase243MarkdownEditorDuplicationTest` (7 tests, all green): byte-exact
expected content for each keystroke; `EditorSim` mirror of the editor's runtime
state asserts `doc.content never regresses` and `assertConsistent` re-tokenizes
the whole document fresh (`MarkdownBlockTokenizer.tokenize(value)`) and checks
blocks + candidates match; `joinBlockSources' content round-trip` holds. The
two temporary probe files from the build-up (`Phase243ScratchProbeTest`,
`Phase243ScratchDebugTest`) were deleted — the final suite is the regression
net, not ad-hoc probes.

## Part 2 — canvas page rotation removed

Removed **every** surface of the Phase-223/240 "canvas twist" feature:

- `services/CanvasRotationPolicy.kt` — deleted (`sanitize`/`rotatePoint`/
  `accumulate`/`intentionalRotationDelta`/gates all gone).
- `SettingsManager` — deleted `canvasTwistEnabled` (pref `canvas_twist_enabled`)
  and `canvasRotationDegreesForPage`/`setPageCanvasRotationDegrees` (pref
  `canvas_rotation_<page>`). No DB schema involved (prefs only).
- `AnnotationCanvas`:
  - deleted `rotationDegrees`/`onRotationDegreesChanged`/`canvasTwistEnabled`
    parameters, `internalRotationDegrees`, the hoisted re-sync `LaunchedEffect`,
    and the twist math inside the two-finger handler (which is now classic
    pinch-zoom + pan again — it neither computes nor applies rotation, so a
    radial pinch cannot drift the page);
  - deleted `rotationZ` from the main canvas, `LiveStrokePreview` and
    `StrokeSelectionOverlay` graphicsLayers;
  - `StrokeSelectionOverlay` no longer takes `canvasRotationDegrees`;
    `SelectionCornerHandle` no longer un-rotates drag deltas (`/zoom` directly).
  - **Kept**: per-note/per-embed `note.rotationDegrees` /
    `embed.rotationDegrees` (`AnnotationCanvas.kt` embed/sticker draw + drag
    handlers) and the per-SELECTION transform feature (`rotateSelectedStrokes`,
    `SelectionRotationHandle`, `CanvasItemRotationMath`) — those are unrelated
    item transforms, not canvas page rotation.
- `EditorScreen`:
  - deleted `canvasTwistEnabled` + `rotationDegrees` state, the
    `ToolbarAlso` "Canvas Twist" row and the "Rotated N° / Reset" row, the
    `rotationDegrees`/`onRotationDegreesChanged`/`canvasTwistEnabled`/
    `onCanvasTwistToggle`/`onRotationReset` params, and the rotation line in
    `onResetZoomPan` (which now resets only zoom + pan).
  - **Kept** selection transform (`scaleSelectedStrokes`/`rotateSelectedStrokes`)
    and the sticker embed's `rotationDegrees = 0f`.
- Tests: `Phase240RotationGateTest` (11 tests) deleted; the 5
  `CanvasRotationPolicy` tests in `Phase223PerspectiveGridPolicyTest` removed
  (import + class-level comment updated); Paparazzi `rotatedCanvas` snapshot in
  `Phase223DraftingGridSnapshotTest` removed (it documented the mechanism).
- Docs: `docs/ARCHITECTURE.md` (living map) updated — services table entry
  dropped `CanvasRotationPolicy.kt`, the phase-223 "Rotate" bullet and the
  phase-240 gating paragraph now note the phase-243 removal, and the
  phase-226 review-fix text describing corner-handle un-rotation was rewritten
  to the rotation-free behavior.

### Verification

- `gradle :app:compileDebugKotlin` — clean.
- `gradle testDebugUnitTest` — **3556 tests, 0 failures** (down 17: −11
  `Phase240RotationGateTest`, −5 `Phase223PerspectiveGridPolicyTest` rotation
  tests, −1 Paparazzi `rotatedCanvas`). The one pre-existing warning
  (`DownloadablePluginInstallerTest.kt:156` null-safety) is untouched.
- `gradle assembleDebug` — green.
- `gradle lintDebug` — 0 errors.
- No new dependencies, `.github/workflows/` untouched, base-APK-size rule
  intact, `allowBackup=false` untouched, no DB schema/migration.