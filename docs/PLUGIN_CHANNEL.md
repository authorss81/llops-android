# Plugin update channel — operator runbook (Phase-32-NEW-04)

This is the **operator** guide for going live with the hosted plugin-update
channel (`plugin-updates.inkflow.app`). The channel is **fail-closed by
design**: until the steps below are performed, every shipped build refuses the
manifest fetch with a clear non-alarming "update checks are disabled" message —
there is no security regression, only an inert release-engineering switch.

Reader first: `docs/plugin-architecture.md` (hybrid model), `docs/PLUGINS.md`
("Publishing a downloadable plugin release" + the Phase-24 update section).
For plugin *artifacts* (not the manifest host) the signing checklist lives in
`docs/PLUGINS.md`; this document only covers **the manifest transport's
compile-time certificate pin**.

---

## 1. The trust anchor and why it is a placeholder today

The update manifest (`https://plugin-updates.inkflow.app/v1/manifest.json`)
carries the `downloadUrl` / `sha256` / `pinnedCertHash` of every plugin offer.
Because those values ARE the trust anchor for the rest of the chain
(B1-CRYPTO-01), the manifest itself is fetched over a TLS connection whose
server leaf **must hash to a COMPILE-TIME pin** — never a value from the
network or user settings.

That pin is `PLUGIN_MANIFEST_CERT_PIN`:

```
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt:242-243
```

Line 242–243 are:

```kotlin
const val PLUGIN_MANIFEST_CERT_PIN: String =
    "sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
```

The value `sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=` is a
**well-formed-but-REPLACEMENT placeholder** (`AAECAwQFBgcI…` = bytes 0x00…
0x1F — deliberately recognizable — base64 of a valid 32-byte digest, so the
transport treats the pin as CONFIGURED and enforces the pin gate rather than
degrading to unpinned HTTPS). Because no real `plugin-updates.inkflow.app`
leaf hashes to it, **every fetch fails closed** and no forged/missing manifest
can ever reach the update engine.

> **Hard rule: do not "improve" the placeholder into a different wrong value.**
> A changed-but-still-wrong constant is indistinguishable from the placeholder
> at runtime but erases the "still the documented placeholder" signal this page
> provides. Keep `sha256/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=` until
> step 3 substitutes the REAL leaf hash for `plugin-updates.inkflow.app`.

The fail-closed contract is PINNED BY TEST so a future edit can neither silently
accept a bad pin nor swap in a changed-but-wrong constant:

- `app/src/test/java/com/authorss81/noteflow/PinnedCertHashTest.kt` — asserts
  `PinnedCertHash.matches` returns **true** for a certificate's known-good pin
  and **false** for the placeholder pin, any well-formed wrong pin, near-miss
  (1-char shorter/longer), wrong-prefix, malformed and blank pins, and pins
  the exact placeholder value (`placeholder manifest pin source contract is
  pinned so the channel stays fail-closed`).
- `app/src/test/java/com/authorss81/noteflow/HttpsManifestTransportTest.kt` —
  `the compiled-in host and pin constants are well-formed` asserts
  `PinnedCertHash.parse(PLUGIN_MANIFEST_CERT_PIN)` is a parseable 32-byte
  digest, and the whole transport suite runs the REAL `HttpsManifestTransport`
  against a local TLS server proving wrong-pin fetches are refused.

---

## 2. How the pin is computed — MUST match the app's algorithm

The app computes the pin in `PinnedCertHash.base64Sha256(cert)`:

```kotlin
fun base64Sha256(cert: X509Certificate): String =
    PluginDigest.sha256Base64(cert.encoded)   // SHA-256 of the certificate DER
```

i.e. `sha256/` + Base64( SHA-256( **certificate DER encoding** ) ). This is
**NOT** the RFC-7469 *SPKI* pin that `openssl x509 -pubkey` produces — the app
hashes the whole leaf certificate, public key + identity + validity + signature.

> ⚠️ **Footgun — use the DER command, not the `-pubkey` command.** The often-
> quoted pin recipe `openssl x509 -pubkey | openssl pkey -pubin -outform der
> | openssl dgst -sha256 -binary | base64` yields the SPKI hash, which this app
> will REJECT (verified: the two commands produce different values for the same
> certificate). Substituting an SPKI pin would make a genuinely-good host fail
> closed forever. Use the certificate-DER recipe below.

### 2a. Compute the correct leaf pin (authoritative)

```bash
openssl s_client \
    -connect plugin-updates.inkflow.app:443 \
    -servername plugin-updates.inkflow.app \
    -showcerts < /dev/null 2>/dev/null \
  | sed -n '/BEGIN CERTIFICATE/,/END CERTIFICATE/p' \
  | openssl x509 -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

Output is the 44-char Base64 S-suffix; the compiled-in constant becomes:

```kotlin
const val PLUGIN_MANIFEST_CERT_PIN: String =
    "sha256/<that-base64>"
