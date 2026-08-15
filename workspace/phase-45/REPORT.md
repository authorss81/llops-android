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
  - `DekDeviceStore` (interface) + `DekDeviceBlob(encoded, authRequired)` (:216-224);
  - `SharedPrefsDekDeviceStore` (production impl, prefs `noteflow_keystore`/`noteflow_sec_dek` + `dek_auth_required`). **`clear()` uses `commit()`** (disk-sync-acknowledged, returns the result), not `.apply()`, so a process kill right after "password set" can never resurrect the non-auth blob; it also removes the stale `dek_auth_required` flag (:236-262).
  - `class SecurityService internal constructor(dekStore)` + `SecurityService.forDevice(context)` factory (blocks Android-only construction in tests; all device call sites use it) (:32-45).
- **`readDek()` fails closed** (:155-173): absent blob **or** `authRequired=true` blob ⇒ `null` — no passwordless unwrap is even attempted.
- **`getOrCreateDek()` never mints over an auth-gated blob** (:177-195): if a biometric-gated copy exists it returns null rather than re-persisting a fresh non-auth key. Post-review it also takes `allowPasswordlessMint` (default true) and refuses to mint ANYTHING when the vault is password-protected (see the factory gate below).

### `app/src/main/kotlin/com/authorss81/noteflow/services/DekAtRestPolicy.kt` (new, pure JVM)
- `DekAtRestMode` (`DEVICE_WRAPPED_NOT_AUTHGATED` / `PASSWORD_ONLY` / `BIOMETRIC_GATED_AUTH_COPY`) + `DekAtRestPolicy.modeFor(hasMasterPassword, biometricAuthEnabled)` — the single decision table for where the DEK may live at rest.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- `enforceDekAtRestPolicy()` (:1918-1936) returns `Boolean` (phase-45 review fix): `PASSWORD_ONLY` ⇒ `security.clearDek()` → the durable `commit()` result; `BIOMETRIC_GATED_AUTH_COPY` ⇒ `storeDek(dek, authRequired = true)` → its success (subscript failure surfaces instead of silently claiming "enabled"); `DEVICE_WRAPPED_NOT_AUTHGATED` ⇒ `true`.
- Wired into:
  - `setMasterPassword` (:1963) — after the wrapped-DEK + settings commit;
  - `changeMasterPassword` (:2005) — replaced the inline `if (biometricAuthEnabled) storeDek(auth=true)` with the policy (also now clears any stale non-auth copy when biometrics are off);
  - `verifyMasterPassword` (:2058) — **every password unlock** re-asserts the policy (clear non-auth; keep auth-gated only when biometrics on);
  - `verifyBiometricsAndUnlock` (:2171) — heals any stale wrapper + pins the auth-gated copy to the current DEK;
  - `setBiometricEnabled` (:2124-2152) — phase-45 review fix: the TARGET state is applied first, then the policy; the setting is only persisted once the at-rest state was actually achieved (a failed auth-gated store now returns `false` and reverts `settings.biometricAuthEnabled`). The pre-fix `storeDek(dek, authRequired = enabled)` non-auth re-wrap (on disabling) is deleted.
- Constructor call site `SecurityService(appContext)` → `SecurityService.forDevice(appContext)` (:115).

### `app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt`
- `NoteflowSqlcipherFactory` call site `SecurityService(context)` → `SecurityService.forDevice(context)` (:345). The factory's `getOrCreateDek()` fallback can no longer resurrect the *real* DEK from prefs (that blob is gone once a password exists).
- **Phase-45 review fix** (:345-362): a locked open (`VaultKeyHolder.dek == null`) on a password-protected vault now fails closed instead of reaching `getOrCreateDek()`. The factory reads `SettingsManager.hasMasterPassword` and calls `getOrCreateDek(allowPasswordlessMint = !passwordProtected)`. Before this, a locked open would mint a FRESH non-auth DEK — recreating the bypass blob AND opening the real vault with the wrong SQLCipher passphrase (`SQLiteNotADatabaseException` → the phase-43 classifier quarantines a healthy vault).

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

---

## Addendum — `.done` marker restored (2026-08-15, re-run)

