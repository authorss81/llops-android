# Phase 144 report — Deep network boundary: DNS-rebinding resolve-and-pin (R2-B1N-02) + plugin egress/exec scan & classloader hardening (R2-B1N-03)

Status: **DONE** — both R2-B1N-02 and R2-B1N-03 fixed.

## Findings fixed

### R2-B1N-02 (LOW) — SSRF host guard is textual-only; the DNS-rebinding window is not mitigated
`SsrfHostPolicy` validates the host STRING; a DNS-rebinding domain that answers the first
query with a public IP and the connect-time query with `127.0.0.1`/`169.254.169.254`/LAN was
unreachable-by-text but reachable-by-DNS: the transport's `URL.openConnection()` re-resolves
inside the platform DNS cache at connect time, so the connect could land on an internal host
the blocklist never saw.

**Fix — new `app/src/main/kotlin/com/authorss81/noteflow/services/DnsRebindingPolicy.kt`**
(pure JVM, `java.net`/`javax.net.ssl` only), applied **per hop, before any connection**:

1. `resolveAndPin(host, resolver)` resolves the hop **once** (injectable resolver —
   production default `InetAddress.getAllByName`) and validates **EVERY** returned A/AAAA
   against the `SsrfHostPolicy` ranges (loopback, RFC-1918, CGNAT, link-local/cloud-metadata,
   IPv6 ULA/link-local, IPv4-mapped/compatible forms). **Any** internal answer refuses the
   whole hop (`Verdict.Refused` — fail-closed, never a partial pin).
2. `applyPinToConnection(...)` attaches a `PinnedSslSocketFactory` + `PinnedHostnameVerifier`
   to the `HttpsURLConnection`. The layered `createSocket(Socket, host, port, autoClose)` —
   what the platform invokes after it pre-connected its own plaintext socket — checks that
   pre-connected peer against the validated addresses; a socket that reached a **non-pinned
   (rebound) address is closed and the connect rebuilt to a pinned address**, so a DNS
   rebinding between our resolve and the platform connect can never carry a handshake to an
   internal host (worst case a rebinding server sees an aborted, TLS-less TCP connect).
3. `PinnedHostnameVerifier` re-checks the hop host: `hostname == expectedHost` **and**
   `SsrfHostPolicy.blockedReason(expectedHost) == null`, then delegates to the platform
   hostname verifier.

Wired into **all six** reachable user-influenced/plugin transports (each gained an injectable
`dnsResolver: (String) -> Array<InetAddress>` constructor param defaulting to the platform
DNS, so existing no-arg call sites compile unchanged):

| Transport | Resolve+pin point |
|---|---|
| `plugins/citation/HttpsTitleFetcher.kt` | per hop, after `blockedReason` — `TitleFetchException(pin.reason)` |
| `plugins/webcapture/WebPageFetcher.kt` | per hop, after `WebPageFetchPolicy.rejectHop` — `IOException(pin.reason)`; `CONNECT_TIMEOUT_MS` const |
| `services/AppFacadeHost.kt` | per hop, before the connection factory — `FacadeResult.Failed(...)` |
| `plugins/websearch/DuckDuckGoClient.kt` | per hop, before the connection factory — `DuckDuckGoSearchException(pin.reason)` |
| `plugins/weather/WeatherClient.kt` | per hop, before the connection factory — `WeatherServiceException(pin.reason)` |
| `plugins/dictionary/DictionaryClient.kt` | per hop, before the connection factory — `DictionaryServiceException(pin.reason)` |

### R2-B1N-03 (LOW) — plugin egress/exec static-scan gate bypassable by trivial string obfuscation
`ArtifactStaticScan` matched exact `java.net.*`/`java.lang.Runtime`/`ProcessBuilder` tokens,
and `PluginFrameworkClassLoader` delegated everything outside the app namespace — **including
`java.*`/`javax.*`** — to the app classloader. A rogue artifact (stolen/leaked signer key)
could build `Class.forName("java.net." + "Sock" + "et")` / fragmented constants that never
equaled a forbidden token, so the scan passed and raw sockets bypassed `FacadeHost.httpGet`.

**Fix — two layers, matching how the name can be spelled:**

