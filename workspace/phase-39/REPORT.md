# Phase 39 — B1-CRYPTO-01: Pin the plugin update manifest transport and stop following redirects

- **Commit:** `4d72a6a` (`llops: phase-39 — B1-CRYPTO-01: pin the plugin update manifest transport and stop following redirects`)
- **Finding fixed:** `B1-CRYPTO-01` (CRITICAL) — docs/security-report.md:359-365
- **Date:** 2026-08-15
- **Status:** `DONE`

## 1. What was vulnerable (before)

`PluginUpdateChecker.toTargetEntry` copies three fields — `downloadUrl`, `sha256`,
`pinnedCertHash` — verbatim from the hosted update manifest into the persisted
active plugin entry (`PluginUpdateChecker.kt:30-38`, `74-82` before). The manifest
itself was fetched over chain-validation-only HTTPS with redirects ENABLED:

- `PluginManifestFetcher.kt` (before commit): `HttpsManifestTransport : ManifestTransport`
  at `:84`, `parsed.openConnection() as HttpsURLConnection` at `:96`,
  `connection.instanceFollowRedirects = true` at `:99`, generic `if (responseCode !in 200..299)` at `:105` — no pin, no host allow-list, follows redirects (including a 302 `Location: http://...` downgrade).
- `HostedPluginManifest.kt:190` (before): `DEFAULT_PLUGIN_MANIFEST_URL` was a bare HTTPS constant — no compiled-in pin existed.
- `HttpsPluginDownloadTransport.kt` (before): `createPinnedConnection(url, request.pinnedCertHash)` at `:57`, `instanceFollowRedirects = true` at `:63`; `createPinnedConnection` at `:133-166` and `defaultTrustManager` at `:168-175` — the pin was attacker-suppliable via the manifest, and the download followed redirects too.

**Exploit (closed now):** a MITM / compromised CA / 302→http downgrade serves a forged
manifest whose offer redefines `downloadUrl`/`sha256`/`pinnedCertHash` to all-attacker
values; every subsequent check (TLS pin to the attacker cert, whole-file SHA-256,
signature signer-hash) passes self-consistently and `DexClassLoader` runs attacker DEX
as a plugin with full app privileges on one click. The "never from the network" pin
guarantee documented in `PinnedCertHash.kt:20-21` was silently contradicted.

## 2. What changed (after)

| File | Change |
|------|--------|
| `plugins/runtime/PinnedTlsConnector.kt` (NEW) | Shared helper `PinnedTlsConnector.open(url, expectedCertHash, baseTrustManager)` chain-validates against a base `X509TrustManager` (production = system trust store, `systemTrustManager()` at `:77`), pin-verifies the leaf against `expectedCertHash` via constant-time `PinnedCertHash.matches` (`:57`), sets `hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()` (`:68`), `useCaches = false` (`:69`) and `instanceFollowRedirects = false` (`:72`). |
| `plugins/runtime/HostedPluginManifest.kt` | New `DEFAULT_MANIFEST_HOST = "plugin-updates.inkflow.app"` (`:197`); `DEFAULT_PLUGIN_MANIFEST_URL` derived from it (`:199-200`); `PLUGIN_MANIFEST_CERT_PIN` (`:220`) — a well-formed 32-byte `sha256/<base64>` **placeholder** with a kdoc stating it MUST be replaced with the real production host-leaf hash before the hosted channel goes live (fails closed until then). |
| `plugins/runtime/PluginManifestFetcher.kt` | `HttpsManifestTransport` rewritten (`:109`): constructor `expectedCertPin = PLUGIN_MANIFEST_CERT_PIN`, `expectedHost = DEFAULT_MANIFEST_HOST`, `trustManagerOverride: X509TrustManager? = null` (test-only seam). Guard order: HTTPS-scheme-only → host allow-list match (`:125`) → `PinnedCertHash.parse(expectedCertPin) != null` or fail closed (`:130`, "…plugin update checks are disabled", no connection) → `PinnedTlsConnector.open(parsed, expectedCertPin, trustManagerOverride ?: PinnedTlsConnector.systemTrustManager())` (`:137-138`). 3xx refused explicitly (`:146-148`, "...an HTTP redirect ($responseCode), which is never followed."). 2xx only; null-body → `Failed`; size cap `MAX_BYTES = 256 * 1024` (`:224`) enforced via `readNBytes(MAX_BYTES + 1)` (`:163-167`). Pin mismatch surfaced via `SSLHandshakeException` cause-chain walk (`isPinnedCertFailure`) for JDK 21. |
| `plugins/runtime/HttpsPluginDownloadTransport.kt` | Refactored onto `PinnedTlsConnector.open(url, request.pinnedCertHash)` (`:55`); deleted `createPinnedConnection` + `defaultTrustManager`; explicit `if (responseCode in 300..399)` refusal (`:65`); kept `Accept`/`User-Agent` headers (`:56-57`); same `isPinnedCertFailure` cause-chain walk (`:120`, `:145`). Download transport and manifest transport now share one pin+no-redirect policy. |
| `plugins/runtime/PinnedCertHash.kt` | KDoc trust note updated (pin must never come from the network; the manifest is itself fetched over a transport pinned to `PLUGIN_MANIFEST_CERT_PIN`). |
| `plugins/runtime/PluginUpdateChecker.kt` | KDoc: `toTargetEntry` copying offer fields into the persisted entry is safe ONLY because the manifest arrived over the pinned, no-redirect transport. |
| `plugins/runtime/SignatureVerifiedPluginRuntime.kt` | KDoc: entry fields are trusted because they came from the pinned manifest; artifact verification always runs against publisher-committed values. |
| `app/src/test/java/com/authorss81/noteflow/HttpsManifestTransportTest.kt` (NEW) | 8 pure-JVM tests over a private JDK-only `TinyHttpsServer` (`SSLContext` + `SSLServerSocket`, hand-rolled HTTP/1.1, per-test self-signed keypairs from `keytool -genkeypair -storetype PKCS12`, request-target capture in a `CopyOnWriteArrayList`). Scope: pin-matched 200 → loaded; mismatched pin → rejected; `http://` refused before any connection; non-allow-listed host refused before any connection; 302 cross-host redirect refused and target URL never contacted; HTTPS→HTTP redirect refused and plaintext target never contacted; malformed compiled-in pin fails closed without contacting the server; compiled-in constants well-formed (host, URL prefix, pin parses to a 32-byte digest). |

