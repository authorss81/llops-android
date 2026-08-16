# Phase 92 — B1-AUTH-07 (LOW): `isMasterPasswordValid` unrestrained master-password oracle

**Finding** (`docs/security-report.md`, row `B1-AUTH-07 | LOW | phase-92`):
`NoteflowViewModel.isMasterPasswordValid` — the only caller of which is the
create-backup dialog's password field (`HomeScreen.kt:1316`) — was a side-effect-free
verifier: it ignored `lockoutActive()` and never bumped the persisted
`failedUnlockAttempts` / `lockoutUntilEpochMs` counters that `verifyMasterPassword`
enforces. Each attempt ran full PBKDF2 and returned a clean pass/fail with zero
throttling. On an unlocked device an attacker with a brief window of access could
hammer the "Create password-protected backup" dialog as an in-app offline-equivalent
oracle that never tripped the LockScreen's 5-attempt exponential lockout.

## What changed

### 1. `NoteflowViewModel.kt` — one shared lockout, both verification surfaces

- **Before** (`NoteflowViewModel.kt:1920-1937` in the finding; current tree
  `:2860-2873` pre-fix):
  ```kotlin
  suspend fun isMasterPasswordValid(password: String): Boolean {
      val dek = try { unwrapMasterDek(password) } catch (e: Exception) { null } ?: return false
      dek.fill(0.toByte())
      return true
  }
  ```
- **After** (`NoteflowViewModel.kt:2908-2923`):
  ```kotlin
  suspend fun isMasterPasswordValid(password: String): Boolean {
      if (lockoutActive()) return false
      if (settings.masterPasswordCredentialOrLegacy == null) return false
      val dek = try { unwrapMasterDek(password) } catch (e: Exception) { null }
      if (dek == null) {
          recordFailedMasterPasswordVerification()
          return false
      }
      dek.fill(0.toByte())
      resetMasterPasswordVerificationCounters()
      return true
  }
  ```

- **Shared failure helper** `recordFailedMasterPasswordVerification()`
  (`NoteflowViewModel.kt:2867-2879`) — now called by BOTH `verifyMasterPassword`
  (`:2806`, replacing the old inline catch bookkeeping) and `isMasterPasswordValid`
  (`:2917`): bumps `_failedUnlockAttempts` + persisted `settings.failedUnlockAttempts`;
  at `MAX_FAILED_ATTEMPTS` (5) sets the persisted `settings.lockoutUntilEpochMs`
  (`computeLockoutDelayMs` exponential backoff, same as before), starts the LockScreen
  countdown ticker, **and calls `lock()`** so an in-app surface that trips the lockout
  performs the same data-layer teardown as `lock()` (B1-AUTH-02 posture: no live keyed
  SQLCipher connection sits behind the LockScreen). `lock()` itself is password-vault
  gated, so passwordless vaults (which can never reach these verifiers anyway) are
  untouched.

- **Shared success helper** `resetMasterPasswordVerificationCounters()`
  (`NoteflowViewModel.kt:2886-2891`) — cleared by both surfaces on a verified password
  (same contract as the old unlock success block).

- `verifyMasterPassword` (`:2769-2809`) now delegates its success/failure bookkeeping to
  the two shared helpers; its behavior is otherwise unchanged (lockout guard →
  credential guard → `unwrapMasterDek` → reinstate → `initializeData` →
  `startPluginLifecycle` → `flushPendingEditorSaves` → `enforceDekAtRestPolicy`), and its
  catch now routes through `recordFailedMasterPasswordVerification()` — the old inline
  counter block (which read `settings.failedUnlockAttempts = newCount`) is removed, so
  there is exactly ONE place where a failed master-password verification is accounted.

### 2. `HomeScreen.kt` — re-authentication immediately before the export, honestly surfaced

- The create-backup dialog already verified the master password immediately before the
  `ImportExportService.exportBackup(...)` call; that verification is now throttled by
  the shared lockout. Its error message (`HomeScreen.kt:1316-1328`) now distinguishes an
  active/just-tripped lockout (`"Too many failed attempts. Try again after the lockout
  countdown."`) from a plain wrong password (`"Incorrect master password"`) — never a
  silent degradation, and a tripped 5th attempt locks the vault before any export byte
  moves (`return@launch` skips `checkpointWal`/`stampDatabaseChecksum`/`exportBackup`).

### 3. `B1Crypto04PasswordStrengthTest.kt` — documentation/comment accuracy only

- The two stale references to `isMasterPasswordValid` being a "side-effect-free oracle"
  were updated: the assertion (no `PasswordStrengthPolicy` reference inside
  `isMasterPasswordValid`) is unchanged and still green — B1-AUTH-07 adds the lockout,
  never a password-strength gate.

## Verification

- `gradle testDebugUnitTest` (full suite):
  **1591 tests, 0 failures, 0 errors, 0 skipped** (final run `BUILD SUCCESSFUL`).
  Includes the new `B1Auth07IsMasterPasswordOracleTest` (11 tests) plus the affected
  source-pin suites (`B1Crypto04PasswordStrengthTest`, `B1Auth02LockedOpenTest`,
  `B1Crypto02DekAtRestTest`, `B1Crypto03MasterPasswordAtomicTest`, `B2Ui1LockedFlushTest`)
  all green.
  - One earlier full-suite run showed the documented pre-existing
    `WikiLinkParserCacheUnitTest` cancellation-timing flake (fails only when a
    concurrent cache scan is cancelled mid-flight) — it passes in isolation
    (`gradle :app:testDebugUnitTest --tests ...WikiLinkParserCacheUnitTest` → green) and
    did not reproduce on the final run. Unrelated to this diff (documented since phase-67,
    re-verified in phases 70/74/76/79/80/81/86/88/89/91).
