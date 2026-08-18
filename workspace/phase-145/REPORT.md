# Phase 145 report — Credential & key hygiene: no silent WebDAV-credential deletion on auth-bound expiry (R2-B1C-02) + scoped/zeroized DEK hex copies (R2-B1C-03)

Status: **DONE** — both R2-B1C-02 and R2-B1C-03 fixed.

## Findings fixed

### R2-B1C-02 (LOW) — Auth-bound WebDAV credentials are silently DELETED on any `load()` exception; the biometric re-auth path the design implies does not exist
`WebDavCredentialStore.load()` collapsed every load-time exception into the pre-fix generic
`catch (e: Exception) { clear(); null }` (`WebDavCredentialStore.kt:203-231`). The auth-bound
AndroidKeyStore key (`setUserAuthenticationRequired(authBound)`,
`AUTH_VALIDITY_WINDOW_MS` = 10 min) throws `UserNotAuthenticatedException` (keystore message
form: `KeyStoreException: user not authenticated`) when the biometric window expires — the
store treated that as corruption and **deleted** the intact remembered credentials, so the
biometric gate became effectively unexercisable and the user had to retype the password.

**Fix:**

- `WebDavCredentialStore.load()` is now a thin convenience over the classified
  `loadDetailed()` returning a sealed `WebDavCredentialLoadResult`
  (`None` / `Credentials` / `AuthRequired` / `Corrupt`, `WebDavCredentialStore.kt:26-47`).
- `UserNotAuthenticatedException` **and** the message-form
  `KeyStoreException("user not authenticated")` map to `AuthRequired` — the blob is intact, a
  recent biometric authentication would decrypt it again, and it is **NEVER cleared**
  (`WebDavCredentialStore.kt:293-307`). `clear()` is reachable only from the genuine
  AEAD/tag/decrypt failure branch (tampered ciphertext, a key invalidated by biometric
  re-enrollment) and from explicit user "forget" flows.
- The missing re-auth path is now real, mirroring
  `SecurityService.getDecryptionCipher`/`decryptWithCipher` (`SecurityService.kt:105-127`):
  - `prepareReauthCipher()` (`WebDavCredentialStore.kt:326`) prepares a **DECRYPT** cipher over
    the stored auth-bound blob when the window is still open, so the UI can wrap it in a
    `BiometricPrompt.CryptoObject`.
  - On biometric success `decryptWithReauthCipher(cipher)` (`:349`) completes the decrypt; a
    failed/retried re-auth returns `null` **without clearing**.
- `WebDavSyncDialog` classifies on launch (`loadDetailed()`, `WebDavSyncDialog.kt:123-139`):
  `AuthRequired` keeps remember-me checked and shows a non-alarming notice with an
  "Unlock with biometrics" `OutlinedButton` wired through
  `BiometricAuthHelper.promptBiometricAuth(activity, title, subtitle, cryptoObject, onSuccess, onError)`
  (`WebDavSyncDialog.kt:82-119`). When the window had already closed, `prepareReauthCipher()`
  returns null, the prompt runs without a `CryptoObject`, and its success refreshes the
  keystore window, after which `loadDetailed()` re-runs. The saved copy survives every path
  ("your remembered credentials are still saved and can be unlocked again later").
- No new permission, no new dependency: `MainActivity` is already a `FragmentActivity`, and
  the prompt reuses the existing `BiometricAuthHelper`.

**Tests — `R2B1C02WebDavCredentialHygieneTest` (6):**

1. expired window (`KeyStoreException("user not authenticated")` through the injectable key
   source) → `AuthRequired`, blob/remember-me/auth-bound flags all survive;
2. genuine AEAD failure (tampered ciphertext) → `Corrupt` and cleared — the only clearing state;
3. `CryptoObject` re-auth round-trip: `prepareReauthCipher()` → `decryptWithReauthCipher()`
   decrypts the stored blob;
4. a failed re-auth (tamper between prepare and decrypt) returns null and does **not** clear;
5. source pin: the `UserNotAuthenticatedException` and message-form auth arms reach
   `AuthRequired` before any `clear()`;
6. source pin: the dialog classifies, keeps remember-me on `AuthRequired`, and wires
   `BiometricPrompt.CryptoObject` / `prepareReauthCipher()` / `decryptWithReauthCipher`.

