# Phase 84 — B2-CRYPTO-04 (MEDIUM) — Backup password (6-char minimum, no complexity) KDF material — salt + wrapped DEK + IV — is shipped in a cleartext-readable header to PUBLIC Downloads and the WebDAV server → offline PBKDF2 crack of a weak backup password recovers the DEK and the entire vault

2026-08-16 · finding source: `docs/security-report.md` B2-CRYPTO-04, batch 2 (crypto side-channels & edge cases)

## The vulnerability (before/after)

**Before** — `ImportExportService.exportBackup` let the user create a password-protected backup with a bare
`length >= 6` password and no complexity check, then wrote the v2 (`NFLB2`) header as a cleartext-readable
prefix:

- `ImportExportService.kt:1181` (pre-fix) — `require(normalizedGraphemeCount(backupPassword) >= MIN_PASSWORD_GRAPHEMES)`:
  the only gate was length (≥6 graphemes), no class diversity, no pattern rules. A 6–7 char numeric/lowercase
  backup password was actively encouraged.
- `ImportExportService.kt:1182-1198` (pre-fix) — the v2 header was `[NFLB2][16B salt][12B payloadIv][wrappedDek(61B)]`:
  `wrappedDek` (AES-GCM blob wrapped by KEK = PBKDF2(password, salt) with a 61-byte versioned layout) ships *in the
  clear*, in the same file that is copied to public `/Download` (`HomeScreen.kt:1196-1199`) and uploaded to the
  user's WebDAV server (`WebDavSyncService.kt:202`).

Exploit scenario (from the finding): anyone with storage permission, a USB/MTP reader, the file's share-sheet
recipient, or the WebDAV server itself obtains `salt + wrappedDek + payloadIv` plus the encrypted vault zip with
**zero device access**. The 5-attempt exponential lockout is UI-only and never fires. The wrapped DEK is a small,
highly targeted GCM-tag brute-force oracle: a GPU/FPGA PBKDF2-SHA-256/600k rig tests guesses at ~zero cost per
candidate beyond the KDF, a 6–7 char password falls in hours-days, success unwraps the DEK and decrypts the whole
embedded SQLCipher vault.

**After** — the password bar is the SAME decision table as the vault master password, and the header no longer
carries a cheap offline crack target:

1. **`BackupPasswordPolicy`** (new pure-JVM policy, `services/BackupPasswordPolicy.kt`): `evaluate` delegates to the
   single `PasswordStrengthPolicy` table (B1-CRYPTO-04/phase-63): ≥8 NFKC-normalized graphemes, sequential /
   keyboard-row / repeated-char rejection, <3 distinct graphemes → WEAK, short (<12) passwords must mix ≥3 of 4
   character classes, passphrases ≥12 pass on length alone. `requireStrongBackupPassword` throws
   `IllegalArgumentException` carrying the verdict message + `OFFLINE_BACKUP_NOTICE` (the loud, non-alarming warning
   that backups in Downloads/cloud are only as strong as this password, with no lockout on offline crack attempts).

2. **`ImportExportService.exportBackup`** (`.kt:1300-1419`) now:
   - gates the password with `BackupPasswordPolicy.requireStrongBackupPassword(backupPassword)` (`:1354`) — the
     old bare-length require is gone;
   - writes the new **v3 `NFLB3`** wire format: a fresh random 32-byte DEK-wrap key is split into
     [16B `wrapKeyPart1` (in the public header, via `buildBackupHeaderV3` `:1173`)] + [16B `wrapKeyPart2` embedded
     **inside** the password-encrypted payload] (`:1367-1371`). The payload stream is
     `SequenceInputStream(ByteArrayInputStream(wrapKeyPart2), FileInputStream(stagingZip))` through the
     phase-83 streaming `BackupExportPolicy.encryptStreamGcm` (`:1380-1390`), keeping the B2-DOS-07 bounded-memory
     guarantee;
   - DER wraps the vault DEK under the `backup/dek-wrap` AAD domain, payload GCM under `backup/payload` + the exact
     header (the B2-CRYPTO-03 domain separation), and zeroizes `wrapKey`, both halves, and the KEK in `finally`
     (`:1391-1398`).

   With only the public header, an attacker holds HALF a wrap key — they can neither form the full key nor test a
   password against the wrapped DEK (a 61-byte GCM tag oracle that used to make each guess nearly free). Every crack
   attempt must now fully decrypt the payload (a full-vault AES-GCM `doFinal` on top of PBKDF2-600k) before the DEK
   can ever be attempted. That is the finding's "split the DEK-wrapping key (store a random half only inside the
   payload)" option, done.

