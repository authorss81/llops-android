# Phase 90 — B1-PLAT-8 (LOW): Master password minimum length of 6; on-device lockout does not protect against offline brute force

**Status:** DONE · **Date:** 2026-08-16 · **Finding:** B1-PLAT-8 (Batch 1 · Android platform surface)

## Summary

`B1-PLAT-8` (LOW) complained that a NEW master password could still be as short as
6 characters and that the on-device 5-attempt lockout gave false assurance against
an offline attacker who copies the prefs + SQLCipher vault and GPU-brute-forces the
wrapped DEK.

Since phase-63 (B1-CRYPTO-04) the actual enforcement gate was already the pure-JVM
`PasswordStrengthPolicy` with a floor of **8** graphemes (the old `MIN_PASSWORD_LENGTH = 6`
in `NoteflowViewModel.kt` is long gone). This phase closes the residual gap:

1. **Raise the floor to ≥ 10** NFKC-normalized graphemes and
2. **reject common / prefix-suffix password words** (the exact wordlist-first keyspace an
   offline cracker feeds to a GPU rig), and
3. **document** in code + `docs/RELEASE.md` that offline brute force is only mitigated by
   password *entropy*, never by the on-device lockout.

## What changed (before → after, `file:line`)

### Enforcement policy — `app/src/main/kotlin/com/authorss81/noteflow/services/PasswordStrengthPolicy.kt`

