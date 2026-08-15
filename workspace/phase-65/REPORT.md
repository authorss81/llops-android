# Phase 65 — B1-CRYPTO-07: the biometric DEK key is only biometric-gated on API 30+; below that any device credential (PIN/pattern/password) satisfies it

**Status:** DONE (2026-08-15)
**Finding:** [B1-CRYPTO-07] (MEDIUM), Batch 1 · Cryptography & key management
**Scope:** one finding — no DB schema change, no migration, no new deps, `.github/workflows/` untouched.

## 1. What the finding said

- **Evidence:** `SecurityService.kt:38-45` — `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` was applied ONLY on `Build.VERSION.SDK_INT >= R`; on API 26-29 the DEK-wrapping key was created with bare `.setUserAuthenticationRequired(true)`; `BiometricAuthHelper.kt:11-17` only checked strong-biometric *availability* at prompt time, not what the KEY requires; `SecurityService.kt:58-66` (`getDecryptionCipher`) created the `_auth` key on demand.
- **Exploit scenario:** On an API 26-29 device with biometrics enabled, the DEK-wrapped device copy (`noteflow_sec_dek`) is protected by a key that a screen-PIN (4-6 digits, unlimited attempts at the keystore level) can authorize, so an attacker with the device unlocks it with the PIN while the UI claims biometric-only protection. On API 30+ the spec is correct → version-dependent downgrade.

### Confirmed platform semantics (why the bare-required path is the trap)

AOSP `KeyGenParameterSpec.Builder` defaults `userAuthenticationValidityDurationSeconds` to **0** (`frameworks/base …/KeyGenParameterSpec.java`, `private int mUserAuthenticationValidityDurationSeconds = 0`). The pre-30 keystore daemon maps **any** value that is not `-1` to `KM_TAG_USER_AUTH_TYPE = HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC` — i.e. a device credential **OR** a biometric authorizes the key. Only `setUserAuthenticationValidityDurationSeconds(-1)` maps to `HW_AUTH_BIOMETRIC` (biometric-only, per use) on API 26-29 — and even that binds "any biometric", never STRONG-guaranteed. `AUTH_BIOMETRIC_STRONG` itself is an API-30+ constant, so the platform **cannot** express a strong-only DEK key below API 30.

## 2. Root cause (before/after)

### Before

```kotlin
// SecurityService.getOrCreateKey(authRequired = true) — the DEK wrapper key:
.setUserAuthenticationRequired(authRequired)
.let {
    if (authRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        it.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
    } else {
        it                                   // API 26-29: bare required(true)
    }                                        //   validity stays 0 → HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC
}
```

On API 26-29 the key's default validity (0) lands in the device-credential path: a screen PIN/pattern/password satisfies the unwrap. `setBiometricEnabled(true, …)` happily persisted the auth-gated blob and flipped the setting — the UI claimed "biometric unlock" while a PIN opened it.

### After

The fix has **four layers**:

1. **Authoritative refusal** — `NoteflowViewModel.setBiometricEnabled`: on API 26-29 an *enable* is refused with a clear, non-alarming message (`_biometricRefusalMessage`); the setting is never flipped, no weak-bound blob is ever written.
2. **Migration/downgrade** — `NoteflowViewModel.enforceDekAtRestPolicy`: a legacy `biometricAuthEnabled = true` that survived a pre-fix install on API 26-29 is downgraded to PASSWORD_ONLY (setting off + `security.clearDek()`) on the next password set/change/unlock, with the same one-time message.
3. **Defensive key binding** — `SecurityService.getOrCreateKey`: any pre-30 auth key (legacy/edge re-provision) is bound with `setUserAuthenticationValidityDurationSeconds(-1)` — the strongest pre-30 expression (biometric-only per use), never the bare `setUserAuthenticationRequired(true)` whose default accepts a device credential.
4. **No cipher below API 30** — `SecurityService.getDecryptionCipher` + `NoteflowViewModel.getBiometricCipher` return null below API 30, so the biometric prompt is never given a weak-bound cipher; `LockScreen` falls back to the master password and `disableBiometricFallback()` turns the setting off.

Plus the finding's "store an explicit marker of the API level at key creation": `storeDek` now stamps `wrapperApiLevel = Build.VERSION.SDK_INT` onto the persisted `DekDeviceBlob` (non-secret, auditable; deliberately NOT consulted on the read path — see §8).

## 3. What changed (file:line evidence)

### New pure-JVM decision table — `services/BiometricKeyBindingPolicy.kt` (new, 77 lines)

