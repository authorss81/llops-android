# Phase 66 — B1-CRYPTO-08: the artifact signer pin binds only ONE entry's cert (last-signed-entry-wins) instead of the full signer set — no chain/expiry/key-usage validation

**Status:** DONE (2026-08-15)
**Finding:** [B1-CRYPTO-08] (MEDIUM), Batch 1 · Cryptography & key management
**Scope:** one finding — no DB schema change, no migration, no new deps, `.github/workflows/` untouched.

## 1. What the finding said

- **Evidence:** `ArtifactSignatureVerifier.kt:89-105` (`findSignerCertificate`) iterated entries in JarFile order and took `certs.firstOrNull()` of the LAST non-META-INF entry that happened to carry certificates; entries outside the manifest `Name:` sections return `certificates == null` and were skipped. `ArtifactSignatureVerifier.kt:100-102` — no assertion that every loaded entry is signed by the pinned cert, no chain build, no `checkValidity()`, no key-usage check.
- **Exploit scenario:** the pin check only proves "at least one entry in the archive was signed by the pinned certificate". A JAR with two signers — the genuine pinned cert on one benign entry, an attacker key on `classes.dex` — would pass whenever iteration ended on the genuine-signed entry. Today neutralized by the whole-file SHA-256 pin, it becomes a real bypass the moment the sha256 trust is perturbed (B1-CRYPTO-01). An expired/revoked pinned cert is also silently accepted.

## 2. Root cause (before/after)

### Before

```kotlin
// ArtifactSignatureVerifier.findSignerCertificate (pre-fix)
var signer: X509Certificate? = null
while (entries.hasMoreElements()) {
    val entry = entries.nextElement()
    if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
    jar.getInputStream(entry).use { it.copyTo(OutputStream.nullOutputStream()) }
    val certs = entry.certificates
    if (certs != null && certs.isNotEmpty()) {
        signer = certs.firstOrNull() as? X509Certificate ?: continue   // LAST signed entry wins
    }
}
signer   // null => "not signed", but never a full-signer-set decision
```

An unsigned entry was *skipped* (`certificates == null`), a multi-signer entry was collapsed to `certs.firstOrNull()`, mixed signers across entries were never compared, and the returned cert was fed to the pin hash WITHOUT ever calling `checkValidity()` or inspecting `KeyUsage`.

### After

The fix has **two layers**:

1. **Full signer-set binding** — `ArtifactSignatureVerifier.collectSignerSet` (replaces `findSignerCertificate`) force-verifies the JAR (`JarFile(file, verify = true)`) and requires:
   - **every** non-META-INF entry to carry certificates — an unsigned entry inside an otherwise-verified jar is a hard rejection (the attacker-key-on-`classes.dex` shape);
   - every entry's FULL certificate set to be a single-element set — a multi-signer entry (two signature blocks covering the same entry) is a hard rejection;
   - the distinct signer across all entries to be exactly ONE — an archive mixing different certs anywhere fails, never "passes if iteration happens to end on the genuine entry";
   - an EMPTY verified signer set to fail hard — never a fallback to a last-seen value.
2. **Certificate usability** — new pure-JVM `SignerCertificatePolicy.validate` runs in `verify()` BEFORE the pin compare:
   - `cert.checkValidity(now)` — an expired or not-yet-valid pinned cert is rejected (previously silently accepted);
   - the RFC-5280 `KeyUsage` extension must permit digital signatures (bit 0); an absent extension is unrestricted and accepted.

Defense in depth: a key-usage-invalid cert is ALSO caught by the signer-set gate when the platform JAR verifier surfaces such entries with `null` certificates (observed on JDK 21 with a `keyCertSign`-only key).

## 3. What changed (file:line evidence)

### `plugins/runtime/ArtifactSignatureVerifier.kt` (modified)

