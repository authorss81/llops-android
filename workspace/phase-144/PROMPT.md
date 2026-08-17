# Phase 144: Deep network boundary — DNS-rebinding mitigation + plugin egress/exec scan hardening [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1N-02, R2-B1N-03) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase hardens the SSRF host guard to resolve + pin
per hop, and makes the plugin egress/exec static gate resist trivial string
obfuscation.

## Source findings (both OPEN, LOW)

1. **R2-B1N-02** — SSRF host guard is textual-only; DNS-rebinding is NOT
   mitigated (`services/SsrfHostPolicy.kt:24-29` KDoc: "no DNS resolution
   happens here … out of scope"). No reachable transport implements
   resolve-and-pin (`HttpsTitleFetcher.kt:65-70`, `WebPageFetcher.kt:28-32`,
   `AppFacadeHost.kt:61-62`, `DuckDuckGoClient.kt:155-156`,
   `WeatherClient.kt:96-97`, `DictionaryClient.kt:61-62`). A DNS-rebinding
   domain answers the first query with a public IP and the connect-time query
   with `127.0.0.1`/`169.254.169.254`/LAN — the fetch reaches an internal host
   the textual check never saw.
2. **R2-B1N-03** — Plugin egress/exec static-scan gate is bypassable by trivial
   string obfuscation (`ArtifactStaticScan.kt:243-263` matches exact tokens
   from constant pools + DEX string/type tables `:333-404`,`:413-441`);
   `PluginFrameworkClassLoader.kt:27-29` + `:62` delegate everything outside the
   app namespace — INCLUDING `java.*`/`javax.*` — to the app classloader. A
   rogue artifact can build net egress via
   `Class.forName("java.net." + "Sock" + "et")` / fragmented constants and skip
   the facade. (Caveat keeping this LOW: the compile-time pin table is empty
   `CompileTimePluginPins.RELEASES = []`, `GeneratedLlmPluginPin.kt:22` null, so
   nothing unpinned downloads/installs.)

## The fix (where & how)

- **R2-B1N-02:** Per hop, resolve the host and validate EVERY returned A/AAAA
  against the internal ranges (reuse `SsrfHostPolicy`'s structural blocklist on
  the resolved addresses), then pin the connect to the checked addresses
  (custom `SSLSocketFactory` + `HostnameVerifier`), or refuse any name whose
  resolution contains an internal address. Apply to the Web Capture/Citation
  fetchers and the AppFacadeHost (the user-driven + plugin-driven fetchers).
- **R2-B1N-03:** Treat `ArtifactStaticScan` as advisory and harden it: structural
  DEX analysis rejecting string-built `Class.forName`/reflection of
  `java.net`/`java.lang.Runtime`/`ProcessBuilder`, AND/OR make
  `PluginFrameworkClassLoader` refuse resolving `java.net.*`/`java.lang.Runtime`
  so ALL plugin I/O must flow through the facade. Keep the fail-closed posture.

## Verification

- New/updated pure-JVM unit tests: a fetch-plan resolver that resolves a name,
  blocks a rebinding response (internal-IP A/AAAA), and pins connect to checked
  addresses; an obfuscated-egress scan test (concatenated `Class.forName`
  strings now rejected); classloader refusal tests for `java.net.*`/Runtime.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-144/REPORT.md`.

## Definition of done

- Both findings closed with `file:line` before/after evidence.
- Web Capture/Citation/AppFacadeHost resolve-and-pin (or refuse mixed
  public/internal resolutions); plugins cannot reach I/O outside the facade.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep the plugin
  fail-closed pin posture + no-network-in-base-app rule.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.