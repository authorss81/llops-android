# Phase 63 — B1-CRYPTO-04: weak-password policy for the master password (+ UI-only-lockout documented)

**Status: DONE** · 2026-08-15 · FIXES `B1-CRYPTO-04` (MEDIUM, Batch 1 · Cryptography & key management)

## Finding (from `docs/security-report.md`)

The master password had only a 6-grapheme length floor and **no complexity/entropy
check** (`NoteflowViewModel.kt` used `MIN_PASSWORD_LENGTH = 6`; the only protection was
600k-iteration PBKDF2-HMAC-SHA256). The salt + wrapped DEK (`masterPasswordSalt` /
`masterPasswordWrappedDek`, now the single `MPB1|…` blob per phase-62) and the SQLCipher
`noteflow.sqlite` sit on the normal data partition. An attacker who obtains a data copy
(cloud/manual backup, forensic extraction, shared device image) cracks the wrapped DEK
**offline** with a GPU/FPGA PBKDF2-SHA-256 rig; a 6–7 char lowercase/numeric password
falls in hours-to-days. The on-device 5-fail lockout never fires for an offline attacker
— and the finding also flags that the UI lockout is inherently process-local/UI-only and
that `isMasterPasswordValid` is a side-effect-free oracle (no attempt accounting).

## What changed (before / after)

### 1. New pure-JVM `services/PasswordStrengthPolicy.kt` (new file — the single decision table)

- `PasswordStrengthVerdict` (`:50`) — `ACCEPTED` / `TOO_SHORT` / `TOO_LONG` /
  `SEQUENTIAL` / `WEAK` / `LOW_DIVERSITY`, each carrying a human-readable, non-alarming
  `message` for the dialogs.
- `PasswordStrengthPolicy.evaluate(raw)` (`:94`) on the **NFKC-normalized** password
  (the exact byte string `EncryptionService.deriveKey` hashes, B2-CRYPTO-07):
  1. **TOO_SHORT** — `< 8` graphemes (`MIN_STRENGTH_GRAPHEMES = 8`, `:77` — stronger
     than the old 6 floor, still ≤ the 128 cap enforced by `TOO_LONG`).
  2. **TOO_LONG** — `> 128` graphemes (shared with `EncryptionService.MAX_PASSWORD_GRAPHEMES`).
  3. **SEQUENTIAL** — `containsSequentialPattern` (`:113`): a known keyboard-row
     substring (`qwerty`, `asdf`, `qaz`, `1234567890`, …) or a monotonic run of 4+
     consecutive ASCII letters/digits in either direction (`12345678`, `abcdefgh`,
     `9876`, `zyxw`).
  4. **WEAK** — fewer than 3 distinct graphemes (`distinctGraphemeCount` `:141`), killing
     the `aaaaaaaa` / `12121212` / `abab…` tiny-keyspace class regardless of length.
  5. **LOW_DIVERSITY** — under 12 graphemes with fewer than 3 of the 4 character classes
     (upper / lower / digit / symbol, `classCount` `:144`) — rejects `password1`,
     `letmein1`. Passphrases ≥ 12 graphemes pass on length alone, so
     `correct horse battery staple` is accepted.
- KDoc (`:1-47`) documents the finding's caveat explicitly: **lockout is UI-only** and the
  vault is only as strong as the password; TEE-bound attempt gating / Argon2id are recorded
  as follow-ups and deliberately NOT implemented here (no new deps, no API-floor change —
  the policy runs on the API 26+ floor unchanged).
- Pure JVM (only `java.text.BreakIterator`, `Character`, `Locale.ROOT`, `EncryptionService`),
  so the whole decision table is unit-testable in `app/src/test`.

### 2. `ui/viewmodel/NoteflowViewModel.kt` — authoritative gate at set/change only

- **BEFORE** (`:2064-2065`, `:2122-2123`): `setMasterPassword` /
  `changeMasterPassword` checked only `isValidPasswordLength` (6..128 graphemes) — any
  6+ char password was accepted.
- **AFTER**:
  - `setMasterPassword` (`:2071-2072`): `if (!PasswordStrengthPolicy.evaluate(password).accepted) return false`
    — a weak NEW password is rejected before any salt/DEK work, with no state flipped.
  - `changeMasterPassword` (`:2134-2135`): `if (!PasswordStrengthPolicy.evaluate(newPassword).accepted) return false`
    — only the NEW password is strength-gated; the OLD password stays verify-only
    (`verifyMasterPassword`), so a pre-existing weaker vault keeps unlocking and rotating.
  - **Unlock paths never strength-gate**: `verifyMasterPassword` (`:2232`),
    `unwrapMasterDek` (`:2293`), `isMasterPasswordValid` (`:2324`) and
    `verifyBiometricsAndUnlock` are untouched — a 6-char vault created before this phase
    is never locked out by the new policy.

### 3. `ui/components/Dialogs.kt` — non-alarming, specific messages

