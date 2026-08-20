# Phase 181: Re-fix — last-used notebook must open after app start AND after export/home return [DONE]

You are working on **InkFlow/Noteflow**. Phase-168 (DONE) claimed "app must always open
the last-used notebook", but USER REPORTS it still does not: after opening the app, or
after exporting and returning to the home page, the app does NOT open the last-used
notebook. This is a REGRESSION re-fix phase.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, and `workspace/phase-168/REPORT.md`
first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-181 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Reproduce + inventory (commit it)
- Trace the CURRENT cold-start restore in `NoteflowViewModel.kt`:
  `initializeDataCore` (`:1866-2011` area) reads `settings.lastNotebookId`
  (`:1970`) then `activeNotebookId` fallback; `selectNotebook` (`:2130`) writes
  `activeNotebookId` (`:2133`) and `lastNotebookId` (`:2136`); `:4768` re-stamps
  `lastNotebookId` on selection changes.
- Identify the EXPORT-RETURN path: after `exportBackup` / `exportVaultToZip` /
  `exportObsidianVaultZip` / `exportNoteToHtml` finishes in `ImportExportService.kt`
  (`:1390`, `:2931`, `:3197`, `:3319`), what happens to `_selectedNotebook` /
  `lastNotebookId`? Does the export completion reset/recreate the notebook list or
  navigate HomeScreen in a way that loses the selection?
- Check `HomeScreen.kt` — is the selected notebook rendered from
  `viewModel.selectedNotebook` (StateFlow) or from a local `remember` snapshot that
  dies on navigation/config change?
- COMMIT this step with the trace.

## Step 2 - Fix the regression
- Ensure `lastNotebookId` is the SINGLE source of truth for cold start AND for
  post-export Home return. Wherever HomeScreen recomposes after export, it must
  re-bind to the ViewModel's selected-notebook state (never a dead local copy).
- If export completion rebuilds the notebook list / closes the DB handle, restore
  the selection from `lastNotebookId` after the operation, and confirm
  `observeSections` re-arms the correct section (phase-168:5 root cause).
- Keep the legacy `activeNotebookId` fallback for pre-168 installs.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Add pure-JVM tests for the notebook-restore policy: cold start with a valid
  `lastNotebookId`, stale/deleted id falls back to `activeNotebookId` then default,
  and an export-completion re-selection keeps the pre-export notebook.

## Definition of done
- After cold start AND after every export + Home return, the last-used notebook is
  selected and its pages visible (file:line evidence).
- `workspace/phase-181/REPORT.md`: root-cause of the regression, fix, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Never write plaintext while locked.