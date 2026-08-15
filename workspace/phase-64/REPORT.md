# Phase 64 — B1-CRYPTO-05: `getOrCreateDek` silently mints a brand-new DEK when the stored one becomes undecryptable → silent re-key destroys access to existing data

**Status:** DONE (2026-08-15)
**Finding:** [B1-CRYPTO-05] (MEDIUM), Batch 1 · Cryptography & key management
**Scope:** one finding — no DB schema change, no migration, no new deps, `.github/workflows/` untouched.

## 1. What the finding said

- **Evidence:** `SecurityService.kt:134-144` (`readDek` returned `null` on ANY failure
  path, including AndroidKeyStore key loss → `EncryptionService.generateDek()` →
  `storeDek` OVERWRITES the pref); `SecurityService.kt:104-106` (`storeDek` swallowed
  every exception with no signal); `NoteflowDatabase.kt:335-343` (the factory then used
  the brand-new DEK as the SQLCipher passphrase).
- **Exploit scenario:** AndroidKeyStore aliases do not survive app-data restores / ROM
  migrations / keystore resets on some OEMs. If prefs survive but the keystore key does
  not, `readDek()` → null, a fresh DEK is minted and persisted, and the next DB open
  fails against the still-encrypted vault → the phase-09 H2 handler quarantines the real
  vault as `*.corrupt-*` and the genuinely-survivable data is permanently unrecoverable,
  with no diagnostic distinguishing "key lost" from "data corrupt".

## 2. Root cause (before/after)

### Before

```kotlin
// SecurityService.readDek() — ONE null for EVERYTHING:
fun readDek(): ByteArray? {
    val blob = dekStore.read() ?: return null          // (a) no blob  → null
    if (blob.authRequired) return null                 // (b) biometric → null
    return try {
        ...
    } catch (e: Exception) {
        null                                           // (c) KEY LOST → null  ← conflated
    }
}

fun getOrCreateDek(allowPasswordlessMint: Boolean = true): ByteArray? {
    val existing = readDek()
    if (existing != null) return existing
    ...
    val newDek = EncryptionService.generateDek()       // silent mint
    storeDek(newDek, authRequired = false)             // OVERWRITES the stored wrapper
    return newDek
}
```

### After

```kotlin
// SecurityService.readDekResult() — sealed, distinguishes the four states:
fun readDekResult(): DekReadResult {
    val blob = dekStore.read() ?: return DekReadResult.NoBlob       // (a)
    if (blob.authRequired) return DekReadResult.AuthRequired        // (b)
    return try {
        ... DekReadResult.Unlocked(dek)
    } catch (e: Exception) {
        DekReadResult.KeyLost(blob.wrapperAlias)                    // (c) now distinguishable
    }
}

fun getOrCreateDek(allowPasswordlessMint: Boolean = true): ByteArray? {
    return when (val result = readDekResult()) {
        is DekReadResult.Unlocked -> result.dek
        DekReadResult.AuthRequired -> null                          // must use the biometric flow
        is DekReadResult.KeyLost -> throw KeystoreKeyLostException( // NEVER re-key
            "Stored device DEK copy cannot be unwrapped — … restore from a backup or start fresh.",
            result.wrapperAlias)
        DekReadResult.NoBlob -> { if (!allowPasswordlessMint) return null; …mint… }
    }
}
```

A stored-but-undecryptable wrapper now **throws** at every mint site instead of silently
re-keying, so the next SQLCipher open can never try a wrong passphrase and the phase-43
quarantiner is never tripped for this cause.

## 3. What changed (file:line evidence)

### New pure-JVM decision type — `services/DekReadResult.kt` (new)

- `sealed class DekReadResult` — `NoBlob` / `Unlocked(dek)` / `AuthRequired` /
  `KeyLost(wrapperAlias)`.
- `class KeystoreKeyLostException(message, wrapperAlias)` — typed `RuntimeException`
  thrown at every mint site on key loss.

### `services/SecurityService.kt`

- `SecurityService.readDekResult()` (`:162-186`) — the sealed read; distinguishes
  "no blob stored" from "blob present but undecryptable" (keystore key lost / unreadable
  blob). Returns the **non-secret** `wrapperAlias` marker on `KeyLost`.
