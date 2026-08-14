# Phase 102 — B2-CRYPTO-01 (LOW): Tamper HMAC is compared with non-constant-time `String.equals`

## Finding (from `docs/security-report.md`, B2-CRYPTO-01)

- Area: Batch 2 — Crypto side-channels & edge cases (CWE-650, unsafe MAC/tag/pin comparison).
- Evidence: `DatabaseSecurityHelper.kt:153` — `return stored == current` on the 64-char hex
  checksum Strings. Kotlin `==` is `String.equals`, which returns on the **first** mismatching
  character, so the comparison time is proportional to the length of the matching prefix.
- Exploit: an attacker who can flip bytes of `noteflow.sqlite` and observe tamper-banner
  latency can recover the stored checksum one nibble at a time. The AndroidKeyStore HMAC key is
  non-extractable, so the recovered value alone cannot forge a new baseline — but it is the
  textbook unsafe-MAC-compare gap, and the codebase already shipped the correct primitive via
  `PinnedCertHash.kt:54-58` (`MessageDigest.isEqual`).

## What changed

### 1. Shared constant-time helper (new)

`app/src/main/kotlin/com/authorss81/noteflow/utils/ConstantTime.kt` — pure-JVM
`ConstantTime.hexEqual(a, b)` delegates to `MessageDigest.isEqual` over the US-ASCII byte arrays
(the same primitive `PinnedCertHash` uses). Pure `java.security` — available on every Android
API level, including the app floor of API 26+, so **no platform fallback or notice is required**
(AGENTS.md hardware-reality rule satisfied; nothing newer than API 26 is used).

### 2. Primary fix — `DatabaseSecurityHelper.kt:153`

`services/DatabaseSecurityHelper.kt`
- BEFORE: `return stored == current` (first-mismatch early exit).
- AFTER: `return ConstantTime.hexEqual(stored, current)` (full-length `MessageDigest.isEqual`
  loop). Both sides are fixed-length lowercase hex, so the equal-length path is fully constant
  time; a length difference returns false with no value leakage.

### 3. Same-class audit sweep (the finding's directive: "audit all crypto comparisons in the same PR")

Every HMAC/digest/pin/tag comparison in the codebase was located. Two comparison sites (of the
original compromised class) were fixed here; `PinnedCertHash.kt:54-58` was already correct
(`MessageDigest.isEqual`) and left untouched:

| File:line (before) | What it compares | After |
|---|---|---|
| `services/DatabaseSecurityHelper.kt:153` | DB tamper HMAC checksum | `ConstantTime.hexEqual` |
| `services/localsend/LocalSendProtocol.kt:253` (`a == b` in `fingerprintsMatch`) | LocalSend TLS certificate fingerprint (SHA-256 hex) pin | `ConstantTime.hexEqual` (normalization of `:`-stripping/lowercasing preserved) |

**One comparison site was audited and deliberately NOT fixed here because another phase owns it:**
`plugins/runtime/ArtifactSignatureVerifier.kt:57` (`if (!sha256.equals(expectedSha256.trim(),
ignoreCase = true))`, the artifact SHA-256 digest gate) is finding **B2-CRYPTO-02, assigned to
phase-103** (`workspace/SECURITY_FIX_PLAN.md:78`). Per the phase constraint ("do not fix OTHER
security findings… a different phase") it is left in its original state for phase-103, which will
also enforce the single-helper policy for that site.

Out-of-scope comparisons reviewed and deliberately NOT changed (not an HMAC/tag/pin secret):
- GCM authentication tags (`EncryptionService`, `SecurityService`, `WebDavCredentialStore`,
  `ImportExportService`) — verified inside the AEAD cipher itself by the platform, constant-time.
- `EncryptionService.kt:85` `combined[0] == PAYLOAD_VERSION` — a format marker constant in code,
  not a recoverable secret; child of B1-DB-8's scope, not this finding.
- Password attempts (`validateBackupPassword`, master-password unlock) — validated via AEAD tag
  decryption, not string compare.

## Security / checksum / secrets handling

- No keys, passwords, salts, wrapped DEKs, or decrypted note content are logged or compared via
  the new path — the helper only ever sees already-derived checksums/digests/fingerprints.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE: untouched.
- No DB schema change, no migration. No new dependencies. `.github/workflows/` not modified.

## Tests (pure-JVM, `app/src/test`)

- **New** `ConstantTimeTest.kt` (8 tests) — pins the observable contract of the constant-time
  compare: identical values equal; a flip in the **first**, **middle**, or **last** byte is each
  a mismatch (no prefix short-circuit); different lengths never equal; empty==empty; byte-wise
  case sensitivity (callers normalize lowercasing before calling); caller-side whitespace/
  separator normalization is honored. The true constant-time property (flat latency) cannot be
  asserted reliably in a unit test on a shared CI runner — any `nanoTime` measurement is flaky —
  so the review-level check asserts the full-pair behavior that a short-circuiting `String.equals`
  would violate.
- **Updated** `LocalSendProtocolTest.kt` (+1 test) — a fingerprint flip near the last nibble is
  still a mismatch (no first-prefix short-circuit).
- All existing integrity/plugin-runtime tests stay green (see verification below).

## Verification output

1. `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.ConstantTimeTest" --tests "com.authorss81.noteflow.LocalSendProtocolTest"`
   → **BUILD SUCCESSFUL** (all new/updated tests pass).
2. `gradle testDebugUnitTest` → 630 tests, **1 failed**:
   `EncryptionAndServiceTest.testEncryptDecryptCycle`
   (`NullPointerException: EncryptionService.decrypt, parameter encryptedBase64`).
   **Proven pre-existing and unrelated**: reproduced on the clean tree (`git stash` before this
   phase's diff; commit `a86c8f4`) with the same single failure. Root cause is the mockable
   `android.jar` (`isReturnDefaultValues=true`): `android.util.Base64.encodeToString` returns
   `null` instead of throwing, so `EncryptionService.base64Encode`'s `java.util.Base64` fallback
   (`EncryptionService.kt:44-50`) never fires and `encrypt` yields null. Same failure documented
   in the phase-101 REPORT; out of scope for a Batch-2 crypto-comparison phase.
   Two other tests (`PluginUpdateEngineTest` / `WikiLinkParserCacheUnitTest`, coroutine/timing
   tests) failed once in one interleaved run but passed on the immediate re-run and in isolation —
   flaky scheduling on the shared runner, not caused by this phase (they also pass on the clean
   tree's dedicated runs).
3. `gradle assembleDebug` → **BUILD SUCCESSFUL** (APK produced; one transient
   `:app:mergeExtDexDebug` OOM failure recovered on retry — low-RAM runner characteristic,
   re-run green).

## Out of scope (documented, not fixed)

- `EncryptionAndServiceTest.testEncryptDecryptCycle` pre-existing failure (see above).
- **B2-CRYPTO-02 / phase-103** — `ArtifactSignatureVerifier.kt:57` non-constant-time digest
  gate. Audited here, deliberately left untouched so its own phase can fix it.
- All other B2/B1 security findings remain in their own phases; nothing else was changed here.
- Reviewed-but-not-changed comparison classes listed above (GCM tags via AEAD, version marker).