The B1-CRYPTO-02 fix (commit `2fc44e0`) was fully implemented, committed and
verified, but the phase's `.done` marker was never committed with it. The next
runner tick re-selected the completed phase and left stale `.no_work`
(empty) + `.attempts` (`1`) markers (commit `125d6ce`) — the same
phase-32/42/43 scenario where a phase with all work already committed gets
re-run and the evidence gate records a no-work "failure".

On this re-run the fix was re-verified against the current tree:

- `gradle :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **978 tests / 0
  failures / 0 errors / 0 skipped** (94 test suites; `B1Crypto02DekAtRestTest`
  9/9 + `DekAtRestPolicyTest` 4/4 green, all existing password set/verify/change
  suites green).
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (2m 35s, 49 executed /
  41 up-to-date).

Marker state corrected exactly as phase-42 (`workspace/phase-42/{.no_work => .done}`
in `ba74d5d`) and phase-43 (`55671e1`): `.no_work` renamed to `.done`,
`.attempts` deleted, everything committed + pushed so `select-phase` stops
re-selecting this completed phase. No code, schema, dependencies, or
`.github/workflows/` changed in this addendum.

---

## Addendum 2 — review FINDINGS fixed (2026-08-15)

The phase-45 review raised eight findings; all actionable ones are fixed here.

1. **HIGH — locked open could mint a fresh non-auth DEK over a password-protected
   vault.** `NoteflowSqlcipherFactory.create` now reads
   `SettingsManager.hasMasterPassword` and calls
   `security.getOrCreateDek(allowPasswordlessMint = !passwordProtected)`
   (`NoteflowDatabase.kt:345-362`); `getOrCreateDek` itself also refuses to mint
   when the gate is off (`SecurityService.kt:177-195`). A locked open now fails
   closed (`VaultKeyHolder.dek == null` → the existing "Vault is locked" throw)
   instead of minting a wrong-key DEK that would both recreate the non-auth blob
   and trip the phase-43 quarantiner via `SQLiteNotADatabaseException`.
2. **MEDIUM — `setBiometricEnabled(true, …)` could report success with no usable
   blob.** `storeDek` (`SecurityService.kt:127-150`) and `clearDek`/`DekDeviceStore.clear`
   (`SecurityService.kt:206-212`, `:216-220`, `:251-256`) now return success/failure;
   `enforceDekAtRestPolicy()` (`NoteflowViewModel.kt:1918-1936`) propagates it;
   `setBiometricEnabled` applies the target state first, persists the setting only
   when the at-rest state was actually achieved, and reverts on failure
   (`NoteflowViewModel.kt:2124-2152`).
3. (folded into 2) the enable path no longer swallows a failed auth-gated store.
4. (kept as-is) unlock flows re-assert the policy best-effort; the durable success
   contract lives in the factory gate + `setBiometricEnabled`, which are the user-
   and attacker-reachable guarantees.
5. **NEW TESTS** (981 total, +3 over 978): `locked open never mints a fresh DEK
   over a password-protected vault`, `passwordless vault may still mint when a
   device copy is missing`, `db factory must gate passwordless minting on the
   master-password state` (source-level wiring pin for `NoteflowDatabase.kt`).
   The passwordless-boot test now asserts the non-gated copy is *preserved*
   (formerly asserted against an empty fake store).
6. **Marker hygiene corrected again**: the review run re-created `.no_work`,
   `.deferred`, `.deferred_attempts`, `.session` alongside `.done`; all stale
   markers removed, `.done` kept.
7. **REPORT `file:line` anchors refreshed** to the current tree (fed from the
   post-fix line numbers above).
8. (cosmetic) trailing newlines added to `SecurityService.kt` and
   `DekAtRestPolicy.kt`.

Verified on this tree: `gradle :app:testDebugUnitTest` → **981 tests / 0
failures / 0 errors / 0 skipped** (94 suites). One unrelated pre-existing flaky
test (`WikiLinkParserCacheUnitTest#a cancelled scan propagates cancellation…`,
coroutine-cancellation timing) failed on one full-suite run, then passed in
isolation and on the final full-suite run; it is untouched by this diff.
`gradle :app:assembleDebug` → **BUILD SUCCESSFUL**.

Scope remains tight: no schema change, no migration, no new dependencies,
`.github/workflows/` untouched.