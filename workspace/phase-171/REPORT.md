# Phase 171 REPORT — Release signing v3 + plugin-update channel operator runbook

Date: 2026-08-19
Phase: `workspace/phase-171/PROMPT.md` (phase-161 Kali round-2 triage fix; closes **Phase-32-NEW-03** INFO + **Phase-32-NEW-04** INFO — the final two live packaging findings)

## 1. Task

Release-engineering/operator phase, NOT a security regression: the two remaining
INFO findings are safe-but-inert states. This phase:

- **Part A (NEW-03):** enable APK Signature Scheme v3 on the release build —
  root cause is `minSdk = 26` (< AGP 8.7.3's automatic-v3 threshold of 28), so
  every prior release APK was v2-only and had no in-place signing-key-rotation
  capability. Fix = `enableV3Signing = true` in `releaseConfig`, verified with
  `apksigner verify --print-certs -v` on a fresh `assembleRelease`.
- **Part B (NEW-04):** make the placeholder plugin-manifest cert pin honest and
  actionable — an operator runbook + a pure-JVM test pinning the fail-closed
  contract. The placeholder constant itself stays (no real channel certs to pin;
  a changed-but-wrong constant is worse than the documented placeholder).

Constraints honored: NO `.github/workflows/` edits; NO new dependencies; NO
schema change; base-APK-size rule intact; `minSdk` NOT bumped (a
user-approval-level change, out of scope); B1-PLAT-1 fail-closed gate untouched.

## 2. Part A — APK Signature Scheme v3 (NEW-03): root cause proven + fixed

### 2.1 Root cause confirmed empirically (not assumed)

Baseline (`git HEAD` = the pre-phase-171 tree with only the phase-171 prompt
committed), `gradle assembleRelease` with the CI-provisioned real keystore, then
`apksigner verify --print-certs -v` on every output — **all five release APKs
were v2-only**:

```
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false     # <- every output
```

Why: AGP 8.7.3 enables v3 automatically only when `minSdk >= 28`; this app
floors at `minSdk = 26` (`app/build.gradle.kts:34`), so nothing turned v3 on.
This exactly matches the phase-32/159 audit claim ("Phase-32-NEW-03 … v2-only")
and the phase-170 report's `apksigner verify --verbose` output (v2 line only).

### 2.2 The fix

`app/build.gradle.kts`, inside `signingConfigs.create("releaseConfig")` (the
`:36-60` block, now ~`:54-78`), at the top of the block (~`:57`):

```kotlin
enableV3Signing = true
```

with a full KDoc comment explaining the minSdk<28 root cause and why v2 stays on
(Android 8.x/API 26-27 fallback), v1/v4 are untouched (v4 only helps Android 11+
incremental installs, not required), and `minSdk` is deliberately not bumped.

### 2.3 Verification (HARD GATE evidence)

`gradle assembleRelease` (R8 ON, real keystore `CN=InkFlow Release`) → all 5
outputs verify with **both schemes**:

```
$ apksigner verify --print-certs -v app/build/outputs/apk/release/app-universal-release.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V3.0 Signer: certificate DN: CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US
V3.0 Signer: certificate SHA-256 digest: 69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196
V3.0 Signer: key algorithm: RSA · key size (bits): 2048
```

Identical `v2:true v3:true` on the other four ABIs. Post-fix APK SHA-256:

| Output | SHA-256 |
|---|---|
| `app-arm64-v8a-release.apk` | `171d43eb44cabc4f608cc6c3bd1b14174931cd7113e0961446110ca1606eb773` |
| `app-armeabi-v7a-release.apk` | `6be954925fb00cba57f859f2f582706e94a9be7f629483727a24254994c1a8d0` |
| `app-universal-release.apk` | `2c9693252d93ce57e9d94136e7081760cde27e3bd64170e13f182fc0eb79ad76` |
| `app-x86-release.apk` | `3a6fdb42480cab46ecb0046d0e6e47f0c75fa3b7c59098ff7372aab7bc513da0` |
| `app-x86_64-release.apk` | `7f5522eaafb374d6c25e05bfd6c2f4eb0a54ee71bbe6c20d28d263771070ccf0` |

Signer identity is the REAL `CN=InkFlow Release` cert (SHA-256
`69636edb…c50196`, matching phase-170's documented real identity) — NOT the
Android debug key. The cert is a 2048-bit RSA key (the real release key; the
runbook/RELEASE.md keytool example uses 4096 for NEW keys — both valid).

### 2.4 Fail-closed unchanged (B1-PLAT-1)

Removed the keystore env vars from the environment and ran:

```
$ gradle assembleRelease
> ... RELEASE_SIGNING_TASK_NAMES gate ...
Release build refused: no release keystore configured (B1-PLAT-1). Set KEYSTORE_FILE
(existing keystore), KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD, then run the
release task again. The debug-keystore/repo-blob fallbacks were removed ...
BUILD FAILED in 1s
```

The `whenReady` early gate (`app/build.gradle.kts:133-153`) still refuses the
release build **before R8** when the keystore is unset. `B1Plat01ReleaseSigningTest`
(8 tests) stays green against the modified build file — its slice assertions on
the `releaseConfig` env reads (`KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/
`KEY_PASSWORD`) still hold, and the debug buildType is untouched.

## 3. Part B — Plugin-update channel operator runbook (NEW-04)

### 3.1 What exists (re-verified with `file:line`)

- Trust anchor: `PLUGIN_MANIFEST_CERT_PIN` at
  `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt:242-243`
  = `"sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="` (base64 of bytes
  0x00..0x1F — the documented, well-formed REPLACEMENT placeholder).
- It is well-formed (a parseable 32-byte digest — `PinnedCertHash.parse`),
  so `HttpsManifestTransport` (`PluginManifestFetcher.kt:135-139`) treats the pin
  as CONFIGURED and enforces the pin gate — never "disabled", never unpinned
  HTTPS. Because no real `plugin-updates.inkflow.app` leaf hashes to it, every
  fetch fails closed with the honest "certificate does not match the pinned
  hash" message (host allow-list `DEFAULT_MANIFEST_HOST` at
  `HostedPluginManifest.kt:219`, no redirects `PluginManifestFetcher.kt:151-155`).
- This phase does NOT substitute the pin (constraint: no real channel certs).

### 3.2 The runbook — `docs/PLUGIN_CHANNEL.md` (new)

Contains:
- **exact file/line** to change: `HostedPluginManifest.kt:242-243`;
- **the correct pin recipe** — and a footgun callout: the app's pin is
  `PinnedCertHash.base64Sha256(cert)` = Base64(SHA-256(**certificate DER**)),
  NOT the RFC 7469 SPKI pin that `openssl x509 -pubkey` produces. The
  authoritative command:
  ```
  openssl s_client -connect plugin-updates.inkflow.app:443 -servername plugin-updates.inkflow.app -showcerts < /dev/null 2>/dev/null \
    | sed -n '/BEGIN CERTIFICATE/,/END CERTIFICATE/p' \
    | openssl x509 -outform der | openssl dgst -sha256 -binary | openssl base64
  ```
  **Verified:** both pipelines run locally against a test cert — DER-based gives
  `i3MlAe7gHC/SLbKCHfLK0W52vDEvsKHeuYp9eXYZdBY=` = hex
  `8b732501…7416` (exactly the signer's SHA-256 from `apksigner`), while the
  SPKI variant gives a different value (`Ky5HANf…`). Substituting an SPKI pin
  would make a genuinely-good host fail closed forever — documented.
- the **leaf-only requirement** (pin the leaf, not an intermediate/root; why:
  the next intermediate rotation silently locks the channel);
- a **go-live checklist** (compute → substitute → gates → re-ship → verify one
  hosted fetch succeeds, with the exact criterion);
- a **cert-renewal/rotation procedure** (90-day certs rotate; ship the new pin
  before the old expires; re-issue artifact pins if the artifact key rotates);
- related evidence (transport files, host allow-list, fail-closed refusal copy).

Referenced from `docs/RELEASE.md` (new "Plugin update channel" section + updated
verify snippet showing the new v2+v3 scheme lines + updated Artifacts note) and
`docs/PLUGINS.md` (sibling note to the B1-NET-03 limitation blockquote).

### 3.3 The fail-closed contract test

`PinnedCertHashTest` extended 4 → **9** tests (cert-level `matches` behavior,
using a real generated leaf cert via the existing keytool pattern):

- `known-good pin matches its real certificate` — the contract's positive side;
- `the placeholder manifest pin never matches any real certificate` —
  `matches(cert, PLUGIN_MANIFEST_CERT_PIN)` == false (both the cert-matches and
  the base64-matches paths), and the known-good differs from the placeholder;
- `a well-formed wrong pin and near-miss pins never match a certificate` —
  well-formed wrong pin, 1-char-short, 1-char-long;
- `wrong prefix malformed and blank pins never match a certificate` —
  wrong-case `SHA256/` prefix, malformed base64, `""`, bare `sha256/`,
  valid-form-but-wrong digest;
- `placeholder manifest pin source contract is pinned so the channel stays fail-closed` —
  asserts the exact placeholder string, that it parses to 32 bytes (configured +
  pin-gated, never unpinned-HTTP), and that it never cert-matches.

This pins the fail-closed contract so **no future edit can silently accept a bad
pin** or swap in a changed-but-still-wrong constant without the suite failing.
`HttpsManifestTransportTest`'s `the compiled-in host and pin constants are
well-formed` (asserts `parse(PLUGIN_MANIFEST_CERT_PIN)` is a 32-byte digest)
continues to hold.

## 4. Verification

```
gradle assembleDebug      # green — single monolithic app-debug.apk (128,717,267 B,
                          #   SHA-256 d9922a0c…; first invocation transient failure,
                          #   rerun fully green — documented flake class, phase-67/170)
gradle assembleRelease    # green — 4 ABI splits + universal, all v2+v3-signed with
                          #   the real CN=InkFlow Release keystore, R8 ON
env -u KEYSTORE_FILE ... gradle assembleRelease
                          # green failure — B1-PLAT-1 gate: "Release build refused …
                          #   (B1-PLAT-1)" in 1s, before R8
gradle testDebugUnitTest  # 2306 tests completed, 1 failed:
                          #   Phase148UiFailureTextScrubTest "UNC path must be
                          #   redacted" — the documented PRE-EXISTING failure
                          #   (reproduced on a clean stash in phases 149/153/158/166/170,
                          #   untouched by this diff)
```

Key suites green (from the run's XML): `B1Plat01ReleaseSigningTest` 8/8,
`PinnedCertHashTest` 9/9, `HttpsManifestTransportTest` 9/9,
`CompileTimePluginPinStoreTest` 11/11, `PluginUpdateCheckerTest` 14/14,
`PluginUpdateEngineTest` 11/11, `B2Deps04PluginSigningTest` 9/9.

## 5. Files changed (with `file:line` anchors)

- `app/build.gradle.kts` — `enableV3Signing = true` + root-cause KDoc added inside
  `signingConfigs.create("releaseConfig")` (~`:57`; block now `:54-78`);
  B1-PLAT-1 gate (`:133-153`) untouched.
- `app/src/test/java/com/authorss81/noteflow/PinnedCertHashTest.kt` — extended
  4→9 tests: cert-level `matches` fail-closed contract + exact placeholder source
  pin (keytool-generated real leaf cert, `java.io`/`java.security` only).
- `docs/PLUGIN_CHANNEL.md` — NEW operator runbook (file/line, leaf-DER `openssl`
  recipe + SPKI footgun verified, leaf-only requirement, go-live checklist,
  renewal procedure, evidence map).
- `docs/RELEASE.md` — new "Plugin update channel (operator action —
  Phase-32-NEW-04)" section referencing `docs/PLUGIN_CHANNEL.md`; verify snippet
  now requires `v2 scheme: true` AND `v3 scheme: true`; Artifacts note updated
  (v2+v3, minSdk-26/AGP-8.7.3 rationale).
- `docs/PLUGINS.md` — manifest-host pin fail-closed note + runbook pointer
  (sibling to the existing B1-NET-03 limitation blockquote).
- `docs/security-report.md` — top-risks summary updated; Phase-32-NEW-03 →
  `FIXED` (phase-171, full evidence); Phase-32-NEW-04 → `OPEN — intentional
  fail-closed, RUNBOOKED (phase-171)`.
- `docs/phase-status.md` — phase-171 row → `DONE`.
- `docs/ARCHITECTURE.md` — "Implemented in phase-171" note in Build/CI
  essentials + v2+v3 verification line in the signing bullet.
- `workspace/phase-171/REPORT.md` — this report; stale `.no_work`/`.attempts`/
  `.timeout` markers from the pre-run no-work false-detection removed.

No `.github/workflows/` change. No Room/migration. No new dependency. No behavior
change to debug builds, the runtime plugin chain (still fail-closed), or the
signing gate.

## 6. Out of scope / documented follow-ups

- **NEW-04 operator go-live** — not done here by design: the pin substitution + a
  `200` hosted-fetch verification is an operator action with production
  credentials/channel, runbooked in `docs/PLUGIN_CHANNEL.md` (go-live checklist).
- **minSdk bump to 28 / v4 signing** — explicitly out of scope (user-approval
  level / Android-11-incremental-install nicety only).
- **B2-DEPS-05 (LLM model pin)** — still OPEN, a separate phase (not touched).