# Phase 104 — B2-CRYPTO-03 (LOW): Backup v2 payload is not bound to its own header by AAD; the KEK does double duty (wrap DEK + encrypt payload) with only IV separation

## Finding (from `docs/security-report.md`, B2-CRYPTO-03)

- Area: Batch 2 — Crypto side-channels & edge cases.
- Evidence (before): `ImportExportService.kt:1185` wrapped the DEK with
  `EncryptionService.encrypt(key, kek)` (authenticated under `FIELD_AAD`); the SAME KEK then
  encrypted the whole zip payload (`ImportExportService.kt:1188-1190`) with a bare
  `cipher.init(...)` + `doFinal` and NO `updateAAD`; the header
  `magic|salt|payloadIv|wrappedDek` was written at `:1193-1198`.
- Exploit: nothing bound the ciphertext to its own salt/IV/wrapped-DEK header, so a crafted
  file could splice the header of one legitimate export onto the payload of another. With
  different salts the tag failed → a false-negative "Incorrect backup password" that sends the
  user down the wrong path; with a reused salt+IV pair the wrapped-DEK and payload GCM uses
  shared a key with zero domain separation. No plaintext recovery (IVs random per export) — an
  authenticated-format integrity/hygiene gap.
- Prescribed fix: `cipher.updateAAD(headerBytes)` before `doFinal` (bind the header), and give
  the two KEK uses distinct AAD constants (`backup/dek-wrap` vs `backup/payload`) so cross-use
  is structurally impossible.

## What changed

### 1. Domain-separable AAD API — `services/EncryptionService.kt:96-149`

- BEFORE: the only encryption entry points were `encrypt`/`decrypt`, which hard-code
  `FIELD_AAD` (`EncryptionService.kt:21`) and Base64 the result.
- AFTER: added `encryptAad(data, key, aad)` / `decryptAad(combined, key, aad)` (raw bytes, no
  Base64) that bind a **caller-supplied AAD** with the same `[version][12-byte IV][ct+tag]`
  wire format. `decryptAad` keeps a single legacy retry on `AEADBadTagException` with
  `FIELD_AAD` so pre-fix wrapped DEKs (which only ever used `FIELD_AAD`) still unwrap.

### 2. Header-binding + domain separation — `services/ImportExportService.kt:1101-1164`

- Two internal AAD constants: `BACKUP_DEK_WRAP_AAD = "backup/dek-wrap"` and
  `BACKUP_PAYLOAD_AAD = "backup/payload"` (`ImportExportService.kt:1105-1106`).
- `buildBackupHeader(salt, payloadIv, wrappedDek)` serializes the exact on-disk header
  (`ImportExportService.kt:1109-1118`) so encrypt and decrypt both reconstruct the SAME AAD.
- `encryptBackupPayload` / `decryptBackupPayload`
  (`ImportExportService.kt:1126-1164`): the payload GCM authenticates
  `BACKUP_PAYLOAD_AAD ‖ header` before `doFinal`. `decryptBackupPayload` retries the legacy
  **zero-AAD** payload on tag mismatch so pre-fix backups still restore — a spliced NEW-format
  payload fails both the AAD path and the legacy path, so the splice is still rejected.

### 3. Call-site updates (the actual file writer/readers)

- **`exportBackup`** (`ImportExportService.kt:1251-1260`): BEFORE — `EncryptionService.encrypt`
  (FIELD_AAD) + bare payload cipher + byte-by-byte header writes. AFTER — DEK wrapped with
  `BACKUP_DEK_WRAP_AAD`, header built once via `buildBackupHeader`, payload encrypted via
  `encryptBackupPayload`, single `fos.write(header)` + `fos.write(cipherText)`. On-disk layout
  is byte-identical (magic|salt|iv|wrappedDek=61B|payload); the `android.util.Base64`
  round-trip of the wrapped DEK is gone.
- **`tryParseBackupV2`** (`ImportExportService.kt:1335-1346`): payload decrypt now authenticates
  `rawBytes[0..headerSize)` — the header straight from the file — before `doFinal`.
- **`validateBackupPassword`** (`ImportExportService.kt:1368-1378`): wrapped-DEK probe now uses
  `decryptAad(wrappedDek, kek, BACKUP_DEK_WRAP_AAD)` (raw bytes, no Base64 hop).
- **`importBackup`** (`ImportExportService.kt:1416-1425`): the restore-time DEK unwrap likewise
  uses `decryptAad(..., BACKUP_DEK_WRAP_AAD)`.

