# Phase 94 — B2-LOG-05 (LOW): WebDAV failure paths echo raw exception text (and pasted credentials) into the sync-status UI

**Finding** (`docs/security-report.md`, row `B2-LOG-05 | LOW | phase-94`):
WebDAV failure paths in `WebDavSyncService.kt` built user-facing `SyncResult`
messages from raw exception text, and `WebDavSyncDialog.kt` rendered them
verbatim as the sync status. The concrete leaks:

- `validateServerUrl` malformed-URL catch: `"Invalid WebDAV server URL: ${e.message}"`
  (`WebDavSyncService.kt:64-70`) — the JVM `MalformedURLException` message dumps
  the user's RAW input. A paste like `https://user:pass@…` would export its
  credentials through the exception text straight into the dialog.
- connect / upload / download blanket catches:
  `"Connection failed: ${e.localizedMessage ?: e.message}"` etc.
  (`:189`, `:232`, `:299`) — a server-offered or resolver-produced exception
  message can carry the full server URL and arbitrary text.
- the resolver-defusal catch embedded `e.message` (`:314`) and the origin-gate
  IllegalState embedded `e.message` (`:326`).
- the too-large catch returned `e.message` directly (`:366`).

The dialog rendered `res.message` / `failureMessage` with no scrubbing at three
sites (`WebDavSyncDialog.kt:201`, `:246`, plus the success-path render).

## What changed

### 1. New pure-JVM decision table — `app/.../services/WebDavFailurePolicy.kt`

