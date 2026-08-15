# Phase 47 — B1-AUTH-02 (HIGH): locking zeroizes `VaultKeyHolder` but leaves the keyed SQLCipher connection open — FIXED

- **Date:** 2026-08-15
- **Finding:** `B1-AUTH-02` — *Locking zeroizes `VaultKeyHolder` but leaves the keyed SQLCipher connection open, and the DB factory silently re-derives the DEK from prefs while the vault is locked — the lock is not enforced at the data layer* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.

## Root cause (before)

1. `NoteflowViewModel.lock()` (`NoteflowViewModel.kt:2485-2498`) called `repository.zeroizeKey()` only. The Room/SQLCipher connection (`NoteflowDatabase.INSTANCE`) stayed **open and keyed** (`NoteflowDatabase.kt` `dispose()` was never called during a lock), so a stale coroutine / enabled plugin hook sniffed plaintext for all non-field-encrypted columns off the surviving handle.
2. `NoteflowSqlcipherFactory.create` (`NoteflowDatabase.kt:343-362`) — on any FRESH open with `VaultKeyHolder.dek == null` — walked the `SettingsManager.hasMasterPassword` gate into `SecurityService.getOrCreateDek(allowPasswordlessMint = !passwordProtected)`. For a password-protected vault that call failed closed *after* the phase-45 fix (returned null ⇒ throw), but the code path still reached the DEK source-of-truth on a locked open and, before phase-45, re-materialized a non-auth device copy.
3. Net effect: after `lock()`, `repository`/`db` access returned plaintext (live keyed handle) or could trigger an open that reached key material with no credential — the Compose `if (authenticated)` LockScreen boolean was not enforced at the data layer.

## What changed (after) — `file:line`

### 1. `lock()` drops the keyed connection at the data layer — `NoteflowViewModel.kt:2528-2560`

```kotlin
if (settings.hasMasterPassword) {
    sectionsJob?.cancel()
    pagesJob?.cancel()
    NoteflowDatabase.dispose()          // :2546  close + forget the Room/SQLCipher instance
    databaseDisposedByLock = true       // :2547  unlock must reinstate a live connection
    dataInitialized = false             // :2548  unlock re-establishes observer jobs
}
```

- `NoteflowDatabase.dispose()` (`NoteflowDatabase.kt:395-400`) closes the Room instance and nulls the singleton, so **no keyed SQLCipher handle survives the lock**. Any stale handle now fails closed with the connection pool closed.
- Observer jobs are cancelled so nothing keeps collecting from the closed vault, and `dataInitialized = false` makes the next unlock re-run `initializeData()` against the fresh connection (re-creating the section/page observers so the phase-43 `B1-AUTH-07` half-implementation does not leave the home list empty).
- Skipped for passwordless vaults: there is no lock boundary there (the device-wrapped DEK is the boot credential by design), so closing the still-active session would only break the running UI.

### 2. Factory fails closed on a locked open — `NoteflowDatabase.kt:343-371`

```kotlin
var dek = VaultKeyHolder.dek
if (dek == null) {
    val passwordProtected = SettingsManager(context.applicationContext).hasMasterPassword
    if (!LockedOpenGuard.isOpenAllowed(dekInMemory = false, hasMasterPassword = passwordProtected)) {
        throw IllegalStateException("Vault is locked: database key not available")   // :358
    }
    // Passwordless vault only:
    val security = SecurityService.forDevice(context)
    dek = security.getOrCreateDek(allowPasswordlessMint = true)                       // :365
    if (dek != null) VaultKeyHolder.dek = dek
}
```

- The locked branch throws **before** any `getOrCreateDek()` / persisted-copy access — the DEK source of truth is never touched on a locked open. The decision table lives in the new pure-JVM `services/LockedOpenGuard.kt` (`LockedOpenGuard.isOpenAllowed`, `:26`): DEK present ⇒ allowed; DEK absent + master password ⇒ refused; DEK absent + passwordless ⇒ allowed (device copy is the boot credential).
- The thrown `IllegalStateException("Vault is locked: database key not available")` is deliberately NOT wildcarded as corruption by the phase-43 classifier (`CorruptionClassifierTest:84` pins `isDatabaseCorruptException` = false for it), so a locked open can never quarantine a healthy vault.

### 3. Explicit unlocks reinstate the live connection — `NoteflowViewModel.kt:2058-2063, 2080, 2200`

```kotlin
private fun reinstateDatabaseAfterLock(): Boolean {
    if (!databaseDisposedByLock) return true                 // cold start / in-session re-verify: no-op
    return try {
        repository.reopenDatabase(getApplication())          // dispose() + fresh getDatabase() with DEK present
        databaseDisposedByLock = false
        true
    } catch (e: Exception) { false }                         // fail closed
}
```

