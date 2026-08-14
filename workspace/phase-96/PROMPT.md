# Phase 96: B2-UI-6 - Vault-wide imports/exports run on... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-UI-6, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-UI-6` (LOW)
- **Area:** Batch 2 - Compose/UI, concurrency, TOCTOU
- **Evidence:** `HomeScreen.kt:48` (`rememberCoroutineScope()` - cancelled when HomeScreen leaves composition, i.e. on every lock), `HomeScreen.kt:186-243` (import loop readUriBytes -> persistFile -> createPage per URI), `HomeScreen.kt:457-474,479-497` (backup/Obsidian/HTML exports to Downloads), `HomeScreen.kt:110-135` (performRestore also scope-scoped), `NoteflowViewModel.kt:2055-2067` (lock -> recomposition disposes HomeScreen mid-operation)
- **Exploit scenario:** Triggering a lock disposes HomeScreen and cancels the coroutine at the next suspension point: a multi-entry import stops partway, leaving already-persisted plaintext note files in `imports/` whose DB page rows were never created (orphaned plaintext corpus - same exposure class as B1-DB-4), and not-yet-renamed outputs in Downloads remain while no snackbar/completion ever runs.

## The fix (where & how)

`HomeScreen.kt:48,186-243,457-497,110-135` - run imports/exports/restore on `viewModelScope` (survives composition teardown) with an explicit completion path that posts a snackbar via the snackbarMessages pipeline; in the import loop delete the just-persisted source file if the `createPage` step was cancelled, so no orphaned plaintext files accumulate on lock.


## Verification

- Unit test (or documented manual verification): cancelling mid-import leaves no orphaned plaintext files in `imports/`, and a lock no longer abandons the export silently. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-UI-6 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-96/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
