# Phase 62 — B1-CRYPTO-03: salt + wrapped-DEK written atomically as ONE versioned blob

**Status: DONE** · 2026-08-15 · FIXES `B1-CRYPTO-03` (MEDIUM, Batch 1 · Cryptography & key management)

## Finding (from `docs/security-report.md`)

`NoteflowViewModel.setMasterPassword` / `changeMasterPassword` wrote the salt and the
wrapped DEK as **two independent SharedPreferences `.apply()` writes**. A process kill
(low-memory killer, crash, battery pull) landing exactly between them left e.g. a NEW
salt with an OLD/missing wrapped DEK on disk. Every subsequent `verifyMasterPassword` /
`isMasterPasswordValid` then hit an AEAD tag mismatch permanently; the phase-09 H2
handler quarantined the vault as `*.corrupt-*`, and the user lost the entire vault from a
single unlucky kill. No settings-level checksum could detect the half-written pair.

## What changed (before / after)

### 1. New pure-JVM `services/MasterPasswordCredential.kt` (new file)

The salt + wrapped DEK + format become **one value**:

- `MasterPasswordCredential.serialize(salt, wrappedDek)` → `MPB1|<standard-base64 salt>|<wrappedDek base64>` (`MasterPasswordCredential.kt:55`).
- `MasterPasswordCredential.parse(blob)` fails CLOSED on absent/malformed/half values — a salt with no wrapper, a blank wrapper, an unknown format, or undecodable salt all parse to `null` (`MasterPasswordCredential.kt:66`).
- `MasterPasswordCredential.fromLegacy(saltB64, wrappedDek)` is the pre-phase-62 two-key reader so existing vaults keep unlocking until the next set/change migrates them (`MasterPasswordCredential.kt:82`).
- Pure JVM (`java.util.Base64` only — `android.util.Base64` returns defaults under the repo's `isReturnDefaultValues` unit-test config), so the serialization is unit-testable.

### 2. `services/SettingsManager.kt`

- **BEFORE** (`SettingsManager.kt:76-82`): two independent `var masterPasswordSalt` /
  `var masterPasswordWrappedDek` properties, each writing its own pref with `.apply()`.
- **AFTER**:
  - `masterPasswordCredentialOrLegacy` (`:84`) — the single read accessor: versioned
    blob first, legacy two-key pair as fallback.
  - `commitMasterPasswordCredential(salt, wrappedDek)` (`:101-108`) — writes the ONE
    versioned blob and removes the two legacy keys in **a single synchronous
    `commit()`**, and returns the disk-acknowledged result. SharedPreferences' commit is
    an atomic temp-file + rename, so a torn/killed write leaves the PREVIOUS blob intact
    and a half pair is structurally impossible. There is no `.apply()` async two-step.
  - `hasMasterPassword` (`:...`) now derives from `masterPasswordCredentialOrLegacy` (blob
    or legacy pair) — unchanged semantics for the `NoteflowSqlcipherFactory` /
    `LockedOpenGuard` gates.
  - `clearSecuritySettings` also removes the new blob key.

### 3. `ui/viewmodel/NoteflowViewModel.kt`

- **BEFORE** (`:2081-2082`, `:2123-2124`): `settings.masterPasswordSalt = Base64(...)`
  then `settings.masterPasswordWrappedDek = wrappedDek` — two independent writes.
- **AFTER**:
  - `setMasterPassword` (`:2059-2116`): the wrap is **round-trip validated** inside the
    derive block (`EncryptionService.decrypt(wrapped, derivedKek)` must succeed,
    `:2082-2083`), then `settings.commitMasterPasswordCredential(salt, wrappedDek)`
    (`:2094`) — and the commit's failure aborts (`return false`) BEFORE any in-memory
    state flips (`repository.encryptionKey`, `_hasMasterPassword`). On first run a failed
    commit leaves the vault passwordless; on a re-set it leaves the OLD credential.
  - `changeMasterPassword` (`:2118-...`): identical round-trip + atomic single-commit swap
    (`:2136-2137`, `:2145`). A failed commit keeps the OLD pair — the vault stays
    unlockable with the old password, never bricked.
  - `verifyMasterPassword` (`:2211`): null guard now reads
    `settings.masterPasswordCredentialOrLegacy`.
  - `unwrapMasterDek` (`:2272-...`): reads the credential through
    `settings.masterPasswordCredentialOrLegacy`; `saltBytes()` decode is fail-closed on
    malformed base64. Both halves now come from ONE stored value, so they can never be
    half-written relative to each other.

### 4. `services/DekAtRestPolicy.kt` — doc comment only (references the new accessor).

## Why a single `commit()` closes it (platform guarantee)

`SharedPreferences.Editor.commit()` (API 1+, inside the API 26+ floor — no new API, no
fallback needed) serializes the whole preference XML and swaps it in via an atomic
temp-file + `rename`, then returns a disk-acknowledged boolean. So ANY kill — between the
single putString and the disk rename, or mid-write — leaves the previous complete blob;
there is no two-step window for a half pair to reach the disk. `commit()`'s return value
additionally lets the caller abort without touching in-memory security state.

## Migration / backward compatibility

- **No DB schema change, no Room migration.** Only SharedPreferences shape changes.
- Pre-phase-62 devices that already have `master_password_salt` +
  `master_password_wrapped_dek` in prefs keep unlocking via `fromLegacy` (the accessor
  falls back to the two keys, still present untouched on those devices). The next
  `setMasterPassword` / `changeMasterPassword` migrates them to the blob in the same
  atomic commit and drops the two keys.
- The legacy salt string was written by `android.util.Base64.encodeToString(NO_WRAP)`;
  `java.util.Base64` decodes the same standard alphabet, so byte-identical salt round-trip.

## Checksum / secrets handling

- Salt and wrapped DEK remain unencrypted prefs values exactly as before — they are
  PBKDF2 salt and an authenticated AEAD-wrapped key, not plaintext secrets; the wrapping
  is unchanged (`EncryptionService.encrypt`, 600k PBKDF2). No key material is logged, and
  every derived KEK + round-trip check buffer is zeroized (`kek?.fill(0)`,
  `check.fill(0)`).
- The round-trip decrypt adds a self-consistency check that catches a mangled wrap
  before it is ever committed — an extra guard the finding requested.

## Verification output

- `gradle :app:testDebugUnitTest` → **1224 tests, 2 failed**.
  - The 2 failures are the documented pre-existing `B1Plat01ReleaseSigningTest` asserts on
    `docs/RELEASE.md` / `app/build.gradle.kts` (`B1Plat01ReleaseSigningTest.kt:104,161`),
    proven unrelated in phases 55/59/60/61; this diff touches neither file.
  - New `B1Crypto03MasterPasswordAtomicTest` (7 tests) green:
    1. blob serialize/parse round-trip + fail-closed on malformed/half values;
    2. a torn single write leaves the OLD credential durable + unlockable (old password
       works, new one doesn't) — never a half pair;
    3. a store that dies on the SECOND write (the audit's exact injected fault) refuses
       the second change and the vault keeps unlocking with the last fully committed pair;
    4. under every torn-write position the surviving value is always a complete pair;
    5. legacy two-key pair still resolves (pre-62 vaults unlock);
    6. source pin: `setMasterPassword`/`changeMasterPassword` commit via
       `commitMasterPasswordCredential`, the two standalone pref statements are gone,
       round-trip decrypt present;
    7. source pin: the atomic commit is one synchronous `.commit()` (no `.apply()`), the
       lock flows read via `masterPasswordCredentialOrLegacy`.
- `gradle :app:assembleDebug` → **green** (173.7 MB debug APK, SHA-256
  `e463a9445a86e9ddfd1a4ac9c340efa842e008716657462b0380de2cc225eb32`). First invocation
  hit the documented transient dex-merge/RAM failure on the CI runner; the retry compiled
  the merge tasks and the final run is fully `UP-TO-DATE` green.

## Out-of-scope (noted, NOT fixed here)

- `B1-CRYPTO-04` (weak-password policy / offline brute force), `B1-CRYPTO-05` (silent DEK
  re-mint), `B1-CRYPTO-06` (checksum fails open), `B1-CRYPTO-07` (API 26-29 biometric
  downgrade) — separate phases, per AGENTS.md one-finding-per-phase.
- The backup archive header (NFLB2) already stores salt + wrapped DEK as one header block
  authenticated under a domain AAD — its own concern, untouched.
- The `commit()`-return-abort does not roll back `verifyMasterPassword`'s successful
  `reinstateDatabaseAfterLock`/unlock state in `changeMasterPassword`; a failed credential
  swap after a successful old-password verify leaves the vault unlocked in-session with
  the old credential — safe (never bricked, never half), and the UI re-verifies on retry.

## Phase-62 review fixes (post-commit)

Applied after review; no behavior regression to the atomicity fix.

1. **Session restore on failed credential swap (review finding 4).** `changeMasterPassword`
   now snapshots `_authenticated` + `_failedUnlockAttempts` BEFORE `verifyMasterPassword`
   (`NoteflowViewModel.kt`) and, when `commitMasterPasswordCredential` fails, restores the
   pre-call session: an already-unlocked session stays unlocked with the old credential
   (still valid on disk); a session that was locked is returned to locked (`repository.zeroizeKey()`,
   `_authenticated.value = false`) so the returned `false` is never paired with a silently
   unlocked session. `setMasterPassword` needed no change — it already commits before any
   in-memory flip.
2. **Present-but-unparseable blob stays "has master password" (review finding 5).**
   `SettingsManager.hasMasterPassword` now also returns true when the
   `master_password_credential` key EXISTS but the accessor cannot parse it (unknown/future
   format version). A downgraded app therefore treats the vault as protected and never opens
   it passwordless — which would otherwise cascade into the phase-09 corruption-quarantine
   path. The unlock paths still fail closed on such a blob (`verifyMasterPassword` returns
   false, no lockout counted). Never reachable in this version (the atomic commit only ever
   writes the valid `MPB1` blob); it is a downgrade/future-format guard.
3. **Legacy half-pair devices unchanged (review finding 6, documented).** A pre-fix device
   that already lost its wrapped DEK (salt present, wrapper missing) resolves to no-credential
   exactly as pre-fix — those vaults were already bricked by the old bug and cannot be healed
   (the AEAD-wrapped DEK bytes are unrecoverable). The fix prevents the failure mode going
   forward; it does not resurrect lost keys.
4. **Doc line-number drift (review finding 7, documented).** The pre-fix evidence lines
   `NoteflowViewModel.kt:1794-1795/1829-1830` cited above and in the audit are from the audit
   revision; the actual pre-fix lines in this phase's parent commit were `:2081-2082/:2123-2124`.
5. **Trailing newline (review finding 8).** `MasterPasswordCredential.kt` ends with a newline.

Verification re-run after the fixes: `gradle :app:testDebugUnitTest` (see output below) and
`gradle :app:assembleDebug`.
