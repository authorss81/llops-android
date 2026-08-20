# Phase 181 — Re-fix: last-used notebook must open after app start AND after export/home return

**Status:** DONE (2026-08-20)
**Phase-168 claim:** "[DONE] app must always open the last-used notebook"
**User report:** still does NOT open the last-used notebook — after opening the app, or
after exporting and returning to the home page.

## Root cause of the regression

Phase-168 fixed the **cold-start** path: `selectNotebook` writes `lastNotebookId` on
every selection change and `initializeDataCore` restores `lastNotebookId ?:
activeNotebookId` (with first-existing/default fallbacks). That part is correct and
was re-verified here.

The regression is the **export-return / background path**, which phase-168 never
touched:

1. Every export (`exportBackup`, `exportVaultToZip`, `exportNoteToHtml`,
   `exportObsidianVaultZip`) writes to a user-picked SAF destination
   (`ui/components/SaFExporter.kt`, `ACTION_CREATE_DOCUMENT`). Launching the picker
   sends `MainActivity` to the background.
2. `MainActivity.kt:207-210` calls `viewModel.lock()` on `Lifecycle.Event.ON_STOP`
   — and ON_STOP fires on ANY backgrounding (SAF export picker, home button, app
   switch, phone-away).
3. The old `NoteflowViewModel.lock()` (`:4683-4781`) nulled
   `_selectedNotebook`/`_sections`/`_pages` (`:4772-4777`) and zeroized the DEK
   (`:4712`) **UNCONDITIONALLY**, regardless of vault kind.
4. For a **passwordless** vault `_authenticated` never flips (`:4778-4780` gates it
   on hasMasterPassword) and `dataInitialized` stays true (the reset lives inside
   the same gate), so on return nothing re-runs the phase-168 restore:
   `initializeData()` early-returns (`:1817`) and the flows are `dbGate`-based.
   HomeScreen (`HomeScreen.kt:80`) is correctly bound to
   `viewModel.selectedNotebook.collectAsState()` — it was never a dead local
   snapshot — but the ViewModel itself had `_selectedNotebook = null`. The last-used
   notebook was lost on every export + home-return and every phone-away.

The B1-AUTH-02 design comment (`:4726-4728`) already documented the intent
("Skipped for passwordless vaults: there is no lock boundary there... closing the
still-active session would only break the UI") — the code honored that for the DB
dispose/dataInitialized block but NOT for the selection StateFlows or DEK
zeroization. Phase-181 closes that gap end-to-end.

## Fix

`NoteflowViewModel.lock()` now gates the ENTIRE session teardown on
`if (settings.hasMasterPassword)`:

- Moved **inside** the gate: `repository.zeroizeKey()` + `resetDecryptFailures()`
  (DEK drop + decrypt-failure ledger reset), `invalidatePaletteIndex()`, the
  cap-notified clears, and the selection/content StateFlow clears
  (`_pages`/`_selectedPage`/`_sections`/`_selectedSection`/`_selectedNotebook`) +
  `_authenticated.value = false`.
- Left **outside** (harmless, defensive, unchanged): the clipboard scrub
  (`ClipboardGuard.scrubUnconditionally`, B2-UI-2 B2Ui2 ordering pin preserved)
  and the shared vault-search job cancel (R2-B1A-02).
- A **passwordless** `lock()` is now a true session-preserving no-op: the last-used
  notebook, its sections, and its pages stay in the StateFlows across the SAF
  picker / phone-away and remain open when the user returns. The device-wrapped
  DEK (the passwordless boot credential) also stays live.
- A **password-protected** vault still fully tears down on `lock()` (B1-AUTH-02) and
  the unlock paths re-run `initializeData()`/`initializeDataCore()`, which restores
  `lastNotebookId` on return — unchanged behavior, B2Ui4 pins intact.

No DB schema change. No new dependencies. `.github/workflows/` untouched. Base-APK
size rule intact. No new permissions.

## Test list

New — `app/src/test/java/com/authorss81/noteflow/Phase181ExportReturnNotebookRestoreTest.kt` (8):
1. Passwordless export-return keeps the pre-export notebook open (behavior model).
2. Password-protected export-return routes through the lock → unlock re-init
   restore (behavior model).
3. Cold start with a valid `lastNotebookId` opens that notebook.
4. Cold start falls back to `activeNotebookId` when `lastNotebookId` is missing.
5. Brand-new vault boots to the default notebook + section.
6. Source pin: DEK zeroization + selection clears + authenticated flip all live
   inside the single `hasMasterPassword` gate.
7. ON_STOP lock hook remains wired (MainActivity source pin).
8. HomeScreen binds the selected notebook to the ViewModel StateFlow, not a local
   snapshot.

Updated pins (were asserting the OLD unconditional-clear behavior):
- `B2Ui4UnlockReinitializesStateTest` — the pure-JVM `SessionModel.lock()` now
  clears selection/pages only inside `if (hasMasterPassword)`, and the passwordless
  test now asserts the session is KEPT (`pages == ["pA"]`,
  `selectedNotebook == "nb1"`, `selectedSection == "sec1"`) instead of
  `pages.isEmpty()` (which had pinned the bug).
- `B1Db08DecryptFailureTest` — the "ledger reset at session boundary" gate-region
  extraction was re-bounded to inside `if (settings.hasMasterPassword)`,
  preserving the zeroize-before-reset-before-dispose ordering intent.

## Verification

- `gradle assembleDebug` — **BUILD SUCCESSFUL** (52 s, 113 tasks).
- `gradle testDebugUnitTest` — **2417 tests, 1 failed** (only the pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure at `Phase148UiFailureTextScrubTest.kt:234`,
  reproduced on clean stashes in prior phases — untouched by this phase).
- Targeted first run: `Phase181ExportReturnNotebookRestoreTest`,
  `B2Ui4UnlockReinitializesStateTest`, `Phase168LastNotebookRestoreTest`,
  `B2Ui2ClipboardScrubTest`, `B1Auth02LockedOpenTest`, `B2Ui1LockedFlushTest`,
  `B1Db08DecryptFailureTest`, `Phase140ShareStateLockTest`,
  `Phase153LockedSnackbarPolicyTest` — all green.

## Definition of done — file:line evidence

| Claim | Evidence |
|---|---|
| Cold start opens the last-used notebook | `NoteflowViewModel.kt:1970` reads `lastNotebookId ?: activeNotebookId`; `:1985-1996` restores + re-arms observers; `selectNotebook` `:2136` persists `lastNotebookId`. |
| Export/home-return keeps the last notebook (passwordless) | `lock()` `NoteflowViewModel.kt:4726` gates ALL teardown on `hasMasterPassword`; a passwordless ON_STOP lock no longer nulls `_selectedNotebook` (selection clears now at `:4790` inside the gate). |
| Home binds to VM state, not a dead snapshot | `HomeScreen.kt:80` `viewModel.selectedNotebook.collectAsState()`. |
| Password-protected path unchanged | Full teardown still inside gate at `:4726-4792`; unlock restore verified by `B2Ui4UnlockReinitializesStateTest`. |

## Commits

- `ce89ebe` — step 1: root-cause trace (`workspace/phase-181/STEP1_TRACE.md`).
- `d96d746` — step 2: gate lock() session teardown on hasMasterPassword.
- `4aa980d` — step 3: `Phase181ExportReturnNotebookRestoreTest` regression tests.
- (this commit) — step 4: REPORT + docs updates.

## Docs updated

- `docs/ARCHITECTURE.md` — "Implemented in phase-181" note.
- `docs/phase-status.md` — phase-181 row.