# Phase 242 — Fix Dots Disappear on Page Reopen + Stroke Persistence

## Goal
Fix the "close page, even dots are not visible on reopen" bug — strokes drawn are not being persisted to the database, or the page on reopen doesn't load them correctly.

## Context — Verified Root Cause (from screenshots)

The user reports:
- Drawing stroke shows only dots (not the full stroke) — separate bug, see phase-240
- After closing the page and reopening, the dots are not visible at all — the strokes are not being persisted to the database OR are not being loaded back correctly

## Bug Analysis

### Likely causes (ordered by probability):

1. **Strokes not flushed on page close/navigation away**
   - The user draws a few points, navigates away from EditorScreen (closes the page)
   - The active stroke's points are in `activePoints` but never committed (no `onDragEnd` was called)
   - The partial stroke is lost
   - Only strokes that went through the full `onDrag` → `onDragEnd` → `handleStrokesChange` → `triggerAutoSave` flow are persisted

2. **Strokes committed but autosave not triggered**
   - The 1s debounced autosave fires 1s after last change
   - If the user navigates away within 1s, the autosave might be cancelled before it fires
   - Phase 206 changed this to a bounded delay, but if the user closes the page very quickly, the autosave might be lost

3. **Page reopen doesn't trigger data reload**
   - The EditorScreen uses `viewModel.strokes` which is a `StateFlow`
   - When the user closes and reopens the page, the StateFlow should be re-collected
   - But if the page key doesn't change, the StateFlow might still hold the OLD data (which was wiped when the user left)
   - This is a Compose `key()` or `LaunchedEffect` issue

4. **DB query filters out the new strokes**
   - When the page reopens, the new strokes are queried from the DB
   - If the query has a wrong filter (e.g. wrong pageId, or the strokes are tagged as "in progress" and filtered out), the new strokes won't show
   - This is the most likely cause if the DB does have the strokes but they're not being queried

5. **DecryptedPageCache is stale**
   - Phase 207's `DecryptedPageCache` caches decrypted pages
   - If the cache is keyed by a stale page ID, the reopen might hit the cache and skip the fresh DB query

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Find the `DisposableEffect` that cleans up on leave
- Ensure `activePoints` are flushed (committed) when the user navigates away
- Ensure the page `key` is correct so the StateFlow is re-collected on reopen
- If using `onDispose`, add a `viewModel.flushPendingSaves()` call

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- Add a `flushPendingSaves()` method that forces the autosave to fire immediately
- Call this from the EditorScreen's `onDispose` or `DisposableEffect`
- This ensures all committed strokes are persisted before the page closes

### 3. `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`
- Check `getStrokesForPage` query
- Ensure it returns all strokes for the page (no filter that hides new strokes)
- Check that the strokes are sorted by timestamp/insertion order so they appear in the right order

### 4. `app/src/main/kotlin/com/authorss81/noteflow/services/StrokeGeometryPolicy.kt`
- Check that new strokes aren't being flagged as "geometry over budget" and filtered
- Phase 50's B2-DOS-01 cap is at 20K points per stroke — shouldn't apply to short draws

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- Must not break existing tests
- Must work with the existing encryption pipeline (Phase 62 MasterPasswordCredential)

## DoD
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:testDebugUnitTest` 3420+ tests green
- Manual test: draw stroke, close page, reopen page → stroke is visible
- Manual test: draw multiple strokes in quick succession → all persist
- Manual test: navigate away from EditorScreen mid-draw → pending stroke is flushed
- `workspace/phase-242/REPORT.md` with file:line evidence