- `SecurityService.getOrCreateDek()` (`:203-236`) — `KeyLost` **throws**
  `KeystoreKeyLostException` (both with and without `allowPasswordlessMint`, so the
  factory's null fall-through can never silently mint either); mint is reachable only
  from `NoBlob` + `allowPasswordlessMint`.
- `SecurityService.readDek()` (`:199-202`) — legacy accessor re-implemented on top of
  `readDekResult` (flattens to null; used only by non-minting readers).
- `SecurityService.storeDek()` (`:142-149`) — stamps the non-secret marker:
  `wrapperAlias` = the actual keystore alias used (`noteflow_dek_key` /
  `noteflow_dek_key_auth`) + `wrapperVersion = 1`.
- `DekDeviceBlob` (`:262-273`) — gains `wrapperAlias: String?` / `wrapperVersion: Int`
  (defaults keep existing constructors source-compatible).
- `SharedPrefsDekDeviceStore` (`:285-330`) — persists `dek_wrapper_alias` +
  `dek_wrapper_version` on `write`, reads them back, and drops them on `clear()`.

### `ui/viewmodel/NoteflowViewModel.kt`

- `_keystoreKeyLost` / `keystoreKeyLost` StateFlow (`:984-985`) — the in-session
  keystore-key-lost recovery state (the vault DB is NOT quarantined here).
- `dbGate` (`:1088-1092`) — now `combine(_authenticated, _corruptionBlocked,
  _keystoreKeyLost)`; no note-data flow opens the DB while the key-lost recovery screen
  is up (a passwordless factory open would throw `KeystoreKeyLostException`).
- passwordless boot `init` block (`:1336-1365`) — routes through `readDekResult()`:
  `Unlocked` → use DEK + init; `NoBlob` → mint + persist (true first run / after
  removeMasterPassword); `AuthRequired` (anomalous for a passwordless vault) and
  `KeyLost` → set `_keystoreKeyLost` and **never** mint. The old `var dek =
  security.readDek(); if (dek == null) { generateDek(); storeDek() }` collapse is gone.
- `initializeData` catch (`:1213-1226`) — a failed open whose device copy reads
  `KeyLost` (and whose corruption flag is NOT set) surfaces the key-lost recovery screen
  instead of rethrowing/treating as corruption.
- `setMasterPassword` (`:2218-2234`) — the `existing ?: security.readDek()` fallback is
  now `readDekResult()`; `KeyLost` throws `KeystoreKeyLostException` (refuses to wrap a
  fresh DEK under a new password while the vault is still encrypted under the lost key).
- `attemptKeystoreKeyLostRecoveryFromBackup(uri, password, onError)` (`:1895-1941`) —
  the restore-from-backup path: validates the password BEFORE closing the DB, mints a
  fresh DEK in memory, imports the backup re-keyed into it, and persists the new device
  wrapper ONLY AFTER the restore succeeds — a failed restore never overwrites the old
  wrapper, so the recovery screen stays shown and the user can retry. Clears
  key-lost/corruption/restore flags, forces the record-AAD migration, restarts the
  process.
- `startFreshAfterKeystoreKeyLoss()` (`:1944-1959`) — explicit start-fresh: moves the
  old (lost-key-encrypted) vault files aside as `noteflow.sqlite.keystore-lost-<ts>`
  (bytes preserved for offline recovery), clears the stale device wrapper, and boots a
  brand-new passwordless vault with a fresh DEK.
- `quarantineVaultFiles(suffixTag)` (`:1970-1982`) — renames db + `-wal`/`-shm`/
  `-journal` aside, preserving bytes (mirrors the phase-53 `migrate-failed-<ts>`
  quarantine philosophy).

### `MainActivity.kt`

- Collects `keystoreKeyLost` (`:180`); routes to the new `KeystoreKeyLostScreen`
  (`:391-399`) between the corruption screen and the restore-block screen.
- `KeystoreKeyLostScreen` (`:1017-1108`) — dedicated recovery UI: explains the device
  security key is lost, data was NOT erased, offers restore-from-backup (with the backup
  password field) and an explicit start-fresh that requires a second confirm step.

### `NoteflowDatabase.kt`

- No code change needed: the passwordless factory path already goes through
  `getOrCreateDek(allowPasswordlessMint = true)` (B1-AUTH-02/phase-47 gate), which now
  throws `KeystoreKeyLostException` on key loss — a belt-and-braces backstop under the
  VM-init gate.

## 4. Why this closes the vulnerability

1. `readDekResult()` distinguishes `NoBlob` (true first run → mint is legitimate) from
   `KeyLost` (blob present but its wrapping keystore key is gone → mint is forbidden).
2. Every mint site (`getOrCreateDek`, the VM passwordless init, `setMasterPassword`)
   now refuses to mint over a `KeyLost` blob — the stored wrapper can never be
   overwritten by a freshly generated DEK.
3. On key loss the app shows the explicit recovery screen (restore-from-backup re-keys
   into a fresh DEK; start-fresh moves the old vault aside, bytes preserved) instead of
   silently re-keying, so the phase-43 quarantiner is never tripped for this cause and
   "key lost" is never misreported as "data corrupt".
4. A non-secret `wrapperAlias`/`wrapperVersion` marker is persisted with each blob so
   the recovery flow/diagnostics know which AndroidKeyStore alias should hold the key.

## 5. Tests

New `app/src/test/java/com/authorss81/noteflow/B1Crypto05SilentRekeyTest.kt` (16 tests):

- **Behavioral (pure JVM via the `DekDeviceStore` seam):** missing blob ⇒ `NoBlob` and
  `readDek()` null; stored-but-undecryptable blob ⇒ `KeyLost` carrying the
  `wrapperAlias` marker; missing vs corrupt reported differently; auth-gated blob ⇒
  `AuthRequired` and `getOrCreateDek` returns null without minting; `getOrCreateDek`
  **throws `KeystoreKeyLostException`** over an undecryptable blob with the marker
  riding the exception and the stored blob never overwritten (writes == 0); the same
  with `allowPasswordlessMint = false` (never falls through to a null); NoBlob still
  mints on a true first run / fails closed when minting is disabled; marker
  round-trips through `readDekResult`.
- **Source-level wiring pins** (same technique as `B1Crypto02DekAtRestTest`):
  passwordless init routes through `readDekResult` and the old `var dek =
  security.readDek()` mint is gone; `setMasterPassword` throws `KeystoreKeyLostException`;
  `dbGate` gates on `_keystoreKeyLost`; VM exposes the state + both recovery exits;
  MainActivity collects the state and renders `KeystoreKeyLostScreen`;
  `storeDek` stamps the alias/version markers; `SharedPrefsDekDeviceStore` persists and
  clears `KEY_WRAPPER_ALIAS`/`KEY_WRAPPER_VERSION`; `getOrCreateDek` throws on the
  `KeyLost` branch and mints only from `NoBlob`.

## 6. Verification output

- `gradle :app:testDebugUnitTest` → **1255 tests, 2 failures — both
  `B1Plat01ReleaseSigningTest`** (`docs/RELEASE.md` + `app/build.gradle.kts` signing
  asserts; files untouched by this phase; the same 2 pre-existing failures documented in
  phases 55/59/60/61/62/63). Previous phase total was 1239 ⇒ **+16 new tests, 0
  regressions.**
- `gradle :app:assembleDebug` → **green**. Debug APK `app-debug.apk` 173.7 MB,
  SHA-256 `8ff42d828428c055ee37fd93556636f869d1932bdf3584fde5b71c20be929599`.
  (First invocation of the earlier build hit the documented transient
  `IncrementalSplitterRunnable` packaging flake; retry clean.)

## 7. Constraints honored

- **No DB schema change**, no migration, no new dependencies, `.github/workflows/`
  untouched.
- Never logs keys/passwords/decrypted content; `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE intact (untouched).
- OS/API floor (API 26+): the fix uses only `AndroidKeyStore` + `Base64` + prefs, all
  available on API 26; no newer-API requirement was introduced (keystore loss is handled
  identically on all supported API levels via the sealed read + recovery screen).

## 8. Out of scope / related findings (not touched)

- **B1-CRYPTO-06** (DatabaseSecurityHelper tamper check fails OPEN) — separate finding,
  different phase.
- **B1-CRYPTO-07** (biometric DEK key gating on API 26-29) — separate finding, phase-65.
- **Biometric self-heal:** when a master password + biometrics vault loses the *auth*
  keystore key, the next **password** unlock re-wraps the auth-gated blob under a fresh
  keystore key via `enforceDekAtRestPolicy` — no data loss (the DEK itself is unchanged)
  and no recovery screen is needed; the password path keeps working. This is NOT the
  silent DEK re-key the finding forbids (the blob is re-wrapped with the *same* DEK).
- **Auth-blob biometric prompt fail-closed:** `getDecryptionCipher()` returns null when
  the auth keystore key is lost (existing behavior), so the biometric prompt is
  unavailable until the next password unlock re-wraps — the user falls back to the
  password.
- **`Migration`/re-key of a legacy passwordless vault after key loss** (the old vault
  file encrypted under the lost DEK) is deliberately not attempted — the bytes are
  preserved as `*.keystore-lost-<ts>` for offline recovery with the original key
  material; the in-app exits are restore-from-backup or explicit start-fresh.
