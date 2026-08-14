# Phase 105 — B2-CRYPTO-05 (LOW): `EncryptionService.decrypt` version-byte-guessing fallback runs a SECOND full GCM decrypt only on `AEADBadTagException` — a timing/tag-behavior oracle + malleable-retry

## Finding (from `docs/security-report.md`, B2-CRYPTO-05, lines 751-757)

- Area: Batch 2 — Crypto side-channels & edge cases.
- Evidence (before): `EncryptionService.kt:85-93` — a payload whose first byte equals
  `PAYLOAD_VERSION` (and is ≥13 bytes) is decrypted versioned first, and ONLY an
  `AEADBadTagException` triggers the legacy path (`decryptCore(... offset=0, withAad=false)` at
  `EncryptionService.kt:93`); `EncryptionService.kt:96-113` runs a full `AES/GCM doFinal` per
  attempt.
- Exploit: a co-located/forensic attacker (or one inducing a controlled decrypt through a
  plugin) can time field decrypts to classify each ciphertext row as versioned (2 decrypts,
  AAD-bound) vs legacy (1 decrypt, NO AAD) — flagging exactly which records are NOT bound by
  `FIELD_AAD` (see B2-CRYPTO-09 / phase-107). The fallback also means a tag mismatch ALWAYS
  gets one retry — malleable-retry behavior that padding-oracle playbooks probe for.
- Prescribed fix: pick the format DETERMINISTICALLY (legacy formats are exactly 12-byte-IV +
  ciphertext with no version prefix; versioned are ≥13 with byte 0 = 1 — distinguish by a
  committed length/format marker) so a tag failure NEVER triggers a second decrypt on a
  guessed layout; **drop legacy support / fail closed if feasible**.

## What changed

### 1. Primary fix — `app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt` `decrypt` (`:91-98`) + `decryptCore` (`:160-173`)

- **BEFORE** (`:85-93, :156-173`): `decrypt` decoded the payload, probed `byte[0] ==
  PAYLOAD_VERSION && size >= 13`, tried the versioned decrypt, and on ANY `AEADBadTagException`
  silently re-ran `decryptCore(offset=0, withAad=false)` — a full second GCM attempt against a
  guessed unversioned, no-AAD layout.
- **AFTER**: `decrypt` selects the format **deterministically and fails closed**:
  - payload must be ≥ `GCM_IV_LENGTH + 1` (13) bytes, else `IllegalArgumentException`;
  - `byte[0]` must equal `PAYLOAD_VERSION`, else `IllegalArgumentException("…missing version
    marker")`;
  - otherwise a single `decryptCore` run (offset 1, `FIELD_AAD` bound). A tag mismatch
    (`AEADBadTagException`) **propagates immediately** — there is no second decrypt, no legacy
    retry, no re-guessed layout. `decryptCore` lost its now-dead `offset`/`withAad` parameters.
- **Why dropping the legacy path is safe:** every field-payload writer in this repo has always
  produced `[PAYLOAD_VERSION][12-byte IV][ciphertext+tag]` bound to `FIELD_AAD` —
  `encrypt` (`:62-77`) and `encryptAad` (`:108-120`), both present in the FIRST integrated
  commit (`d31c23d`); the format has never changed in the repo's full history (3 commits touch
  this file). A GCM search of `app/src/main` confirms no other writer exists. "Legacy"
  (unversioned, no-AAD) payloads have never been written, so keeping a reader for them served
  only as an oracle/retry. `decrypt` call sites (`NoteRepository.kt:158,456,467,630,833,836`,
  `ImportExportService.kt:1200,1492`, `NoteflowViewModel.kt:1890,1932`) all consume
  `encrypt` output, so the deterministic rule never rejects real data.
- **`decryptAad` is intentionally untouched** (out of scope): its `FIELD_AAD` retry re-uses
  the SAME layout (offset 1) with a different AAD and only rescues pre-B2-CRYPTO-03 wrapped
  DEKs so old backups still restore — pinned by
  `BackupV2CryptoIntegrityTest.pre-fix backups still restore`. That is not version-byte
  guessing and is required backward compatibility (see Out of scope).

### 2. Test-enabling robustness — `base64Encode`/`base64Decode` (`:44-60`)

`android.util.Base64` returns `null` (it does NOT throw) for malformed input, and the unit-test
`android.jar` mockable defaults (`unitTests.isReturnDefaultValues = true`) make it return
`null` for every call. Previously this silently produced an NPE either in `base64Decode`'s
result or up-stream (`combined.size`). The fallback to `java.util.Base64` ("API 26+", exactly
the app floor) now fires on a `null` return as well as on a thrown exception:

- `base64Encode`: `val encoded = Base64.encodeToString(...); if (encoded != null) encoded else
  java.util.Base64...` — on device, primary path unchanged for valid data.
- `base64Decode`: `val decoded = Base64.decode(...); if (decoded != null) decoded else
  java.util.Base64...` — malformed Base64 now surfaces as `IllegalArgumentException` (caught by
  `decryptOrNull`/`isFieldEncrypted`/`reencryptFieldValue`) instead of an NPE.

