# Phase 52 — B1-NET-05 (MEDIUM): HTTPS→HTTP redirect downgrades — FIXED

- **Date:** 2026-08-15
- **Finding:** `B1-NET-05` — *HTTPS→HTTP redirect downgrades: default redirect-following defeats every "HTTPS only / never cleartext" guard in all `HttpURLConnection`-based transports* (MEDIUM)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched. All new decision logic is pure-JVM (`services/StrictRedirectPolicy.kt`); the four downgradeable transports were re-pointed through it with `instanceFollowRedirects = false`, and the is-logistics one-line hardening for `LocalSendSender` was added. API-floor-neutral (API 26+): the fix is scheme/host validation + manual redirect loops, no `Build.VERSION` branch, no hardware fallback needed. Refusals surface as the existing typed, non-alarming plugin/facade errors — never a silent degrade.

## Root cause (before)

All of these `java.net.HttpURLConnection` transports left `instanceFollowRedirects` at its platform `true` default, so `HttpURLConnection` followed 3xx **internally**, and the app's "HTTPS only" guard (`requireSecureUrl` in WebDAV, `url.startsWith("https://")` in the facade) ran exactly once — on the *initial* URL, never on the redirected connection:

1. `DuckDuckGoClient.search` (`plugins/websearch/DuckDuckGoClient.kt`, `openConnection` at the pre-fix `:140`) — default following.
2. `OpenMeteoClient.get` (`plugins/weather/WeatherClient.kt`, `openConnection` at the pre-fix `:81`) — default following.
3. `DictionaryClient.lookup` (`plugins/dictionary/DictionaryClient.kt`, `openConnection` at the pre-fix `:46`) — default following.
4. `AppFacadeHost.httpGet` (`services/AppFacadeHost.kt`, **explicit `instanceFollowRedirects = true`** at the pre-fix `:53`) — the plugin facade claimed "only HTTPS (TLS) is allowed" and then followed a downgrading `Location` to cleartext.

An `https://` server answering `302/307 Location: http://…` therefore made each of these continue over plaintext. Already-fixed transports (re-verified, never re-broken): `PluginManifestFetcher.kt:98` + `HttpsPluginDownloadTransport.kt:62` route through `PinnedTlsConnector` (`=-false` at `:72`, phase-39/42) and their 3xx is refused outright; `WebDavSyncService.createConnection` (`:158`, phase-40) refuses all redirects; `WebPageFetcher` (`:37`, phase-51) and `HttpsTitleFetcher` (`:76`, phase-51) run manual per-hop loops.

## What changed (after) — `file:line`

### 1. New shared pure-JVM hop policy — `services/StrictRedirectPolicy.kt` (new)

- `checkTlsHop(uri)` (`:42`) — validates a hop we are **about to connect to** (the entry URL and every resolved redirect target): scheme MUST be `https` (`:43-47`), the URL MUST have a host (`:48-51`), and the host MUST pass the B1-NET-04 blocklist `SsrfHostPolicy.blockedReason` (`:52-55`). Throws `RedirectRefusedException` (`:35`, `IOException` subtype) on any violation.
- `resolveNextTlsHop(cur, location)` (`:65`) — RFC-3986-resolves a 3xx `Location` against the current hop; returns null for a blank `Location` (`:66`); refuses malformed targets (`:70`), redirect loops (resolves back to `cur`, `:72-74`) and then re-runs `checkTlsHop` on the resolved target (`:75`).
- `MAX_REDIRECTS = 5` (`:32`) — every loop caps at 5 hops.

### 2. `DuckDuckGoClient.kt`

- Constructor gained `connectionFactory: (String) -> HttpURLConnection` (default `URL(url).openConnection()`) (`:139-141`).
- `search` (`:144`) is now a manual loop: `checkTlsHop` per hop (`:155`), `instanceFollowRedirects = false` (`:163`), 3xx → `resolveNextTlsHop` (`:167-175`) with a typed error on a missing `Location`, hop cap at `MAX_REDIRECTS + 1` (`:151`); `RedirectRefusedException` is wrapped into the existing user-facing `DuckDuckGoSearchException` (`:194-197`).

### 3. `WeatherClient.kt` (`OpenMeteoClient`)

- Same constructor seam (`:65-67`).
- `get` (`:85`) is now a manual loop: `checkTlsHop` per hop (`:96`), `instanceFollowRedirects = false` (`:104`), 3xx → `resolveNextTlsHop` (`:108-116`), cap (`:92`), typed re-wrap (`:134-137`). 404-as-null and the oversized-response guard are preserved inside the loop.

### 4. `DictionaryClient.kt`

- Same constructor seam (`:44-46`).
- `lookup` (`:50`) is now a manual loop: `checkTlsHop` (`:61`), `instanceFollowRedirects = false` (`:69`), 3xx → `resolveNextTlsHop` (`:73-81`), cap (`:57`), typed re-wrap (`:102-105`). 404→null preserved.

### 5. `services/AppFacadeHost.kt`

- `httpGet` (`:53`) rewritten: the `url.startsWith("https://")` one-shot check is replaced by `StrictRedirectPolicy.checkTlsHop` run on the entry URL AND every hop inside a `MAX_REDIRECTS + 1` loop; `instanceFollowRedirects = false` (`:67`); a downgrading/blocked hop returns `FacadeResult.Failed("HTTP GET refused: …")` (`:99-100`); the explicit `instanceFollowRedirects = true` (`old :53`) is gone; overload-read the truncated guard `contentLengthLong`/`readBytes` cap is preserved (`:82-91`).
- Constructor gained the injectable `connectionFactory` for tests (`:29-31`).

