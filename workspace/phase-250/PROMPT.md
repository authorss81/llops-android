# Phase 250 — Data-loss criticals: stale autosave + lock-during-load wipes page

## Goal
Close the **CRITICAL** data-loss paths from `AUDIT_2026-08-30.md` and the 5/5 audit:

1. **Stale autosave can land AFTER a newer `flushPendingSaves`**, overwriting the newer flush's write. The `triggerAutoSave` `delay(1000)` debounce + the fire-and-forget `viewModel.autosaveStrokes` write can complete in a non-cancellable state, then the user's `flushPendingSaves` writes the newest snapshot, and the older coroutine wakes up and persists its stale list LAST. Result: the most recent stroke disappears on reopen.
2. **Lock during page load wipes the page**: `EditorScreen.kt:866-884` `LaunchedEffect(page.id) { val data = viewModel.loadEditorCanvasPage(page.id); if (viewModel.authenticated.value) { strokes = data.strokes; ... }; isInitialLoadComplete = true }`. If `lock()` fires between `loadEditorCanvasPage` returning and `isInitialLoadComplete = true`, the page renders empty, and any back-press before unlock triggers `flushPendingSaves(strokes=emptyList(), ...)` which **deletes all strokes from the DB**.

## Context — verified at `2709453`

### Bug 1: stale autosave after flush
- `EditorScreen.kt:1011-1029`:
  ```kotlin
  fun triggerAutoSave(newStrokes: List<Stroke>) {
      if (!isInitialLoadComplete) return
      saveJob?.cancel()
      saveJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
          delay(1000)
          viewModel.autosaveStrokes(page.id, newStrokes, stickyNotes, mediaEmbeds, layers)
      }
  }
  ```
  The `viewModel.autosaveStrokes` call enters `persistEditorSaveSuspend` → `VaultWriteGate.persistNow` → `repo.saveStrokesForPage`. None of these check `isActive` between the gate check and the actual write.
- `NoteflowViewModel.kt:4026-4043` `flushPendingSaves` does `pendingDebounce?.cancel(); pendingDebounce?.join()`. The `join()` returns as soon as the cancel completes; the suspended `persistEditorSaveSuspend` continues to commit a STALE snapshot AFTER the flush has already persisted the newest one.

The fix: introduce a monotonically increasing `editorSaveGeneration: Int` (in `NoteflowViewModel`). Every call to `triggerAutoSave` and `flushPendingSaves` captures the current generation and stamps it on the coroutine. Inside `persistEditorSaveSuspend` (or at the top of `repo.saveStrokesForPage`), check the generation: if the stamped generation is not the latest, the write is skipped. This guarantees that a stale autosave that was cancelled AFTER `delay(1000)` elapsed cannot commit.

A simpler approach: have `flushPendingSaves` NOT just cancel+join the debounce, but also `withContext(NonCancellable)` wrap the body (this is phase-249 bug 2) AND bump a generation token that any in-flight `autosaveStrokes` checks at the start of `saveStrokesForPage` and skips if bumped. Both fixes together close the race.

### Bug 2: lock during load wipes page
- `EditorScreen.kt:866-884`:
  ```kotlin
  LaunchedEffect(page.id) {
      val data = viewModel.loadEditorCanvasPage(page.id)
      if (viewModel.authenticated.value) {
          strokes = data.strokes
          layers = data.layers
          stickyNotes = data.stickyNotes
          mediaEmbeds = data.mediaEmbeds
      }
      isInitialLoadComplete = true
  }
  ```
  The `if (authenticated.value)` check happens once. If `lock()` fires AFTER the `loadEditorCanvasPage` returns but BEFORE `isInitialLoadComplete = true`, the `if` branch is skipped, `strokes/layers/...` remain at their previous values (often `emptyList()` for a fresh page), and `isInitialLoadComplete = true` is set. The back-press in this window calls `flushPendingSaves(strokes = emptyList())` → DB rows deleted.

The fix: re-check `viewModel.authenticated.value` IMMEDIATELY before `isInitialLoadComplete = true`. If still authenticated, do the assignment. If not, treat the page as "loaded but locked" — keep `isInitialLoadComplete = false` until unlock so `triggerAutoSave`/`flushPendingSaves` are no-ops. Use a separate `isEditorReady` flag and gate the back-press / autosave on it.

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/data/NoteflowViewModel.kt`
- Add `@Volatile var editorSaveGeneration: Int = 0` (or an `AtomicInteger`).
- `triggerAutoSave` and `flushPendingSaves` capture `val myGen = ++editorSaveGeneration` and pass `generation = myGen` to the autosave / flush calls.
- `saveStrokesForPage` (or a thin wrapper) checks the generation: if the stamped gen is not the latest, log + return without writing. Document the contract.
- Wrap `flushPendingSaves` body in `withContext(NonCancellable) { ... }` (already in phase-249; reuse).

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- `LaunchedEffect(page.id)`: re-check `viewModel.authenticated.value` at the assignment moment; if false, set `isInitialLoadComplete = false` and a `loadFailedDueToLock = true` flag; the `BackHandler` / top-bar back paths check `!loadFailedDueToLock` before calling `flushPendingSaves`.
- On unlock, the `LaunchedEffect(page.id)` is re-keyed (it depends on `viewModel.authenticated.value` already) — re-run the load.

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase250DataLossCriticalsTest.kt` (pure JVM, 5+ tests)
- Generation-token semantic test: a coroutine stamping gen 1 that completes AFTER gen 2's `flushPendingSaves` is no-op'd (model the `repo.saveStrokesForPage` generation check in pure JVM).
- `EditorScreen.kt:866-884` source pin: the assignment is inside a `if (viewModel.authenticated.value) { ... }` block, AND `isInitialLoadComplete = true` is INSIDE the same `if` (not after).
- Source pin: `BackHandler { ... }` and the top-bar back path check `!loadFailedDueToLock` BEFORE calling `viewModel.flushPendingSaves(...)`.
- Source pin: `NoteflowViewModel.flushPendingSaves` body wrapped in `withContext(NonCancellable) { ... }` (already in phase-249; pin for completeness).
- Source pin: `editorSaveGeneration` is bumped BEFORE every `flushPendingSaves` and BEFORE every `triggerAutoSave`.

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- The generation-token check is at the start of the WRITE (inside `repo.saveStrokesForPage` or its entry), not at the UI layer — preserves the contract for plugin / WebDAV paths.
- The `loadFailedDueToLock` flag is session-scoped (no persistence); on next unlock the page reloads.
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: rapid draw → immediate back-press does not lose ink (no "dots disappearing on page reopen" regression)
- Manual: draw → back-press 800ms later (within debounce window) does not lose ink
- Manual: open a page → lock the vault → unlock → the page reloads cleanly
- Manual: open a page → lock the vault → back-press while locked → no `flushPendingSaves` fires (no DB write)
- `workspace/phase-250/REPORT.md` with file:line evidence
