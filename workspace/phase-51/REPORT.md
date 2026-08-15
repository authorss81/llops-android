# Phase 51 — B1-NET-04 (MEDIUM): SSRF in Web Capture and Citation title-fetch — FIXED

- **Date:** 2026-08-15
- **Finding:** `B1-NET-04` — *SSRF in Web Capture and Citation title-fetch: no host blocklist, redirects re-check only the scheme — localhost / LAN / cloud-metadata endpoints reachable* (MEDIUM)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched. All new logic is pure-JVM (`services/SsrfHostPolicy.kt` + the policy/core entry gates); only the two network transports were touched to re-apply the policy on every redirect hop. API-floor-neutral (API 26+): the fix is host-string/CIDR parsing + per-hop revalidation — no `Build.VERSION` branch or hardware fallback required. Blocked fetches surface as typed, non-alarming plugin errors ("Private or internal IP addresses cannot be fetched.", etc.) — never silent.

## Root cause (before)

1. `WebPageFetchPolicy.validateUrl` (`plugins/webcapture/WebPageFetchPolicy.kt:31-58` before) checked only scheme + host **presence** — `localhost`, `127.0.0.1`, `192.168.*`, `10.*`, `169.254.169.254` all passed. Web Capture would happily fetch and store internal endpoints into the vault.
2. `WebPageFetcher.doFetch` (`plugins/webcapture/WebPageFetcher.kt:21-61` before) followed redirects **without ever assigning the resolved URL** (`return@repeat` discarded `cur.resolve(loc)`), and its only hop guard (lines 25-28) re-checked the http(s) scheme but never the host. An attacker-controlled public page answering `302 Location: http://169.254.169.254/...` bypassed the entry check entirely.
3. `HttpsTitleFetcher.fetch` (`plugins/citation/HttpsTitleFetcher.kt:32-48` before) used Java's **default redirect following** (`instanceFollowRedirects` stays `true`) with only a first-hop `https` check — a redirect could silently downgrade to `http://` and/or land on an internal host, and the Citation plugin then displayed the returned `<title>`.
4. `CitationFormatterCore.validateUrl` (`plugins/citation/CitationFormatterCore.kt:22-38` before) accepted any host containing a `.` — including IP literals like `169.254.169.254` and `192.168.1.1`.

## What changed (after) — `file:line`

### 1. New shared pure-JVM blocklist — `services/SsrfHostPolicy.kt` (new)

`SsrfHostPolicy.blockedReason(host)` (`:30`) returns a human reason or null, covering:
- hostnames: `localhost`, `*.localhost`, `*.local` (`:34-39`);
- IPv4 textual encodings (`parseIpv4` `:70`): dotted-quad **and** the short 1-3 segment forms Java's `InetAddress` constructs (`127.1`, `2130706433`, `0x7f000001`), all normalized to a 32-bit value;
- blocked IPv4 CIDRs (`ipv4Reason` `:105`): `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10` (CGNAT), `127.0.0.0/8`, `169.254.0.0/16` (link-local / cloud metadata `169.254.169.254`), `172.16.0.0/12`, `192.168.0.0/16`;
- IPv6 (`parseIpv6` `:134` + `ipv6Reason` `:193`): `::`/`::1` loopback, `fe80::/10` link-local, `fc00::/7` ULA (incl. AWS IMDSv2 `fd00:ec2::254`), and the **IPv4-mapped/compatible embedded forms** (`::ffff:192.168.0.1`, `::127.0.0.1`) re-checked through the IPv4 ranges.

All checks are textual/structural — **no DNS resolution**, so unit tests run without a network and a hostile DNS answer cannot silently pass. (Recursive DNS-resolution enforcement at connect time is documented out-of-scope below.)

### 2. Entry gate — `plugins/webcapture/WebPageFetchPolicy.kt`

- `validateUrl` (`:31`) now calls `SsrfHostPolicy.blockedReason(host)` and returns `Either.Error(blocked)` before any connection is made (`:58-65`).
- New `rejectHop(absoluteUrl)` (`:80`) — the shared hop-revalidation used by the fetcher: same scheme allow-list + blocklist applied to a **resolved** redirect `Location`.

### 3. Redirect-loop revalidation — `plugins/webcapture/WebPageFetcher.kt`

