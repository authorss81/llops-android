# Phase 242 — Fix Dots Disappear on Page Reopen + Stroke Persistence

Status: **DONE**
Date: 2026-08-29

## Root cause

Two navigate-away data-loss paths could drop freshly-drawn ink so that it never
reached the database, and therefore never appeared on page reopen:

1. **In-progress (uncommitted) stroke dropped on navigate-away.** The persistent
   stroke list is only mutated when `detectDragGestures.onDragEnd` fires and the
   canvas emits the committed list via `onStrokesChanged`. If the page leaves
   composition *before* `onDragEnd` — closing while the pointer is still down, or
   a swipe that is classified as a navigation and degrades to
   `onDragCancel` (which in phase-206 only CLEARS `activePoints` without
   committing) — the partial ink lives only in the ephemeral `activePoints`
   list. It never reaches the editor's `strokes`, so the editor's dispose flush
   (which persists only the COMMITTED list) has nothing to save. On reopen the
   dots the user watched themselves draw are gone.

2. **Back-path flush ordering.** The system-back (`BackHandler`) and top-bar-back
   paths cancelled the pending 1s debounced autosave and then called
   `flushEditorPageSave` *without awaiting the cancellation*. If a debounced
   write had already started, it could still land *after* the navigated-away
   flush and, being the last writer, persist a STALE snapshot (older strokes).

The load path (`NoteRepository.getStrokesForPage` → `StrokeDao.getStrokesForPageBounded`)
was verified correct: it returns every stroke row for the page (only the
B2-DOS-01 stored-size cap filters, never an in-progress/flag/recency predicate),
ordered by `ROWID` (insertion order), and materialises a `Stroke` for every
returned row — so once a stroke row exists, it renders on reopen. `saveStrokesForPage`
writes (insert `@Upsert`) every changed stroke, so the only missing link was that
the in-progress stroke never became a `strokes` entry to be flushed.

## Fixes

### 1. `AnnotationCanvas.kt` — commit the in-progress stroke on dispose
Added a disposal-scoped `DisposableEffect(Unit)` (`AnnotationCanvas.kt:1249-1305`)
that, if `activePoints` is non-empty and the current tool is a freehand
non-LASER tool, builds a `Stroke` from exactly the live on-screen ink (already
world-space smoothed samples — no re-simplify/snap pass) and emits it through
the SAME `onStrokesChanged` → `CanvasCommitListPolicy.emittedList` channel the
live drag-end commit uses, so it reaches `handleStrokesChange` → `strokes` and
the editor's owning dispose-flush persists it.

- All brush parameters (tool/colour/width/color-mode/seed/gradient/layer/debug/
  symmetry/continuous/paged) are read through `rememberUpdatedState` wrappers
  (`AnnotationCanvas.kt:1238-1248`) so the dispose uses the CURRENT values, not
  the first-composition capture.
- `ink.isNotEmpty()` doubles as the duplicate guard: `onDragEnd` clears
  `activePoints`, so an already-committed stroke leaves an empty list and no
  second stroke is emitted.
- LASER (a fleeting pointer highlight) is excluded; symmetry twin is baked via
  `SymmetryCommitPolicy.bakedTwin` (axis approximated with `pageWidthPx` — the
  primary ink is never lost).
- Ordering safety: Compose disposes child effects before the parent's, so this
  runs before the editor's `DisposableEffect(page.id)` flush, and the emitted
  list updates the shared `strokes` state that the flush reads at disposal time.

### 2. `NoteflowViewModel.kt` — ordered `flushPendingSaves`
Added `flushPendingSaves(...)` (`NoteflowViewModel.kt:4022`), the single
navigate-away flush: it CANCELS and AWAITS the pending debounce, then persists
the newest snapshot through the lock-safe gate. `disposeEditorPageFlush(...)`
now delegates to it (single source of truth), preserving the existing public
name + pinned internals.

### 3. `EditorScreen.kt` — every back path uses the ordered flush
- `BackHandler` (`EditorScreen.kt:1729-1737`)
- Top-bar back button (`EditorScreen.kt:1756-1769`)

both now call `viewModel.flushPendingSaves(page.id, strokes, stickyNotes,
mediaEmbeds, layers, saveJob)` and null the `saveJob` slot, instead of the old
non-awaited `saveJob?.cancel() + flushEditorPageSave(...)`. The dispose path
keeps `viewModel.disposeEditorPageFlush(...)` (unchanged, source-pinned by the
phase-73 test). Result: closing the page inside the 1s debounce window — or
mid-gesture — never loses committed strokes and no stale snapshot lands last.

## Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
  — dispose-commit of the in-progress stroke + `rememberUpdatedState` wrappers.
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
  — new `flushPendingSaves`; `disposeEditorPageFlush` delegates to it.
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
  — BackHandler + top-bar back route through the ordered flush.
- `app/src/test/java/com/authorss81/noteflow/Phase242StrokeDisposePersistenceTest.kt`
  — NEW, 7 tests (ordered-flush behavioural model + source-level wiring pins for
  the navigate-away paths and the canvas dispose-commit).

No schema change, no new dependencies, `.github/workflows/` untouched,
base-APK-size rule intact.

## Verification

- `gradle :app:assembleDebug` — BUILD SUCCESSFUL.
- `gradle :app:assembleRelease` (fail-closed, with a throwaway local keystore via
  `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`) — BUILD
  SUCCESSFUL; the throwaway keystore was deleted immediately after.
- `gradle :app:testDebugUnitTest` — **3566 tests / 0 failures / 0 errors**
  (phase-240 baseline 3556 + 7 new `Phase242StrokeDisposePersistenceTest`); the
  prior `Phase148UiFailureTextScrubTest` UNC-path flake was green this run.

## Manual test notes (device)

- Draw a stroke, close the page, reopen → the stroke is visible (persisted).
- Draw multiple strokes in quick succession → all persist (debounce + dispose
  flush).
- Navigate away from EditorScreen mid-draw (while the pointer is down) → the
  in-progress stroke is committed and persisted by the dispose-commit.