1. **Classloader (the decisive gate) — `plugins/runtime/PluginFrameworkClassLoader.kt`**:
   `loadClass` now also refuses, with a `ClassNotFoundException`, anything under
   `java.net.*` / `javax.net.*` and the exact `java.lang.Runtime` / `java.lang.ProcessBuilder`
   classes (kept exact so `java.lang.String`/`Integer`/`List`… still delegate). Because the
   refusal keys on the **resolved name**, even string-built `Class.forName("java.net." +
   "Sock" + "et")` — which lands here through the plugin's loader chain — is refused. All
   plugin I/O now MUST flow through the capability facade, the only transport running the
   TLS/SSRF/size-cap policy.
2. **Static scan (advisory, fail-noisy at verify) — `plugins/runtime/ArtifactStaticScan.kt`**:
   parsed strings/types that carry a dot-form **package-prefix fragment** (`java.net.`,
   `java.lang.`, `javax.net.ssl.`, `javax.net.`) at a word boundary are rejected — a plugin
   assembling an egress/exec class name dynamically cannot avoid shipping at least the prefix
   fragment. Dot-form only (slash-form descriptors like `Ljava/lang/String;` never
   false-positive), and word-boundary only (benign `kotlin.jvm.JvmInline`,
   `example.com/notjava.lang.other` still pass).

## Tests

New: `app/src/test/java/com/authorss81/noteflow/B1Net08DnsRebindingPinTest.kt` (15 tests) —
resolve-and-pin validation (whole-public → Pinned; any internal/mapped answer → Refused;
mixed public+internal → Refused wholesale; dedupe; unresolved/empty/blank → Refused, never
throws), pin application (plain-HTTP no-op), `PinnedSslSocketFactory` layered behavior
(delegate unchanged for a pinned pre-connected socket; a pre-connected socket outside the pin
is closed and rebuilt to a pinned address — loopback-only via a real `ServerSocket`, no
external network; unconnected pre-socket rebuilt; empty pin rejected), `PinnedHostnameVerifier`
(host equality + blocked-host refusal), and source pins (all six transports call
`resolveAndPin` + `applyPinToConnection`; the three JSON clients still route through
`StrictRedirectPolicy` with `instanceFollowRedirects = false`).

Updated `PluginBytecodeIsolationTest.kt` (+2, 24 total): classloader refuses every
`java.net.*`/`javax.net.*`/`Runtime`/`ProcessBuilder` name while `java.lang.String` etc. still
resolve; egress refusal is name-based and catches string-built `Class.forName`; `ArtifactStaticScan`
rejects string-fragmented `java.net.`/`java.lang.` dex artifacts and does not false-positive on
benign dot-form fragments.

Updated `B1Net05RedirectDowngradeTest.kt` + `B2Dos04FacadeGetStreamingCapTest.kt`: the existing
transport/`AppFacadeHost` tests now inject a pure-JVM stub DNS resolver (returns a public
address) so the new resolve step never touches a network in unit tests.

`gradle testDebugUnitTest` — **1978 tests, 0 failures / 0 errors / 0 skipped** across 178
suites. (One pre-existing `WikiLinkParserCacheUnitTest` cancellation-timing flake failed in the
full run and passes in isolation — unrelated to this diff, documented in earlier phases 40/143 too.)

`gradle assembleDebug` — **BUILD SUCCESSFUL** (debug APK `app/build/outputs/apk/debug/app-debug.apk`).
The first plain invocation hit a transient Kotlin-daemon session-file issue; the immediate
re-run completed with all tasks up-to-date and the APK produced.

## Constraints honored

- Pure JVM, no external network in unit tests; no new dependencies.
- No DB schema change, no migration, no `.github/workflows/` edits.
- Plugin download/manifest transports (`PinnedTlsConnector`, `HttpsPluginDownloadTransport`,
  `PluginManifestFetcher` funnelling into `HttpsManifestTransportTest`) are intentionally NOT
  wired to resolve-and-pin — they are pinned-identity, host allow-listed transports, not
  user-influenced fetchers, and a `localhost`-backed real-TLS test would illegal-refuse on
  loopback.
- No keys/passwords/decrypted content logged; `allowBackup="false"` untouched.

## Docs updated

- `docs/security-report-round2.md`: R2-B1N-02 + R2-B1N-03 table rows and detail sections marked **FIXED in phase 144**.
- `docs/phase-status.md`: phase-144 row → `DONE`.
- `docs/ARCHITECTURE.md`: "Implemented in phase-144" note on network + plugin-runtime sections.