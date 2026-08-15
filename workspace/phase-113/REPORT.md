# Phase 113 — B2-CRYPTO-07 (INFO): No Unicode normalization for master/backup passwords

Finding fixed: `B2-CRYPTO-07` — master/backup passwords entered PBKDF2 as raw
UTF-16 code units with zero `Normalizer` usage. Two visually identical
passwords could permanently mismatch: a password typed once as NFC `é`
(U+00E9) and later typed as NFD `e` + U+0301 on another keyboard/IME derived a
different key and silently locked the vault (and any password-protected
backups) with no diagnostic. `password.length` semantics were also
code-unit-based, and long passwords shifted PBKDF2 per-iteration cost.

The finding is INFO because there is exactly ONE derivation path (two byte
sequences can never silently collide), so the impact was denial-of-service /
operational, not compromise.

## What changed (file:line)

### `app/.../services/EncryptionService.kt`

| Point | Before | After |
|-------|--------|-------|
| `deriveKey` (`:66`) | `PBEKeySpec(password.toCharArray(), salt, 600k, 256)` — raw UTF-16 | NFKC-normalizes first (`normalizePassword`) — **the single derivation path**; documented in the KDoc. All six call sites inherit the fix through this one function. |
| new | — | `normalizePassword(raw)` (`:50`) — `Normalizer.normalize(raw, NFKC)`. |
| new | — | `normalizedGraphemeCount(raw)` (`:60`) — length in **extended grapheme clusters** via `BreakIterator.getCharacterInstance()`, on the normalized form. |
| new | — | `MIN_PASSWORD_GRAPHEMES = 6`, `MAX_PASSWORD_GRAPHEMES = 128` (public consts) + `isValidPasswordLength(raw)` (`:72`) — grapheme-based length gate. |
| new | — | `deriveKeyLegacyRaw(password, salt)` (`:143`) — the pre-fix raw-UTF-16 derivation, used ONLY to READ pre-fix vaults/backups whose password was set with a non-NFKC byte sequence. |
| new | — | `passwordCandidates(raw)` / `deriveKeyCandidates(password, salt)` (`:100`/`:123`) — the normalized-form-first candidate list for unlock paths; the raw legacy candidate is only present when `raw != normalize(raw)`, so the common path never runs a second PBKDF2. |

### `app/.../ui/viewmodel/NoteflowViewModel.kt`

| Point | Before | After |
|-------|--------|-------|
| `setMasterPassword` (`:1800`) | `password.trim().isEmpty() || password.length < MIN_PASSWORD_LENGTH` (code units) | normalizes first, then `normalized.isBlank() || !isValidPasswordLength(normalized)`; derives from the normalized string (`:1819`). |
| `changeMasterPassword` (`:1845`) | `newPassword.length < MIN_PASSWORD_LENGTH` | normalized-form grapheme gate; derives from normalized (`:1860`). |
| `MIN_PASSWORD_LENGTH` companion const | code-unit 6 | removed — superseded by `EncryptionService` grapheme constants. |
| `verifyMasterPassword` (`:1906`) | single raw derive + decrypt; `finally` zeroized `kek` | deleg; a wrong/nonexistent master state short-circuits to `false` without bumping failed-attempt counters; on success the KEK is zeroized inside `unwrapMasterDek`. |
| new `unwrapMasterDek` (`:1950`) | — | shared unlock helper: iterates `deriveKeyCandidates` (normalized first, legacy raw only if different), GCM-authenticated, side-effect free, zeroizes every rejected KEK. |
| `isMasterPasswordValid` (`:1988`) | duplicated raw derive + decrypt | delegates to `unwrapMasterDek`, zeroizes the DEK. |

### `app/.../services/ImportExportService.kt`

| Point | Before | After |
|-------|--------|-------|
| `exportBackup` backup-password `require` (`:1266`) | `backupPassword.length >= 6` (code units) | grapheme min+max `require` on the NFKC-normalized form (`normalizedGraphemeCount`). |
| `tryParseBackupV2` (`:1397`) | single raw derive + decrypt | iterates `deriveKeyCandidates`; a wrong-password outcome retries the legacy raw candidate; a **corruption** diagnosis (password proven via the wrapped-DEK probe) remains final. KEK ownership/zeroization preserved. |
| `validateBackupPassword` (`:1462`) | single raw derive + decryptAad | iterates `deriveKeyCandidates`; zeroizes every rejected KEK. |

### UI length messages (grapheme-based, non-alarming)

| Point | Before | After |
|-------|--------|-------|
| `Dialogs.kt` set-master block (`:583`) | `password.length < 6` | `normalizedGraphemeCount`, min AND max messages. |
| `Dialogs.kt` change-master block (`:539`) | `newPass.length < 6` | grapheme min/max messages. |
| `HomeScreen.kt` backup-dialog pre-check (`:1177`) | `backupPasswordInput.length < 6` | grapheme-based min message (the real gate stays `isMasterPasswordValid`). |

