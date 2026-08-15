# Phase 45 — B1-CRYPTO-02 (HIGH): Master password bypass via the non-auth DEK copy — FIXED

- **Date:** 2026-08-15
- **Finding:** `B1-CRYPTO-02` — *Master password is bypassable: a non-user-authenticated AndroidKeyStore copy of the vault DEK persists after the password is set* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.

## Root cause (before)

1. `SecurityService.getOrCreateDek()` wrapped the vault DEK under a **non-user-authenticated** AndroidKeyStore key and persisted it in plain SharedPreferences (`noteflow_keystore` → `noteflow_sec_dek`).
2. `NoteflowViewModel.setMasterPassword()` re-used that DEK and re-wrapped it under the password-derived KEK but **never called `SecurityService.clearDek()`** — the non-auth copy stayed at rest.
3. `setBiometricEnabled(false, …)` ALSO re-wrapped the DEK non-auth (`storeDek(authRequired = false)`), re-instantiating the bypass.
4. Result: root/forensic access or an in-process plugin could invoke the keystore key under the app UID — no credential, no biometric, no lockout — and recover the vault DEK.

## What changed (after) — `file:line`

### `app/src/main/kotlin/com/authorss81/noteflow/services/SecurityService.kt`
- The device-wrapped DEK copy now lives behind an internal **`DekDeviceStore`** seam:
  - `DekDeviceStore` (interface) + `DekDeviceBlob(encoded, authRequired)` (:150-166);
  - `SharedPrefsDekDeviceStore` (production impl, prefs `noteflow_keystore`/`noteflow_sec_dek` + `dek_auth_required`). **`clear()` uses `commit()`** (disk-sync-acknowledged), not `.apply()`, so a process kill right after "password set" can never resurrect the non-auth blob; it also removes the stale `dek_auth_required` flag (:168-201).
  - `class SecurityService internal constructor(dekStore)` + `SecurityService.forDevice(context)` factory (blocks Android-only construction in tests; all device call sites use it) (:32-47).
- **`readDek()` fails closed** (:150-159): absent blob **or** `authRequired=true` blob ⇒ `null` — no passwordless unwrap is even attempted.
- **`getOrCreateDek()` never mints over an auth-gated blob** (:161-174): if a biometric-gated copy exists it returns null rather than re-persisting a fresh non-auth key.

### `app/src/main/kotlin/com/authorss81/noteflow/services/DekAtRestPolicy.kt` (new, pure JVM)
- `DekAtRestMode` (`DEVICE_WRAPPED_NOT_AUTHGATED` / `PASSWORD_ONLY` / `BIOMETRIC_GATED_AUTH_COPY`) + `DekAtRestPolicy.modeFor(hasMasterPassword, biometricAuthEnabled)` — the single decision table for where the DEK may live at rest.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- `enforceDekAtRestPolicy()` (:1913-1924): `PASSWORD_ONLY` ⇒ `security.clearDek()`; `BIOMETRIC_GATED_AUTH_COPY` ⇒ `storeDek(dek, authRequired = true)` only.
- Wired into:
  - `setMasterPassword` (:1957) — after the wrapped-DEK + settings commit;
  - `changeMasterPassword` (:1999) — replaced the inline `if (biometricAuthEnabled) storeDek(auth=true)` with the policy (also now clears any stale non-auth copy when biometrics are off);
  - `verifyMasterPassword` (:2052) — **every password unlock** re-asserts the policy (clear non-auth; keep auth-gated only when biometrics on);
  - `verifyBiometricsAndUnlock` (:2155) — heals any stale wrapper + pins the auth-gated copy to the current DEK;
  - `setBiometricEnabled` (:2118-2134) — enabling ⇒ auth-gated store only; **disabling ⇒ `clearDek()`** (the pre-fix `storeDek(dek, authRequired = enabled)` non-auth re-wrap is deleted).
- Constructor call site `SecurityService(appContext)` → `SecurityService.forDevice(appContext)` (:115).

### `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
- `NoteflowSqlcipherFactory` call site `SecurityService(context)` → `SecurityService.forDevice(context)` (:345). The factory's `getOrCreateDek()` fallback can no longer resurrect the *real* DEK from prefs (that blob is gone once a password exists).

## Verification output

