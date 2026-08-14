# Phase 107 — B2-CRYPTO-09: Field AEAD AAD is a single global constant (LOW)

Status: FIXED (2026-08-14)

## Finding summary

`FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1"` (`EncryptionService.kt`) was applied
identically to every field ciphertext across **all four field tables**
(`pages.{title,extractedText}`, `strokes.{textContent,pointsJson}`,
`media_embeds.textContent`, `note_versions.{title,extractedText}`). Because the AAD is
constant, a write-capable adversary (root, downloadable plugin per B1-AUTH-01, crafted
restore per B1-AUTH-05, or DB-layer tampering) can **transplant** a note's title /
extractedText / stroke-geometry ciphertext into any other record — the GCM tag still
verifies and the vault renders swapped content as authentic. Content-granularity
integrity was genuinely unenforced.

## What changed (file:line, before → after)

### `app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt`
- Before: single global `FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1"` at line 21,
  used unchanged for every field (`encrypt`/`decrypt`, old :67). No per-record binding.
- After:
  - `FIELD_AAD_V2_PREFIX = "Noteflow-Vault-Field-Encryption-v2|"` (:27) — domain-separated
    prefix; v1 constant retained ONLY as the legacy migration-fallback reader.
  - `fieldAad(table, recordId, fieldName)` (:197) builds `v2|<table>|<recordId>|<fieldName>`
    UTF-8 AAD (AND-separated, per the finding's `pages|$pageId|title` shape).
  - `encryptField(data, key, table, recordId, fieldName)` (:205) — same wire format as
    `encrypt` (`[PAYLOAD_VERSION][12-byte IV][ct+tag]`) but bound to the per-record AAD via
    `encryptAad`, so the deterministic phase-105 format checks apply unchanged.
  - `decryptField(...)` (:221) — per-record AAD first; ONLY `AEADBadTagException` retries
    v1 `FIELD_AAD` (the migration reader for pre-phase-107 rows). Malformed/unversioned
    payloads are rejected before any decrypt (same policy as `decrypt`, phase-105). A
    transplanted NEW-format ciphertext fails BOTH attempts, so the fallback never rescues a
    relocation; after the migration pass no legacy rows remain for it to read.
  - `decryptFieldOrNull(...)` (:238) — null-returning per-record analogue of `decryptOrNull`.
  - `isFieldBoundToRecord(...)` (:253) — true iff the payload decrypts under its OWN
    per-record AAD (deliberately does NOT consult the legacy fallback, so the migration pass
    detects legacy rows without looping).
  - `decryptCoreAad(combined, key, aad, offset)` (:151) reused from `decryptAad` as the
    shared decrypt core.

### `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`
- Before: every field encrypt/decrypt used global-AAD `EncryptionService.encrypt/decrypt`
  (e.g. read helper :158) and `isFieldEncrypted(value, key)` had no record context.
- After — every record-field call site now passes `(table, recordId, fieldName)`:
  - `migrateFieldRecordAad(dek)` (:175–247): one-time pass binding every pre-phase-107
    ciphertext to its record context. Reads legacy rows via `decrypt` (global v1 AAD),
    re-encrypts them with `encryptField` under the SAME DEK (AAD change only — no re-key).
    Skips rows already bound (`isFieldBoundToRecord`), leaves plaintext/blank untouched
    (that is `reencryptPlaintextFields`' B2-CRYPTO-10 concern). Runs in a single Room
    transaction; uses the existing bulk DAOs. **No DB schema change.**
  - `isFieldEncrypted(value, key, table, recordId, fieldName)` (:155) + the whole
    `reencryptPlaintextFields` pass (:254–319) now detect and write per-record AAD.
  - `createPage` (:446/:449, id generated before first use so the AAD binds to the final record id),
    `renamePage` (:472), `updatePageTitleAndTags` (:484) → `"pages"` + page id.
  - `getStrokesForPage` (:541/:552) and `saveStrokesForPage` (:656/:664) → `"strokes"` + stroke id.
  - `getMediaEmbedsForPage` (:715), `saveMediaEmbedsForPage` (:804) → `"media_embeds"` + embed id.
  - `createNoteVersion` (:884/:887) and `getNoteVersions` (:905/:907) → `"note_versions"` + version id.
  - `decryptPageIfNeeded` (:919/:922) → `"pages"` + page id.

### `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
- Added one-time migration flag `fieldAadMigrated` (:35–39) so the O-rows pass does not run
  on every unlock.

### `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- `initializeData()` (:1129–1150): runs `repository.migrateFieldRecordAad(dek)` once (guarded by
  `fieldAadMigrated`; flag set only on success, retried next unlock on failure). This covers all
  unlock paths (no-master-password init, `verifyMasterPassword`, biometric) since they all
  funnel through `initializeData`.
- Restore paths `attemptRecoveryFromBackup` (:1661) and `restoreEncryptedBackupFromZip` (:2073):
  reset `fieldAadMigrated = false` after a successful import so a backup carrying legacy
  global-AAD rows is re-migrated on the next launch (the DEK is often unchanged in a
  same-device restore, so the re-key pass alone would skip them).

### `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`
- `reencryptFieldValue(value, oldDek, newDek, table, recordId, fieldName)` (:1202): the
  cross-device re-key now decrypts the source with `decryptField` (reads both legacy and
  already-bound rows) and re-encrypts with `encryptField` — every restored row ends up
  per-record bound. Signature grew by 3 record-context args.
- `migrateTable` (:1666) passes `(table, id, column)` from the `fieldEncryptedColumns`
  (:1186–1191) loop → record context is threaded through the whole restore re-key.

### Tests
- New `app/src/test/java/com/authorss81/noteflow/FieldRecordAadTest.kt` (11 tests):
  round-trip bound decrypt; transplant to a different record id / column / table fails the
  GCM tag; `decryptFieldOrNull` null on transplant; legacy global-AAD payload still decrypts
  via the migration fallback in its own record; legacy rows are detected as NOT record-bound
  (so migration converts them) while still readable via the v1 reader; fresh writes detect as
  record-bound; same-value-different-record ciphertexts are mutually non-decryptable; the
  legacy fallback only fires on `AEADBadTagException` (malformed/unversioned fail closed);
  `fieldAad` domain separation and stability.
- `WebDavSyncServiceTest` (:196–208) updated for the new `reencryptFieldValue` signature
  (plaintext/blank/null still → null).

## Verification output

- `gradle testDebugUnitTest`: full suite green on the fix tree (665 tests).
  - Note on a flake: `PluginUpdateEngineTest > a hash mismatch ... never applied` failed once
    when `testDebugUnitTest` was chained with `assembleDebug` in a single invocation. I proved
    it unrelated: on the clean tree (`git stash`) that exact test passes in isolation, and it
    passes again in repeated full runs on the fix tree. It is an order/parallelism flake in the
    plugin-update crypto fixture (real RSA signing, SHA-256 mismatch), not a regression of this
    phase. It was NOT present in `Phase 106` as a known failure.
- `gradle :app:assembleDebug`: BUILD SUCCESSFUL (debug APK packaged).

## Checksum / secrets handling

- No keys, passwords, or decrypted content are logged or surfaced anywhere new. The migration
  runs on the in-memory DEK only (`repository.encryptionKey`), never on plaintext; legacy rows
  are decrypted in memory and immediately re-encrypted with the same DEK.
- `decryptField`'s legacy fallback is the single well-defined pre-phase-107 layout (v1 global
  AAD), consistent with the phase-105 policy of no version-byte guessing: the retry fires only
  on `AEADBadTagException` of an otherwise-valid versioned payload.
- `allowBackup=false`, ClipboardGuard and FLAG_SECURE untouched.

## API/device floor

- No new API levels used. All field crypto is pure JVM (`AES/GCM/NoPadding`,
  `java.util.Base64` fallback already in place since phase-105). API 26+ unaffected; the
  migration is a pure-Room-persisted flag, no hardware dependency. No fallback/notice needed.

## Out of scope (documented, NOT fixed here)

- B2-CRYPTO-10 (`EncryptionService.kt`: plaintext field writes when `encryptionKey == null`)
  remains a separate phase (108) for empty/partially-encrypted-vault encryption enforcement;
  this phase only migrates already-encrypted rows and leaves plaintext rows alone.
- Whole-backup / DEK-wrap decrypts (`ImportExportService` backup v1/v2 paths,
  `NoteflowViewModel.setMasterPassword/verifyMasterPassword/changeMasterPassword` KEK wraps)
  are NOT record field ciphertexts — out of scope by design.
- The pre-existing `PluginUpdateEngineTest` flake is documented above; not touched.
- `docs/security-report.md` / `docs/phase-status.md` are generated by the pipeline and were
  NOT edited here.