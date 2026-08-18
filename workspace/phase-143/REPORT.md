# Phase 143 report — Web Capture HTTPS-only: default bare/https input, cleartext only with an explicit per-fetch opt-in

Status: **DONE** (review-fix commit `53befcb` appended: checkbox detection now shares the policy's scheme parser via `WebPageFetchPolicy.namesHttpScheme`, the opt-in is strictly per-fetch, and KDoc/REPORT parity claims were corrected).

## Finding fixed

### R2-B1N-04 (LOW) — Web Capture still fetched plain-HTTP entry URLs over cleartext
Web Capture is now HTTPS-by-default. A plain `http://` entry or an http redirect is
refused unless the user explicitly opts in per-fetch, reusing the WebDAV
`allowInsecureHttp` UX (`WebDavSyncService.kt:134-142`). **One intentional
difference from WebDAV:** WebDAV confines cleartext to local-network hosts, whereas
Web Capture's per-fetch opt-in applies to any host — the `SsrfHostPolicy` blocklist
still refuses loopback/private/link-local/metadata/.local destinations either way.

**Before** (`app/src/main/kotlin/com/authorss81/noteflow/plugins/webcapture/WebPageFetchPolicy.kt:18`):
```kotlin
private val ALLOWED_SCHEMES = setOf("http", "https")
```
`validateUrl` accepted `http://` and `rejectHop` allowed http hops; `WebPageFetcher.kt:22-53`
fetched them over cleartext — on open Wi-Fi an on-path attacker could rewrite the body the
Markdown was derived from.

**After** (`WebPageFetchPolicy.kt`):
```kotlin
private const val HTTPS_SCHEME = "https"
private const val HTTP_SCHEME = "http"

// R2-B1N-04 (phase-143): HTTPS-by-default. Clear-text http is NOT in the
// allow-list; it is tolerated only with an explicit per-fetch opt-in
// ([allowInsecureHttp]), mirroring WebDAV's `allowInsecureHttp` model.
private val ALLOWED_SCHEMES = setOf(HTTPS_SCHEME)

fun validateUrl(input: String, allowInsecureHttp: Boolean = false): Either   // http refused by default
fun rejectHop(absoluteUrl: String, allowInsecureHttp: Boolean = false): String?
```

- `ALLOWED_SCHEMES = { "https" }` — an explicit `http://` entry/redirect is refused with
  `INSECURE_HTTP_REFUSED_MESSAGE` unless `allowInsecureHttp = true` for that one fetch.
- Bare/host-only input still defaults to `https://` (unchanged behaviour, now the ONLY
  non-scheme path).
- `schemeAllowed(scheme, allowInsecureHttp)` is the single gate shared by entry + hops, so a
  redirect can never widen the entry policy.
- `WebPageFetcher.fetch(url, allowInsecureHttp)` threads the opt-in to the per-hop re-validation
  (`WebPageFetcher.kt:28`, `:49`) — an https→http redirect downgrade stays refused by default.
- `WebCapturePlugin.captureWebPage(…, allowInsecureHttp: Boolean = false)` (interface
  `NoteflowPlugin.kt`) + `WebCaptureEngine.captureWebPage`, + `NoteflowViewModel.captureWebPage`
  thread the flag through.
- UI: `WebCaptureDialog` (HomeScreen.kt) shows, only when the entered address
  names the `http` scheme, the same warning + checkbox as the WebDAV dialog
  ("HTTP is insecure — this page is
  fetched over cleartext and could be altered in transit. Tick to allow this ONE-TIME cleartext
  fetch."). The checkbox's visibility is driven by the NEW pure-JVM
  `WebPageFetchPolicy.namesHttpScheme` (same scheme regex the policy uses), so
  the UI can never disagree with `validateUrl` (this also covers
  non-`http://`-looking spellings such as `http:example.com`). The checkbox is
  per-fetch, never persisted, and is cleared as soon as the address is edited
  away from an http scheme (e.g. `http://` → `https://`); the dialog otherwise
  fetches over https only.

## Tests

New/updated pure-JVM tests (all four verification bullets covered):

| Case | Test | Evidence |
|---|---|---|
| `http://` entry refused by default | `WebCaptureExtractorTest.plain http url is refused by default` (`:112-116`); `B1Net04SsrfBlocklistTest.validateUrl refuses plain http by default unless opted in` (`:150-158`) | `validateUrl("http://example.com")` → `Either.Error(INSECURE_HTTP_REFUSED_MESSAGE)` |
| bare host input defaults to https | `validateUrl normalizes a bare host to https for the fetcher` (`B1Net04SsrfBlocklistTest.kt:133-137`) and `bare hostnames are normalized to https` (`WebCaptureExtractorTest.kt:100-104`) — both pre-existing, still green | `validateUrl("example.com/path")` → `url = "https://example.com/path"`, `scheme = "https"` |
| explicit per-fetch opt-in allows cleartext | `WebCaptureExtractorTest.plain http url is accepted with the explicit per-fetch opt-in` (`:119-123`) | `validateUrl("http://example.com", allowInsecureHttp = true)` → Valid, `scheme = "http"` |
| https→http redirect hop refused | `B1Net04SsrfBlocklistTest.an https-to-http redirect hop is refused by default but allowed with the opt-in` (`:188-198`) | `rejectHop("http://8.8.8.8/", false)` → error; `rejectHop("http://8.8.8.8/", true)` → null |
| checkbox/"http scheme" detection matches the policy | `WebCaptureExtractorTest.namesHttpScheme mirrors the policy scheme extraction` (`:126-133`) | `namesHttpScheme("http://…") == true` exactly when `validateUrl` treats the input as needing the opt-in (https/bare/ftp → false) |

`gradle testDebugUnitTest` — **2008 tests, 0 failures / 0 errors / 0 skipped**
(app: 1958 / 0 / 0 / 0 in 177 suites; plugins:llm: 50 / 0 / 0 / 0).

`gradle assembleDebug` — **BUILD SUCCESSFUL** (debug APK `app/build/outputs/apk/debug/app-debug.apk`).
The first plain invocation hit the known transient DexArchiveMerger packaging flake (documented in
phase-142 too); the immediate re-run completed with all tasks up-to-date and the APK produced.

## Constraints honored

- No DB schema change, no `.github/workflows/` edits, no new dependencies.
- WebDAV `allowInsecureHttp` model untouched — only its UX was reused (per-fetch, non-persisted).
- No keys, passwords, or decrypted note content logged; `allowBackup="false"` untouched.
- No other findings fixed in this phase; no new bugs introduced (none observed).

## Docs updated

- `docs/security-report-round2.md`: R2-B1N-04 status row + detail marked **FIXED in phase 143**.
- `docs/phase-status.md`: phase-143 row → `DONE`.
- `docs/ARCHITECTURE.md`: "Implemented in phase-143" note on the Web Capture section.