- `gradle :app:testDebugUnitTest` → **978 tests, 0 failures, 0 errors, 0 skipped** (was 965 before this diff; 13 new tests added).
  - New `DekAtRestPolicyTest` (4): decision table for passwordless / password-only / biometric-gated.
  - New `B1Crypto02DekAtRestTest` (9): drives the **real** `SecurityService` through the `DekDeviceStore` seam —
    - set-master-password (biometrics off) purges the non-auth copy, zero store writes, `readDek()` ⇒ null;
    - every password unlock purges it too;
    - disable-biometrics clears instead of re-wrapping non-auth;
    - an auth-gated blob can never be read passwordlessly (`readDek()`/`getOrCreateDek()` ⇒ null);
    - absent blob ⇒ `readDek()` fails closed;
    - passwordless-boot path unaffected;
    - `clearDek()` empties the store (incl. stale flag) and the password-wrapped DEK stays independent;
    - **source-level wiring pin** (SecurityCryptoAbsenceTest technique): the five master-password/unlock/biometric flows must call `enforceDekAtRestPolicy()` and `setBiometricEnabled` must not contain the old non-auth `storeDek`.
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (clean `rm` of the APK, `packageDebug` re-ran, `app-debug.apk` 173,640,986 B).
  - (Note: the first `assembleDebug` invocation aborted inside `packageDebug` `IncrementalSplitterRunnable` — a transient packaging failure; the identical build completed successfully on the next run, so it is not related to this diff.)
- Existing password set/verify/change tests (encryption/round-trip suites) all still green.

## Checksums / secrets handling

- No new secrets. No keys/passwords/decrypted note content are logged anywhere in this diff.
- The DEK bytes themselves are never logged; the diff only removes/repersists their at-rest wrappers (`commit()` path discards the removed blob).
- `allowBackup=false` (data-extraction rules) and FLAG_SECURE untouched.

## API / hardware floor (API 26+)

- This fix uses only API-26-compatible APIs (`SharedPreferences.commit()`, AES-GCM via JCA — no new platform calls, no AGSL, no dynamic color). No fallback or notice is required for older/lower-end devices.
- The existing `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` API-30+ gating is untouched (that is B1-CRYPTO-07, a separate phase).

## Judgements (out-of-scope, documented only)

- **B1-AUTH-02 (phase-47):** the `NoteflowSqlcipherFactory.create` path that re-derives a DEK on any locked open was NOT changed. Post-fix it can no longer recover the *real* DEK from prefs (the blob is gone), so it does not reintroduce this bypass; the locked-open-data-layer gap is that finding's scope.
- **B1-CRYPTO-05 (phase-64):** `getOrCreateDek` still mints a fresh DEK on undecryptable-blob conditions; this diff only added the "never mint over an auth-gated blob" guard, per B1-CRYPTO-02 scope.
- **B1-CRYPTO-07 (phase-65):** API 26-29 biometric keys remain satisfiable by the device credential — deliberately not touched here.
- **B1-CRYPTO-03 (phase-62):** the salt/wrapped-DEK two-step `.apply()` atomicity is a separate finding; this diff made the *removal* (`clearDek`) durable via `commit()` but did not redesign the settings commit.

## Related-new-notes

- `setMasterPassword` can no longer leave a non-auth copy: enforced immediately after the wrapped-DEK write. A fresh-install flow (passwordless boot → `setMasterPassword`) now ends with ONLY `settings.masterPasswordSalt` + `settings.masterPasswordWrappedDek` + the in-memory DEK holding the SQLCipher passphrase.
- A pre-fix upgrade path is safe: an existing non-auth device copy is read once by `setMasterPassword`/boot to keep existing ciphertext valid, then cleared.

## Files touched

```
app/src/main/kotlin/com/authorss81/noteflow/services/SecurityService.kt
app/src/main/kotlin/com/authorss81/noteflow/services/DekAtRestPolicy.kt          (new)
app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt
app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt
app/src/test/java/com/authorss81/noteflow/DekAtRestPolicyTest.kt                (new)
app/src/test/java/com/authorss81/noteflow/B1Crypto02DekAtRestTest.kt            (new)
docs/ARCHITECTURE.md
docs/phase-status.md
docs/security-report.md
```