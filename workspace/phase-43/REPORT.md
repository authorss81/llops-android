# Phase 43 — B1-DB-1 Fix: narrow "corruption" classifier + no auto-replaced empty vault

**Status:** DONE
**Finding:** B1-DB-1 (HIGH) — "Over-broad 'corruption' classifier quarantines
HEALTHY vaults on any `SQLiteException` and silently replaces them with an empty
DB" (`docs/security-report.md:232-238`).

## The defect (before)

`NoteflowDatabase.kt:287-296` (old `isDatabaseCorruptException`) returned **true**
for:

- every `android.database.sqlite.SQLiteException` (the superclass of
  `SQLiteDatabaseLockedException`, `SQLiteCantOpenDatabaseException`,
  `SQLiteFullException`, `SQLiteDiskIOException`, `SQLiteTableLockedException`),
- every `android.database.SQLException`,
- any class name containing `"SQLiteException"`,
- messages containing `"corrupt"`, `"malformed"` or `"file is not a database"`.

On that verdict the open helpers (`writableDatabase`/`readableDatabase`,
old `:259-261`/`:274-276`) called `quarantineCorruptDatabase()` (renames
`noteflow.sqlite`+`-wal`+`-shm`+`-journal` → `*.corrupt-<ts>`) and then
**immediately re-created a brand-new EMPTY database** with the current DEK.

Result: a routine, fully recoverable hiccup (lock contention, disk I/O, ENOSPC
at open, torn write on kill) was mislabelled as cryptocorruption; a healthy vault
got set aside and replaced with an empty one. The recovery screen then offered
"Start fresh", which permanently discards the (never-corrupt) quarantined copy.

## The fix (what changed, file:line)

### 1. Classifier narrowed to genuine corruption only — `NoteflowDatabase.kt`

`isDatabaseCorruptException` (internal top-level function,
`NoteflowDatabase.kt:404-427`) now returns true **only** for:

- `e is android.database.sqlite.SQLiteDatabaseCorruptException`
  (platform exception for malformed page/header states),
- `e is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException`
  (SQLCipher 4.9.0's own class for an unrecognizable/`file is not a database`
  open — thrown for wrong passphrase or genuinely corrupt bytes),
- messages containing
  `"file is not a database"` / `"malformed"` / `"database disk image is malformed"`.