### Tests

`app/src/test/java/com/authorss81/noteflow/UnicodeNormalizationPasswordTest.kt`
(new, 9 tests, pure-JVM, no network): NFC↔NFD same-key derivation; a DEK
wrapped under the NFC key unlocks with the NFD key; full-width→half-width NFKC
collapse; `normalizePassword` idempotent; grapheme-vs-code-unit counting
(combining marks and non-BMP emoji); the length gate accepting a 100-emoji
(200-code-unit) password while rejecting 200 ASCII or 5 graphemes; single vs
dual `passwordCandidates`; and the legacy path opening a pre-fix non-NFKC
vault via `deriveKeyCandidates`.

## Why a legacy-raw fallback exists (not a second derivation path)

The fix must not lock an EXISTING vault. A pre-fix vault key was derived from
the raw bytes typed at set time; a user whose IME emits NFD (or who used a
compatibility character) has a stored key that the normalized path alone
cannot reproduce — the fix would have turned their formerly-working vault into
the exact silent lockout the finding describes. `deriveKeyCandidates` therefore
tries the single normalized path first and, ONLY when the typed input is not
already normalized, the raw bytes. Both attempts sit behind the same GCM tag
check and the same 600k-iteration PBKDF2, reachable only through human input at
a device prompt — no oracle, no path asymmetry for stored data. New data is
written exclusively with the normalized path.

## OS/API floor (AGENTS.md hardware reality)

`java.text.Normalizer` and `java.text.BreakIterator.getCharacterInstance()` are
available since API 1 (both are JVM/Android `java.text` classes — Android backs
them with ICU, the JVM with the platform Unicode data, and both implement the
same UAX #15 NFKC / UAX #29 grapheme-cluster rules). No feature gating is
required; the API 26+ floor is covered with no fallback and no notice.

## Verification output

1. `gradle :app:testDebugUnitTest --tests UnicodeNormalizationPasswordTest --tests EncryptionAndServiceTest` → first run surfaced 2 genuine failures that were BOTH test-authoring bugs (a mistaken `\uff2f`=O vs `\uff30`=P in the full-width expectation, and feeding the legacy raw candidate back through the normalizing `deriveKey`). Fixed by introducing `deriveKeyCandidates` (the raw candidate must be derived by `deriveKeyLegacyRaw`), then:
2. `gradle testDebugUnitTest` (full multi-module: plugin-sdk, plugins/llm, app) → **BUILD SUCCESSFUL** in 48s. Aggregated app results: **711 tests, 0 failures, 0 errors, 0 skipped** (702 pre-existing + 9 new).
3. `gradle assembleDebug` → **BUILD SUCCESSFUL** in 2m 32s (90 tasks), producing `app/build/outputs/apk/debug/app-debug.apk`.

### Pre-existing flake observed (documented, unrelated, NOT fixed here)

The documented phase-112 intermittent `PluginUpdateEngineTest` flake appeared
once during my first full-suite run (`a hash mismatch on the downloaded
artifact is never applied`, 1 of the class's 10 testcases) and was green on
every isolating re-run. It was proven pre-existing in phase-112 (fails against
the unmodified baseline; root cause = subprocess `keytool`/`jarsigner` signing
with wall-clock `ZipEntry` timestamps). Not related to this change; tracked for
the Phase 27 bug-fix queue.

## Checksum / secrets handling

No key, password, salt, wrapped DEK or decrypted-note content is logged or
newly persisted. The derivation layers' `ByteArray` secrets are zeroized exactly
as before (`kek`/KEK `fill(0)` on every path, now including each rejected
candidate key); `unwrapMasterDek` returns the DEK to the caller for
zeroization, `isMasterPasswordValid` zeroizes it, and `importBackup` still
owns/zeroizes `v2.kek`. `allowBackup="false"`, `ClipboardGuard` and
`FLAG_SECURE` untouched. No DB schema change / no migration.

## Out-of-scope / notes

- Only B2-CRYPTO-07 is addressed. The sibling Batch-2 crypto findings
  (B2-CRYPTO-08 RNG provider pinning, B2-CRYPTO-10 blank-field AEAD) are
  separate phases.
- `docs/security-report.md` status table intentionally NOT edited: every prior
  fix phase (106–112) shipped its REPORT.md and left the planned-phase table
  untouched; this change follows that convention.
- `.github/workflows/` untouched; no new dependency (both `Normalizer` and
  `BreakIterator` are JDK/Android `java.text`).
- Backward compatible: existing ASCII/NFC master passwords and backups derive
  unchanged keys (normalization is a no-op for already-NFKC input); only
  pre-fix non-NFKC-byte passwords get the documented legacy read.