- `verify()` (`:90-114`): the `findSignerCertificate` call + pin compare is replaced by a `when` over `collectSignerSet(file)`; a `SignerSetResult.Rejected` short-circuits to `Result.Invalid`, a `SignerSetResult.Unified` runs `SignerCertificatePolicy.validate(signer.cert)` (`:97`) BEFORE the pin hash compare (`:104-110`). The SHA-256 gate and the B1-AUTH-01 static scan are unchanged.
- New `private sealed class SignerSetResult` (`:136-142`): `Unified(cert)` / `Rejected(reason)`.
- New `collectSignerSet(file)` (`:152-210`):
  - unsigned entry → `Rejected` (`:163-167`) — "every non-META-INF entry must be signed by the pinned certificate";
  - multi-signer entry → `Rejected` (`:169-173`) — "signed by N certificates";
  - non-X.509 cert → `Rejected` (`:175-179`);
  - mixed signers across entries → `Rejected` (`:180-187`) via `sameCert`;
  - empty signer set → `Rejected` (`:190-195`) — "the artifact is not signed (no signed non-META-INF entry was found)";
  - `SecurityException` (tampered signature) / `Throwable` → `Rejected` (`:200-209`), never null/none.
- `sameCert(a, b)` (`:213-214`) — DER byte equality (`encoded.contentEquals`).
- The old `signer = certs.firstOrNull()` last-signed-entry-wins fallback is deleted (source-pinned by a test).

### `plugins/runtime/SignerCertificatePolicy.kt` (new, pure JVM)

- `DIGITAL_SIGNATURE_BIT = 0` (`:41`).
- `validate(cert, now = Date())` (`:56-63`): validity period first, then key usage.
- `validity()` (`:65-76`): `checkValidity(now)`; `CertificateExpiredException` → "its validity period has expired (notAfter …)", `CertificateNotYetValidException` → "its validity period has not started yet (notBefore …)".
- `keyUsage()` (`:78-94`): `cert.keyUsage == null` → Accept (RFC 5280 unrestricted); empty or `bit0 == false` → Reject "its KeyUsage extension does not allow digital signatures".
- KDoc documents the finding and the two enforcement layers.

### `app/src/test/java/.../TestDownloadablePlugin.kt` (test fixtures)

- `newKeystore` gained `alias` (default `"plugin"`), `startDate`, `keyUsage` params — lets tests mint expired / not-yet-valid / non-signing certs (`keytool -startdate` / `-ext keyUsage=…`) and two distinct-alias keys.
- `build` gained `additionalSigners: List<Keystore>` — re-signs an already-signed jar with further keys (jarsigner PRESERVES an existing signature block when re-signing with a DIFFERENT alias, so every entry ends up with a multi-cert set). The distinct-alias requirement is documented in the helper (jarsigner REPLACES a signature made under the same alias).
- New `buildWithUnsignedEntry` — signs a jar, then appends an entry the signature does NOT cover (no manifest `Name:` digest section) via a byte-preserving zip copy; the jar still verifies under `JarFile(verify=true)` but the appended entry carries `null` certificates.

### `app/src/test/java/.../B1Crypto08SignerSetTest.kt` (new, 12 tests)

1. `a single-pinned-signer jar verifies` — positive control.
2. `a jar with two signers over every entry fails verification` — `additionalSigners = [ksB]`, pin = certA ⇒ Invalid ("signed by 2 certificates").
3. `a jar mixing a signed entry and an unsigned entry fails verification` — `buildWithUnsignedEntry` ⇒ Invalid ("not signed").
4. `an unsigned artifact is rejected even when its sha256 matches` — `sign = false` ⇒ Invalid ("not signed").
5. `an expired signing certificate fails verification even when its hash matches the pin` — `startDate = "2000/01/01 00:00:00"` ⇒ Invalid ("expired").
6. `a signing certificate whose KeyUsage excludes digitalSignature is refused` — `keyUsage = "keyUsage=keyCertSign"` ⇒ Invalid (either the signer-set gate or the policy gate, per platform JAR-verifier behavior).
7. `the policy accepts a valid cert with no KeyUsage extension` — keytool default ⇒ Accept.
8. `the policy accepts a cert whose KeyUsage allows digitalSignature` — `keyUsage = "keyUsage=digitalSignature"` ⇒ Accept.
9. `the policy rejects an expired cert` — `startDate = 2000` ⇒ Reject ("expired").
10. `the policy rejects a not-yet-valid cert` — `startDate = "2030/01/01 00:00:00"` ⇒ Reject ("not started").
11. `the policy rejects a cert whose KeyUsage excludes digitalSignature` — `keyUsage = "keyUsage=keyCertSign"` ⇒ Reject ("KeyUsage").
12. `verify binds the full signer set and cert policy - never a last-entry cert` — source pins: `collectSignerSet(file)`, `SignerCertificatePolicy.validate`, `certs.size != 1`, "every non-META-INF entry must be signed", and the ABSENCE of `signer = certs.firstOrNull()`.