- `MIN_API_FOR_STRONG_BIOMETRIC_BINDING = 30` (`:45`) — the first API level whose Keystore can bind a key to `AUTH_BIOMETRIC_STRONG`.
- `strongBiometricKeyBindingSupported(apiLevel)` (`:52-53`) — `apiLevel >= 30`.
- `PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS = -1` (`:63`) — the only pre-30 validity that excludes device credentials.
- `refuseEnableMessage(apiLevel)` (`:70-77`) — non-alarming, human-readable refusal; null on API 30+.
- KDoc documents the pre-30 `HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC` trap and the four enforcement layers.

### `services/DekAtRestPolicy.kt`

- `modeFor` gained `strongBiometricBindingSupported: Boolean = true` (`:54`, default keeps pre-phase-65 call sites/tests source-compatible) and returns `PASSWORD_ONLY` when `biometricAuthEnabled && !strongBiometricBindingSupported` (`:58`) — the B1-CRYPTO-02 at-rest policy now downgrades a non-STRONG-boundable biometrics state. KDoc (`:26`) documents the phase-65 semantics.

### `services/SecurityService.kt`

- `getOrCreateKey` (`:76-98`): the auth-key branch is now three-way — `!authRequired` → unchanged; `SDK >= R` → `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (`:81`); API 26-29 → `@Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS)` (`:94-95`), with the device-credential-trap explanation in the comment (`:83-92`).
- `getDecryptionCipher` (`:105-127`): first line is the policy gate `if (!strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)) return null` (`:111`) — the cipher is never handed to the prompt below API 30.
- `storeDek` (`:170-184`): stamps `wrapperApiLevel = Build.VERSION.SDK_INT` on the persisted blob (the finding's explicit API-level marker).
- `DekDeviceBlob` (`:310-318`) gains `wrapperApiLevel: Int = 0` (default keeps existing constructors source-compatible).
- `SharedPrefsDekDeviceStore` persists `dek_wrapper_api_level` on write (`:355`), reads it back (`:345`), and drops it on clear.

### `services/BiometricAuthHelper.kt`

- `isBiometricAvailable` KDoc (`:16`) now states explicitly that it answers "strong biometric *present* at prompt time", NOT "can the key be STRONG-bound" (the finding's evidence point).
- New `canCreateStrongBiometricBoundKey()` (`:35-36`) delegates to the pure-JVM policy — the availability-vs-binding distinction is now a first-class API.

### `ui/viewmodel/NoteflowViewModel.kt`

- `_biometricRefusalMessage` / `biometricRefusalMessage` StateFlow (`:1106-1107`) — one-shot, non-alarming message surfaced by the settings dialog.
- `enforceDekAtRestPolicy` (`:2194-2227`): first downgrades a legacy `biometricAuthEnabled=true` below API 30 (setting off `:2204`, `_biometricEnabled` off, refusal message `:2206-2207`), then feeds `strongBiometricBindingSupported` into `DekAtRestPolicy.modeFor` (`:2213-2214`) so the weak-bound device copy is `clearDek()`-ed — never re-written on this or any future unlock.
- `setBiometricEnabled` (`:2531-2568`): the authoritative enable gate — `enabled && !strongBiometricKeyBindingSupported(SDK_INT)` ⇒ set `_biometricRefusalMessage` + `return false` (`:2542-2545`) BEFORE the setting is flipped or a blob is written; clears the message on a successful enable (`:2564`).
- `getBiometricCipher` (`:2573-2581`): returns null below API 30 (`:2579`) — `LockScreen` then falls back to the master password and calls `disableBiometricFallback()`.

### `ui/components/Dialogs.kt` (`SecuritySettingsDialog`)

- Collects `biometricRefusalMessage` (`:427`).
- The biometric Switch refuses an *enable* up-front on a non-strong platform (`:502`, `!BiometricAuthHelper.canCreateStrongBiometricBoundKey()`) and shows the refusal message inline — no pointless password prompt on a device where it can never work.
- The confirm handler surfaces the ViewModel's refusal message instead of the generic "Incorrect Master Password" (`:539`).

## 4. Why this closes the vulnerability

1. **A new biometric enable on API 26-29 is impossible** (`setBiometricEnabled` gate): no weak-bound auth-gated blob can be created, so no DEK device copy exists on those devices beyond the password-derived KEK — the PIN-bypass vector is gone because the biometric unlock feature is refused there.
2. **Existing pre-fix state is migrated away** (`enforceDekAtRestPolicy` downgrade): the first password set/change/unlock after the update clears the weak-bound blob and flips the setting off, with a one-time non-alarming message (AGENTS.md hardware-reality rule: never silent degradation).
3. **Defense-in-depth at the key**: any auth key that is ever (re)created below API 30 is bound biometric-only-per-use (`-1`), never the device-credential path.
4. **Defense-in-depth at the cipher**: below API 30 no DEK-wrapping cipher is ever handed to the biometric prompt, so even a legacy blob cannot be unwrapped through the UI.

## 5. Tests

New `app/src/test/java/com/authorss81/noteflow/B1Crypto07BiometricKeyBindingTest.kt` (20 tests):

- **Policy (pure JVM):** API 26-29 refused / 30+ supported; `PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS == -1`; refusal message present + non-alarming below API 30 (mentions the strong-biometric requirement and the Master Password fallback) and absent on API 30+; `DekAtRestPolicy.modeFor` with the new input — biometrics-on + non-strong-boundable ⇒ `PASSWORD_ONLY`, API-30+ ⇒ `BIOMETRIC_GATED_AUTH_COPY`, biometrics-off ⇒ `PASSWORD_ONLY` regardless, passwordless boot unaffected, and the 2-arg call keeps its pre-fix default (backward compatibility).
- **Source pins** (same technique as `B1Crypto02DekAtRestTest`/`B1Crypto05SilentRekeyTest`, since key creation is AndroidKeyStore-bound and cannot run on the JVM): `SecurityService` binds API-30+ keys via `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; the pre-30 branch calls `setUserAuthenticationValidityDurationSeconds(PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS)` (never the bare-required path) gated on `SDK_INT >= R`; `storeDek` stamps `wrapperApiLevel = Build.VERSION.SDK_INT` and the store persists `KEY_WRAPPER_API_LEVEL`; `getDecryptionCipher` is policy-gated; the pre-30 comment documents the PIN/pattern/password trap; `NoteflowViewModel.setBiometricEnabled` refuses (message set) BEFORE flipping the setting; `enforceDekAtRestPolicy` downgrades + clears; `getBiometricCipher` is policy-gated; `BiometricAuthHelper` exposes `canCreateStrongBiometricBoundKey` delegating to the policy; the settings dialog gates the switch + surfaces the refusal message.