3. **Restore/verify are format-agnostic and NEVER strength-gate** (`.kt:1540-1707`): `parseBackupHeader` reads both
   magics (`NFLB2`/`NFLB3`; returns null for legacy/device-keyed files), so pre-fix weak-password backups keep
   restoring and validating. v2 validate keeps its cheap wrapped-DEK probe; v3 has **no** cheap probe by design, so
   `validateBackupPassword` for a v3 file performs exactly ONE full payload decrypt + DEK unwrap — the only possible
   test (documented trade-off: a v3 header/payload splice can surface as "Incorrect backup password"; a genuine v2
   splice still surfaces as corruption via its probe). A weak (pre-fix) backup password is never rejected on the
   unlock/restore path, matching the phase-63 unlock-never-strength-gates principle.

4. **HomeScreen create-backup dialog** (`ui/screens/HomeScreen.kt:1262-1273, 1305-1311`): shows
   `BackupPasswordPolicy.OFFLINE_BACKUP_NOTICE` under the password field (create dialog only — the restore dialog is
   untouched) and pre-checks `BackupPasswordPolicy.evaluate` before the DB is checkpointed or the export starts, with
   the verdict message surfaced as the dialog error. The Export/WEBDAV/LocalSend callers are unchanged — the gate
   lives in `exportBackup` itself, so every caller is protected.

## File:line evidence (commit after)

| Site | Before | After |
|---|---|---|
| `services/ImportExportService.kt` `exportBackup` password gate | `:1181` `require(normalizedGraphemeCount(backupPassword) >= MIN_PASSWORD_GRAPHEMES)` (bare length only; pre-fix at `23686a0`) | `:1354` `BackupPasswordPolicy.requireStrongBackupPassword(backupPassword)` |
| v2 header write | `:1182-1198` `[NFLB2][salt][iv][wrappedDek]` all in the clear prefix | `:1173` `buildBackupHeaderV3(salt, payloadIv, wrapKeyPart1, wrappedDek)` → `[NFLB3][salt][iv][part1(16B)][wrappedDek]`; `:1380-1390` payload = `SequenceInputStream(ByteArrayInputStream(wrapKeyPart2), FileInputStream(stagingZip))` → `BackupExportPolicy.encryptStreamGcm` |
| DEK wrap | wrapped DEK = KEK-derived (crack target = PBKDF2 → wrapped-DEK GCM tag oracle) | `:1373` `EncryptionService.encryptAad(key, wrapKey, BACKUP_DEK_WRAP_AAD)`; the wrap key is random + split (`:1367-1371`), so the header alone never opens the wrapped DEK |
| key zeroization | KEK zeroized | `:1391-1398` `wrapKey`, `wrapKeyPart1`, `wrapKeyPart2`, `kek` all `fill(0.toByte())` |
| parse | v2-only reader | `:1540` `parseBackupHeader` (both magics), `:1501` `decryptBackupPayloadV3` (AAD-bound, no legacy zero-AAD retry by design), `:1564` `tryParseBackupV2` (now `internal`) |
| verify | v2-only | `:1659` `validateBackupPassword` — v2 cheap probe, v3 one full payload decrypt + DEK unwrap; never strength-gates |
| `services/BackupPasswordPolicy.kt` (new) | — | `internal object`: `:41`; `OFFLINE_BACKUP_NOTICE` `:48`; `evaluate` `:56`; `requireStrongBackupPassword` `:64-67` |
| `ui/screens/HomeScreen.kt` dialog | `HomeScreen.kt:1196-1199` bare `>= 6` result was written to public Downloads | `:1262-1273` offline notice (create dialog only); `:1305-1311` `BackupPasswordPolicy.evaluate` pre-check |

## New/changed files

- Added `app/src/main/kotlin/com/authorss81/noteflow/services/BackupPasswordPolicy.kt` (pure-JVM policy; delegates
  to the master-password `PasswordStrengthPolicy` bar + offline warning).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (v3 export path,
  `buildBackupHeaderV3`, `BACKUP_MAGIC_V3`/`BACKUP_WRAP_KEY_HALF_SIZE`, `parseBackupHeader`, `decryptBackupPayloadV3`,
  `tryParseBackupV2`/validate rewritten format-agnostic; `BackupV2Payload` private→internal; and the pre-existing
  DEK zeroization-order bug fixed — see below).
