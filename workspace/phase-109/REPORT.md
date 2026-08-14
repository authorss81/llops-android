# Phase 109 — B1-NET-08 fix report

- **Finding:** `B1-NET-08` (INFO) — WebDAV credential store: keystore key not
  bound to any user-authentication gate; `save()` swallows all exceptions and
  returns silently, so a failed write leaves the previous credentials in place
  while the UI believes they were saved.
- **Status:** FIXED. Committed on `main`.

## What changed (before → after)

### 1. `WebDavCredentialStore.kt` — save() now reports success/failure

- `WebDavCredentialStore.kt:150-177` — **`save(...): Boolean`**. **Before**:
  `WebDavCredentialStore.kt:74-94` caught every exception in an empty catch
  (`catch (e: Exception) { /* never write anything */ }`) and returned `Unit` —
  a failed encryption/keystore write was invisible. **After**: the whole save
  (key acquisition INCLUDED) is wrapped so a throwing or unavailable key source,
  an encryption failure, or a failed durable write all return `false`; the
  caller decides how to surface it. No plaintext fallback, no swallowing.
- **Durable-write failures are surfaced too**: the production prefs adapter
  (`WebDavCredentialStore.kt:60-77` `AndroidCredentialPrefs`) uses
  `commit()` — not `apply()` — so a disk write failure is a synchronous
  `Boolean` the store ANDs into the return value. A silent "didn't land" write
  can no longer happen.
- **Failure preserves data**: on a failed save the previous blob/flag are left
  untouched (no partial/destroyed state) but the caller is told the new write
  did NOT persist — fixing the finding's exact "stale credentials while the UI
  believes they were saved" mismatch.

### 2. `WebDavCredentialStore.kt` — optional biometric auth gate

- `WebDavCredentialStore.kt:116-149` — **`getOrCreateKey(context, authBound)`**.
  **Before**: `WebDavCredentialStore.kt:49-61` built a
  `KeyGenParameterSpec` with no `setUserAuthenticationRequired`. **After**:
  when `authBound` is requested the spec adds
  `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`
  and, on API 30+, `setUserAuthenticationParameters(10min, AUTH_BIOMETRIC_STRONG)`;
  on API 26-29 the equivalent `setUserAuthenticationValidityDurationSeconds(600)`
  is used. The bound key lives under a **separate alias**
  (`noteflow_webdav_credentials_key_auth` vs `..._key`, `WebDavCredentialStore.kt:4-5`)
  so the stored blob's binding is **physical (the key itself)**, never an
  advisory prefs flag that a same-process caller could flip.
- `save(serverUrl, username, passwordOrToken, authBound = false)`
  (`WebDavCredentialStore.kt:142`) persists `webdav_auth_bound` alongside the
  blob; `load()` (`WebDavCredentialStore.kt:182-214`) routes through the binding
  the blob was stored under. A legacy blob written before this phase has no
  flag → reads back through the unbound key (backwards compatible).
- The keystore key is still non-extractable in both modes; the biometric gate
  only controls *usability* (`doFinal` requires a recent biometric
  authentication inside the 10-minute window, or fails loudly → surfaced).

### 3. `WebDavSyncDialog.kt` — UI surfaces credential-save failures

- `WebDavSyncDialog.kt:76-92` — `rememberCredentialsIfRequested()` now:
  - passes `authBound = biometricEnabled && BiometricAuthHelper.isBiometricAvailable(context)`
    so the auth gate engages exactly when the user has opted into the app's
    global biometric unlock AND a strong biometric is actually enrolled
    (no optimistic keygen into an un-unlockable state);
  - on a `false` save shows a non-alarming info banner:
    "Sync succeeded, but your credentials could not be saved securely
    (keystore/biometric unavailable). Re-enter them next time." — the UI can no
    longer claim the new credentials were persisted when they were not.

## OS/API floor (AGENTS.md hardware reality)

