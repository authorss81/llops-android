# Phase 56 — B1-DB-7: Restore accepts a legacy PLAIN zip and an EMPTY-key SQLCipher candidate

**Status:** DONE — 2026-08-15
**Finding:** [B1-DB-7] MEDIUM — Data-at-rest & DB

## The vulnerability (before)

`ImportExportService.importBackup` treated any `PK`-headed payload as a plain
(keyless, unencrypted) backup and `validateAndPrepareRestoredDb` tried the
EMPTY SQLCipher passphrase as one of its open candidates:

- `ImportExportService.kt:1392-1403` (old) — `isPkZip` → legacy path feeds the
  raw plain zip into `restoreFromZip` with `backupDekHex = null`.
- `ImportExportService.kt:1476-1502` (old) — `candidates = listOfNotNull(backupDekHex, currentDekHex, "")`.

An attacker-crafted zip whose inner `noteflow.sqlite` was created with
`sqlcipher evil.sqlite` (empty passphrase) opens with the `""` candidate, passes
`PRAGMA integrity_check`, is re-keyed to the victim's real DEK, HMAC-rearmed
(`:1425-1428`) and moved over the live vault. The only gate was the legacy
confirm dialog `HomeScreen.kt:150-155`.

## The fix

Two pure-JVM gates close the vector end-to-end (entry + DB-open):

### 1. `importBackup` rejects a raw plain zip outright
`ImportExportService.kt` `importBackup` (legacy block, now `:1593-1608`):
a payload classified by `internal fun isPlainPkBackupBytes` (`:2469`, raw `PK<03 04>`/`PK`
header) throws (`:1595-1600`) **before any decrypt or extraction**:

```kotlin
if (isPlainPkBackupBytes(rawBytes)) {
    throw IllegalStateException(
        "Restore rejected: this is an unencrypted (unsigned) backup. " +
            "Only password-protected or device-keyed backups can be restored."
    )
}
```

The app has not produced keyless plain backups since the H4 fix (`exportBackup`
throws unless `key != null` and then emits either NFLB2-v2 or device-DEK-encrypted
base64 — never a raw zip), so this rejects only the unauthenticated format the
finding targets. The authenticated **device-DEK-encrypted** legacy path
(`EncryptionService.decrypt(encryptedStr, key)` → `restoreFromZip(…, null, currentDekHex)`)
and the **NFLB2 password-v2** path are untouched and restored unchanged.

### 2. The empty-key candidate is gone, wrapped in a fail-closed helper
`ImportExportService.kt` `validateAndPrepareRestoredDb` (now `:1692`, candidate
line `:1699`):

```kotlin
val candidates = backupRestoreOpenCandidates(backupDekHex, currentDekHex)
```

with the pure-JVM helper (`:2483`):

```kotlin
internal fun backupRestoreOpenCandidates(backupDekHex: String?, currentDekHex: String?): List<String> =
    listOfNotNull(backupDekHex, currentDekHex).filter { it.isNotBlank() }.distinct()
```

Only the backup's own wrapped DEK (v2) or this device's DEK (device-keyed) can
open a restored backup; both are unguessable. The empty string is stripped even
if a future caller passes it, so the `""` exploit cannot be re-introduced.

### 3. UX hardening (HomeScreen.kt)
- Picker (`:159-176`): a `PK`-headed selection is refused with the same
  non-alarming snackbar **before** any confirm dialog is shown (no misleading
  "Restore legacy backup?" for an unauthenticated file).
- The remaining legacy-device-keyed confirm dialog (`:1138-1147`) wording now
  explicitly flags the backup as **UNTRUSTED, UNSIGNED** and asks the user to
  verify it came from their own device.

## file:line evidence (before/after)