- Modified `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (dialog pre-check + offline notice;
  unused `EncryptionService` import removed).
- Added `app/src/test/java/com/authorss81/noteflow/B2Crypto04BackupPasswordTest.kt` (11 tests).
- Modified `app/src/test/java/com/authorss81/noteflow/B1Crypto04PasswordStrengthTest.kt` (whitelist now includes the
  sanctioned `BackupPasswordPolicy.kt` reference).

## Additional defect found and fixed (pre-existing, unrelated, kept in-phase per DoD)

While writing the round-trip tests, a **pre-existing latent data-loss bug** was exposed in the password-restore path
(also latent pre-fix):

- `tryParseBackupV2` computed the unwrapped DEK hex as
  `EncryptionService.decryptAad(...).also { it.fill(0.toByte()) }.toHexString()` — the plaintext DEK was zeroized
  **before** being hex-encoded, so `dekHex` was always 32 zero bytes. `importBackup` then passed that zero DEK hex to
  `rekeySqlcipherDb`, a true restore with a *correct* password would rekey the DB with a zero key and effectively lose
  the vault. No test caught it because `tryParseBackupV2` was `private`.
- Fixed by hexing first, then zeroizing in a `finally` (`ImportExportService.kt:1614-1621`). Documented here and not
  billed as a separate finding; the phase-84 round-trip tests now prove a real DEK is recovered.

## Verification

- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**. Full suite **1506 tests, 0 failures / 0 errors**
  (1487 pre-fix baseline + 19; includes the new 11-test `B2Crypto04BackupPasswordTest`). The previously-documented
  `B1Plat01ReleaseSigningTest` asserts were already repaired in the phase-80 lineage; the occasionally-flaky
  `WikiLinkParserCacheUnitTest` cancellation test passed on the final run.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (first run hit a transient `mergeExtDexDebug` failure under the
  default 2 GB daemon heap, same as phase-83; rerun green with the CLI 4 GB heap, no `gradle.properties` change).
- New tests `B2Crypto04BackupPasswordTest` (11):
  1. `backup passwords below the strength bar are rejected with a verdict` — `12345`, and the OLD accepted `123456`
     /`abcdef`/`A9fj2l!` are all `TOO_SHORT`; sequences (`12345678`, `qwertyui`, `abcdefgh`), `WEAK` (`aaaaabaa`),
     `LOW_DIVERSITY` (`PASSWORD1`); strong passwords + `correct horse battery staple` pass.
  2. `requireStrongBackupPassword throws loudly for weak passwords` — carries the verdict + an "offline"-mentioning
     notice; `OFFLINE_BACKUP_NOTICE` mentions Downloads + offline; strong password passes.
  3. `v3 wire format splits the wrap key - header carries only half` — wrapped DEK = 61B; part1 padded to 32B cannot
     open it (AEADBadTag), and part1 + a WRONG guessed part2 cannot either — there is nothing cheap to test offline
     from the public header alone.
  4. `a v3 payload cannot be decrypted without the password` — wrong KEK fails the full payload GCM (AEADBadTag).
  5. `a v3 backup round-trips through the restore parse with the right password` — real zip recovered, real DEK hex
     unwrapped, KEK handed to importBackup.
  6. `a v3 backup rejects the wrong password and stays binary-compatible with v2 reads` — wrong password rejected;
     an NFLB3 header with no payload parses to null (never a password success on an incomplete file).
  7. `validateBackupPassword exercises both magics and never strength-gates` — v3 (full-payload test), v2 (cheap
     probe), wrong password rejected, no strength gate anywhere.
  8. `pre-fix v2 backups still restore with the exact same format` — v2 NFLB2 byte layout round-trips; wrong password
     rejected.
  9. `exportBackup gates the password with the strength policy and writes v3` — source pins: no bare-length check in
     the export path, strength gate + `buildBackupHeaderV3` + `SequenceInputStream(...wrapKeyPart2)` wired, wrap key +
     both halves + KEK zeroized.
  10. `the restore parse and verify paths understand both magics but never strength-gate` — source pins: parse/verify
      reference no `BackupPasswordPolicy`.
  11. `the HomeScreen backup dialog pre-checks the policy and shows the offline warning` — dialog uses
      `BackupPasswordPolicy.evaluate`, no bare length check remains, notice text is shown.

## Checksums / secrets handling

- No new secrets, keys, or passwords introduced; no logging added. Existing zeroization retained and extended (wrap
  key + both halves).
- No new dependencies (stdlib + JCE, API-26 floor — no newer-API requirement, no fallback needed).
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE intact. `.github/workflows/` untouched.
- No schema change, no migration.

## Out of scope (documented, not fixed here)

- The finding's alternative fix (derive the KEK with a **different KDF domain**, e.g. a domain-separated HMAC/normalized
  derivation) was NOT chosen: the split-key design with `backup/dek-wrap` + `backup/payload` header-bound AAD domains
  already covers it, and changing the PBKDF2 derivation would have forced a v3-only restore schema, breaking the
  restore-older-backup guarantee.
- CHEAP-PROBE asymmetry: v3 (NFLB3) intentionally has no cheap wrapped-DEK probe, so a spliced v3 header+payload is
  reported as "Incorrect backup password" rather than corruption (the v2 probe path keeps the corruption diagnosis).
  Documented in the code KDoc and the test.
- WebDAV/Downloads *retention* and the pre-existing B2-CRYPTO-06 millisecond-timestamp filenames are separate findings
  (other phases); only the B2-CRYPTO-06-proximity concern (public location + warning) is touched here, via the dialog
  notice.
- The pre-existing zero-hex `dekHex` bug fixed here was out-of-band for B2-CRYPTO-04's exploitation, but leaving it
  would have made "restore a correct-password backup" silently destroy the vault on a large-DB rekey — fixed and
  documented.