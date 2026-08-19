# Phase 169 REPORT — "Pages become Unreadable (decryption failed) after export / sometimes" + fail-closed hardening

Date: 2026-08-19
Commit: (see git log)

## 1. Task

User feedback: after exporting (or sometimes), pages show `Unreadable (decryption failed)`
and the contents don't show. Phase-169 (1) investigates WHY decryption fails after export
and (2) hardens the fail-closed path so corrupted rows are surfaced properly and are
recoverable WITHOUT raw ciphertext ever leaking.

Full spec: `workspace/phase-169/PROMPT.md`.

## 2. Diagnosis

### Method

Traced every crypto touchpoint on (a) the export chain, (b) the import/restore chain, and
(c) the read/display chain, with `file:line` evidence.

| Cause | Evidence | Verdict |
|---|---|---|
| Export mutates AAD / record ids / DEK | `ImportExportService.exportBackup` (`:1313-1500`): checkpointWal → `stampDatabaseChecksum` → `VaultSnapshotCopyPolicy.checkpointThenCopy` → pack the DB file byte-verbatim → encrypt the payload (v3: DEK-wrapped under backup password; legacy: device-keyed). No field is ever read, decrypted, re-keyed or re-bound during export. | **REFUTED** — export is a faithful snapshot; it cannot corrupt a row. |
| Backup password mismatch / wrong DEK unwrap after import | `validateBackupPasswordFile` (`:1820-1891`) fails GCM before the live DB is closed (H1, phase-09); wrong password/DEK → `"Incorrect backup password."` / `"corrupt or created on a different device"` — never a partial swap. `openedWith` probing (`:2236-2308`) only accepts candidates that pass `PRAGMA integrity_check`. | **REFUTED** as produced by the app's own flows — always surfaced loudly, vault untouched. |
| `reencryptPlaintextFields` misses `note_versions` | Covered since phase-09 (C1): `NoteRepository.reencryptPlaintextFields` sweeps `note_versions.{title,extractedText}` (`:488-518`); `fieldEncryptedColumns` includes them (`ImportExportService.kt:1254`). | **REFUTED** — the local sweep and the restore map are complete (verified against the read path's 7 decrypt columns). |
| Threshold too low (3 distinct) | `DecryptFailurePolicy.PERSISTENT_FAILURE_THRESHOLD = 10` distinct NOTES (`:51`); a single corrupt note can never trip it (`recordDecryptFailure` dedupes `note:<pageId>`). | **REFUTED** — threshold is honest; prompt's "~3" was stale. |
| **Cross-key restore silently strands un-re-keyable rows (the real bug)** | `migrateTable` (`ImportExportService.kt:2811-2834` pre-fix): `reencryptFieldValue` returned null for ANY non-migrateable value —— including a structurally-ciphertext row whose decrypt under the OLD DEK failed (`AuthFailed`) —— and the row was silently LEFT in place. The DB had ALREADY been re-keyed at the SQLCipher layer (`rekeySqlcipherDb`, `:2401`) to the CURRENT DEK. Reading that row afterwards with the NEW DEK fails GCM authentication → `DecryptFailurePolicy.UNREADABLE_MARKER` forever (`NoteRepository.decryptFieldForDisplay`, `:130-158`). This is exactly "after exporting → pages show Unreadable" for cross-key restores: cross-device import, or restore of a pre-password-change backup, or keystore-key-lost recovery with a fresh DEK. Reproducer pinned in `Phase169ExportImportRoundTripTest` ("a missed rekey … renders the marker"). | **CONFIRMED (a) real import-path bug — silent and permanent** |
| Marker-overwrite data loss (secondary but real) | Once a page renders unreadable, the editor/rename UI pre-fills the displayed (marker) text; `NoteRepository.updatePageBody`/`updatePageTitleAndTags`/`renamePage` encrypt and persist whatever they are given, so a save/rename of the unchanged marker would permanently REPLACE the still-recoverable original ciphertext with the marker string. | **CONFIRMED — contents "don't show" + become unrecoverable** |

### Root-cause summary

- **(a) real bug:** the restore re-key could install a vault guaranteed to contain
  permanently unreadable pages, silently.
- **(b) UX/behavior:** single unreadable pages (below the 10-note threshold) rendered the
  terse marker with no "why/act" guidance, and a marker save/rename destroyed the last
  recovery chance.

## 3. Fixes

### 3.1 Restore re-key fails LOUDLY instead of stranding rows (the real export→import bug)

- New pure-JVM decision `ImportExportService.reencryptFieldOutcome(...)`
  (`ImportExportService.kt`) returning the sealed `FieldReencryptOutcome`:
  - `Migrated(value)` — genuine ciphertext re-keyed to the new DEK under the SAME
    per-record AAD (`table|recordId|fieldName`);
  - `LeavePlaintext` — blank / genuine plaintext (never structurally a payload) stays;
  - `AuthFailed` — structural ciphertext whose decrypt under the OLD DEK fails.
- `reencryptFieldValue` kept as a compatibility wrapper (used by
  `WebDavSyncServiceTest`; same null semantics for plaintext/failure).
- `migrateTable` (`:2811-2834`) counts `AuthFailed` rows per `(table,column)` (cursor
  closed in `finally`) and throws `RestoreReEncryptionException(table, column, count)`
  BEFORE any file swap.
- `validateAndPrepareRestoredDb` catches it, QUARANTINES the rejected temp DB
  (`quarantineRejectedRestoredDb`, same evidence path as `RestoredDbPolicy` rejections),
  and rethrows — the live vault is never touched (`RestoreFailSafe` reopens it).
- `UiFailureTextPolicy.restoreFailureMessage`/`recoveryMessage` classify
  "could not be re-encrypted" → fixed `RESTORE_REENCRYPT_FAIL_TEXT` (never the raw count
  or table names).
- Rationale: if the row cannot be re-keyed NOW (old DEK in hand), it can NEVER be read
  after the SQLCipher re-key, so failing the restore is the only non-destructive outcome;
  the user re-imports a healthy backup instead of inheriting permanently broken pages.

### 3.2 Marker can never be persisted as real content (contents-don't-show data-loss)

- `DecryptFailurePolicy.isUnreadableMarker(String)` (exact match) +
  `UNREADABLE_ROW_GUIDANCE` constant (non-alarming, actionable, never contains the marker).
- `NoteRepository.updatePageBody` (trimmed check), `updatePageTitleAndTags`, and
  `renamePage` throw typed `UnreadableContentWriteException` when asked to persist the
  marker — the original encrypted bytes stay intact and recoverable.
- `NoteflowViewModel` catches it on ALL user-facing write surfaces: live body save
  (`saveMarkdownNoteBody`), the unlock-flush drain (`flushPendingEditorSaves`), the title
  rename (`renamePage`), and the title+tags save (`updatePageTitleAndTags`) — each shows
  `UNREADABLE_ROW_GUIDANCE`; the fire-and-forget `autoTagLanguageOnSave` background merge
  skips it silently (its documented no-message contract), fixing a reviewer-found
  uncaught-exception process crash in the target scenario.

### 3.3 UX (b)

- Single unreadable pages render the marker (unchanged, fail-closed) and any edit/rename
  attempt now surfaces the fixed guidance ("could not be decrypted … was not edited …
  restore a recent backup to recover this note") instead of a generic save failure or a
  silent overwrite.
- When ≥10 DISTINCT notes fail, the existing CorruptionRecoveryScreen path still escalates
  (unchanged, phase-88/163 behavior).

## 4. Round-trip proof (unit level)

`app/src/test/java/com/authorss81/noteflow/Phase169ExportImportRoundTripTest.kt` (12 tests):

1. **Cross-device re-key round trip renders plaintext for every encrypted column, never
   the marker** — encrypt under DEK_A with per-record AAD → `reencryptFieldOutcome`
   re-key to DEK_B → decrypt under DEK_B → `DecryptFailurePolicy.render` == plaintext, for
   all 7 `fieldEncryptedColumns` cells (also pins the map is complete).
2. **Legacy global-AAD rows migrate to per-record-bound ciphertext** under the new key
   (`isFieldBoundToRecord` true; decrypts under DEK_B).
3. **Same-device import is an identity** — unchanged ciphertext decrypts + renders under
   the same DEK.
4. **A missed re-key (the reported symptom) renders the marker — now prevented loudly** —
   an old-DEK row read with the new DEK fails auth → marker; `reencryptFieldOutcome`
   classifies it `AuthFailed` at migration time (incl. a bit-flipped damaged ciphertext).
5. Outcome classification: null/blank/plaintext → `LeavePlaintext`; valid → `Migrated`;
   foreign-tampered → `AuthFailed`.
6. `isUnreadableMarker` exact-match semantics; guidance is blank-safe and marker-free.
7. Source pins: repository write guards (body/title/rename); `migrateTable` fail-loud
   wiring; UI text mapping (fixed text, no raw count); ViewModel catch surfaces
   (≥4 sites).

## 5. Verification

- `gradle testDebugUnitTest`: **2296 tests, 1 failed** — the single failure is the
  documented PRE-EXISTING `Phase148UiFailureTextScrubTest` UNC-path case
  (`err \\fileserver\share\secret-wills.docx`), unrelated to this phase and reproduced on
  a clean tree in prior phases. Phase-169's 12 tests green; `B1Db08DecryptFailureTest`,
  `WebDavSyncServiceTest`, `RestoreHardeningWiringTest`, `B2Crypto04BackupPasswordTest`,
  `B2Ui1LockedFlushTest`, `Phase134LockVaultInflightTest` green.
- `gradle assembleDebug`: green.
- No schema change, no new dependencies, no migration, `.github/workflows/` untouched.
- Fail-closed invariants preserved: no ciphertext/key/decrypted content ever reaches
  exception messages or UI text; DEKs remain zeroizable ByteArrays; the marker is the
  ONLY possible rendering of an auth failure; a locked vault still throws (guards run
  after the key check on the write paths; a locked write throws `VaultLockedWriteException`
  unchanged).

## 6. Docs updated

- `docs/ARCHITECTURE.md` — phase-169 "Implemented in" note under the decrypt-failure
  section.
- `docs/phase-status.md` — phase-169 row → `DONE`.