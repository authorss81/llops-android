# Phase 53 — B1-DB-2 Fix: the plaintext→SQLCipher migration never deletes the original database

**Status:** DONE
**Finding:** B1-DB-2 (MEDIUM) — "Plaintext→SQLCipher migration deletes the original
database file on ANY failure (contradicts the Phase-09 never-delete guarantee)"
(`docs/security-report.md:245-251`).

## The defect (before)

`NoteflowDatabase.kt:191-232` `migratePlaintextIfNeeded`:

- On success it did `dbFile.delete(); tempFile.renameTo(dbFile)` (`:220-221`) — a
  delete-then-rename window in which the user's database file no longer existed.
- The `catch` block (`:224-231`) **unconditionally deleted** `dbFile` PLUS its
  `-wal`/`-shm` companions on ANY exception — a disk-full `sqlcipher_export`, a torn
  source file, a permission error creating the temp file — with **no quarantine name**
  (`*.corrupt-*` / `*.migrate-failed-*`) and **no `setCorruptionDetected` call**, so no
  recovery screen ever appeared. The only copy of a pre-SQLCipher user's notes was
  destroyed irreversibly, and a fresh empty DB was silently created underneath.
  That is exactly the Phase-09 H2 defect the code fixes for normal opens
  (`SafeSupportSQLiteOpenHelper`) but violated for migration.

## The fix (what changed, file:line)

### 1. Atomic, verified swap — `NoteflowDatabase.kt` `migratePlaintextIfNeeded`

`app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt:201-258`:

- **Before the file may replace the original it is verified**: `isPlaintextSqlite(tempFile)
  || tempFile.length() == 0L` → `throw IllegalStateException(...)` (`:231-233`). A
  successful `sqlcipher_export` writes a real, non-empty encrypted database (SQLCipher
  output never carries the plaintext `"SQLite format 3"` header).
- **The swap is atomic**: `tempFile.renameTo(dbFile)` (`:238`) maps to `rename()` which
  atomically replaces the target on bionic/Linux — so there is NO delete-then-rename
  window in which the user has no database file. Only if the rename fails (returns false)
  do we throw, which is caught by the failure path below.
- **Companion cleanup happens only AFTER the swap**: the stale plaintext `-wal`/`-shm`
  files are deleted (`:241-244`) strictly after the verified encrypted file is in place,
  and `DatabaseSecurityHelper.updateStoredChecksum(context)` (`:246`) re-stamps the HMAC
  on the freshly migrated vault (same call the old success path made).

### 2. Failure path preserves the original + raises the recovery flag — `NoteflowDatabase.kt`

The catch block (`:247-257`) no longer touches `dbFile`:

```kotlin
val timestamp = quarantineMigrateFailed(dbFile, tempFile)
com.authorss81.noteflow.services.DatabaseSecurityHelper.setCorruptionDetected(context, timestamp)
throw e
```

- `quarantineMigrateFailed(dbFile, tempFile)` (new pure-JVM `internal fun`,
  `NoteflowDatabase.kt:487-510`, File-ops only) deletes ONLY the scratch encrypted copy
  (`noteflow_encrypted.sqlite` — at most a partial copy, never user data) and renames the
  ORIGINAL database + its `-wal`/`-shm`/`-journal` companions to
  `noteflow.sqlite.migrate-failed-<ts>` (**bytes preserved for offline recovery**, mirroring
  the phase-09 `*.corrupt-<ts>` open-failure quarantine). A failed rename simply leaves the
  file in place — the recovery screen is still surfaced.
- `DatabaseSecurityHelper.setCorruptionDetected(context, timestamp)` (`:255`) raises the
  SAME persistent flag the open-path uses. Phase-43's `initializeData` (`NoteflowViewModel.kt:1181-1200`)
  catches the thrown exception, sees the flag, and sets `_corruptionBlocked` → the
  corruption-recovery screen (Restore from backup / explicit Start fresh) appears IN THIS
  SESSION. Subsequent opens are blocked by `SafeSupportSQLiteOpenHelper.throwIfVaultQuarantined`
  (`NoteflowDatabase.kt:298-304`), so an empty DB is never created behind the user.
- `throw e` (`:256`) propagates the original failure instead of swallowing it — the app the
  old code left in a silently-deleted state now always ends at a decision screen.

### 3. API floor

No SDK-gated API was introduced. Everything relies on `java.io.File` rename semantics
(available since API 1/26) and the already-pinned `DatabaseSecurityHelper`/phase-43 flag
wiring. The `rename()` atomic-replace behavior holds on bionic/Linux — the JVM floor for
every supported (API 26+) device. No fallback component is needed (AGENTS.md
hardware-reality rule).

## Tests

New pure-JVM `app/src/test/java/com/authorss81/noteflow/B1Db02MigrationFailureTest.kt`
(7 tests), modelled on the repo's phase-43 `CorruptionClassifierTest` + source-pin
pattern:

