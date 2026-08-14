# Phase 103 — B2-CRYPTO-02 (LOW): Plugin artifact SHA-256 is verified with a non-constant-time `String.equals(ignoreCase)`

## Finding (from `docs/security-report.md`, B2-CRYPTO-02)

- Area: Batch 2 — Crypto side-channels & edge cases (CWE-650, unsafe MAC/tag/pin comparison).
- Evidence (before): `plugins/runtime/ArtifactSignatureVerifier.kt:57` —
  `if (!sha256.equals(expectedSha256.trim(), ignoreCase = true))` — a lexical, first-mismatch
  early-exit digest compare, while the adjacent pin check correctly used
  `PinnedCertHash.matches` → `MessageDigest.isEqual` (`ArtifactSignatureVerifier.kt:68-69`).
- Exploit: an attacker who iteratively corrupts a downloaded artifact and observes rejection
  latency can reconstruct the expected digest character-by-character. The expected digest is
  public today (shipped catalog / manifest), so this is a hardening gap — but any future
  scenario where the expected digest is sensitive (or the pattern copied to a MAC compare)
  inherits the weakness.
- Positive controls verified and preserved: `PluginDigest.sha256Hex(file)` streams the whole
  file (`PluginDigest.kt:42-55`), `PinnedCertHash.parse` rejects non-32-byte digests
  (`PinnedCertHash.kt:46-51`), and the download transport caps bytes at `MAX_BYTES` before
  hashing — neither touched here.

## What changed

### 1. Primary fix — `plugins/runtime/ArtifactSignatureVerifier.kt:57`

- BEFORE: `if (!sha256.equals(expectedSha256.trim(), ignoreCase = true))` — case-insensitive
  `String.equals`, exiting on the first mismatching hex char.
- AFTER: the expected digest is canonicalized **once at its parse boundary**
  (`expectedSha256.trim().lowercase()` — the computed hash is always lowercase since
  `PluginDigest.sha256Hex` emits `%02x`) and compared with the app-wide constant-time helper
  `ConstantTime.hexEqual` (`ArtifactSignatureVerifier.kt:64-65`), which delegates to
  `MessageDigest.isEqual` over the byte arrays. `ignoreCase` is gone from the compare entirely;
  case-insensitivity now comes only from the parse-time normalization. The user-facing mismatch
  message still shows the expected (normalized at the parse boundary) and got (computed)
  digests for diagnosis.

### 2. Single-helper enforcement — `plugins/runtime/PinnedCertHash.kt`

The finding's directive is "one constant-time comparison helper for ALL digest/pin checks".
Phase-102 introduced `utils/ConstantTime.hexEqual` (→ `MessageDigest.isEqual`) as that helper.
`PinnedCertHash` still carried a second, private copy (`MessageDigest.isEqual` over UTF-8
bytes); it now routes both `matches` and `matchesBase64` through `ConstantTime.hexEqual`
(`PinnedCertHash.kt:33-39`), deleting the duplicated `constantTimeEquals` helper and the now
unused `java.security.MessageDigest` import. The base64 pin alphabet is plain ASCII, so the
helper's US-ASCII byte encoding is byte-identical to the previous UTF-8 path — behavior is
preserved exactly.

Every digest/pin compare in the app now funnels through the single `ConstantTime.hexEqual`:
- DB tamper HMAC (`services/DatabaseSecurityHelper.kt`, phase-102)
- LocalSend TLS fingerprint pin (`services/localsend/LocalSendProtocol.kt`, phase-102)
- plugin artifact SHA-256 digest gate (**this phase**, `ArtifactSignatureVerifier.kt:65`)
- plugin cert pin (`PinnedCertHash.matches`, and `plugins/runtime/HttpsPluginDownloadTransport.kt:149`
  via the same function)

## Security / checksum / secrets handling

- The constant-time helper only ever compares already-derived digests/pins; no key, password,
  salt, wrapped DEK, or decrypted note content enters the new path or is logged.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE: untouched.