- `verifyMasterPassword` (`:2080`) and `verifyBiometricsAndUnlock` (`:2200`) place the DEK in memory first, then call `reinstateDatabaseAfterLock()` **before** `_authenticated.value = true` so the dbGate flows re-subscribe against a live connection. A failure zeroizes the DEK and returns `false` without failed-attempt/lockout bookkeeping — a transient open error is never a "wrong password".
- `onCleared()` (`:2562-2569`) now also `NoteflowDatabase.dispose()` so no keyed handle outlives the token that opened it across a ViewModel teardown.

### 4. Pure-JVM tests (new)

- `app/src/test/java/com/authorss81/noteflow/LockedOpenGuardTest.kt` (3 tests) — decision table.
- `app/src/test/java/com/authorss81/noteflow/B1Auth02LockedOpenTest.kt` (9 tests) — a fake-repository model of the factory's exact guarded branch proves a locked open throws and **never** invokes the key re-derivation source (even when a persisted copy would return bytes); an unlocked open never touches it; a passwordless vault re-reads its device copy exactly once; `VaultKeyHolder.zeroize()` clears memory; plus source-level wiring pins (same technique as `B1Crypto02DekAtRestTest`) that `lock()` disposes and never re-derives, the factory throws before any `getOrCreateDek()`, and both unlock paths call `reinstateDatabaseAfterLock()`.

## Verification output

- `gradle :app:testDebugUnitTest` → **1013 tests, 0 failures, 0 errors** (98 suites; was 1001 before this diff — 12 new).
  - Existing security suites still green: `B1Crypto02DekAtRestTest`, `CorruptionClassifierTest`, `PluginBytecodeIsolationTest`, `DekAtRestPolicyTest`, `PluginUpdateEngineTest` (its documented timing flake passed in this run), etc. — the factory-restructure kept both existing source-pins (`allowPasswordlessMint` + `hasMasterPassword` in `NoteflowSqlcipherFactory`).
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (57 tasks executed from scratch on a `--rerun-tasks` clean-daemon rebuild). (One earlier `assembleDebug` invocation aborted with a transient packaging/daemon flake; the identical build — including a full `--rerun-tasks` rebuild after `gradle --stop` — completed successfully. No code/compilation error was involved.)
- `gradle :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minify, 83 tasks; CI parity check — release keeps the new `internal LockedOpenGuard` wired correctly under minification).

## Checksums / secrets handling

- No new secrets. No keys/passwords/decrypted note content are logged anywhere in this diff.
- The fix only removes key material from the data plane: `dispose()` closes the connection, `LockedOpenGuard` never reads a wrapper, and the locked branch throws before the DEK source is touched.
- `allowBackup=false`, data-extraction rules, `ClipboardGuard` and FLAG_SECURE are untouched.

## API / hardware floor (API 26+)

- `LockedOpenGuard` is a pure `kotlin`/`java.lang` decision table (API 1+); `NoteflowDatabase.dispose()`/`RoomDatabase.close()` is API 16+. No AGSL, no dynamic color, no new platform calls, no fallback or notice needed for older/lower-end devices.

## Judgements (out-of-scope, documented only)

- **B2-UI-1 (write side of the same lock boundary):** in-flight save coroutines that execute after `lock()` zeroized the DEK are THIS finding's complementary write-side defect, but are a separate finding scheduled for phase-49. This phase deliberately does not touch autosave scheduling; the new `dispose()` additionally makes those in-flight writes fail closed at the connection pool rather than write plaintext rows.
- **B1-AUTH-03 (plugin lifecycle hooks before unlock):** the `onEnable(context)` hook running at every launch on the lock screen is a separate MEDIUM finding (scheduled phase-67); it is the *caller* that would have exploited the old factory path — with the phase-47 fail-closed factory, even such a hook can no longer open the vault or recover key material on a locked vault.
- **B1-CRYPTO-04 / B1-PLAT-8 / B1-AUTH-07 (offline brute-force, min-length policy, unlock-state re-init gaps):** not this phase; the `dataInitialized=false` reset in `lock()` incidentally re-arms the phase-43/B1-AUTH-07 re-initialization on the next unlock but full state re-validation remains their scope.
- **Passwordless vaults get no disposal on `lock()`:** intentional — there is no credential barrier to enforce (the device-wrapped DEK is readable without credentials by design), and disposing a live, still-active session would only break the running UI.

## Files touched

```
app/src/main/kotlin/com/authorss81/noteflow/services/LockedOpenGuard.kt       (new)
app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt
app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt
app/src/test/java/com/authorss81/noteflow/LockedOpenGuardTest.kt               (new)
app/src/test/java/com/authorss81/noteflow/B1Auth02LockedOpenTest.kt            (new)
docs/ARCHITECTURE.md
docs/phase-status.md
docs/security-report.md
```