### 6. `services/localsend/LocalSendSender.kt`

- `openConnection` (`:486`) now sets `https.instanceFollowRedirects = false` (`:512`) — the pinned payload connection never follows a 3xx (the receiving device's endpoints are built app-side; any redirect would only be a downgrade/forward of transfer bytes, and a 3xx now surfaces as a failed transfer instead). Unchanged behaviour for the existing 200-path.

### 7. Re-verified (no code change): already-non-following transports

`PinnedTlsConnector.kt:72` (plugin manifest + artifact download — phase-39/42), `WebDavSyncService.kt:158` (phase-40), `WebPageFetcher.kt:37` + `HttpsTitleFetcher.kt:76` (phase-51). All confirmed still `instanceFollowRedirects = false`, and source-pinned by the new test.

## Checksum / secrets handling

- No keys, passwords, salts or decrypted note content are logged, printed, or persisted anywhere in the new paths. Refusal messages contain only the policy reason / scheme / host-class text — never credentials and never request bodies. The WebDAV `Authorization` header is unaffected (still only ever attached on the origin-gated `createConnection` after `instanceFollowRedirects = false`).

## Verification

- New pure-JVM test class `app/src/test/java/com/authorss81/noteflow/B1Net05RedirectDowngradeTest.kt` — **28 tests** covering:
  - `StrictRedirectPolicy`: same-scheme https follow (absolute + relative, `:47`); https→http downgrade refused (`:77`); protocol-relative `//host` keeps the https scheme (RFC-3986 network-path ref) and is followed (`:95`); non-http(s) schemes refused (`:107`); redirect to every B1-NET-04 blocked host refused (`:119`); loops refused (`:134`); blank → null (`:144`); malformed refused (`:154`); `checkTlsHop` entry enforcement (http/ftp/file refused, blocked hosts refused, public https incl. `8.8.8.8` allowed, `:162`);
  - Per-transport (injected fake `HttpURLConnection`, no network): DDG refus-a 302 → `http://` without opening a second connection (`:192`), refuses a 307 to a blocked host (`:214`), follows a same-scheme https redirect to a 200 result (`:235`), refuses a loop (`:262`), refuses an http entry before any connection (`:282`), caps the chain at `MAX_REDIRECTS + 1` (`:302`);
  - Open-Meteo: refuses a 301 → `http://` (`:337`), follows a same-scheme https redirect to a parsed forecast (`:354`);
  - Dictionary: refuses a 302 → `http://` (`:378`), follows a same-scheme https redirect (`:401`), **degrades offline** (bundled `OfflineWordList`) when the online lookup is refused (`:424`);
  - App facade: `httpGet` refuses a 302 → `http://` (`:440`) and a blocked-host hop (`:457`), follows a same-scheme https redirect (`:474`), refuses an http entry before connecting (`:492`), caps the chain (`:504`);
  - Source pins: every base-app transport file contains `instanceFollowRedirects = false` (`:524`); the four downgradeable/facade transports route hops through `StrictRedirectPolicy` (`:550`); and a repo-wide scan proves **no `instanceFollowRedirects = true` assignment exists anywhere under `app/src/main`** (comment lines excluded, `:561`).
- `gradle :app:testDebugUnitTest` — **1103 tests green, 0 failures** (1075 pre-phase baseline + 28 new).
- `gradle :app:assembleDebug` — **green** (`app-debug.apk` on disk, ~173.6 MB range as in prior phases).
- `gradle assembleRelease` interop sanity: not run this phase (unchanged signing/config; prior phases built it green; the PROM/PROMPT verification list names only `testDebugUnitTest` + `assembleDebug`).

## Out of scope (documented, not fixed here)

- **`plugins/llm` `AssistantModelDownloader.kt:72`** (`instanceFollowRedirects = true`): this is the separate **non-base** downloadable-plugin module (`:plugins:llm`, `include(":plugins:llm")` in `settings.gradle.kts:27`; `:app` depends only on `:plugin-sdk`, `app/build.gradle.kts:131`). Its build is NOT covered by this phase's verification commands (`gradle :app:testDebugUnitTest` / `:app:assembleDebug`), so editing an unverifiable artifact was judged out of scope. Before that module ships it needs the same one-line treatment: `instanceFollowRedirects = false` (+ ideally a `StrictRedirectPolicy` hop loop on its model URL, which is operator-configured), mirroring phase-52. Same-finding instance, tracked in REPORT/phase-status so a later phase picks it up with the llm module build in scope.
- **Name-based DNS-rebinding egress** (already tracked as out-of-scope since phase-51; transport-level resolved-IP pinning is a future phase). The literal/IPv4/IPv6 blocklist re-run on every hop closes the redirect-vector; recursive DNS verification at connect time is out of this finding's scope.
- **`httpsOnly`/optional-HTTP allowances**: no transport in this phase has an "insecure allowed" mode, so `checkTlsHop` (https-only on every hop) loses nothing. `WebDavSyncService`'s explicit local-network HTTP opt-in is unchanged and source-pinned (redirects still refused there, so an `http://` opt-in host cannot be redirected *into* a different host either).
- **Other B1-NET findings** (`B1-NET-01/02/03/04/06/07/08/09`): untouched here — each is or will be its own phase.