## 4. Verification output

- `gradle :app:testDebugUnitTest` (full run): **1287 tests completed, 2 failed** — the two failures are the documented pre-existing `B1Plat01ReleaseSigningTest` asserts on `app/build.gradle.kts` (debug buildType signingConfig) and `docs/RELEASE.md` (debug-keystore-fallback wording), proven unrelated in phases 55/59/60/61/62/63/64/65 (they fail identically on a clean stash; both files are untouched by this diff).
- `gradle :app:testDebugUnitTest --tests "B1Crypto08SignerSetTest" --tests "ArtifactSignatureVerifierTest"`: all green (12 new + 10 existing verifier tests).
- `gradle :app:assembleDebug`: green — first invocation had a transient failure (documented daemon/dex-merge flake seen across phases 48–65), re-run fully `UP-TO-DATE` green; debug APK on disk, SHA-256 `41f942898479ac91096854ee0985f04221997308baf340d804aa435009e9241b` (173.7 MB).

## 5. Checksum / secrets handling

- No new secrets, no logging of keys/passwords/decrypted content. The certificate validity dates in rejection messages are public cert metadata, not secrets.
- All pin digests continue to go through the constant-time `ConstantTime.hexEqual` compare (`ArtifactSignatureVerifier.kt:73`); the `SignerCertificatePolicy` adds no new cryptographic primitives (uses `X509Certificate.checkValidity` / `getKeyUsage`).

## 6. API-26+ floor

The fix is pure JVM (`java.security.cert`, `java.util.jar`) — identical behavior on every supported API level, no newer-API-only code path and therefore no fallback or non-alarming notice is required (AGENTS.md hardware reality satisfied).

## 7. Input judged out-of-scope (documented, NOT fixed here)

- **Certificate revocation / CRL / OCSP** — the finding's "expired/revoked" wording: expiry is now enforced, but revocation checking against a CRL/OCSP responder is a network dependency the offline-first design deliberately avoids; revocation is documented as a follow-up (a revoked cert also fails pin-mismatch after rotation).
- **Chain building / path validation** — the pin binds the leaf signing cert to a compile-time hash (self-signed or trust-anchor-style), so intermediate-chain validation adds nothing here; the finding's "no chain build" is neutralized by the whole-file SHA-256 pin + the leaf pin. Documented, not implemented.
- **B1-CRYPTO-01 (sha256 trust perturbation)** — that finding is a separate phase; the signer-set + policy gates here make the signature check robust so it cannot become the *silent* bypass B1-CRYPTO-01 describes, but B1-CRYPTO-01 itself is out of scope.
- **Multi-`PKCS7` SignedData ordering within one signature block** — `entry.certificates` already flattens a block's signer chain; the enforcement target (multiple SIGNER certs per entry / across entries) is fully covered.

## 8. Files changed

```
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/ArtifactSignatureVerifier.kt  (modified)
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/SignerCertificatePolicy.kt    (new)
app/src/test/java/com/authorss81/noteflow/TestDownloadablePlugin.kt                       (modified — fixtures)
app/src/test/java/com/authorss81/noteflow/B1Crypto08SignerSetTest.kt                      (new — 12 tests)
docs/security-report.md  ·  docs/phase-status.md  ·  docs/ARCHITECTURE.md                 (status/doc notes)
workspace/phase-66/REPORT.md                                                             (this file)
```

No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched. `allowBackup="false"`, `ClipboardGuard`, FLAG_SECURE and all encryption paths untouched.