### R2-B1C-03 (INFO) — DEK material lives in multiple immutable hex `String` copies during open and restore — unzeroizable heap residue
Every SQLCipher open converted the 32-byte DEK into a 64-char immutable hex `String`
(`NoteflowDatabase.kt:172,400` pre-fix `passphrase = dek?.toHexString()` →
`SupportOpenHelperFactory(passphrase.toByteArray(...))`), and the restore pipeline held BOTH
the backup and current DEKs as hex Strings across the whole `validateAndPrepareRestoredDb`
(`ImportExportService.kt:1724,1744,1782,1617,1929,2031-2033,2128` — `toHexString`/`fromHex`
round-trips). Java `String`s are immutable and only GC-freed, never zeroized — a process
memory dump after lock could still sample them.

**Fix — passphrases become zeroizable bytes; hex is scoped to the smallest function:**

- **Open path** (`data/db/NoteflowDatabase.kt`): the SQLCipher passphrase is built directly as
  ASCII-hex **bytes** by `ByteArray.toSqlcipherPassphraseBytes()` (`:207-216`) — byte-identical
  to the old lowercase-hex `String` (`toHexString().toByteArray(UTF_8)`), so existing on-disk
  vaults still open. `NoteflowSqlcipherFactory.create` (`:453-461`) hands `passphraseBytes` to
  `SupportOpenHelperFactory(passphraseBytes)` and `migratePlaintextIfNeeded(context,
  passphraseBytes)` (byte[] `openOrCreateDatabase` overloads), and zeroizes
  `passphraseBytes.fill(0)` in the same try/finally. `dek?.toHexString()` is gone.
- **Restore pipeline** (`services/ImportExportService.kt`): DEKs travel as zeroizable
  `ByteArray`s end-to-end:
  - `BackupV2Payload.dek` is `ByteArray?` (`:1518`); `tryParseBackupV2File` unwraps to bytes
    (`:1764`) and zeroizes the wrap-key halves it consumed (`finally`, `:1775-1778`);
  - `importBackup` threads `v2.dek`/`currentDek` (bytes) into `restoreFromZip` and zeroizes
    `v2.dek` in its `finally` (`:1985`); the legacy device-keyed path passes `key` with no hex
    wrapper;
  - `restoreFromZip` / `validateAndPrepareRestoredDb` signatures are `ByteArray?`; hex Strings
    exist ONLY inside `validateAndPrepareRestoredDb` — the DEKs are **copied**
    (`backupDekOwned`/`currentDekOwned`, `:2204-2209`) so the repository's live array is never
    zeroized, and both owned copies are zeroized in the function's `finally`.
  - Every SQLCipher String-API touch in the restore (`rekeySqlcipherDb`, 
    `migrateFieldCiphertexts`, the candidate opens `tempDb, candidateBytes, ...`) now feeds the
    passphrase as ASCII bytes via `String.toAsciiBytes()` (zeroized right after use) instead of
    the String-typed clone.

**Tests — `R2B1C03DekHexScopingTest` (3):**

1. open path: `toSqlcipherPassphraseBytes` exists, `SupportOpenHelperFactory(passphraseBytes)`,
   `passphraseBytes.fill(0.toByte())`, the migration open takes the byte[], and no
   `dek?.toHexString()` remains;
2. restore path: `BackupV2Payload` carries `ByteArray?`, the v2/legacy restore threads bytes + 
   zeroizes, the validator takes bytes, copies before hex, zeroizes the owned copies, and the
   FILE-wide pin is **exactly 3** `toHexString()` sites (1 definition + the 2 in-validator hex
   calls) — hex cannot escape the validator;
3. the rekey/migration helpers feed SQLCipher ASCII bytes (`toAsciiBytes` + `fill(0)` after use).

## Regression updates to existing tests
Old-signature pins updated in lockstep (the production API changed from hex `String` to
`ByteArray`): `B2Crypto04BackupPasswordTest` (2 asserts + a `ByteArray?.asHex()` test helper),
`Phase138RestoreStreamingTest`, `B1Db07PlainZipRestoreRejectedTest` (3 pins), and
`RestoreHardeningWiringTest` (2 pins). `B1Db03VoiceNoteEncryptionTest` pins were unchanged
(the validator body kept its re-key wiring).

## Verification
- `gradle testDebugUnitTest` — **green, 0 failures** (full suite).
- `gradle assembleDebug` — **green** (90 tasks).
- No DB schema change, no Room migration, no new dependencies, `.github/workflows/` untouched,
  API 26+ / Android-reality fallbacks respected (the re-auth flow degrades gracefully to a
  re-entry notice when no `FragmentActivity` or strong biometric is available).