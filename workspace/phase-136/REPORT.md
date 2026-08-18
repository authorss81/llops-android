# Phase 136 — Tamper-verify baseline cadence: checkpoint + re-arm at every session end

**Status: DONE (2026-08-18)**

Closes one OPEN finding from `docs/security-report-round2.md`:
`R2-B1D-01` (MEDIUM).

---

## R2-B1D-01 (MEDIUM) — the tamper baseline is only re-armed on event-driven mutations, so ordinary edits raise a FALSE "Mismatch" on the next start

### Before

The WAL-aware tamper baseline (B1-DB-6: HMAC-SHA256 over `noteflow.sqlite` + `-wal`,
keyed by an AndroidKeyStore non-extractable key) was written by exactly the trusted
arm sites: fresh-vault creation, plaintext→encrypted migration, legacy-field
re-encryption, backup, and the validate-then-arm restore path. **Nothing re-armed the
baseline at a session boundary.** `NoteflowDatabase.dispose()` (`NoteflowDatabase.kt:431-436`
pre-fix) just closed the connection:

```kotlin
fun dispose() {
    synchronized(this) {
        INSTANCE?.close()
        INSTANCE = null
    }
}
```

The vault runs `JournalMode.WRITE_AHEAD_LOGGING`, so ordinary note edits are
committed-but-uncheckpointed WAL frames. Reproduction path of the finding:
create a master password → edit a note → lock / exit → next process start runs
`verifyDatabaseIntegrityNow` and the pre-edit baseline does not match the
post-edit `main + -wal` state → the per-session tamper banner ("Database
integrity check failed") is shown for a completely ordinary edit. The false
signal made the tripwire itself untrustworthy.

### After

The fix follows the PROMPT's primary option: **checkpoint + re-arm the baseline at
every session end**, implemented at the single teardown funnel —
`NoteflowDatabase.dispose()` (`NoteflowDatabase.kt:454-482`):

```kotlin
fun dispose() {
    synchronized(this) {
        INSTANCE?.let { db ->
            runCatching {
                db.query("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    if (cursor != null) {
                        while (cursor.moveToNext()) { /* fully step the cursor */ }
                    }
                }
            }
            db.close()
            cachedAppContext?.let { ctx ->
                runCatching { DatabaseSecurityHelper.updateStoredChecksum(ctx) }
            }
        }
        INSTANCE = null
    }
}
```

Ordering is the point:

1. **`PRAGMA wal_checkpoint(FULL)` runs on the still-live keyed connection**
   (`NoteflowDatabase.kt:462-469`, cursor fully stepped — same pattern as
   `NoteRepository.checkpointWal` `:324-332`) so every committed WAL frame from the
   session collapses into the main file.
2. **The vault is closed** (`db.close()` `:470`) — the file is now quiescent, no
   open writable handle survives (B1-AUTH-02 posture unchanged).
3. **The stored baseline is re-armed against the now-quiescent file** via the SAME
   trusted helper the other arm sites use, `DatabaseSecurityHelper.updateStoredChecksum`
   (`:477`, `DatabaseSecurityHelper.kt:74-80`), wrapped in `runCatching` (best-effort:
   a keystore/prefs failure must never break the lock or restore).
4. The app Context needed for the checksum prefs is cached when the database is
   built — `cachedAppContext` (`NoteflowDatabase.kt:65-67`, assigned in
   `getDatabase` `:419`, application-scoped, no leak).

Every path that closes the live vault funnels through `dispose()`, so all of them
now checkpoint + re-arm:

- **Master-password `lock()`** — `NoteflowViewModel.kt:4056-4060`:
  `if (settings.hasMasterPassword)` → cancel section/page observers →
  `NoteflowDatabase.dispose()` → `databaseDisposedByLock = true`. The re-arm is
  therefore master-password-gated at the LOCK boundary by construction: a
  passwordless vault is never disposed on lock (its device-wrapped DEK is the boot
  credential — there is no lock boundary). Note: at APP EXIT (`onCleared`) the
  re-arm does run for passwordless vaults too — that is intended and beneficial:
  passwordless vaults already carry a baseline (armed at first-run/migration/
  backup), so re-arming it at a clean exit closes the SAME false-`Mismatch` for
  them. Comment added at the call site documenting the cadence.
- **App exit** — `NoteflowViewModel.onCleared` `:4089` (still funnels through
  `dispose()`).
- **Restore / recovery swap** — `NoteRepository.closeDatabase` `:501` and
  `reopenDatabase` `:511` (`NoteflowDatabase.dispose()` + rebuild). In the restore
  flow the pre-swap live file is stamped first, then `importBackup`'s
  `rearmBaselineFromFile` (`ImportExportService.kt:1813`) re-stamps the verified
  imported DB after swap — the final baseline always reflects the imported vault,
  so the intermediate stamp is harmless.
- **Reopen-after-lock** — `reinstateDatabaseAfterLock` → `reopenDatabase`: dispose
  is a no-op (INSTANCE already null) and the baseline from the last lock already
  holds.

**Security model preserved:**

- The session's own writes are now part of the baseline, so the next start verifies
  `Verified`. A verification still **never re-baselines** (B1-CRYPTO-06 invariant:
  `DatabaseSecurityHelper.verifyDatabaseIntegrity` `:181-186` and
  `DatabaseIntegrityPolicy.verdictFor` are write-free — source-pinned).
- Post-exit tampering of `main` or `-wal` by an off-device/pre-boot actor is still
  detected: the baseline is armed at a moment the app itself wrote the bytes, and
  any change after the process exits leaves a `Mismatch`.
- Trade-off (documented, matches the PROMPT's intended semantics): corruption or
  tampering that occurs **during** a session, before the session-end lock, is
  re-armed over. That is consistent with the checksum's threat model (a
  same-privilege runtime attacker already has the plaintext); B1-DB-1 corruption
  detection at open-time is unchanged. A hard process kill without any lock still
  leaves the last session's WAL frames to be flagged on the next start — the
  intended detection flip-side of B1-DB-6, unchanged.

### Evidence (tests)

`Phase136TamperBaselineCadenceTest` (8 tests):

- *Pure-JVM cadence behavior* (via `DatabaseHmacPolicy.streamDbAndWal` +
  `DatabaseIntegrityPolicy.verdictFor`):
  - `a session-end re-arm after an in-session edit verifies clean` — arm →
    edit → re-arm → `Verified`.
  - `without the session-end re-arm the same in-session edit trips the tripwire`
    — control: same edit with no re-arm → `Mismatch` (the tripwire still works).
  - `the checkpoint folds WAL-only session frames into the re-armed baseline` —
    arm → WAL-frame edit → re-arm → `Verified`.
  - `a fully checkpointed empty WAL never moves the re-armed baseline` — the
    dispose checkpoint outcome is byte-identical to the no-WAL state.
- *Source-level wiring pins*:
  - `dispose checkpoints the WAL closes then re-arms against the cached context`
    — ordering `wal_checkpoint(FULL)` < `db.close()` < `updateStoredChecksum(ctx)`
    < `INSTANCE = null`, fully-stepped cursor, `runCatching`-wrapped re-arm,
    `cachedAppContext` set in `getDatabase`.
  - `lock disposes only inside the master-password branch and onCleared funnels
    through dispose`.
  - `the restore and reopen paths funnel through the same disposal`.
  - `verification still never re-baselines`.

### Verification

- `gradle testDebugUnitTest` — **1919 total (app 1869 + plugins:llm 50), 0
  failures, 0 errors, 0 skipped.**
- `gradle assembleDebug` — green (first invocation hit the documented transient
  `mergeExtDexDebug` flake; retry fully green).

### Files touched

- `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
  (`cachedAppContext` field, `getDatabase` context cache, rewritten `dispose()`).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
  (comment at the master-password `lock()` dispose call).
- `app/src/test/java/com/authorss81/noteflow/Phase136TamperBaselineCadenceTest.kt` (new).
- Docs: `docs/ARCHITECTURE.md` (phase-136 note), `docs/phase-status.md` (phase-136 → DONE).

No schema change, no migration, no new dependencies, `.github/workflows/` untouched.

---

## Review fixes (phase-136 follow-up, commit after 8c6b566)

Applied in response to the phase-136 review findings:

1. **The full-file re-arm now runs OFF the caller's thread.** The checkpoint +
   `db.close()` stay synchronous on the live connection (B1-AUTH-02 posture), but
   `DatabaseSecurityHelper.updateStoredChecksum` (full-file HMAC + checksum-prefs
   commit) runs on a dedicated single-thread daemon executor
   (`REARM_EXECUTOR`, `NoteflowDatabase.kt:69-74`) so `lock()`/`onCleared()` (main
   thread) never block on it. Ordering is preserved two ways:
   `getDatabase` joins the pending re-arm (`pendingRearm?.join()`,
   `NoteflowDatabase.kt:436-447`) before rebuilding the vault, and `onCleared`
   awaits it at app exit (`NoteflowDatabase.awaitPendingRearm()`,
   `NoteflowViewModel.kt:4089-4103`) so the last session's baseline is durable
   before process teardown kills the daemon thread.
2. **`db.close()` is now best-effort too** (`runCatching { db.close() }`,
   `NoteflowDatabase.kt:500`), and `INSTANCE = null` always runs — a close failure
   can no longer leak a keyed handle NOR skip the re-arm schedule.
3. **The stored baseline is durable:** `updateStoredChecksum` and
   `rearmBaselineFromFile` now use `SharedPreferences.commit()` instead of
   `.apply()` (`DatabaseSecurityHelper.kt:74-86, 87-99`), so a hard kill right
   after lock/app-exit cannot drop the re-arm.
4. **Passwordless-exit re-arm clarified (behavior kept):** the re-arm at app exit
   for passwordless vaults is a deliberate, beneficial by-product (they already
   hold a baseline from first-run/migration/backup); only the lock boundary is
   master-password-gated. Docs corrected to say so.
5. **Source-pin tests hardened** (`Phase136TamperBaselineCadenceTest`): pins are
   scoped to the `dispose()` body / `getDatabase` body instead of whole-file
   `indexOf`, and a new pin asserts the re-arm helpers commit synchronously
   (no `.apply()`).

**Residual limitation (finding 1, accepted — NOT changed):** a hard process kill
(no `lock()`, no `onCleared`) still leaves the killed session's WAL frames
un-checkpointed, so the next start's verification Mismatches a baseline that
predates them — the false banner reappears for that one interrupted session.
Closing this would require either verifying only at a quiescent state or
downgrading `Mismatch` for interrupted sessions, both of which weaken the
post-exit tamper tripwire (a genuine post-kill tamper is indistinguishable from
the app's own killed-session writes with a file checksum alone). That is a
product/security decision; per AGENTS.md it was NOT made silently. The explicit
finding repro (edit → lock → reopen) is fixed, and the tripwire remains strict for
every cleanly-ended session.

### Verification

- `gradle testDebugUnitTest` — green (see run in this workspace).
- `Phase136TamperBaselineCadenceTest` — 8/8 green after the review fixes.

### Files touched (review fixes)

- `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
  (`REARM_EXECUTOR`, `pendingRearm`, `getDatabase` join, rewritten `dispose()`,
  `awaitPendingRearm()`).
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
  (`onCleared` awaits the pending re-arm).
- `app/src/main/kotlin/com/authorss81/noteflow/services/DatabaseSecurityHelper.kt`
  (`.apply()` → `.commit()`).
- `app/src/test/java/com/authorss81/noteflow/Phase136TamperBaselineCadenceTest.kt`
  (hardened pins + commit() pin).
- Docs: this REPORT, `docs/ARCHITECTURE.md`, `docs/phase-status.md`.
