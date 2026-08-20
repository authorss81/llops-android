# Phase 181 — Step 1: Reproduce + inventory (2026-08-20)

## Symptoms reported
- After opening the app (cold start), the last-used notebook is NOT open.
- After exporting (backup / vault zip / Obsidian zip / HTML) and returning to the
  home page, the last-used notebook is NOT open.

## Trace 1 — cold-start restore (phase-168 path) — WORKS
`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- `initializeDataCore()` `:1865` — the phase-168 restore block `:1964-2013`:
  - `:1970` reads `settings.lastNotebookId ?: settings.activeNotebookId` (primary +
    legacy fallback).
  - `:1976` resolves the notebook; deleted id falls back to the FIRST existing
    notebook (`:2001-2005`, persisted back) or to the default notebook+section
    (`:2007-2013`).
  - `:1988-1989` persists `lastNotebookId` + sets `_selectedNotebook`.
  - `:1992-1996` / `:2006` re-arm `observeSections`; stale section pref degrades
    to the notebook's first section (never drops the notebook).
- `selectNotebook()` `:2130` writes `activeNotebookId` (`:2133`) and
  `lastNotebookId` (`:2136`) on EVERY selection change.
- `HomeScreen.kt:80` renders `viewModel.selectedNotebook` (StateFlow) — no dead
  local snapshot.
- CONCLUSION: cold start restore is correctly implemented and would only fail if
  `lastNotebookId` was not persisted, i.e. if the process was killed after ON_STOP
  cleared `_selectedNotebook` (see Trace 3) before `onCleared()` ran, or for
  passwordless vaults where lock() wiped the DEK.

## Trace 2 — export-return path
`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` call sites:
- `:694` exportBackup, `:726` exportObsidianVaultZip, `:884` exportNotebookVaultZip,
  `:931` exportSectionVaultZip, `:1580-1581` exportBackup.
`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`:
- `:1768` exportNoteToHtml, `:1881` exportSectionVaultZip.
`ImportExportService.kt` (`services/`): `exportBackup` `:1390`, `exportVaultToZip`
`:2931`, `exportNoteToHtml` `:3127`, `exportObsidianVaultZip` `:3319`.
- All exports write to a user-picked SAF destination (`ui/components/SaFExporter.kt`,
  `ACTION_CREATE_DOCUMENT`). Launching the picker sends MainActivity to the
  background → `onStop`.
- NONE of the export completions rebuild the notebook list or re-navigate Home; the
  selection lives in `_selectedNotebook` StateFlow (`HomeScreen.kt:80`) and is only
  lost if the ViewModel clears it.

## Trace 3 — ROOT CAUSE: `lock()` clears the session for passwordless vaults
`NoteflowViewModel.kt` `:4683-4781`:
- `:4709-4716` — `searchVaultJob` cancel, then **`repository.zeroizeKey()` and
  `resetDecryptFailures()` run UNCONDITIONALLY**.
- `:4729-4767` — the has-master-password teardown (observed: dispose DB, reset
  `dataInitialized`, pause plugin lifecycle) is correctly gated
  `if (settings.hasMasterPassword)`.
- `:4768-4777` — **UNCONDITIONALLY** `invalidatePaletteIndex()` +
  `_pages.value = emptyList()` + `_selectedPage.value = null` +
  `_sections.value = emptyList()` + `_selectedSection.value = null` +
  `_selectedNotebook.value = null`.
- `MainActivity.kt:207-210` — `ON_STOP` calls `viewModel.lock()`. `ON_STOP` fires
  on ANY backgrounding: the SAF export picker, home button, app switch.
- `MainActivity.kt:389-404` — idle auto-lock also calls `lock()` (`:400`).
- For a PASSWORDLESS vault `_authenticated` stays `true` (`:4778-4780` only flips
  it for hasMasterPassword vaults), so there is NO re-init on return: the flows
  are `dbGate`-based (`:1732-1763`), `dataInitialized` stays `true`
  (`initializeData()` early-returns `:1817`), and nothing re-runs the phase-168
  restore. Result: the home list is populated (notebooks flow re-emits) but
  `selectedNotebook` is `null` — the last-used notebook is NOT open.
- The B1-AUTH-02 comment at `:4726-4728` STATES the intent: "Skipped for passwordless
  vaults: there is no lock boundary there (the device-wrapped DEK is the boot
  credential by design), so closing the still-active session would only break the
  UI." The code only respects that for the dispose/dataInitialized block, NOT for
  the selection StateFlows or the DEK zeroization.

## Why phase-168's tests still pass
`B2Ui4UnlockReinitializesStateTest` models the teardown that applies to
HAS-MASTER-PASSWORD vaults; the passwordless branch of the real `lock()` was never
modeled because the model `lock()` nulls unconditionally (`:125-129`) — it mirrors
the ALL-VAULTS behavior, which is exactly the bug. The regression slipped through
because the modeled + source tests exercise the password-protected path.

## Fix direction (Step 2)
Move `repository.zeroizeKey()`, `resetDecryptFailures()`, and the
selection-StateFlow clearing (`_pages/_selectedPage/_sections/_selectedSection/
_selectedNotebook` + `invalidatePaletteIndex` + cap-notified clears) INSIDE the
`if (settings.hasMasterPassword)` block so a passwordless lock() is a true
session-preserving no-op, matching the documented B1-AUTH-02 design. This preserves
every `lockBlock.contains(...)` source pin and the scrub-before-zeroize ordering
pin (B2Ui2 `:195-206`).