This is the only way the pure-JVM round-trip tests of `decrypt` can run (no Robolectric, no new
dependency), and it incidentally fixes the PRE-EXISTING `EncryptionAndServiceTest.
testEncryptDecryptCycle` failure (mockable `android.jar` Base64 → null), the same one
documented in the phase-101/102/103/104 reports as a known artifact. Verified: the pre-existing
failure reproduces on a pristine baseline (worked tree at HEAD before edits), and is now green.

## Security / checksum / secrets handling

- No key, password, salt, wrapped DEK or decrypted content is logged or newly persisted; the
  change only removes a fallback decrypt path and hardens Base64 null-handling.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE: untouched.
- No DB schema change, no migration (constraint honored — nothing at rest changes shape); no
  new dependencies; `.github/workflows/` not modified.
- **API floor (API 26+)**: primary path still `android.util.Base64` (all APIs); the new
  `java.util.Base64` fallback requires API 26+, which IS the app floor (`minSdk = 26`,
  `app/build.gradle.kts:16`) — no fallback or non-alarming notice needed (AGENTS.md
  hardware-reality rule satisfied).

## Tests (pure-JVM, `app/src/test`)

**New `EncryptionServiceDecryptFormatTest.kt`** (7 tests, all green):
1. `correct versioned payload round-trips through the single chosen path` — full
   `encrypt` → `decrypt` round-trip through the deterministic versioned path (now actually
   runnable in JVM thanks to the Base64 fix).
2. `tampered ciphertext fails exactly once with the tag exception - no fallback retry` — a
   ciphertext-byte flip raises `AEADBadTagException` directly (the OLD code swallowed it and
   ran a legacy retry); `decryptOrNull` returns null (public surface stays non-crashing).
3. `tampered IV also fails with the tag exception` — GCM authenticates the IV; still one
   attempt, propagates.
4. `flipping the version marker to a non-1 value is rejected - never re-guessed as legacy` —
   byte[0]→0x02 raises `IllegalArgumentException` BEFORE any decrypt; the old code treated this
   as legacy and attempted a shifted no-AAD decrypt.
5. `legacy unversioned payload is rejected - legacy support dropped, fail closed` — a manually
   built `[12-byte IV][GCM ct+tag]` no-AAD payload with IV[0]=7 raises
   `IllegalArgumentException`; `decryptOrNull` returns null.
6. `legacy payload whose IV happens to start with the version byte is deterministically
   rejected` — the exact 1-in-256 collision the old fallback existed for: the payload is NOT
   versioned, so it fails closed (either the format check or a single versioned-path tag fail)
   and is NEVER silently decrypted as legacy.
7. `overshort payload is rejected before any decrypt attempt` — < 13 bytes →
   `IllegalArgumentException`, `decryptOrNull` null.

Existing suites passing unchanged: `BackupV2CryptoIntegrityTest` (pins `decryptAad`'s
FIELD_AAD fallback — untouched), `WebDavSyncServiceTest`
(`restoreFieldReencryptLeavesPlaintextAlone` — plaintext/blank fields still return null from
`reencryptFieldValue`), `EncryptionAndServiceTest` (incl. the formerly-red
`testEncryptDecryptCycle`).

## Verification output

1. `gradle :app:testDebugUnitTest --tests <new test classes>` → BUILD SUCCESSFUL (33 tests).
2. `gradle testDebugUnitTest` (full multi-module suite: plugin-sdk, plugins/llm, app) → BUILD
   SUCCESSFUL. App module report: **646 tests, 0 failures, 0 errors** (636 baseline + 10 new
   phase-105/related tests; the pre-existing `testEncryptDecryptCycle` failure is now green).
3. `gradle assembleDebug` → BUILD SUCCESSFUL. First invocation flaked at `:app:mergeExtDexDebug`
   with a `DexArchiveMergerException` (no root-cause detail in the task output; class/type
   changes compiled cleanly in `compileDebugKotlin`); a rerun (`--rerun-tasks` on
   `:app:mergeExtDexDebug`) succeeded cleanly and the final full `assembleDebug` was BUILD
   SUCCESSFUL with `app/build/outputs/apk/debug/app-debug.apk` produced — the failure was a
   transient Gradle-daemon/dex-merge hiccup, unrelated to code (source compiles, tests green,
   rerun green).

## Out of scope (documented, not fixed)

- B2-CRYPTO-09 (phase-107): per-record AEAD binding / migrating legacy no-AAD rows — this
  phase drops the legacy *reader*, phase-107 handles write-path binding and migration.
- `decryptAad`'s `FIELD_AAD` retry (EncryptionService.kt:132-143): kept deliberately so backups
  exported before B2-CRYPTO-03 still restore; it retries the SAME layout with a different AAD
  (not a guessed layout) and is pinned by `BackupV2CryptoIntegrityTest`. Not the B2-CRYPTO-05
  oracle.
- `NoteRepository` decrypt failure-fallbacks returning raw ciphertext (B1-DB-8, phase-88), and
  all other Batch-2 findings — separate phases.
- **New related observation (not fixed here):** `isFieldEncrypted`
  (`NoteRepository.kt:155-163`) classifies a field via "did decrypt throw?" rather than a
  structural check — the same gap B2-CRYPTO-10 (phase-108) targets. No action taken in this
  phase.