Transient open failures — `SQLiteDatabaseLockedException` ("database is locked"),
`SQLiteDiskIOException` ("disk I/O error"), `SQLiteFullException` ("database or
disk is full"), `SQLiteCantOpenDatabaseException` ("unable to open database
file") and the bare `SQLiteException`/`SQLException` parents — are **never**
classified as corruption.

### 2. Quarantine must NOT auto-create a replacement DB — `NoteflowDatabase.kt`

`SafeSupportSQLiteOpenHelper.writableDatabase` (`:246-271`) and
`readableDatabase` (`:273-284`): on a genuine-corruption open failure the vault
is quarantined (bytes preserved, `quarantineCorruptDatabase` `:315-337`, sets the
persistent flag via `DatabaseSecurityHelper.setCorruptionDetected`) and the
original exception is **rethrown** — the open FAILS. The old recreate
(`delegate = SupportOpenHelperFactory...; delegate.writableDatabase`) is deleted.

New private guard `throwIfVaultQuarantined()` (`:298-304`) runs at the top of
both getters: once the persistent flag is set, any further open throws
`IllegalStateException` immediately, so OOM-bind/Room must never mmap/create a
fresh empty `noteflow.sqlite` behind the user's back. The empty vault is only
created after the user's explicit choice:
- "Restore from backup" → `importBackup` writes the restored DB, then clears the
  corruption flag (`NoteflowViewModel.kt:1664-1665`) and restarts the process;
- "Start fresh" → `startFreshAfterCorruption` clears the flag and
  `stampDatabaseChecksum` creates the empty vault (`NoteflowViewModel.kt:955-971`).

### 3. Same-session recovery surfacing + no flow crashes — `NoteflowViewModel.kt`

- `initializeData()` (`:1146-1165`) now wraps its body (`initializeDataCore`,
  `:1167-1232`) in a try/catch: if an exception is thrown AND the quarantine flag
  is set, `_corruptionBlocked.value = true` — the `CorruptionRecoveryScreen`
  appears IN THE CURRENT SESSION instead of crashing a dead DB. Any non-corruption
  exception is rethrown (never silently swallowed).
- The six Room-backed note flows (`notebooks` `:1084-1089`, `allSections`
  `:1092-1097`, `allActivePages` `:1100-1105`, `paletteItems` `:1108-1113`,
  `recentPages` `:1140-1145`, `trashedPages` `:1148-1153`) now gate on a new
  `dbGate` (`:1047-1049` = `authenticated && !corruptionBlocked`) instead of
  `_authenticated` alone, and append `.catch { emit(emptyList()) }` so a first-
  discovery race (a flow subscribed before the flag flips) cannot crash a
  collector.
- `startFreshAfterCorruption()` (`:955-971`): resets `dataInitialized=false` and
  calls `initializeData()` after clearing the flag, so the fresh empty vault gets
  its default notebook/section (previously `initializeData` had bailed at the
  corrupt open and "start fresh" left a blank, uninitialized home screen).
- `attemptRecoveryFromBackup()` (`:1651-1669`): on successful import also calls
  `DatabaseSecurityHelper.clearCorruptionDetected` + `_corruptionBlocked.value = false`.
  Without this, the newly restored vault would re-open behind the (now-clearable)
  recovery screen because the post-restart open is guard-blocked by the
  still-set flag.

### 4. API floor

No SDK-gated API is used anywhere in this change — the behaviour relies on
`SQLiteNotADatabaseException` (present in the pinned `sqlcipher-android 4.9.0`)
and existing `android.database.sqlite` classes available since API 26. The
classifier falls back to message matching on any device/ABI, so the API-26+
floor holds with no added dependency (AGENTS.md hardware-reality rule).

## Tests

New pure-JVM `app/src/test/java/com/authorss81/noteflow/CorruptionClassifierTest.kt` (10 tests):

- **Corrupt → true:** `SQLiteDatabaseCorruptException`; `SQLiteNotADatabaseException`
  ("file is not a database"); messages "file is not a database" (incl. prefixed by
  the filename) / "database disk image is malformed" / "malformed".
- **Transient → false:** `SQLiteDatabaseLockedException` ("database is locked",
  message-only too), `SQLiteDiskIOException`, `SQLiteFullException`,
  `SQLiteCantOpenDatabaseException`, plain `SQLiteException`, `android.database
  .SQLException`, "unable to open database file", "not a database text file"
  (old substring false-positive guard), blank/unrelated messages, `null`.

The android/sqlcipher exception constructors instantiate fine under the repo's
existing `testOptions.unitTests.isReturnDefaultValues = true`
(`app/build.gradle.kts:104-108`) — no Robolectric was needed.

## Verification

Run on the CI Linux runner (system gradle 8.13, AGP 8.7.3, JDK 17):

- `gradle testDebugUnitTest` — **953 tests, 0 failures** (91 result files).
  `CorruptionClassifierTest`: 10/10 green.
- `gradle assembleDebug` — **BUILD SUCCESSFUL**. (The first invocation failed
  once in `:app:mergeExtDexDebug` with a transient `DexArchiveMergerException`;
  the identical command re-ran green — a daemon-state/dex-merge flake, no source
  change. Same class of transient first-run failure documented in phase-42.)

## Before/after (vulnerability path closure)

| Step | Before (phase-42 tree) | After (this phase) |
|---|---|---|
| Locked / I-O / ENOSPC open fails | `isDatabaseCorruptException` true → Vault renamed `.corrupt-*`, empty DB created | Classifier false → exception rethrown, vault untouched |
| Genuine corrupt / wrong-key open fails | Quarantine, then empty DB auto-created behind the user | Quarantine (bytes preserved, flag set), open rethrows, `CorruptionRecoveryScreen` surfaced in-session |
| Any open attempt while flag set | Could silently recreate empty DB | `throwIfVaultQuarantined()` fails the open — empty vault only after explicit Start-Fresh or successful Restore |
| After "Start fresh" | Vault empty but home uninitialized | flag cleared → `stampDatabaseChecksum` creates empty vault → `initializeData()` builds default notebook/section |
| After "Restore from backup" + restart | No auto-recreate, but the flag could keep the recovery screen in front of the restored vault | corruption flag cleared on success; post-restart open proceeds normally |

## Checksum / secrets handling

- No keys, passwords or decrypted content are logged or added; no new logging.
- `allowBackup="false"`, `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE
  intact. `.github/workflows/` untouched. No DB schema change, no migration.
- No new dependencies (the `sqlcipher-android` 4.9.0 exception used for the
  `is` match is already the pinned database library).

## Out of scope (observed, not fixed — B1-DB-1 only)

- **B1-DB-2** (docs/security-report.md:240-246): `migratePlaintextIfNeeded`
  (`NoteflowDatabase.kt:191-232`) still deletes the original plaintext file on
  ANY migration failure — separate phase-53 finding; untouched here.
- The `databaseTampered` integrity banner may still show alongside the recovery
  screen while `databaseTampered` and `corruptionBlocked` are both true — cosmetic,
  pre-existing, not part of this finding.
- A transient non-corruption open failure (e.g. locked) still bubbles up to
  whatever DAO call triggered it; the vault data is intact and the call can simply
  be retried. No quarantine, no flag, no empty DB. (The old behaviour would have
  destroyed the vault — the point of the fix.)

## Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
  (`isDatabaseCorruptException` :404-427; `SafeSupportSQLiteOpenHelper`
  :246-304 / `throwIfVaultQuarantined`; removed auto-create recreate lines;
  `SafeSupportSQLiteOpenHelper` ctor no longer takes `passphrase`)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
  (`dbGate` :1047-1049, six gated+catching note flows, `initializeData` wrapper
  :1146-1165 + `initializeDataCore`, `startFreshAfterCorruption` :955-971,
  `attemptRecoveryFromBackup` :1651-1669)
- NEW `app/src/test/java/com/authorss81/noteflow/CorruptionClassifierTest.kt`
- `docs/security-report.md` (B1-DB-1 row → FIXED), `docs/phase-status.md`
  (phase-43 row), `docs/ARCHITECTURE.md` (vault subsection note)