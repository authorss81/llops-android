# Phase 168: App must always open the last-used notebook [NOT STARTED]

You are working on **InkFlow/Noteflow**. User feedback: when the app opens it
should always open the last notebook the user was using (it currently doesn't).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- `SettingsManager.kt` already has `lastNotebookId` (L79-81, pref key
  `last_notebook_id`) and `activeNotebookId` (L67).
- `NoteflowViewModel` exposes `selectedNotebook` StateFlow
  (`val selectedNotebook by viewModel.selectedNotebook.collectAsState...`).
- Root causes to investigate:
  1. Is `lastNotebookId` WRITTEN on every notebook selection change (and on
     app close)? Find the setter call site — it may only be written on
     "add notebook" or never.
  2. Is it READ + applied at cold start? Find the init/launch code path that
     sets `selectedNotebook` — it may default to the first notebook and never
     restore `lastNotebookId`.
  3. Race: notebook list may load asynchronously AFTER the default selection —
     ensure the restore applies once the list is available.

## Definition of done
- `lastNotebookId` is written on every selection change (including the
  notebook-switcher) and on exit.
- On cold start, `selectedNotebook` restores to `lastNotebookId` (if that
  notebook still exists) instead of the first/random notebook.
- If the last notebook was deleted, fall back to the first existing notebook
  (and update the pref).
- `workspace/phase-168/REPORT.md` documents the previous selection source with
  file:line and the new restore logic.
- Unit tests: selection change persists pref; cold-start restore picks the last
  notebook; deleted-last-notebook falls back to first.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT change the security model.
- No DB schema change — prefs only.
- Respect existing single-ViewModel architecture and code style.