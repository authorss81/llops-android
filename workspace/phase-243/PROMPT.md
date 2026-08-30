# Phase 243 — Fix Markdown Editor Text Duplication + Remove Page Rotation Feature

## Goal
Fix two user-reported issues from screenshots:
1. **Markdown text duplicates** — typing `*hi*` shows `_bu - hi` multiple times in the editor (the raw source is rendered MULTIPLE times, and the block split causes content to appear duplicated)
2. **Remove page rotation** — the user explicitly said "dlette page rotation it is not needed" (delete page rotation). The two-finger twist rotation feature in the canvas should be removed.

## Context — Verified Root Cause (from screenshots)

### Bug 1: Markdown text duplication
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/components/markdown/HybridMarkdownEditor.kt`
**Symptom (from screenshot):** User typed `hi` and the screen shows `_bu - hi` appearing MULTIPLE times in the editor, with `* hi *` shown below it as rendered preview.

**Root cause:** The editor at line 130-133 calls `emitBlockEdit(index, newRaw)` on every keystroke, which:
1. Sets `dirty = true` (line 104)
2. Calls `doc = MarkdownBlockTokenizer.replaceBlock(doc, index, newRaw)` (line 105) — re-tokenizes the entire document on every keystroke
3. Calls `onValueChange(doc.content)` (line 106) — emits the full content

But the rendered blocks below still show the OLD content. The `LaunchedEffect(value)` at line 93 checks `if (!dirty && value != doc.content)` — if `dirty` is true, it skips the re-tokenize. BUT the parent (EditorScreen) is also receiving the updated `value` via `onValueChange` and updating its own state, which then causes a re-render. The re-render re-runs the LaunchedEffect with the new value but the `dirty` is still true so nothing happens. HOWEVER, the next call to `RawBlockEditor` in the forEachIndexed at line 125 gets a new value from the `editingText` state but the state's update flow has a race condition.

The actual bug: The `editingText` local state in the composable at line 87 is set on every keystroke via the OutlinedTextField's onValueChange. But the `value` parameter coming INTO the composable (line 70) is the PARENT's value, which is `doc.content`. When the user types, the OutlinedTextField updates `editingText`, calls `onValueChange` which is `emitBlockEdit` which updates `doc` and calls `onValueChange(doc.content)`. The parent updates its state and re-passes it down. The `LaunchedEffect(value)` at line 93 sees the same value, dirty is true, so it does nothing. BUT there's a race: the `editingText` local state can become stale because the parent might pass a different value (the full document with the raw block) and the editor's local state `editingText` only updates via the OutlinedTextField.

Multiple `_bu` appearing — this is because the block tokenizer is including the raw block in its rendered output OR there are multiple blocks being created when the user types `*hi*` and the tokenizer creates:
- Block 1: empty paragraph
- Block 2: the typed text with `*` markers
- The `*` at the start of `*hi*` gets interpreted as italic markdown, which when rendered shows the `_` underline

The fix: The `MarkdownBlockTokenizer.replaceBlock` should NOT re-tokenize the entire document. It should only update the specific block. OR the `onValueChange` should pass only the affected block's content, not the whole document.

### Bug 2: Remove page rotation feature
**File:** `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
**Symptom:** User said "dlette page rotation it is not needed" — they want the two-finger twist rotation feature removed.

**Current code:**
- `EditorScreen.kt:572` `var canvasTwistEnabled by remember { mutableStateOf(viewModel.settings.canvasTwistEnabled) }`
- `EditorScreen.kt:1353-1359` rotation code that calls `CanvasRotationPolicy.accumulate` when 2 fingers are down
- `EditorScreen.kt:2711, 3131, 3182, 3196` applies `rotationZ = internalRotationDegrees` to the canvas
- `AnnotationCanvas.kt:1323-1364` the 2-finger handler that detects rotation

**Fix:** Remove all rotation code:
1. Remove `canvasTwistEnabled` from SettingsManager (or default to false and hide the UI)
2. Remove the rotation block from AnnotationCanvas.kt:1353-1359
3. Remove `rotationZ` from all `Modifier.graphicsLayer { ... }` calls
4. Remove the rotation settings UI from `Canvas & Paper Options` sheet
5. Remove `CanvasRotationPolicy` and its tests
6. Keep `phase-238`'s `WindowSizeClass` adaptive layout (that was a separate fix for responsive UI)

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/markdown/HybridMarkdownEditor.kt`
- Fix the block tokenizer to NOT emit multiple blocks when user types `*hi*`
- Fix the `emitBlockEdit` function to only update the specific block, not re-tokenize the entire document
- The fix: keep `doc` stable, only update the specific block's source
- The `MarkdownBlockTokenizer.replaceBlock` should take the EXISTING doc and just replace the specific block's source, preserving the other blocks' structure

### 2. `app/src/main/kotlin/com/authorss81/noteflow/services/MarkdownBlockTokenizer.kt`
- Fix `replaceBlock` to be an in-place mutation of the existing document
- Don't re-tokenize the whole document

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Remove `canvasTwistEnabled` state and setting
- Remove `internalRotationDegrees` state
- Remove `rotationDegrees` from settings
- Remove `onRotationDegreesChanged` callback
- Remove the rotation handler in `AnnotationCanvas.kt:1353-1359`

### 4. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Remove the rotation calculation block
- Keep the zoom and pan (no rotation)

### 5. `app/src/main/kotlin/com/authorss81/noteflow/services/CanvasRotationPolicy.kt`
- Delete the file entirely

### 6. `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
- Remove `canvasTwistEnabled`, `canvasRotationDegreesForPage` keys
- Migration: existing user data — just ignore these keys

### 7. Remove the rotation toggle from `Canvas & Paper Options` sheet (EditorScreen.kt around line 5380-5390)

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Must not break existing tests (run `gradle testDebugUnitTest`)
- The remaining `Modifier.graphicsLayer` must still compile

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:testDebugUnitTest` 3420+ tests green (some rotation tests will need to be deleted)
- `gradle :app:lintDebug` 0 errors
- Manual test: typing `*hi*` in markdown editor shows text only ONCE, no `_bu` duplication
- Manual test: 2-finger gesture no longer rotates the canvas
- `workspace/phase-243/REPORT.md` with file:line evidence
