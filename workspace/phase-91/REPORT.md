# Phase 91 — B1-CRYPTO-06 (LOW): Tamper check fails OPEN (missing/undecryptable checksum ⇒ trust and re-baseline)

**Status:** DONE · **Date:** 2026-08-16 · **Finding:** B1-CRYPTO-06 (Batch 1 · Cryptography & key management)

## Summary

`B1-CRYPTO-06` (LOW) complained that `DatabaseSecurityHelper.verifyDatabaseIntegrity`
`DatabaseSecurityHelper.kt:146-154` **fails OPEN**: every "cannot verify" situation was
collapsed into `true` (verified):

1. a **MISSING stored checksum** ran `updateStoredChecksum(context); return true` — silently
   **RE-BASELINING** the HMAC against whatever SQLCipher file was on disk, so an attacker
   who can only delete the `db_hmac_checksum` pref gets the app to bless a possibly-tampered
   file as "verified" (the exploit the finding describes); and
2. `computeDatabaseHmac` returning `null` (DB file absent/empty, keystore key missing, or a
   stream error) fell through `?: return true` — trusting an unverifiable state.

This phase makes the verification **fail-closed and write-free**:

- the helper returns a sealed three-outcome `DatabaseIntegrityVerdict` from a new single
  pure-JVM decision table `services/DatabaseIntegrityPolicy.kt` —
  `Verified` / `Mismatch` / `CannotVerify` — never a bare boolean;