- **BEFORE** (`:620-628`, `:670-678`): the set/change dialogs enforced only the bare 6-char
  floor themselves, and any rejection surfaced as a generic "Failed to set master password".
- **AFTER**:
  - Change-password dialog (`:629-646`): `val verdict = PasswordStrengthPolicy.evaluate(newPass)`;
    a non-`ACCEPTED` verdict shows `changeError = verdict.message` (the exact reason), before
    the match/confirm check and the `viewModel.changeMasterPassword` call.
  - Set-password dialog (`:681-700`): same `errorMessage = verdict.message` pattern ahead of
    `viewModel.setMasterPassword`.
  - `EncryptionService` import dropped, `PasswordStrengthPolicy` import added.

### 4. Tests — `app/src/test/.../B1Crypto04PasswordStrengthTest.kt` (10 tests)

Rejected set (weak/sequential/short), accepted set, NFKC-folding behavior, passphrase
exception, plus source pins:

- `evaluate` returns the exact verdict per input: `123456`, `abcdef`, `hunter2` →
  TOO_SHORT; `12345678`, `abcdefgh`, `987654321`, `zyxwvuts` → SEQUENTIAL; `qwertyuiop`,
  `asdfghjkl1`, `1qaz2wsx`, `qwerty123` → SEQUENTIAL; `aaaaaaaa`, `11111111`,
  `12121212`, `a*40`, `12*20` → WEAK; `password1`, `letmein1`, `iloveyou9`,
  `monkey123` → LOW_DIVERSITY; a 256-char input → TOO_LONG.
- NFKC folding: full-width `１２３４５６７８` → normalized `12345678` → SEQUENTIAL;
  full-width `１２３４５６` → TOO_SHORT.
- Accepted: `CorrectHorseBatteryStaple9!`, `Tr0ub4dor&3`, `W3lcome2Vault!`, `D4rkMn2!`,
  `Éléphant9!`; passphrases `correct horse battery staple`,
  `correcthorsebatterystaple`, `Sunshine#2026`.
- Source pins (read the repo files, same pattern as `B1Crypto03MasterPasswordAtomicTest`):
  - `PasswordStrengthPolicy.evaluate` appears EXACTLY twice in NoteflowViewModel (set +
    change) and never inside `verifyMasterPassword` / `isMasterPasswordValid` blocks;
  - the old bare `isValidPasswordLength` gate is gone from both set/change blocks;
  - both Dialogs.kt dialogs render `verdict.message`;
  - a repo-wide scan proves `PasswordStrengthPolicy` is referenced only by the policy
    file, NoteflowViewModel.kt and Dialogs.kt (no leak into any other subsystem).

## Verification output

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B1Crypto04PasswordStrengthTest"` — **PASS** (compile + 10 tests).
- `gradle testDebugUnitTest` — **1239 tests completed, 2 failed**, both
  `B1Plat01ReleaseSigningTest` asserts on `docs/RELEASE.md`/`app/build.gradle.kts` signing
  config. **Proven pre-existing and unrelated**: the same 2 fail on a fully stashed clean
  tree at commit `fd8274d` (same run, same assertions), matching the documented pattern in
  phases 55/59/60/61/62. This diff touches neither file.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (173.7 MB debug APK).

## Checksum / secrets handling

- No new secrets, keys, passwords or ciphertexts are logged or persisted. The policy only
  *rejects* weak password strings and never stores, derives or logs them; the wrapped-DEK /
  salt handling is unchanged (phase-62 atomic blob; B1-CRYPTO-02 at-rest policy).
- No keystore, no PBKDF2, no new data at rest. `allowBackup=false`, `ClipboardGuard` and
  FLAG_SECURE are untouched.

## API floor / hardware reality

- The policy uses only `java.text.BreakIterator` (API 1+), `Character` and
  `String`/`Locale.ROOT` — no API-gated features, no fallback required (AGENTS.md
  "API 26+ floor" satisfied unchanged). NFKC normalization already existed (B2-CRYPTO-07).

## Out-of-scope / documented follow-ups

- **Backup-password strength** (the "app encourages a 6-char backup password" angle) belongs
  to the B1-DB-7 finding family, NOT this one (per `docs/security-report.md:767`); the
  backup-password length floor stays 6 here. A future phase should route backup passwords
  through the same `PasswordStrengthPolicy` for parity — documented, not changed here.
- **UI-only lockout** is by design and now documented in the policy KDoc; the 
  `isMasterPasswordValid` side-effect-free oracle (no attempt counters) is required by the
  backup dialog flow (a typo there must not lock the vault) and is unchanged.
- **TEE-bound attempt gating / Argon2id KDF** — recorded as follow-ups in the policy KDoc
  and this report. Not implemented: both need either a new dependency (Argon2id) or a new
  platform capability (TEE); neither was justified for this MEDIUM finding under the
  repo's no-new-deps-without-justification rule. Existing PBKDF2-600k stays.
- No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.
