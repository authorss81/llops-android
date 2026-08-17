# Phase 145: Credential & key hygiene — no silent WebDAV-credential deletion on auth-bound expiry + bounded DEK hex copies [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1C-02, R2-B1C-03) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase fixes the credential store's silent-delete
catch-all and reduces unzeroizable DEK hex-string residue.

## Source findings (both OPEN — LOW, INFO)

1. **R2-B1C-02** (LOW) — Auth-bound WebDAV credentials are silently DELETED on
   any `load()` exception: `WebDavCredentialStore.kt:203-231` (generic
   `catch (e: Exception) { clear(); null }` at `:227-230`), `:126-140`
   (`setUserAuthenticationRequired(authBound)` + `AUTH_VALIDITY_WINDOW_MS` =
   10 min), `WebDavSyncDialog.kt:56,77` (`authBound = biometricEnabled &&
   isBiometricAvailable`). When the auth-bound key's expiry throws
   `KeyStoreException: user not authenticated`, `load()` deletes the remembered
   creds with no `BiometricPrompt.CryptoObject` re-auth flow (compare
   `SecurityService.getDecryptionCipher` `SecurityService.kt:105-127` — WebDAV
   is not wired).
2. **R2-B1C-03** (INFO) — DEK material lives in multiple immutable hex `String`
   copies during open and restore, unzeroizable heap residue:
   `NoteflowDatabase.kt:172,400` (`dek?.toHexString()` fed to
   `SupportOpenHelperFactory(passphrase.toByteArray(...))`);
   `ImportExportService.kt:1724,1744,1782,1617,1929,2031-2033,2128` (both DEKs
   held as hex strings across the whole restore pipeline).

## The fix (where & how)

- **R2-B1C-02:** Route auth-bound loads through a `BiometricPrompt.CryptoObject`
  re-auth flow (reuse the `SecurityService` biometric wiring pattern), or drop
  remember-me when the auth window cannot be satisfied; clear only on a true
  AEAD/tag failure, NEVER on `UserNotAuthenticatedException`
  (`WebDavCredentialStore.kt:227-230`).
- **R2-B1C-03:** Where feasible keep passphrases in zeroizable buffers
  (`ByteArray`/`CharArray` with `fill(0)` after use) and bound hex round-trips;
  scope hex copies to the smallest function; avoid holding both DEKs as strings
  across the entire restore (`ImportExportService.validateAndPrepareRestoredDb`).

## Verification

- New/updated pure-JVM unit tests: an auth-bound load that throws
  `UserNotAuthenticatedException` returns null WITHOUT clearing (credentials
  preserved); a genuine AEAD/tag failure still clears; DEK hex scoping source
  pins (no `toHexString` outside the smallest scope in the restore pipeline).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-145/REPORT.md`.

## Definition of done

- Both findings closed with `file:line` before/after evidence.
- A 10-minute auth-window expiry no longer destroys remembered WebDAV
  credentials; DEK hex copies are scoped + zeroized where feasible.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep the
  PBKDF2/AES-256-GCM/AndroidKeyStore model and `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.