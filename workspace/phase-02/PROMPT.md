# Phase 2: Security — fix restore/sync data-loss paths (CRITICAL) [PARTIAL]

You are working on **InkFlow/Noteflow**, an offline-first encrypted notes + canvas
Android app. Kotlin 2.0.21, Compose, Room + SQLCipher. This is a strict security
phase: fix the four highest-severity data-loss holes found in the audit. Do NOT
add features. Do NOT change the DB schema version.

## Verified context (from security audit, do not re-audit)

The Room DB is genuinely SQLCipher-encrypted and the crypto core is solid.
The weakest surviving surface is the restore/sync path:

### C1 — Cross-device restore silently destroys ALL stroke geometry
`migrateFieldCiphertexts` (`app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` ~:1414-1416)
re-keys only `pages.title`, `pages.extractedText`, `strokes.textContent`,
`media_embeds.textContent`. But `strokes.pointsJson` IS field-encrypted at write
(`NoteRepository.kt` ~:452-455) and read-decrypted with a fallback that returns
raw ciphertext on failure (`NoteRepository.kt` ~:368-374). On a cross-device
restore the `pointsJson` stays under the old DEK → decrypt fails → every stroke
vanishes silently.

**Fix:** include `strokes.pointsJson` (and any other field-encrypted column the
decrypt path reads) in the re-key/decrypt-reencrypt pass in
`migrateFieldCiphertexts`. Use the same decrypt-with-backup-DEK →
encrypt-with-current-DEK pattern already used for the other columns.
(Verified: the 5 field-encrypted columns are `pages.title`, `pages.extractedText`,
`strokes.textContent`, `strokes.pointsJson`, `media_embeds.textContent`;
`pointsJson` is encrypted at `NoteRepository.kt:454` and read with fallback-to-raw
at `:368-373`. `migrateFieldCiphertexts` at `ImportExportService.kt:1414-1416`
re-keys only title/extractedText/textContent — `pointsJson` is missing.
`reencryptPlaintextFields` at `NoteRepository.kt:115-157` handles all 5, so use
it as the reference pattern.)

### H2 — "Self-heal" corruption handler silently wipes the vault
`SafeSupportSQLiteOpenHelper` deletes DB + WAL + SHM + journal and recreates an
empty DB on ANY `SQLiteException`, including messages containing
`"file is not a database"` (`app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`:
predicate `isDatabaseCorruptException` :261-270, destructive trigger in the
`writableDatabase` getter :232-235 and `readableDatabase` getter :247-250,
actual file deletion in `cleanDatabaseFiles` :272-283 — it deletes `-journal`
too). That string is exactly what SQLCipher returns for a wrong-key or torn-write
DB, and the predicate is so broad that even a transient I/O `SQLException`
triggers it → irreversible total data loss.

**Fix:** Never delete on open failure. On a DB open/corruption exception:
1. Rename the corrupt files (`noteflow.sqlite` → `noteflow.sqlite.corrupt-<timestamp>`,
   same for `-wal`, `-shm`) instead of deleting.
2. Surface a clear error/banner so the user can attempt recovery from a backup
   (the app already has `attemptRecoveryFromBackup`).
3. Only create a fresh DB when the user explicitly chooses recovery.

### H1 — Failed restore bricks the app (DB stays closed)
`performRestore` closes the DB (`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` :100-112)
and on failure only shows a Toast (:109) — no reopen, no restart, and (unlike the
success path which calls `exitProcess(0)` at :107) the app keeps running with a
dead Room instance → every DB call throws until force-stop. Wrong password is
trivially reachable because the restore dialog only rejects a blank password.

**Fix:**
- On restore failure, restore the working DB state: reopen the database, then
  restart the process (e.g. `ProcessPhoenix`-style intent) OR at minimum reopen
  the Room instance so the app is usable again.
- Validate the password during restore by attempting a decryption of the backup
  header/DEK before swapping — reject wrong passwords with a clear message
  instead of proceeding.

### H3 — No schema-version validation before swap
`validateAndPrepareRestoredDb` (`ImportExportService.kt` :1342-1373) tries opening
with `[backupDekHex, currentDekHex, ""]`, runs only `PRAGMA integrity_check`
(:1352), re-keys if the DEK differs (:1367-1371), but NEVER reads
`PRAGMA user_version`. `commitRestoredFiles` (:1431-1456) then moves the DB into
the live path unconditionally → a backup from a NEWER schema is later wiped by
`fallbackToDestructiveMigration()` (`NoteflowDatabase.kt` ~:314).

**Fix:** Read `PRAGMA user_version` from the temp restored DB before swap. If it
is greater than the app's current Room version, reject the restore with a clear
"backup is from a newer app version" message. If lower, allow (older backups
migrate forward normally).

### H4 — Plaintext backup path still exists
`exportBackup` legacy branch writes a plain zip when `key == null`, including all
plaintext `imports/` files (`ImportExportService.kt` ~:1108-1116).

**Fix:** Remove the silent plaintext fallback. A backup must either be
password-encrypted (v2 NFLB2) or device-keyed — never a plain zip containing
journal/voice/image files without explicit user intent. If a truly unencrypted
export is ever needed it must require an explicit user confirmation with a
warning.

## Also fix (small, same phase)
(None beyond the four above. Note: `HandwritingToTextDialog.kt:68` copies
decrypted text to the clipboard without `ClipboardGuard.recordCopy()` — but that
dialog is unreachable dead code removed in a later phase, so do NOT touch it
here.)

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes (existing suites must not break).
- The four fixes are implemented with the described behavior, not just comments.
- Add or update unit tests covering: cross-device restore preserves stroke
  geometry; DB open failure does not delete files; restore with wrong password is
  rejected; restore from a newer-schema backup is rejected.

## Constraints
- Do NOT change the DB schema (no new tables/columns, no version bump).
- Do NOT weaken any existing crypto. Reuse `EncryptionService`/`SecurityService`.
- Do NOT add the `INTERNET` permission (a later phase handles WebDAV).
- Do NOT edit `.github/workflows/`.
- Verify each fix against the real file:line, not vibes.
