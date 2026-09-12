# Phase 257 — Undo resurrect + unkeyed page state + load-vs-save race

## Goal
Undo re-adds removed stroke; drawing before async load wipes page (`emptyList()+newStroke` overwrites DB); reopen races dispose flush and inits empty.

## Evidence
- `AnnotationCanvas.kt:610-614`: `LaunchedEffect(filteredStrokes){ clear(); addAll() }` blind overwrite. `074341b/d778cf9` added `pendingLocal` + `lastSeenIds` + `remember(pdfPageFilter,isContinuousMode){ addAll(filtered) }` — VERIFY present, else implement.
- `EditorScreen.kt:244-246,543-544,852`: `strokes/stickyNotes/mediaEmbeds/undoStack/redoStack/isInitialLoadComplete` were `remember{}` unkeyed → stale across pages. Fix: `remember(page.id)`. `handleStrokesChange` must `if(!isInitialLoadComplete) return`. `layers:560` also needs `remember(page.id)` (else cross-page layer bleed).
- `NoteRepository.kt:1474` saves hold `pageSaveLocks[pageId]` but `NoteflowViewModel.kt:4479 loadEditorCanvasPage` did 4 unlocked reads. Fix (`d05b4cb`): `NoteRepository.loadEditorCanvasPage(): RepositoryCanvasData` wrapping all reads in `lock.withLock`; ViewModel delegates.

## Files to change
- `ui/components/AnnotationCanvas.kt`, `ui/screens/EditorScreen.kt`, `data/repository/NoteRepository.kt`, `ui/viewmodel/NoteflowViewModel.kt`

## Tests
- `Phase257UndoPageStateTest`: undo does not resurrect; unkeyed states carry page.id key (source pins); load holds page mutex (source pin).

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