Compatibility matrix:
- **Old backup → new app**: wrapped DEK falls back to `FIELD_AAD`; payload falls back to
  zero-AAD → restores (verified by test 3).
- **New backup → old app**: old app's bare payload decrypt fails the tag → loud "Incorrect
  backup password". Deliberate: the finding demands the tag cover the header, which necessarily
  changes the tag; a loud failure is the honest outcome (no silent mixed-archive acceptance).

## Security / checksum / secrets handling

- No key, password, salt, wrapped DEK or decrypted content is logged or newly persisted; KEK
  zeroization (`kek.fill(0)`) in the existing `finally` blocks is unchanged.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE: untouched.
- No DB schema change, no migration. No new dependencies (pure `javax.crypto`, already in use).
  `.github/workflows/` not modified.
- **API floor (API 26+)**: `Cipher.updateAAD` is JCE, available since API 19 (AES/GCM since
  API 19); the app floor of API 26 is covered — **no fallback or non-alarming notice required**
  (AGENTS.md hardware-reality rule satisfied).

## Tests (pure-JVM, `app/src/test`)

**New `BackupV2CryptoIntegrityTest.kt`** (3 tests, all green):
1. `splicing another export's header onto a payload fails the GCM tag` — two exports built
   with the SAME salt (isolating the header-binding from key mismatch); each payload decrypts
   only with its own header/IV, and `headerA + payloadB` (and the inverse) throws
   `AEADBadTagException`. This is exactly the `decryptBackupPayload` call `tryParseBackupV2`
   makes, so it proves the file-level splice is rejected.
2. `DEK-wrap and payload KEK uses are distinct AAD domains` — a `backup/dek-wrap` ciphertext
   fails `decryptAad` under `backup/payload`; a payload ciphertext fails `decryptAad` under
   `backup/dek-wrap`; the wrapped DEK fed into the payload decrypt path fails too.
3. `pre-fix backups still restore (wrapped-DEK FIELD_AAD and zero-AAD payload)` — the old
   `EncryptionService.encrypt` wire format (built manually; `EncryptionService.encrypt` returns
   null under the unit-test mockable `android.jar` `Base64` default) still unwraps via the
   `FIELD_AAD` fallback, and a zero-AAD payload still decrypts via the legacy path.

## Verification output

1. Pristine-baseline control: stashed this phase's changes and ran `gradle :app:testDebugUnitTest`
   → **636 tests, 1 failed**: `EncryptionAndServiceTest.testEncryptDecryptCycle` (NPE:
   `android.util.Base64.encodeToString` returns `null` under `unitTests.isReturnDefaultValues
   = true`, so `EncryptionService.base64Encode`'s `java.util.Base64` fallback never fires).
   This is the SAME pre-existing failure documented in the phase-102/103 reports; code is
   byte-identical to HEAD, unrelated to this phase.
2. With this phase's changes: `gradle testDebugUnitTest` → **639 tests, 1 failed** — exactly the
   same pre-existing `testEncryptDecryptCycle` (639 = 636 baseline + 3 new). The 3 new
   `BackupV2CryptoIntegrityTest` tests pass. (One run also flaked
   `PluginUpdateEngineTest.a hash mismatch on the downloaded artifact is never applied` — a
   TemporaryFolder/jarsigner timing flake; it passed on the pristine-baseline full run and on
   the final changed-tree full run and is untouched by this phase.)
3. `gradle assembleDebug` → **BUILD SUCCESSFUL** (2m 36s; only the pre-existing "Unable to strip
   libsqlcipher.so …" informational note).

## Out of scope (documented, not fixed)

- `EncryptionService.encrypt/decrypt` still Base64-encode via `android.util.Base64`, which is
  why `testEncryptDecryptCycle` fails under mockable defaults — a test-environment
  `isReturnDefaultValues=true` artifact, pre-existing (phases 101–103), not a B2-CRYPTO-03 issue.
- The `PluginUpdateEngineTest` one-off flake (see above) belongs to the Phase-24 plugin runtime,
  unrelated to backup crypto.
- B2-CRYPTO-04 (backup-password strength / cleartext KDF header), B2-CRYPTO-05 (version-byte
  fallback timing), B2-CRYPTO-06 (millisecond timestamps in filenames) each remain in their own
  phases per the phase constraint.
- New-format backups are intentionally not restorable by pre-fix app versions (loud
  "Incorrect backup password" instead of silent acceptance) — required by the header-binding
  fix; documented above.
- No new related bug was discovered during this phase's work.