| Before (exploit path) | After (closed) |
|---|---|
| `ImportExportService.kt` `:1392-1403` `isPkZip` → plain zip accepted | `:1593-1608` `isPlainPkBackupBytes(rawBytes)` throws `"Restore rejected: this is an unencrypted (unsigned) backup…"` before decrypt/extract |
| `:1476-1502` `listOfNotNull(backupDekHex, currentDekHex, "")` | `:1692/:1699` `backupRestoreOpenCandidates(backupDekHex, currentDekHex)` (empty key stripped fail-closed) |
| `:1425-1428` re-key + HMAC-rearm of a crafted DB | reachable only after an authenticated key opens the backup (v2 DEK or this device's DEK) |
| `HomeScreen.kt:150-155` legacy dialog was the only gate | `HomeScreen.kt:159-176` picker refuses plain zip with a snackbar; `:1138-1147` dialog warns UNTRUSTED/UNSIGNED |

## Checksum / secrets handling

- No new keys, salts, passwords or ciphertext are written. DEK hex strings are
  only used briefly to open `tempDb` and are not persisted or logged.
- `validateBackupPassword`/`tryParseBackupV2` behaviour is unchanged — NFLB2
  restores still require the user-typed backup password and still unwrap the
  DEK via PBKDF2+AES-GCM with zeroization on every path.
- The device-keyed legacy path still requires `key != null` (throws otherwise).

## Verification

- New pure-JVM tests `app/src/test/java/com/authorss81/noteflow/B1Db07PlainZipRestoreRejectedTest.kt` (12):
  - `isPlainPkBackupBytes` classifier (PK header true; NFLB2/device-keyed
    ciphertext/undersized false);
  - `backupRestoreOpenCandidates` never returns `""` (incl. explicit `""`/blank
    inputs), dedupes real keys, empty-set fail-closed when no key material;
  - source pins: `importBackup` rejects the plain zip before decrypt & before
    the legacy `restoreFromZip`; the device-keyed decrypt path remains; the
    restore-DB region consumes only `backupRestoreOpenCandidates` and opens the
    DB with the `candidate` variable (no `openOrCreateDatabase(…,"")` anywhere
    in the file); the HomeScreen picker refuses a plain zip before the legacy
    dialog; the dialog carries the UNTRUSTED/UNSIGNED warning.
- `gradle testDebugUnitTest` — **1154 tests, 0 failures** (1142 prior + 12 new).
- `gradle assembleDebug` — **BUILD SUCCESSFUL**. The first invocation had an
  unreproducible transient build failure (the same first-invocation flake
  documented in phases 48/50/51/53/55); a forced full recompile
  (`:app:compileDebugKotlin :app:assembleDebug --rerun-tasks`) is fully green
  with no `e:` errors and produced the 173.7 MB debug APK
  (SHA-256 `4b025ea469fc70e166957ba687bc752c77639cd709b6071bace3429fae3fc06e`).

## Constraints honoured

- No DB schema change (no migration needed — restore keeps the same format, just
  a tighter open-key set + an entry gate).
- No new dependencies. `.github/workflows/` untouched. `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact. No keys/passwords/decrypted content logged.
- API floor: the fix is pure Kotlin (SDK 26+ unaffected); no newer-API requirement
  and therefore no fallback needed (satisfies the AGENTS.md hardware rule — the
  plain-zip rejection and the candidate set apply identically on all API levels).

## Related bugs found (not fixed here — documented per phase scope)

- The v2 (password) path still lets a *password-holder* craft a backup; that is
  by-design (the typed password IS the trust anchor for portable backups). An
  attacker would need the victim to type the attacker's chosen password, which
  the HomeScreen password dialog makes explicit — out of scope for this finding.
- Device-keyed legacy restores remain fundamentally *unauthenticated-to-the-file*
  (the DEK authenticates format, not provenance): the strengthened dialog warns
  UNTRUSTED/UNSIGNED and asks the user to verify the file came from their own
  device. Moving device-keyed restores to v2-only (dropping the legacy path
  entirely) is a candidate for a future phase if the format is ever removed from
  the exporter.

## Out-of-scope (explicitly per PROMPT)

Only B1-DB-7 was addressed. B1-DB-8 (decrypt-failure ciphertext fallback),
B1-CRYPTO-*, B1-PLAT-* and other findings are separate phases.