| Before | After |
|---|---|
| `const val MIN_STRENGTH_GRAPHEMES = 8` | `const val MIN_STRENGTH_GRAPHEMES = 10` (`:104`) — 6-9 char passwords (incl. the finding's `password1` / `12345678` class) now return `TOO_SHORT` at set/rotate |
| no common-word detection | `commonPasswordBases` (`:129`) + `isCommonPasswordVariant` (`:160`) + `PasswordStrengthVerdict.COMMON_PASSWORD` (`:80`): a widely-leaked base word (`password`, `sunshine`, `letmein`, `admin`, `monkey`, …) is refused **whole** OR **thinly decorated** with only digit/symbol padding around it (`monkey5281`, `2026sunshine`, `password12`, `letmein!!!`). Detection is structural (`before`/`after` around the contiguous lowercase base must be all non-letters) so genuine passphrases that merely contain a word (`correct horse battery staple`, `sunshine on parade 2021`) keep passing |
| `evaluate` order: length → seq → distinct → diversity | `evaluate` (`:137`) now also runs `isCommonPasswordVariant` (`:144`) — checked **before** `LOW_DIVERSITY` so a decorated common word reports "too common", not merely low-diversity. `SEQUENTIAL` still runs first (a `…1234` tail is a predictable pattern) |
| KDoc: UI-only caveat + Argon2id/TEE follow-ups (B1-CRYPTO-04) | KDoc now explicitly states B1-PLAT-8: *"offline brute force on a copied vault is only mitigated by password entropy, NOT by the on-device lockout"* and that restoring to a rooted emulator defeats the UI lockout entirely |

### Enforcement sites (unchanged calls, updated comments)

- `NoteflowViewModel.kt:2447` — `setMasterPassword` gates the NEW password on
  `PasswordStrengthPolicy.evaluate(password).accepted` (comment updated: ≥ 10 graphemes,
  no sequential/keyboard/common-word/prefix-suffix patterns, standard NFKC form).
- `NoteflowViewModel.kt:2527` — `changeMasterPassword` gates only the NEW password; the OLD
  password is verify-only, so a pre-existing weaker vault keeps unlocking and rotating up.
- `Dialogs.kt` set (`:696`) / change (`:644`) dialogs surface `verdict.message` (exact
  human-readable non-alarming reason) — already wired; comments updated to the 10-char bar.
- `services/BackupPasswordPolicy.kt` delegates to the same decision table, so a password
  backup can never be protected by a password weaker than its vault (KDoc re-worded to the
  new floor). Restore/verify paths remain **not** strength-gated (pre-fix weak-password
  backups keep restoring).

### Documentation

- `docs/RELEASE.md` gotchas: new bullet warning that **offline brute force is only mitigated
  by password entropy, not the 5-attempt UI lockout**, with a recommendation to use long
  passphrases (≥ 16 chars).
- `docs/ARCHITECTURE.md` + `docs/phase-status.md` + `docs/security-report.md` updated with
  the phase-90 record; `docs/security-report.md` B1-PLAT-8 finding flipped to
  `STATUS: FIXED (phase-90)`.

## Checksums / secrets handling

- No keys, passwords, or note content are logged, persisted, or new-sourced. The policy
  operates only on the in-memory password string handed to the set/change dialogs and the
  same NFKC-normalized form `EncryptionService.deriveKey` hashes (B2-CRYPTO-07); nothing new
  is written to disk or SharedPreferences. `allowBackup=false`, `ClipboardGuard`, and
  FLAG_SECURE are untouched. No new permissions, no network usage.

## Compatibility / hardware-reality floor (AGENTS.md)

- Pure JVM decision table (`java.text.BreakIterator`, `java.util.Locale`) — runs identically
  on the API 26+ floor. No newer-API requirement, no fallback needed; the change is a
  guard-rail at set/rotate, not a runtime path. `PasswordStrengthVerdict.message` is
  non-alarming by design.
- **Deliberate trade-off (documented in KDoc / REPORT):** existing vaults created with a
  6-9 char password keep unlocking and rotating — the floor applies to NEW passwords only
  (it is, and remains, never at verify/unlock). Unlock never strength-gates.

## Scope / out-of-scope (seen, not fixed here)

- `EncryptionService.MIN_PASSWORD_GRAPHEMES = 6` (`isValidPasswordLength`) is the legacy
  storage-format floor used only by the `UnicodeNormalizationPasswordTest` oracle; it is not
  a live gate (no main-source call site). Left untouched to keep the diff narrow — the
  authoritative gate is `PasswordStrengthPolicy`.
- A TEE-bound attempt gate or Argon2id KDF (would raise offline crack cost) remain tracked
  follow-ups from B1-CRYPTO-04 — deliberately not introduced (no new deps, no new platform
  requirements). New related observation worth noting, NOT fixed here: the lockout counters
  at `verifyMasterPassword` are UI-only by design; that is exactly what B1-PLAT-8 documents.
- Leetspeak-safe fuzzy matching (`w3lcome`) and interior-letter decorations (`xpasswordx`)
  are NOT common-detected — the structural rule intentionally permits them so genuine
  passphrases are never blocked; the residual is documented in the policy KDoc.

## Verification output

### `gradle testDebugUnitTest` (full suite) — GREEN

- App module: **1564 tests, 0 failures, 0 errors, 0 skipped** (aggregated from the Gradle
  JUnit XMLs). `:plugins:llm:testDebugUnitTest` also green. No pre-existing failures
  (the `B1Plat01ReleaseSigningTest` asserts documented as pre-existing in earlier phases
  are green in this tree since the `b9a0b52` CI fix).
- New/updated tests:
  - `B1Crypto04PasswordStrengthTest` — 17 tests green. **3 new B1-PLAT-8 tests:**
    `bare and prefix-suffix decorated common passwords are rejected`
    (`password12`/`passw0rd2025`/`monkey5281`/`2026sunshine`/`letmein!!!`/`Princess2025`/
    `iloveyou2025`/`superman4002`/`sunshine911` → `COMMON_PASSWORD`, and the 
    fires-before-LOW_DIVERSITY ordering pin); `common detection leaves genuine passphrases
    and mutated words alone` (`sunshine on parade 2021`, `my monkey friend`,
    `W3lcome2Vault!`, `xxpasswordxx` → `ACCEPTED`); `the stronger minimum of 10 and the
    offline-entropy caveat are documented` (min == 10, policy + RELEASE.md carry the
    entropy-vs-lockout caveat). Existing cases updated to the 10 floor: 8-9 char inputs now
    assert `TOO_SHORT`, sequential/keyboard/repeated/NFKC cases use ≥10-char inputs,
    accepted set uses ≥10-char passwords (`D4rkMn2!` → `D4rkMn2!9x`), passphrase case
    `Sunshine#2026` → `AuroraSky#2026` (it is now, correctly, a common word).
  - `B2Crypto04BackupPasswordTest` — 11 tests green, updated to the new floor
    (`123456789` → `TOO_SHORT`, `1234567890`/`qwertyuiop`/`abcdefghij` → `SEQUENTIAL`,
    `aaaaabaaaa` → `WEAK`, `sunshine123` → `COMMON_PASSWORD`, `PASSWORD12X` →
    `LOW_DIVERSITY`, and "8 characters" → "10 characters" message assert).
  - `UnicodeNormalizationPasswordTest` — unchanged, green (the legacy
    `EncryptionService.isValidPasswordLength` oracle is untouched).

### `gradle assembleDebug` — GREEN

- First invocation failed with the **documented transient dex-merge failure**
  (`DexArchiveMergerException`, 2 GB default daemon heap — same as reported in phases
  62/83); retry **BUILD SUCCESSFUL** (90 tasks, 31 executed).
- Artifact: `app/build/outputs/apk/debug/app-debug.apk` — 173,787,778 bytes,
  SHA-256 `232ad7013ba0d8337c79b0366829fcccf37076d40d85bc4c523cadf6d41a7cbc`.

### Definition-of-done checklist

- [x] Vulnerability path closed with `file:line` before/after evidence (above).
- [x] API 26+ floor — pure JVM policy, no newer-API requirement, no fallback needed;
      non-alarming verdict messages.
- [x] New unit tests prove the fix; no existing test regressed (1564 app tests green).
- [x] `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (transient dex-merge
      failure on first assembleDebug invocation documented + green on retry).
- [x] `workspace/phase-90/REPORT.md` committed with what/where, checksums/secrets handling,
      verification output, and out-of-scope inputs.

## Constraints honored

- No DB schema change, no migration (password policy is prefs/UI-adjacent only; the
  credential blob format B1-CRYPTO-03 is untouched).
- `.github/workflows/` not edited. No new dependencies.
- Never logs keys/passwords/decrypted content; `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE intact. No other security findings fixed or modified in this phase.