No new Gradle dependencies, no build-file changes, no DB schema change, no
`.github/workflows/` edits.

## 3. Root-cause chain, after the fix

1. Compile-time `PLUGIN_MANIFEST_CERT_PIN` is the only way the manifest host leaf is trusted → a MITM/rogue-CA cert for `plugin-updates.inkflow.app` is rejected before any HTTP byte moves.
2. Host allow-list (`expectedHost`) and scheme gate stop bizarre/pinned-less URLs before connection.
3. `instanceFollowRedirects = false` in both transports + explicit 3xx refusal mean a 302/301/307 (including HTTPS→HTTP downgrades and cross-host jumps) is surfaced as `Failed` — never followed, so the "redirect target" trick is dead.
4. The persisted entry's `downloadUrl`/`sha256`/`pinnedCertHash` can therefore only ever be the values the genuine, pinned manifest committed. This closes the full loop exploited by B1-CRYPTO-01.

## 4. Checksum & secrets handling

- `PLUGIN_MANIFEST_CERT_PIN` is a **public** cert-hash (leaf of the manifest host's TLS cert) — not a secret. It is compile-time metadata, never logged (no logging added anywhere), and survives R8/minify untouched (a constant in the Kotlin source).
- It is a well-formed **placeholder** (base64 of `bytes(range(32))`). Until an operator substitutes the real production host-leaf hash, updates FAIL CLOSED with a clear, non-alarming message ("This build does not carry a valid pinned certificate ... plugin update checks are disabled."). This machine cannot resolve `plugin-updates.inkflow.app` (GitHub Actions runner DNS), so the genuine hash must be captured from the manifest-hosting certificate by someone with access to it — **required before the hosted update channel ships** (the host "guarantee" was never a live channel in this repo).
- `PinnedCertHash.matches` is constant-time (`ConstantTime`), so the pin comparison does not leak the pin via timing.
- No keys, passwords, or decrypted note content are referenced, logged, or touched by this change.

## 5. Verification output

```
$ gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.HttpsManifestTransportTest"
BUILD SUCCESSFUL   (8 tests, 0 failures, 0 errors)

$ gradle testDebugUnitTest
BUILD SUCCESSFUL
Unit-test XML rollup: tests=884  skipped=0  failures=0  errors=0
  (was 876 before this phase; +8 new HttpsManifestTransportTest, 0 regressions)

$ gradle assembleDebug --rerun-tasks
BUILD SUCCESSFUL   90 actionable tasks, 90 executed
artifact: app/build/outputs/apk/debug/app-debug.apk (173,605,602 bytes)
```

Note: the very first `assembleDebug` invocation of the session failed transiently
(no error present in a subsequent identical rebuild); two consecutive re-runs —
including a full `--rerun-tasks` rebuild — succeeded with no code change, so it was
an environment/daemon flake, not a code defect.

## 6. OS/API floor (AGENTS.md hardware reality)

The fix uses plain `javax.net.ssl` + `HttpsURLConnection` APIs available since API 1,
no newer API is required, so the API 26+ floor is unaffected and no fallback/notice is
needed. Fail-closed message is shown only when the compiled-in pin is missing/malformed
(a build-time condition, not a runtime capability question).

## 7. Out of scope (noted, NOT fixed here — other phases)

- `B1-NET-03` (manifest-supplied `downloadUrl` can inject logcat lines / leak in error text on refused downloads). The explicit-3xx and fail-closed paths now produce cleaner errors, but `AndroidPluginLogger.error` still prints refused URLs — separate finding, separate phase.
- `B1-CRYPTO-02/04` (master-password key-handling/hardening) — different findings.
- Replacing the placeholder pin with the real production host-leaf hash (operator action, noted above).
- Signing the manifest body (alternative to transport pinning suggested by the finding) — the compile-time-pinned transport fully closes the exploit path and was chosen to keep the diff small; body-signing would be an enhancement, not a requirement.

## 8. Verification vs Definition of Done (PROMPT.md)

- [x] `file:line` before/after evidence in the commit (see sections 1-2).
- [x] New pure-JVM tests reject unpinned/mismatched-pin manifests, `http://`, cross-host and HTTPS→HTTP redirects; follow repo layout in `app/src/test`.
- [x] No existing test regressed (884 green).
- [x] `gradle testDebugUnitTest` + `gradle assembleDebug` both pass and reported here.
- [x] No DB schema change. No new dependencies. `.github/workflows/` untouched. `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE intact.
- [x] No other security finding fixed in this phase.