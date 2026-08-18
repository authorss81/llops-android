# Phase 143 report — Web Capture HTTPS-only: default bare/https input, cleartext only with an explicit per-fetch opt-in

Status: **DONE**.

## Finding fixed

### R2-B1N-04 (LOW) — Web Capture still fetched plain-HTTP entry URLs over cleartext
Web Capture is now HTTPS-by-default. A plain `http://` entry or an http redirect is
refused unless the user explicitly opts in per-fetch, using the WebDAV
`allowInsecureHttp` model (`WebDavSyncService.kt:134-142`).

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
- UI: `WebCaptureDialog` (HomeScreen.kt) shows, only when the entered address is a plain
  `http://`, the same warning + checkbox as the WebDAV dialog ("HTTP is insecure — this page is
  fetched over cleartext and could be altered in transit. Tick to allow this ONE-TIME cleartext
  fetch."). The checkbox is per-fetch, never persisted, and the dialog otherwise fetches over
  https only.

## Tests

New/updated pure-JVM tests (all four verification bullets covered):

| Case | Test | Evidence |
|---|---|---|
| `http://` entry refused by default | `WebCaptureExtractorTest.plain http url is refused by default` (`:112-118`); `B1Net04SsrfBlocklistTest.validateUrl refuses plain http by default unless opted in` (`:150-158`) | `validateUrl("http://example.com")` → `Either.Error(INSECURE_HTTP_REFUSED_MESSAGE)` |
| bare host input defaults to https | `B1Net04SsrfBlocklistTest.validateUrl normalizes a bare host to https for the fetcher` (`:133-137`); `WebCaptureExtractorTest.bare hostnames are normalized to https` (`:100-104`) | `validateUrl("example.com/path")` → `url = "https://example.com/path"`, `scheme = "https"` |
| explicit per-fetch opt-in allows cleartext | `WebCaptureExtractorTest.plain http url is accepted with the explicit per-fetch opt-in` (`:120-124`) | `validateUrl("http://example.com", allowInsecureHttp = true)` → Valid, `scheme = "http"` |
| https→http redirect hop refused | `B1Net04SsrfBlocklistTest.an https-to-http redirect hop is refused by default but allowed with the opt-in` (`:182-199`) | `rejectHop("http://8.8.8.8/", false)` → error; `rejectHop("http://8.8.8.8/", true)` → null |

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