- `gradle assembleDebug`: first invocation hit the documented transient
  `:app:packageDebug` packaging failure; retry `BUILD SUCCESSFUL`. Debug APK
  173,793,374 bytes, SHA-256 `881c83e3d1b99667380527f6ec26dd19d7dca09718066d68058e384673d70536`.

## New test file

`app/src/test/java/com/authorss81/noteflow/B1Auth07IsMasterPasswordOracleTest.kt` (11 tests):

Behavioral model (a faithful mirror of the production decision table, clock injectable):
- repeated invalid backup-password submissions trip the SAME 5-attempt lockout as unlock
  attempts (4 → no lockout; 5th → persisted 30 s backoff + data-layer lock + ticker);
- an active lockout refuses BOTH surfaces before any PBKDF2 work (oracle closed) and does
  not churn counters;
- both surfaces share ONE counter set — the 5th failure accumulated across either surface
  locks, and a correct password is still refused while locked;
- a verified password on the dialog clears the shared counters;
- the lockout survives an app restart (persisted counters) and lapses past the backoff;
- the exponential backoff table matches production (30 s → 1 m → … → 15 m cap);
- a vault with no master-password credential refuses both surfaces without counting.

Source-level wiring pins (same technique as `B1Auth02LockedOpenTest`):
- `isMasterPasswordValid` enforces `lockoutActive()` FIRST, guards the credential,
  routes through both shared helpers, still zeroizes the DEK, and stays free of the
  password-strength gate;
- `verifyMasterPassword`'s body delegates to the same two helpers and the old inline
  `settings.failedUnlockAttempts = newCount` counter block is gone;
- the shared failure helper applies `MAX_FAILED_ATTEMPTS`, the persisted
  `settings.lockoutUntilEpochMs` + `computeLockoutDelayMs`, `lock()` and
  `startLockoutTicker()`;
- the HomeScreen dialog verifies (`isMasterPasswordValid`) BEFORE `exportBackup` and
  surfaces the lockout message honestly.

## Checksums / secrets

- No secrets, keys, or passwords were logged, printed, or committed. `unwrapMasterDek`
  unchanged (side-effect-free, every rejected KEK zeroized). The verified DEK in
  `isMasterPasswordValid` is still zeroized (`dek.fill(0.toByte())`) before the counters
  reset.
- `allowBackup="false"`, `ClipboardGuard` and FLAG_SECURE untouched. The new `lock()`
  call inside the shared failure helper reuses the existing lock path (which itself
  scrubs the clipboard first — B2-UI-2).

## API floor (AGENTS.md hardware reality)

- No new API requirement: everything is pure JVM logic + existing Android call paths
  (`lock()`, `SettingsManager`, coroutines) on the API 26+ floor. No fallback needed;
  no new dependencies.

## Out of scope (deliberately not fixed here)

- The finding's overlapping note that `changeMasterPassword`/`setBiometricEnabled`
  re-wrap the DEK under a non-auth keystore key when biometrics are off was the
  B1-CRYPTO-02 bypass — already fixed in **phase-45** (`enforceDekAtRestPolicy`,
  `B1Crypto02DekAtRestTest`); re-verified untouched by this diff.
- The **restore** backup-password path (`pendingRestoreBytes != null`, `performRestore`)
  validates the backup's OWN password-derived DEK (not the master password) and is not a
  master-password oracle; it is also only reachable when the user already holds a backup
  file, and offline brute force of a leaked backup is a documented B1-CRYPTO-04 /
  B1-PLAT-8 residual (mitigated by password strength, never by the on-device lockout).
  Not changed.
- `verifyBiometricsAndUnlock` / `removeMasterPassword` inlined their own success
  reset in the original phase commit (functional no-ops, same values) — not an
  oracle surface (no free password verification). The review-fix commit routes both
  through the shared `resetMasterPasswordVerificationCounters()` so they cannot
  drift (and `removeMasterPassword` now also clears the stale lockout countdown it
  used to omit) — see "Phase-92 review fixes" below.
- No DB schema change, no migration, no `.github/workflows/` edit, no new dependencies.

## Phase-92 review fixes (commit `llops: phase-92 review fixes`)

- **Unlock-path behavioral note (review FINDING #2):** pre-fix, the 5th failed UNLOCK
  attempt (`verifyMasterPassword`) only zeroized the DEK + started the ticker — the
  SQLCipher connection was never disposed. Post-fix the shared failure helper's
  `lock()` disposes the data layer on EVERY surface that trips the threshold (incl.
  the create-backup dialog). Deliberate B1-AUTH-02 strengthen (no live keyed
  connection behind the LockScreen); documented, not reverted.
- **Delegated the two remaining inline success resets (review FINDING #4):**
  `verifyBiometricsAndUnlock` (`NoteflowViewModel.kt:2991-2994` pre-fix) and
  `removeMasterPassword` now call the shared `resetMasterPasswordVerificationCounters()`
  instead of duplicating it. `removeMasterPassword` additionally gains the
  `_lockoutRemainingMs.value = 0L` clear the old inline block omitted (previously a
  stale lockout countdown could outlive a password removal). Semantics otherwise
  identical — the shared reset writes the same four values the inline blocks wrote.
- **Pinned the model's backoff table to production (review FINDING #3):**
  `B1Auth07IsMasterPasswordOracleTest` gains `the model backoff table is pinned to the
  production constants and formula`: `const val MAX_FAILED_ATTEMPTS = 5` plus
  `computeLockoutDelayMs`'s `30_000L` seed, `(failures - MAX_FAILED_ATTEMPTS)`
  exponent, `exponent.coerceAtMost(5)` shift cap and `15 * 60 * 1000L` cap are now
  source-pinned, so a drift between the (tautological) behavioral model and the
  production backoff is caught.