The phase-71 B2-LOG-03 pattern (`services/FailureLogPolicy.kt` — "never read the
exception object") applied to **user-facing UI text**. All API-26+ pure JVM,
no new deps:

- Fixed category constants — `CONNECT_FAILURE_MESSAGE`,
  `UPLOAD_FAILURE_MESSAGE`, `DOWNLOAD_FAILURE_MESSAGE`,
  `TOO_LARGE_DOWNLOAD_MESSAGE`, `INVALID_URL_MESSAGE`. Human, stable, no
  interpolation points.
- `stripUrlUserInfo(url)` — regex `([A-Za-z][A-Za-z0-9+.-]*://)[^/@\s]*@`
  drops any `scheme://<userinfo>@` prefix. Regex-based (not `java.net.URI`
  parsing) so sanitizing can never itself throw on hostile input.
- `scrubForDisplay(text)` — defense-in-depth display sanitizer: strips userinfo
  **and** collapses `scheme://host/path` → `host/...` (path replaced by `...`;
  the host is the user's own configuration, never a secret).
- `configFailureText(e, fallback)` — the ONLY place a message is read:
  `scrubForDisplay(e.message.orEmpty())`, blank → the fixed fallback.
- `refusalReason(e)` — maps resolver-defusal messages to FIXED tokens
  (`network path` / `outside the configured server` / `not HTTPS` /
  `malformed/unusable` / `unexpected`). Never echoes the href or the URL.

### 2. Sink fixes — `WebDavSyncService.kt`

- `validateServerUrl` malformed catch: `throw IllegalArgumentException(
  WebDavFailurePolicy.INVALID_URL_MESSAGE)` — the raw-paste echo is gone.
- `validateServerUrl` returns `WebDavFailurePolicy.stripUrlUserInfo(...)`, so a
  pasted `user:pass@` never survives into the stored/echoed URL (embedded
  credentials belong ONLY in the username/password fields → Authorization header).
- `testAndPrepareConnection`: IllegalArgument catch → `configFailureText(e,
  INVALID_URL_MESSAGE)`; blanket catch → `CONNECT_FAILURE_MESSAGE` (the
  `"Connection failed: ${e.localizedMessage ?: e.message}"` line is gone).
- `uploadEncryptedVault`: IllegalArgument → `configFailureText(...)`; blanket →
  `UPLOAD_FAILURE_MESSAGE`.
- `downloadLatestEncryptedVault`: resolver IllegalArgument →
  `"Sync refused: ${WebDavFailurePolicy.refusalReason(e)}"`; origin-gate
  IllegalState → fixed `"Sync refused: the app will not connect to a host other
  than your configured WebDAV server."` (the `${e.message}` suffix is gone);
  too-large → `TOO_LARGE_DOWNLOAD_MESSAGE`; blanket → `DOWNLOAD_FAILURE_MESSAGE`.

Grep-pinned in the test: no `localizedMessage`, no `${e.message}`, no
`e.message ?:` left anywhere in the file.

### 3. Render-side defense in depth — `WebDavSyncDialog.kt`

All three status renders route through `WebDavFailurePolicy.scrubForDisplay(...)`
(upload `res.message`, restore `failureMessage`, download `res.message`), so
even a future sink can't put raw exception/URL text in front of the user.

Auth (401) + HTTP-status failures were already fixed strings and are untouched.

## Verification

- WebDAV families first: `B2Log05WebDavFailureTextTest` + existing
  `WebDavSyncServiceTest`, `WebDavHrefResolverTest`,
  `B1Net07WebDavDownloadPolicyTest` — green (`BUILD SUCCESSFUL`).
- `gradle testDebugUnitTest` (full suite): **1619 tests, 0 failures, 0 errors,
  0 skipped** (`BUILD SUCCESSFUL`).
- `gradle assembleDebug`: `BUILD SUCCESSFUL` (the noted "Unable to strip…"
  native-lib packaging message is the long-standing pre-existing one, not an
  error).

## New test file

`app/src/test/java/com/authorss81/noteflow/B2Log05WebDavFailureTextTest.kt`
(13 tests), pure JVM, three layers:

Sanitizer table (`WebDavFailurePolicy`):
- `stripUrlUserInfo` strips `user:pass@` from an `https://` URL, from the
  http-opt-in local URL, and keeps a userinfo-free URL byte-identical;
- `scrubForDisplay` strips userinfo AND collapses `scheme://host/path` to
  `host/...`, and leaves plain prose untouched;
- `configFailureText` returns the scrubbed message or the fixed fallback when
  blank;
- `refusalReason` classifies the resolver's defusal categories to FIXED tokens
  and never lets an href/URL text survive.

Fixed-text contract:
- the five category constants are equal to their documented strings;
- the policy source never interpolates (`"\${"` absent);
- the fixed constants never appear as raw strings inside the service;
- the malformed-URL message (which legitimately mentions `cloud.example.com` as
  a documentation example) carries no userinfo — asserted by searching for
  `@cloud` / `://`-with-credentials, not the example host.

Behavioral end-to-end:
- a connect failure whose exception message embeds `user:pass@` and a URL yields
  the FIXED `CONNECT_FAILURE_MESSAGE` with a scrubbed text free of every secret
  token;
- the dialog's rendered statuses pass through `scrubForDisplay`.

Source pins:
- `WebDavSyncService.kt` and `WebDavSyncDialog.kt` contain no `localizedMessage`,
  no `${e.message}`, no bare `res.message`-assign;
- `WebDavFailurePolicy.kt` has exactly 2 CODE reads of `e.message`
  (`configFailureText` + `refusalReason`), both only through `scrubForDisplay`
  (the KDoc documents the pre-fix pattern with `<e.message>` placeholders).

## Test-iteration notes (test bugs, not code bugs)

Two assertions were wrong in the first draft and were corrected without touching
production behavior:

1. `configFailureText` expected `host/...` but the fixed fallback was prefixed —
   expected value corrected to `"Malformed URL: host/..."`.
2. The malformed-URL secret assertions used `cloud.example.com` — which is a
   legit example INSIDE the fixed `INVALID_URL_MESSAGE` — so they matched even
   on a clean build. Changed to `@cloud` / credential-bearing `://` patterns.
3. The `e.message` source-pin regex matched KDoc `<e.message>` placeholders; now
   scoped to the executable `e.message.orEmpty()` reads (exactly 2).

## Checksums / secrets

- No secrets, keys, passwords, or decrypted note content were logged, printed,
  or committed. Credentials now exist ONLY in the username/password fields and
  the `Authorization` header; the stored URL is userinfo-stripped and every
  render surface scrubbed.
- `allowBackup="false"`, `data_extraction_rules.xml`, FLAG_SECURE untouched.
- No new permissions; `INTERNET` usage unchanged.

## API floor (AGENTS.md hardware reality)

`WebDavFailurePolicy` is pure JVM (two regexes + string ops), API 26+ floor, runs
on every device tier. No fallback needed; no new dependencies;
`app/build.gradle.kts` untouched.

## Out of scope (deliberately not fixed here)

- **B2-LOG-06 (INFO, positive)** — no telemetry/crash SDK in the base APK;
  unaffected, no independent fix.
- The stored URL remains visible (with userinfo stripped) in the dialog's
  credential form — that is the user's own configuration, not a leak.
- The upload PUT / download GET success paths were not touched (only failures
  are in scope for this finding).
- No DB schema change, no migration, no `.github/workflows/` edit, no new
  dependencies.