Behavior (real temp-file `quarantineMigrateFailed` runs, no Android classes):
- `migration failure preserves the original under migrate-failed name and deletes only the scratch` —
  original DB bytes survive byte-for-byte under `noteflow.sqlite.migrate-failed-<ts>`; the
  `-wal`/`-shm`/`-journal` companions ride along with the same suffix; the scratch copy is
  the ONLY thing deleted.
- `migration failure with missing companions still preserves the original` — exactly one
  quarantined file, original bytes preserved.
- `migration failure with no scratch file still quarantines the original without throwing` —
  absent scratch is not an error.
- `quarantine returns the timestamp embedded in the migrate-failed suffix` — returned value
  is a plausible current-time millis and matches the suffix written.

Source-level wiring pins (the Android-bound flow that can't run on the pure JVM):
- `migration catch preserves the original, raises the corruption flag and rethrows` —
  the catch calls `quarantineMigrateFailed(dbFile, tempFile)`, calls
  `DatabaseSecurityHelper.setCorruptionDetected(context, timestamp)`, contains `throw e`,
  and the catch tail contains NO `dbFile.delete()` / wal delete.
- `migration success swaps the verified encrypted file over the original without a delete window` —
  the only `dbFile.delete()` in the whole method is the empty-stub guard; the verification
  gate `isPlaintextSqlite(tempFile) || tempFile.length() == 0L` and `tempFile.renameTo(dbFile)`
  both present in the success path.
- `stale companions are deleted only after the encrypted file is verified in place` —
  ordering: rename → wal cleanup → checksum stamping.

## Verification

Run on the CI Linux runner (system gradle 8.13, AGP 8.7.3, JDK 17):

- `gradle testDebugUnitTest` — first run **1111 tests, 1 failure**: `WikiLinkParserCacheUnitTest`
  `a cancelled scan must propagate CancellationException` — the same documented, pre-existing
  timing flake as phases 40–42 (passes in isolation in this phase too; untouched by this diff).
  Re-run: **BUILD SUCCESSFUL, 0 failures** (1111 tests green, `B1Db02MigrationFailureTest` 7/7).
- `gradle assembleDebug` — first invocation failed with an unreproducible transient build
  failure; the identical command re-ran **BUILD SUCCESSFUL** (same class of transient
  first-invocation daemon flake documented in phases 42/50/51). `:app:assembleDebug`
  UP-TO-DATE green on re-invocation; 173.6 MB debug APK on disk
  (`app/build/outputs/apk/debug/app-debug.apk`).

## Before/after (vulnerability path closure)

| Step | Before (phase-52 tree) | After (this phase) |
|---|---|---|
| Migration fails mid-`sqlcipher_export` | Slide 1: original DB **deleted** + scratch deleted, no quarantine name, no flag → silent total data loss, fresh empty DB appears | Original preserved as `noteflow.sqlite.migrate-failed-<ts>` (bytes + wal/shm/journal intact), scratch-only deleted, corruption flag raised → recovery screen (Restore from backup / Start fresh) |
| Swap on success | `dbFile.delete(); tempFile.renameTo(...)` — crash between the two = no DB file at all | `tempFile.renameTo(dbFile)` atomic replace — original only gone once verified encrypted bytes are in place |
| Post-failure open attempts | Could silently create an empty fresh DB | `throwIfVaultQuarantined()` + phase-43 `initializeData` flag-check keep the recovery screen in front until the user chooses |
| App restart after failure | Deleted original, nothing to recover | `*.migrate-failed-*` bytes remain on disk for manual/backup recovery |

## Checksum / secrets handling

- No keys, passwords or decrypted content are logged or added; no new logging.
- `allowBackup="false"`, `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE intact.
- `.github/workflows/` untouched. No DB schema change, no Room migration. No new dependencies.
- The DB HMAC is re-stamped after a successful migration exactly as before
  (`DatabaseSecurityHelper.updateStoredChecksum`); no checksum-relevant change otherwise.

## Out of scope (observed, not fixed — refer to their phases)

- **B1-DB-3** (`voice_notes/` plaintext audio), **B1-DB-5** (zip-bomb imports) and the
  remaining B1-*/B2-* findings are separate phases — untouched here.
- A migration failure whose original-file RENAME itself fails (e.g. full disk) leaves the
  original plaintext bytes in place and still raises the corruption flag (recovery screen is
  shown regardless); the bytes are preserved either way — the mixed-failure edge is exactly
  as safe as the primary one.

## Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
  (`migratePlaintextIfNeeded` :201-258; new `internal fun quarantineMigrateFailed` :487-510)
- NEW `app/src/test/java/com/authorss81/noteflow/B1Db02MigrationFailureTest.kt`
- `docs/security-report.md` (B1-DB-2 finding block + tracking-table row → FIXED)
- `docs/phase-status.md` (phase-53 row), `docs/ARCHITECTURE.md` (vault-subsection note)
- `workspace/phase-53/REPORT.md` (this file) + `.done` marker