- a missing *or* un-computable checksum is `CannotVerify` ("cannot verify / possibly
  tampered"), surfaced to the recovery UI as a **distinct, non-alarming, per-session
  dismissible** notice instead of trusting the vault;
- the verification function **never writes and never re-baselines** (`hasStoredChecksum`
  is the new read-only accessor; the pref stays write-only through the trusted arm sites);
- the **only** auto-arm is a brand-new vault (`!vaultFilePresentAtStart && !hasStoredChecksum`)
  armed by `initializeDataCore` right after the app creates it — a first run never
  false-alarms the fail-closed notice, and an existing vault is never re-baselined from a
  live-file verify.

## What changed (before → after, `file:line`)

### New decision table — `app/src/main/kotlin/com/authorss81/noteflow/services/DatabaseIntegrityPolicy.kt`

| Before | After |
|---|---|
| (no such module — logic was inline in the helper) | `sealed interface DatabaseIntegrityVerdict` with `Verified` / `Mismatch` / `CannotVerify` (`:18-22`) — the SINGLE three-outcome verdict |
| `stored == null` → re-baseline + `true` | `DatabaseIntegrityPolicy.verdictFor(storedChecksum, currentChecksum)` (`:38-48`): `stored == null` **or** `current == null` → `CannotVerify`. A `null` current is whatever `computeDatabaseHmac` returns when the file is absent/empty, the keystore key is missing, or the stream errors |
| checksum equality = inline `ConstantTime.hexEqual` in the helper | the comparison still funnels through `ConstantTime.hexEqual` (full-length `MessageDigest.isEqual`, B2-CRYPTO-01; fixed-length lowercase hex on both sides) — now pinned inside the policy (`:43`) so a `String.equals` re-introduction is a test failure |
| (no wording) | `CANNOT_VERIFY_NOTICE` (`:71-74`) — the honest, non-alarming, vault-*not*-locked wording surfaced by the recovery banner |

### Helper — `app/src/main/kotlin/com/authorss81/noteflow/services/DatabaseSecurityHelper.kt`

| Before | After |
|---|---|
| `fun verifyDatabaseIntegrity(context): Boolean` re-baselined + collapsed to `true` (`:146-154`) | `fun verifyDatabaseIntegrity(context): DatabaseIntegrityVerdict` (`:151-178`) — FAIL-CLOSED. Reads the stored checksum + `computeDatabaseHmac(...)` and returns `DatabaseIntegrityPolicy.verdictFor(stored, current)`. **Zero writes**: no `updateStoredChecksum`, no `return true`, never a re-baseline |
| (no read-only existence check) | `fun hasStoredChecksum(context): Boolean` (`:114-118`) — new read-only accessor used by the fresh-vault arm guard; the pref remains write-only through `updateStoredChecksum` (`:66-72`) / `rearmBaselineFromFile` (`:79-86`) |

### ViewModel — `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`

| Before | After |
|---|---|
| init verify: `!verifyDatabaseIntegrity(...)` → `_databaseTampered` (`:1109-1118`) | `init` routes through `verifyDatabaseIntegrityNow()` (`:1111-1113`); a *brand-new vault* running its verification before first-data-init is suppressed (no prior state to tamper) |
| `verifyDatabaseIntegrity` consumed as a boolean in `setDatabaseIntegrityCheckEnabled` | `setDatabaseIntegrityCheckEnabled` → `verifyDatabaseIntegrityNow()` (`:1137-1149`); disabling the check clears **both** states |
| (no distinct state) | `_databaseIntegrityUnverified` StateFlow (`:1055-1058`, exposed `:1059`) — the DISTINCT fail-closed "cannot verify" state |
| (no mapping) | `applyDatabaseIntegrityVerdict` (`:1169-1186`): `Verified` → clears both; `Mismatch` → existing per-session tamper banner (B1-DB-6 gate); `CannotVerify` → raises `_databaseIntegrityUnverified` UNLESS `freshUnarmedVault` (`!vaultFilePresentAtStart && !hasStoredChecksum`) |
| (no auto-arm) | `verifyDatabaseIntegrityNow` (`:1188-1193`) + `vaultFilePresentAtStart` (`:129-134`, captured once at process start: `getDatabasePath("noteflow.sqlite")` exists && non-empty) + the ONE legitimate first-run arm in `initializeDataCore` (`:1509-1520`): `if (!vaultFilePresentAtStart && !hasStoredChecksum) repository.stampDatabaseChecksum(appContext)` |

### MainActivity — `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`

| Before | After |
|---|---|
| one tamper banner (`databaseTampered`, errorContainer) | + a second, DISTINCT, non-alarming notice rendered when `databaseIntegrityUnverified` (`:383-449`): tertiaryContainer, title "Vault Integrity Could Not Be Verified", body from `DatabaseIntegrityPolicy.CANNOT_VERIFY_NOTICE`, honest "Don't show again this session" checkbox wired to the SAME `viewModel.dismissDatabaseIntegrityWarning(dontShowAgain)` (per-session dismissal shared with the tamper banner) |

## Why this is the safe trade-off

- **An existing vault whose baseline is missing/unreadable** now shows a one-time,
  non-alarming, per-session-dismissible notice instead of being silently trusted. That is the
  finding's required direction (fail closed = "cannot verify / possibly tampered"), and the
  restore-from-backup path re-arms a fresh baseline from the trusted archive
  (`rearmBaselineFromFile`) — so the notice is actionable.
- **A brand-new vault** (no DB file existed when the process started and no baseline exists)
  is suppressed and its baseline legitimately armed by `initializeDataCore`: there is no prior
  state an attacker could have modified, and a first-run false alarm on the app's own freshly
  created vault would be wrong. This is the single auto-arm this fix permits, and it is
  structurally unreachable for an existing/tampered vault (`vaultFilePresentAtStart == true`).
- Pre-phase-91 vaults with a legitimately-stored baseline verify `Verified` exactly as before;
  the only changed first-boost behavior is honest: a truly missing baseline is surfaced rather
  than silently re-baselined.

## Tests — `app/src/test/java/com/authorss81/noteflow/B1Crypto06DatabaseIntegrityPolicyTest.kt` (11)

Behavioral (decision table, pure JVM):
- matching stored+current → `Verified`; differing current → `Mismatch`;
- **MISSING stored → `CannotVerify`** (never re-baselined to "verified");
- **UN-COMPUTABLE current (null HMAC) → `CannotVerify`** (never trusted);
- both-missing and one-sided `null`s → `CannotVerify`;
- comparison routes through `ConstantTime.hexEqual`, no `String.equals`/`==` on the checksum.

Source pins:
- `verifyDatabaseIntegrity` (a) returns the sealed verdict, (b) routes through
  `DatabaseIntegrityPolicy.verdictFor(stored, current)`, (c) contains **no** `updateStoredChecksum`
  and **no** `return true` (write-free, fail-closed);
- `hasStoredChecksum` is READ-ONLY (no `.edit()`) and keys on `PREF_DB_CHECKSUM`;
- ViewModel declares/wires the distinct `_databaseIntegrityUnverified` state;
- verdict mapping: `Verified` clears both, `Mismatch` → `integrityWarningDismissal.mayShow()`,
  `CannotVerify` → `_databaseIntegrityUnverified.value = !freshUnarmedVault`, and the mapping
  contains no `stampDatabaseChecksum` (no re-baseline from a verdict);
- the fresh-vault arm in `initializeDataCore` is guarded by
  `!vaultFilePresentAtStart && !DatabaseSecurityHelper.hasStoredChecksum(appContext)` and the
  `vaultFilePresentAtStart` probe exists;
- dismissal clears the distinct state through the shared per-session gate and never flips the
  persisted check flag;
- MainActivity collects `databaseIntegrityUnverified`, renders the distinct notice banner from
  `DatabaseIntegrityPolicy.CANNOT_VERIFY_NOTICE` with the honest per-session label, and routes
  its OK through the shared `dismissDatabaseIntegrityWarning(dontShowAgain)`.

Pre-existing regression guard: `B1Db06WalCoverageAndDismissalTest` (16) still green — the
per-session dismissal + WAL-coverage pins survived the refactor (the init path now consults the
session gate through `applyDatabaseIntegrityVerdict`'s `Mismatch` branch, still inside the
B1-DB-6 test's scanned region).

## Verification

- `gradle :app:testDebugUnitTest` — **1577 tests, 0 failures, 0 skipped** (1564 prior → +11 new,
  +2 pre-existing suite re-count; the previously-documented `WikiLinkParserCacheUnitTest`
  cancellation-timing flake did not appear this run).
- `gradle :app:assembleDebug` — green; debug APK **173,792,606 bytes**, SHA-256
  `8ecac53f198ded71a71c4dddd5d87b63a2e7972a8780b1bde2f4f9e46b71e23b`.

## Scope

- **No** Room schema change, **no** migration, **no** new Gradle dependencies,
  `.github/workflows/` untouched.
- **API 26+ pure JVM** (java.io / javax.crypto / java.security) — no platform fallback needed.
- New file: `services/DatabaseIntegrityPolicy.kt`. Modified: `DatabaseSecurityHelper.kt`,
  `NoteflowViewModel.kt`, `MainActivity.kt`. Tests: `B1Crypto06DatabaseIntegrityPolicyTest.kt`.
- `namespace`/applicationId alignment remains B1-PLAT-6 / ROADMAP 21.10 (out of scope, needs
  user approval).