- `doFetch` (`:22`) now (a) re-runs `WebPageFetchPolicy.rejectHop` on **every** hop (including hop 0, defense-in-depth), and (b) **fixes the pre-existing redirect bug**: `cur = resolved` is now assigned so the chain actually advances, and the resolved `Location` is re-validated (`:45-54`) before the next connection. The 5-hop cap is unchanged (`MAX_REDIRECTS = 5`).

### 4. Citation title-fetch — `plugins/citation/HttpsTitleFetcher.kt`

- `fetch` (`:39`) rewritten with a **manual redirect loop**: `instanceFollowRedirects = false` (`:69`), same http(s)-per-`httpsOnly` scheme policy and `SsrfHostPolicy` blocklist applied on the initial URL AND on every hop; relative `Location` values are resolved against the current hop and re-validated (`:86-100`); 5-hop cap; `title == null` fallback behaviour of the plugin unchanged. `httpsOnly=true` is the production default, so an HTTPS→HTTP downgrade hop is now refused too (part of the mandated "re-apply validation on every hop").

### 5. Citation entry gate — `plugins/citation/CitationFormatterCore.kt`

- `validateUrl` (`:26`) now extracts the host via `URI` (replacing the fragile substring parse) and refuses internal hosts via `SsrfHostPolicy.blockedReason` (`:42-46`). `http://169.254.169.254/...`, `http://192.168.1.1/status`, `http://[::ffff:192.168.1.1]/` are refused before any fetch; public hosts behave as before.

## Checksum / secrets handling

- No keys, passwords, salts, or decrypted note content are logged, printed, or persisted in the new paths. The blocklist operates on host strings only; a blocked destination produces a generic typed plugin error message containing only the host-name policy reason, never credentials and never internal request bodies.

## Verification

- New pure-JVM test class `app/src/test/java/com/authorss81/noteflow/B1Net04SsrfBlocklistTest.kt` — **19 tests** covering:
  - `SsrfHostPolicy` rejects loopback / RFC-1918 / link-local-metadata / CGNAT / this-network / `.local`+`localhost` / IPv6 loopback-unspecified-link-local-ULA / IPv4-embedded-IPv6 forms, in every textual encoding; public hosts (incl. boundary addresses `172.15/172.32`, `169.253`, `100.63/100.128`, `1.2.3.4`, `8.8.8.8`) pass;
  - `WebPageFetchPolicy.validateUrl` refuses `localhost`, `127.0.0.1`, `169.254.169.254`, `192.168.1.1`, `10.0.0.1`, `[::1]`, `foo.local`, `2130706433` and still accepts public http/https (incl. `8.8.8.8`);
  - `WebPageFetchPolicy.rejectHop` refuses a resolved redirect to every blocked host (incl. protocol-relative `//169.254.169.254/…`) and to `ftp://` and allows public/relative redirects;
  - `CitationFormatterCore.validateUrl` and `HttpsTitleFetcher` refuse the same set, the fetcher **before any socket connects** (blocked/malformed/non-https URLs throw `TitleFetchException` prior to `openConnection`).
- `gradle testDebugUnitTest` — **1072 tests green, 0 failures** (1053 pre-phase baseline + 19 new).
- `gradle assembleDebug` — **green** (`app-debug.apk` 173,669,722 bytes on disk). First invocation hit the documented transient Gradle-daemon flake (retry fully green — same pattern reported in phases 47-50).

## Out of scope (documented, not fixed here)

- **Recursive DNS-resolution egress enforcement**: a hostile public hostname whose DNS returns an internal IP (DNS-rebinding / `metadata.google.internal`-style names that are not an IP literal) is not caught by a literal blocklist — enforcing it would inject a DNS round-trip (and a resolved-IP→connect pin) into every fetch, a behaviour change beyond this literal-blocklist fix. Numerical/IP-literal and `.local` vectors cited in B1-NET-04 are closed; name-based rebinding should be its own phase (transport-level resolved-IP pinning).
- **HttpsTitleFetcher HTTP allowance**: kept, because (a) entry already normalizes bare hosts to `https`, (b) the Citation plugin has an honest host-label fallback, and (c) `httpsOnly=true` is the production default — the finding said "consider HTTPS-only"; the upgrade is not forced to keep the Data-Capture behaviour identical for opt-in `httpsOnly=false`.
- Other findings that touch these files (B1-NET-05 scheme-downgrade semantics for WebDAV/DuckDuckGo/Weather/Dictionary; User-Agent fingerprinting B1-NET-09) are separate phases and untouched.