- No DB schema change, no migration. No new dependencies (reuses `java.security.MessageDigest`
  from phase-102's `ConstantTime.kt`). `.github/workflows/` not modified.
- **API floor (API 26+)**: `MessageDigest.isEqual` is pure `java.security`, available on every
  Android API level including the app floor — no newer API is used, so **no fallback or
  non-alarming notice is required** (AGENTS.md hardware-reality rule satisfied).

## Tests (pure-JVM, `app/src/test`)

- **Updated** `ArtifactSignatureVerifierTest.kt` (+4 tests):
  - `an uppercase expected digest is normalized at parse time and still verifies` — proves
    case-insensitivity now comes from parse normalization, not `ignoreCase` at compare (the
    constant-time helper is byte-wise and case-sensitive).
  - `whitespace around the expected digest is trimmed once before the constant-time compare` —
    the old `.trim()` behavior is preserved at the parse boundary.
  - `a digest differing only in the very last nibble is rejected` — review-level pin of the
    full-length compare: a difference only at the tail (where a prefix-short-circuiting
    `String.equals` would give up last) is still a hard SHA-256 mismatch.
  - all 7 pre-existing verifier tests stay green.
- **New** `PinnedCertHashTest.kt` (4 tests) — regression guard for the single-helper
  delegation: equal 32-byte base64 hash matches with/without the `sha256/` prefix, a differing
  hash never matches, `parse` still rejects non-32-byte digests, and `parse` accepts a pinned
  32-byte digest.
- The true constant-time property (flat latency) cannot be asserted reliably on a shared CI
  runner, so — consistent with phase-102's `ConstantTimeTest` — the review-level contract
  checks the observable full-pair behavior a short-circuiting compare would violate.

## Verification output

1. Targeted run `gradle :app:testDebugUnitTest --tests ArtifactSignatureVerifierTest --tests
   PinnedCertHashTest --tests ConstantTimeTest` → **BUILD SUCCESSFUL** (all 11 + 4 + 8 tests
   pass; the new uppercase/trim/tail tests are the B2-CRYPTO-02 proof).
2. `gradle testDebugUnitTest` → **636 tests, 1 failed**:
   `EncryptionAndServiceTest.testEncryptDecryptCycle`
   (`NullPointerException: Parameter specified as non-null is null: method
   EncryptionService.decrypt, parameter encryptedBase64`). This is the SAME documented
   pre-existing failure from phases 101 and 102 (mockable `android.jar`
   `android.util.Base64.encodeToString` returns `null`, so `EncryptionService.base64Encode`'s
   `java.util.Base64` fallback never fires) — it also reproduces in isolation on this tree and
   touches code byte-identical to HEAD (this phase only changed `ArtifactSignatureVerifier`,
   `PinnedCertHash` and their tests). Proven unrelated.
   Note: `WikiLinkParserCacheUnitTest.a cancelled scan propagates cancellation and does not cache
   a partial result` is a known coroutine/timing flake on the shared runner (documented in the
   phase-102 report): it passed during this phase's full run, but failed once during the review's
   post-phase re-run and passes in isolation. It is untouched by this phase and unrelated.
3. `gradle assembleDebug` → first run hit the low-RAM runner's transient
   `:app:mergeExtDexDebug` dex-merging OOM (same characteristic documented in phase-102,
   retry recovered); retry with `--no-daemon` → **BUILD SUCCESSFUL**.

## Out of scope (documented, not fixed)

- `EncryptionAndServiceTest.testEncryptDecryptCycle` pre-existing failure (see above).
- `WikiLinkParserCacheUnitTest` cancellation test — known shared-runner scheduling flake
  (phase-102 documented), passes in isolation, untouched here (see Verification above).
- Case normalization is applied at the verifier's expected-digest intake, which is the single
  parse boundary every call site passes through. Persisting-catalog sources
  (`HostedPluginManifest`, `PluginEntryStore`, `PluginUpdateChecker`) were reviewed and left
  untouched — they feed the same verifier gate, and the exact expected-digest they carry is
  already lowercase hex; normalizing in the verifier keeps this LOW finding's diff minimal and
  targeted.
- All other B2/B1 security findings remain in their own phases (per phase constraint); no new
  related bug was found during this work.