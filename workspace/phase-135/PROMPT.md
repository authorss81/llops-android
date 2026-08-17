# Phase 135: Restore hardening — reject structurally-invalid/empty backups + one-in-flight re-entrancy gate + saveable recovery state [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1D-02, R2-b2b1-UI-03, R2-b2b1-UI-06) and
`docs/phase-status.md` + `docs/ARCHITECTURE.md`. This phase makes the restore
path safe against structurally-invalid/EMPTY archives and double-trigger races,
and makes recovery-screen state survive rotation.

## Source findings (all OPEN)

1. **R2-B1D-02** (MEDIUM) — Restore accepts a structurally-invalid or EMPTY
   SQLCipher database and swaps it over the live vault (irrecoverable data loss,
   HMAC re-armed on attacker data). `ImportExportService.kt:1867-1932`
   (`validateAndPrepareRestoredDb`), `:1281-1289` (`checkRestoredSchemaNotNewer`
   only rejects NEWER `user_version`), `:1816-1860` (`extractBackupEntriesTo` —
   a 0-byte `noteflow.sqlite` sets `sawDatabase = true`), `:1801-1808`
   (`rearmBaselineFromFile` re-blesses attacker data), `:2086-2125`
   (`commitRestoredFiles`), `NoteflowDatabase.kt:416-417`
   (`fallbackToDestructiveMigration` silently drops mismatched-schema DBs).
2. **R2-b2b1-UI-03** (LOW) — All four restore entry points run unguarded
   `viewModelScope.launch { closeDatabase → importBackup → reopen/exitProcess }`:
   `NoteflowViewModel.kt:2129-2168` (`attemptRecoveryFromBackup`),
   `:2179-2203` (`attemptKeystoreKeyLostRecoveryFromBackup`), `:3367-3407`
   (`restoreEncryptedBackupFromZip`), `HomeScreen.kt:138-202` (`performRestore`).
   Double-trigger races two file swaps; WebDAV restore can complete AFTER a lock.
3. **R2-b2b1-UI-06** (LOW) — Recovery screens keep `backupPassword`/error in
   `remember { mutableStateOf }` (not `rememberSaveable`) with no `isRestoring`
   gate: `MainActivity.kt:849-900` (`RestoreBlockedScreen`), `:908-…`
   (`CorruptionRecoveryScreen`), `:1001` (keystore-lost screen).

## The fix (where & how)

- **Valid-input gate (R2-B1D-02):** Before the swap, open the restored DB under
  the successful candidate key and (a) require the expected Room schema
  (`pages`/`strokes`/`note_versions`/`media_embeds` tables exist in
  `sqlite_master`), (b) refuse `user_version` outside an accepted range, (c)
  warn/refuse on a zero-rowcount vault unless the user confirms "start fresh".
  Abort + quarantine the incoming file on ANY failure — never re-arm + swap.
- **Re-entrancy gate (R2-b2b1-UI-03):** A single shared `isRestoring`/mutex
  serializing all four entry points; disable the confirm buttons while running;
  the WebDAV path must check `authenticated` before `closeDatabase()` and
  abort+reopen if the vault locked mid-download.
- **Saveable state (R2-b2b1-UI-06):** Hoist recovery state + in-flight flag into
  `rememberSaveable`/view-model state; the restore button is `enabled =
  !isRestoring`; the entry point refuses to start when one is in flight.

## Verification

- New pure-JVM unit tests (repo layout `app/src/test`): an empty/schema-missing
  DB is rejected and quarantined, not swapped; the zero-rowcount confirm path;
  a re-entrancy model test (second trigger refused while first is in flight);
  source pins that every restore entry point is gated.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-135/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- The live vault is never swapped for a structurally-invalid/empty restore.
- No existing restore flow regressed (legit vaults still restore).

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Preserve the
  `allowBackup=false`, quarantine, and fail-closed lock model.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.