```

(`sed` picks the FIRST certificate of `-showcerts` = the leaf; `openssl x509`
without `-pubkey` outputs that certificate's DER; `dgst -sha256` = a single
SHA-256 — exactly `cert.encoded` in the app.)

### 2b. Cross-check against the serving chain (leaf-only requirement)

The pin MUST be the **leaf** certificate of the production deployment, not an
intermediate or root. Confirm you pinned the leaf:

```bash
openssl s_client -connect plugin-updates.inkflow.app:443 \
    -servername plugin-updates.inkflow.app -showcerts < /dev/null 2>/dev/null \
  | openssl crl2pkcs7 -nocrl -certfile /dev/stdin 2>/dev/null \
  | openssl pkcs7 -print_certs -noout
```

The FIRST row is the leaf the pin must match. If you ever pin the intermediate,
the next intermediate rotation silently locks the channel (fail closed), which
is why the renewal procedure in §4 exists.

### 2c. Verify the pin locally before building (optional but recommended)

With a throwaway keystore + `apksigner`, you can prove the value round-trips:
build a release APK after substituting the pin, serve the manifest from a host
whose leaf matches, and check a fetch succeeds (`HttpsManifestTransportTest`
is the JVM-level stand-in). CI will also run `PinnedCertHashTest`, which now
rejects the old placeholder — i.e. the test suite is your canary that the
substitution actually took effect.

---

## 3. Operator go-live checklist

Do every step in order, in the same release:

- [ ] **1. Compute the real leaf pin** for `plugin-updates.inkflow.app` with
      §2a and confirm it is the LEAF (§2b). Save it to the secret store /
      release ticket — the value is public (a cert hash) but the *decision*
      (which cert is the production one) is operator authority.
- [ ] **2. Substitute the pin** in
      `app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/HostedPluginManifest.kt:242-243`
      — replace only the `sha256/<base64>` string, keep the surrounding KDoc
      and the structure. Do NOT touch the pin store / artifacts in this step.
- [ ] **3. Run the test and build gates:**
      ```bash
      gradle testDebugUnitTest      # PinnedCertHashTest (placeholder-rejected) + HttpsManifestTransportTest green
      gradle assembleDebug
      KEYSTORE_FILE=… KEYSTORE_PASSWORD=… KEY_ALIAS=… KEY_PASSWORD=… gradle assembleRelease
      ```
      `B1Plat01ReleaseSigningTest` must stay green (release signing v2+v3, see
      `docs/RELEASE.md`; the APK now also ships APK Signature Scheme v3 for
      in-place key rotation).
- [ ] **4. Re-ship:** bump `versionCode`/`versionName` and publish the new APK
      on every distribution channel (self-hosted GitHub release / sideload).
      The pin travels inside the app — it cannot be pushed by update.
- [ ] **5. Verify one hosted fetch succeeds end-to-end:** install the re-shipped
      build on a device with the real backend live, open the Plugin Store →
      "Check for updates", and confirm a `200` manifest is accepted (an UPDATE
      entry will still refuse unless a release is pinned in
      `CompileTimePluginPinStore`, B1-NET-03 — the *fetch* succeeding is the
      go-live criterion). A wrong/missing pin shows the honest fail-closed
      message instead; record which happened.
- [ ] **6. Close the finding:** annotate this document + `docs/security-report.md`
      Phase-32-NEW-04 as resolved-at-go-live with the substituted value hash.

---

## 4. Certificate renewal / rotation procedure

The pin is the hash of the CURRENT leaf. When the certificate is renewed
(e.g. Let's Encrypt 90-day), the leaf DER changes and the app — fail closed —
drops the channel until the app is re-shipped:

- [ ] Renew cert, deploy to the host, confirm the new leaf via §2a.
- [ ] Re-run §3 (compute → substitute → gates → re-ship → verify one fetch).
- [ ] To make a renewal SEAMLESS, ship the new pin in the app BEFORE the old
      cert expires (a small release whose only change is the rotated pin). Both
      the old and new cert must still be served on the renewal day; the app only
      carries the new pin after it is installed.
- [ ] If anything pins the OLD cert anywhere else (plugins/llm artifacts,
      `CompileTimePluginPinStore.RELEASES`), re-issue those pins too — the 
      artifact signing key is independent from the TLS leaf, but its pin is
      also compiled in; see `docs/PLUGINS.md` "Publishing a downloadable plugin
      release" for the artifact side.

---

## 5. Related evidence

- Transport + pin-gate implementation: `plugins/runtime/PluginManifestFetcher.kt`
  (`HttpsManifestTransport`), `plugins/runtime/PinnedTlsConnector.kt`,
  `plugins/runtime/PinnedCertHash.kt`.
- Host allow-list: `DEFAULT_MANIFEST_HOST` / `DEFAULT_PLUGIN_MANIFEST_URL`
  (`HostedPluginManifest.kt:219-222`), scheme+host+effective-port gate via
  `services/HostPortAllowList.kt` (R2-B1N-05), no redirects ever followed
  (`PluginManifestFetcher.kt:151-155`).
- Fail-closed refusals the user sees: the store surfaces a non-alarming
  "update checks are disabled" / "certificate does not match the pinned hash"
  message — never silent degradation.
- Phase history: B1-CRYPTO-01 (phase-39) built the pinned transport;
  B1-NET-03 (phase-42) moved artifact trust into the APK; Phase-32-NEW-04
  (this document) made the operator substitution a real, verified runbook.