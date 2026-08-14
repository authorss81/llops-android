# Phase 108 — B2-CRYPTO-10 fix report

- **Finding:** `B2-CRYPTO-10` (LOW) — blank/empty plaintext fields stored raw and
  unauthenticated; `isBlank` doubled as the "is this encrypted?" classifier.
- **Status:** FIXED. Committed on `main`.

## What changed (before → after)

### 1. `EncryptionService.kt` — structural classifier (new public API)

- `EncryptionService.kt:279-290` — **NEW `isEncryptedPayload(String)`**: a purely
  structural format check. Decides "is this ciphertext?" ONLY from the payload
  shape — Base64 decodes to ≥ 13 bytes (version byte + 12-byte IV minimum) and
  carries the `PAYLOAD_VERSION` (1) marker — NEVER from content blank-ness. A
  blank plaintext stored correctly is a real 29-byte AEAD payload
  (`[1][12-byte IV][16-byte GCM tag]`, AES-GCM of empty input), so it classifies
  as encrypted; a raw `""` (pre-fix write or a zeroed column) is not a payload.
- `EncryptionService.kt:304-313` — **NEW `isFieldEncrypted(value, key, table,
  recordId, fieldName)`**: the field-layer classifier that replaces the old
  `isBlank`-based probe. Returns false for a raw blank (`""`) so re-encryption
  sweeps re-stamp it; returns true for a correctly-stored blank (a real payload,
  decryptable only under its own per-record AAD); returns false for any
  malformed/transplanted/garbage value.

### 2. `NoteRepository.kt` — classifier wiring + store paths

- `NoteRepository.kt:163-166` — private `isFieldEncrypted` now delegates to
  `EncryptionService.isFieldEncrypted`. **Before:** `if (value.isBlank()) return
  true // nothing to encrypt`, which made every blank column read as "encrypted
  and fine". **After:** blank-ness is never consulted; raw blanks are "not
  encrypted".
- `NoteRepository.kt:260-323` — `reencryptPlaintextFields` **now sweeps blank
  rows**. All `isNotBlank()`/`isNullOrBlank()` gates removed (kept only for
  `NULL` columns: `extracted != null` / `text != null` — there is no stored field
  content to stamp when the column is NULL). **Before**, blank columns were
  permanently skipped (the old classifier said "encrypted", the guard then
  skipped them); **after**, a raw `""` is classified not-encrypted and stamped
  as an authenticated 29-byte payload. Applies to all four field tables:
  `pages.{title,extractedText}`, `strokes.{textContent,pointsJson}`,
  `media_embeds.textContent`, `note_versions.{title,extractedText}`.
- Store paths — blanks are now encrypted as real AEAD payloads whenever a DEK is
  present (never raw `""`):
  - `createPage` → `NoteRepository.kt:455` (`storedExtracted`). **Before:**
    `encryptionKey != null && rawExtracted.isNotBlank()`. **After:**
    `encryptionKey != null`.
  - `saveStrokesForPage` → `NoteRepository.kt:664` (`textContent`),
    `:672` (`pointsJson`). **Before:** both gated on `isNotBlank()`.
  - `saveMediaEmbedsForPage` → `NoteRepository.kt:813` (`textContent`).
  - `createNoteVersion` → `NoteRepository.kt:897` (`extractedText`).
- Decrypt/read paths were **left unchanged intentionally**: after the store fix a
  legitimate blank is a non-blank ciphertext so it reaches the decrypt branch
  and round-trips to `""`; a legacy raw `""`/`NULL` column still reads back as
  `""`/`NULL` (guards skip it). This keeps every pre-existing read path correct
  and backwards-compatible without touching B1-DB-8 territory.

### 3. New pure-JVM tests — `app/src/test/.../BlankFieldEncryptionTest.kt`

7 tests (all pass), covering the finding's exact behaviors:
- empty plaintext round-trips as an encrypted 29-byte payload and decrypts to `""`
  (`decryptField`/`decryptFieldOrNull`);
