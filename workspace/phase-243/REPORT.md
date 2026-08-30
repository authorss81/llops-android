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

`Phase243MarkdownEditorDuplicationTest` (8 tests, all green, after review-fix): byte-exact
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
---

## Review fixes (2026-08-30)

Addressed the review findings on the committed phase-243 work; no behavior
change to the core duplication algorithm or the rotation removal (both re-verified).

### F1 — `Phase243MarkdownEditorDuplicationTest` over-strict invariants

The `assertInvariants`/`assertConsistent` helpers asserted that blocks "cover
every line" and that `joinBlockSources` round-trips the content byte-for-byte.
That is stronger than the tokenizer actually guarantees: for content ending in a
trailing newline (e.g. `a\n\nb\n`), the trailing empty line is owned by NO block
(`MarkdownBlockTokenizer.blocksFromLines` grows the first block of a region
backward but never forward over a trailing blank), so the join drops it and the
over-strict claim would fail on such input. The helpers were relaxed to the real
semantics:

- `assertInvariants` now asserts contiguity/non-overlap for the covered run and
  that any uncovered TAIL consists solely of blank lines (with a diagnostic);
- `assertConsistent`'s round-trip check asserts the block-source join reproduces
  the covered lines exactly, and separately that the content is exactly that
  join plus `"\n"` × the number of trailing blank lines (`"\n".repeat(count)`).

A new regression test, `edits near a trailing blank run stay consistent`,
edits a block adjacent to a trailing `\n` and asserts the relaxed invariants
hold through successive keystrokes (this content would have thrown under the
previous helpers).

### F2 — guarded `emitBlockEdit` fallback (can't write into an unrelated block)

`HybridMarkdownEditor.emitBlockEdit`'s fallback (reached only when the
TEXT-anchored replacement cannot find `prevRaw`, i.e. an external replace or a
checkbox toggle elsewhere shifted earlier bytes) previously called
`MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newRaw)` with the
composition-time index. If that index had drifted to a neighbouring block the
edit would rewrite an unrelated block. The fallback now re-anchors by line:
`lineIndexAtByte(doc.lines, editingAnchorByte)` then
`doc.blocks.indexOfFirst { anchorLine in it.startLine..it.endLine }` and
replaces only that block; if no block contains the anchor line it drops the edit
(the parent `value` re-syncs `doc` via the `LaunchedEffect`). This preserves the
Phase-151 pinned incremental `replaceBlock` path while never corrupting a
neighbour. A small `lineIndexAtByte` helper was added to the editor.

### F3 — Phase151 source-pin updated for the new fallback

`Phase151MarkdownMainThreadPerfTest` source-pins the keystroke path. It asserted
the now-stale literal `MarkdownBlockTokenizer.replaceBlock(doc, blockIndex,
newRaw)`; updated to pin the PRIMARY incremental `replaceContentRun(doc, at,
endByte, newRaw)` call and the incremental `replaceBlock(doc, …)` fallback
(variable-agnostic), so the no-full-document-re-tokenize guarantee still holds.

### Verification (this round)

- `gradle :app:testDebugUnitTest` — **3557 green / 0 failures** (3556 + 1 new
  `Phase243MarkdownEditorDuplicationTest` test). Full suite run twice; the
  pre-existing `Phase151MarkdownMainThreadPerfTest` near-linear-scaling timing
  flake failed once under the full-suite run and passed in isolation (known
  timing flake, not related to these changes).
- `gradle :app:assembleDebug` — green.
- `gradle :app:lintDebug` — 0 errors.
- No schema change, no new deps, `.github/workflows/` untouched.