- minSdk 26. The auth gate intentionally has a **per-API-tier fallback**:
  - API 30+: `setUserAuthenticationParameters(600000, BIOMETRIC_STRONG)` —
    biometric-only, exactly how `SecurityService` gates the DEK
    (`SecurityService.kt:46-53`).
  - API 26-29: the deprecated-but-supported
    `setUserAuthenticationValidityDurationSeconds(600)` (keyguard + biometric
    window model) — the equivalent binding available on those API levels.
  - No strong biometric enrolled → `getOrCreateKey` refuses to mint an
    un-unlockable key and returns `null`; `save()` returns `false` and the
    dialog shows the non-alarming banner (no silent degradation).
- The 10-minute window means a per-operation `doFinal` outside a recent
  biometric unlock fails loudly (returns `false` / `null`) instead of
  discarding the data.

## New pure-JVM tests — `app/src/test/.../WebDavCredentialStoreTest.kt`

10 tests (all pass), via the store's internal persist/key seams
(`CredentialPrefs` + key-source lambda, no Robolectric, JVM `AES/GCM`):
- `saveSuccessPersistsAndLoadsBack` — round-trip through the encrypted blob.
- `saveFailureWhenKeyUnavailablePropagatesNotSwallowed` — key source → null ⇒
  `save() == false`, nothing written, nothing to read (the finding's flattened
  path).
- `saveFailureWhenKeySourceThrowsPropagatesNotSwallowed` — a throwing keystore
  ⇒ `false`, never an exception up-stack, never swallowed.
- `saveFailureLeavesPreviousCredentialsIntactButIsReported` — durable-write
  failure on the 2nd save ⇒ `false` and the OLD credentials still load (stale
  creds preserved, but the caller KNOWS they are stale).
- `saveFailureWhenDurableWriteFailsPropagates` — commit/write failure ⇒ `false`,
  remember flag untouched.
- `saveWithoutAuthBoundUsesUnboundKeySource` / `saveAndLoadWithAuthBoundRoutesThroughBindableKeySource` —
  the `authBound` opt-in routes through the key source and `load()` uses the
  stored binding.
- `loadReturnsNullWhenNothingStored`, `clearRemovesBlobRememberFlagAndBindingFlag`.
- `loadTreatsLegacyBlobWithoutBindingFlagAsUnbound` — pre-fix blobs stay readable
  via the unbound key (no data loss on upgrade).

## Checksum / secrets handling

- No new keys, passwords, salts, or secrets introduced; no logging of keys,
  passwords, or decrypted content anywhere in the changed code.
- The WebDAV password still travels only as keystore-encrypted ciphertext through
  the same `PREF_*` prefs keys (`webdav_encrypted_blob`); the only additional
  persisted value is the Boolean `webdav_auth_bound` (which key to use at
  `load()`) — it is advisory routing, never a security gate (the gate is the
  keystore key itself). Flipping it cannot decrypt anything.
- `allowBackup="false"`, `ClipboardGuard`, and FLAG_SECURE are untouched (no
  manifest/platform change).

## Verification

- `gradle :app:testDebugUnitTest` — **686 tests, 0 failures, 0 errors**
  (aggregated from the test-results XMLs). Includes all 10 new
  `WebDavCredentialStoreTest` cases; the previously-flaky
  `PluginUpdateEngineTest` was green in this run. No regressions.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (Kotlin 2.0.21, no new
  dependencies, no schema change, `.github/workflows/` untouched).

## Out of scope / noted but NOT fixed here

- **`WebDavCredentialStore.clear()`** returns `Unit` (not `Boolean`). It is a
  prefs-removal with no crypto step; the dialog calls it only when the user
  unchecks "remember". A hypothetical failure there leaves creds stored while
  the checkbox is off — a much lower-severity mirror of the save path, and
  outside the finding's exploit. Noted, not changed.
- **B1-NET-01/05/07** (WebDAV `href` host validation, HTTPS→HTTP redirect
  downgrades, download size caps) are separate network findings and were NOT
  touched — distinct phases.
- **`WebDavSyncService` / server-side Basic-auth credential handling** is
  unchanged (B1-NET-01 territory).
- **Biometric-prompt wiring for the WebDAV dialog itself**: the vault unlock
  gate guarantees the auth window; this phase deliberately did NOT add a
  BiometricPrompt to the WebDAV dialog (that would grow the diff well past
  "one finding" scope). If biometric is enabled and the window has lapsed, the
  store fails loudly and the dialog shows the banner — safe, non-alarming.