## 6. Verification output

- `gradle testDebugUnitTest` → **1275 tests, 2 failures — both `B1Plat01ReleaseSigningTest`** (`docs/RELEASE.md` + `app/build.gradle.kts` signing asserts; files untouched by this phase; verified identical on a clean stashed tree — 8 tests / 2 failed pre-stash, as documented in phases 55/59/60/61/62/63/64). The phase-64 total was 1255 ⇒ **+20 new tests, 0 regressions.** (A transient `WikiLinkParserCacheUnitTest` cancellation test flaked on the first run and passed on the re-run — the same documented flake as phase-40.)
- `gradle assembleDebug` → **green**. Debug APK `app-debug.apk` 173.7 MB, SHA-256 `d571b695d77aaa1563817899d7d3f7ebcd99c96d071af38d122f2532cd05b66c`.

## 7. Constraints honored

- **No DB schema change**, no migration, no new dependencies, `.github/workflows/` untouched.
- Never logs keys/passwords/decrypted content; `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE intact (untouched).
- **OS/API floor (API 26+):** the refusal/downgrade is exactly the AGENTS.md hardware-reality rule — the biometric-lock feature requires API 30+; on API 26-29 the user gets a one-time non-alarming message and the setting stays off (Master Password protection, which is the same protection a pre-fix API 26-29 user's PIN bypass was eroding).

## 8. Out of scope / related findings (not touched)

- **`wrapperApiLevel` marker is informational, not read-gated.** The finding says "store an explicit marker of the API level at key creation" — done (`DekDeviceBlob.wrapperApiLevel`). It is deliberately NOT consulted on the read path: pre-fix blobs carry `wrapperApiLevel = 0` (unknown), and gating on it would refuse the first post-upgrade biometric unlock of legitimate pre-fix API-30+ users (whose keys ARE `AUTH_BIOMETRIC_STRONG`-bound). Enforcement lives in the four gates above. A future `wrapperApiLevel >= 30` read-gate becomes safe once every surviving blob has been re-stamped by one post-fix unlock.
- **Minimum-SDK bump to API 30** ("use `AUTH_BIOMETRIC_STRONG` unconditionally once a minimum-SDK bump is feasible") is a product decision (minSdk 26) — not this phase. The refusal/downgrade path implements the finding's primary fix.
- **`WebDavCredentialStore.getOrCreateKey`** (`WebDavCredentialStore.kt:134-138`) intentionally uses a positive validity-duration binding on API 26-29 for the remembered-WebDAV-credentials key — that is the B1-NET-08 feature's documented design ("keyguard + biometric" on API 26-29) and is a separate finding's scope. Documented here; NOT changed (this phase fixes only the vault DEK key).
- **B1-CRYPTO-05 / -06** (keystore-key-lost silent re-key / tamper-check fail-open) — separate findings, already fixed in phases 64 / later.
- **Biometric self-heal on key loss** remains as documented in phase-64 §8 (the next password unlock re-wraps the same DEK under a fresh keystore key).