- zeroing the ciphertext column (`""`) fails decryption (`decryptField` throws
  `IllegalArgumentException`, `decryptFieldOrNull` → null) and is **not**
  classified as an encrypted payload — while the legitimately-stored blank still
  decrypts, so the two are no longer indistinguishable at the field layer;
- the classifier never uses content blank-ness: `isFieldEncrypted("") == false`
  (so `reencryptPlaintextFields` now re-stamps blank rows), a stored blank
  `== true`, long plaintext `== false`;
- `isEncryptedPayload` is purely structural (blank / <13 bytes / wrong version
  marker / non-base64 all false; versioned payloads true);
- blank round-trip + tagging across every field table;
- a stored blank is recognised as already-encrypted (no double-encrypt on re-run)
  and survives a DEK re-key;
- a blank payload is bound to its record context (per-record AAD from
  B2-CRYPTO-09), so `isFieldBoundToRecord` treats it like any other ciphertext.

## Checksum / secrets handling

- No new keys, passwords, salts, or secrets introduced; no logging of keys,
  passwords, or decrypted content. The fix only changes how an already-valuable
  `EncryptionService` API classifies and stamps blanks.
- `allowBackup="false"`, `ClipboardGuard`, and FLAG_SECURE are untouched (no
  manifest/platform change).
- No DB schema change, no Room migration — the fix is column-content-only. An
  encrypted blank is a 29-byte ciphertext in an existing TEXT column; legacy raw
  `""`/`NULL` values remain readable. Nothing renders existing data unreadable.

## OS/API floor (AGENTS.md hardware reality)

- The fix uses only `AES/GCM/NoPadding` (JCE, available since API 10) and
  `SecureRandom` (API 1+) — no newer-API dependency, so no fallback or notice is
  required for the API 26+ floor.

## Verification

- `gradle :app:testDebugUnitTest` — **672 tests, 671 pass / 1 pre-existing
  flaky failure** (see below). All 7 new `BlankFieldEncryptionTest` cases pass
  (`tests="7" skipped="0" failures="0" errors="0"`); `FieldRecordAadTest` and the
  rest of the crypto suite remain green.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL**.

### Pre-existing flaky test (proven unrelated)

`PluginUpdateEngineTest > a hash mismatch on the downloaded artifact is never
applied` (`PluginUpdateEngineTest.kt:215-228`) fails intermittently. It was
reproduced on a **clean checkout** (changes stashed with `-u`) — `CLEAN RUN 1 =
FAILED`, `CLEAN RUN 2 = SUCCESS`, `CLEAN RUN 3 = FAILED` — and with my changes
(`RUN 1 = FAILED, RUN 2 = FAILED, RUN 3 = SUCCESS`). The failure (assert at
`PluginUpdateEngineTest.kt:224`) depends on the artifact-signing/hash fixture and
is independent of the encryption changes in this phase (no shared code path).

## Out of scope / noted but NOT fixed here

- **`migrateFieldRecordAad` isBlank guards left intact** (`NoteRepository.kt:181-245`)
  — per its contract, plaintext/blank rows are `reencryptPlaintextFields`' job.
  It already bound every non-blank row in phase-107; blanks are now stamped by the
  phase-108 sweep, and `encryptField` writes are already per-record-AAD bound.
- **`ImportExportService.reencryptFieldValue`** (`ImportExportService.kt:1202-1210`)
  keeps its `isNullOrBlank()` short-circuit: it re-keys existing ciphertext
  during cross-device restore. A blank stored after this phase is a valid
  non-blank payload and is re-keyed correctly; a legacy raw blank is left as-is.
  No change needed.
- **Read-path decrypt-failure fallbacks** (raw ciphertext returned on failure)
  are `B1-DB-8` (a different phase) and were NOT touched.
- **`encryptionKey == null` raw fallbacks** on the store paths are the
  pre-existing lock-boundary behaviour (`B1-AUTH-02` / `B2-UI-1`, different
  phases) and were NOT changed.