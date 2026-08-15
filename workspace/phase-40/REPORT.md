# Phase 40 — B1-NET-01: WebDAV server-controlled PROPFIND `href` SSRF / credential exfiltration

- **Status:** DONE
- **Finding:** `B1-NET-01` (HIGH) — see `docs/security-report.md` (row flipped to `FIXED` at line 814).
- **Commit implementing the fix:** `4165931` (2026-08-15).
- **Test fidelity note:** this REPORT exists because the initial phase-40 commit
  (`4165931`) shipped the code + tests but NOT the PROMPT-mandated deliverable; it was
  reconstructed during the phase-40 review pass along with the doc status updates
  (`docs/security-report.md:814`, `docs/phase-status.md` phase-40 row, `docs/ARCHITECTURE.md`).

## What changed (before → after, `file:line`)

### 1. `app/src/main/kotlin/com/authorss81/noteflow/services/WebDavHrefResolver.kt` (NEW)

Origin resolver, pure `java.net` so it is unit-testable on the JVM:

- `originOf(url)` :`44` — parses a URL and returns a normalized `Origin(scheme, host, port)`:
  scheme + host lowercased, host trailing-dot stripped, default port materialized
  (`https`→443, `http`→80) so `https://host` ≡ `https://host:443`. Throws
  `IllegalArgumentException` on malformed input or a non-http(s) scheme.
- `sameOrigin(a, b)` :`54`.
- `requireConfiguredServerOrigin(urlString, configuredServerUrl)` :`65` — throws
  `IllegalStateException` when the two normalized origins differ. Called from
  `WebDavSyncService.createConnection` for EVERY connection (PROPFIND, MKCOL, PUT, GET);
  note the Basic `Authorization` header is only attached *after* this gate at
  `WebDavSyncService.kt:147` gate → `:164` header.
- `resolveDownloadHref(serverBaseUrl, requestUrl, href)` :`93` — the PROPFIND-href path:
  - rejects empty, `//`-prefixed (network-path), malformed, and non-HTTP(S) hrefs;
  - resolves relative hrefs (RFC 3986) against the PROPFIND `requestUrl`;
  - rejects any href whose final origin (`scheme+host+port`) differs from the configured
    server — so an absolute `https://attacker.example/…`, an `http://169.254.169.254/…`
    (even with the local-network `allowInsecureHttp` opt-in), and a same-host cleartext
    downgrade are all refused. Dot-segments are NOT separately rejected: RFC 3986
    resolution normalizes them for relative hrefs and the same-origin gate can never be
    escaped by a path, so only the origin gate bounds the download target (rejecting
    in-origin `..` paths would false-break legitimate servers — see review finding 5).

### 2. `app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt` (MODIFIED, +39/-3)

- `createConnection` `:140-169`:
  - `:147` **new** `WebDavHrefResolver.requireConfiguredServerOrigin(urlString, config.serverUrl)` —
    closes the "Basic header attached to ANY host" evidence (`:151-153` pre-fix). The header at
    `:164` is now unreachable for any off-origin host.
  - `:158` **new** `conn.instanceFollowRedirects = false` — 3xx responses surface as their HTTP
    status and fail the sync; credentials/backup bytes are never forwarded to a redirect hop
    (closes the WebDAV slice of B1-NET-05 as well).
- `downloadLatestEncryptedVault` `:287-304` — **before:** `if (latestRemotePath.startsWith("http")) latestRemotePath else build from config` and connect — i.e. the server-supplied host used verbatim. **after:** every href goes through `WebDavHrefResolver.resolveDownloadHref` against `config.serverUrl`; an off-origin link returns
  `SyncResult(false, "Sync refused: the server returned a link that points outside your configured WebDAV server. …")` before any connection. The connection-time backstop gate
  (`createConnection`) is also wrapped to surface the same logical failure through the same
  friendly "Sync refused" channel, and a `3xx` download response now produces an explicit
  non-alarming message ("For your security the app never follows redirects…").

### 3. `app/src/test/java/com/authorss81/noteflow/WebDavHrefResolverTest.kt` (NEW, 19 tests)

Off-origin / private-IP / port-mismatch / scheme-downgrade / non-HTTP hrefs rejected;
same-origin + root-relative + bare-filename + default-`:443` + case/trailing-dot
normalization accepted; in-origin dot-segment paths accepted (must NOT be false-rejected),
off-origin dot-segment hrefs still rejected; `requireConfiguredServerOrigin` cold accept/
reject; two end-to-end PROPFIND-XML cases extracting hrefs with the same regex the service
uses.

## Security posture / checksum & secrets handling

- No keys, passwords, or decrypted note content are logged or added anywhere; the only
  change touching credentials is making where `Authorization: Basic` can be attached
  narrower (config-origin-only).
- No DB schema change, no migration. No new dependencies (JDK only — safe on minSdk 26).
- `.github/workflows/` untouched. `allowBackup="false"`, `ClipboardGuard`, FLAG_SECURE intact.

## Verification

Run on CI Linux runner (system gradle 8.13 / JDK 17 after the fix landed):

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.WebDavHrefResolverTest"` —
  **BUILD SUCCESSFUL** (all 19 resolver tests green).
- `gradle :app:testDebugUnitTest` — 902 tests completed, 2 failed:
  - `WikiLinkParserCacheUnitTest#a cancelled scan …` and
    `PluginUpdateEngineTest#a hash mismatch on the downloaded artifact is never applied`.
  - Both re-run **green in isolation** (flaky timing/cancel cases: `gradle :app:testDebugUnitTest
    --tests "...WikiLinkParserCacheUnitTest" --tests "...PluginUpdateEngineTest"` → BUILD SUCCESSFUL).
  - Both classes are untouched by this diff — pre-existing/environmental, not a regression.
  - A repeated full-suite run (same day) showed only `PluginUpdateEngineTest` failing (1) and
    `WikiLinkParserCacheUnitTest` passing — confirming the same two flaky cases vary per run
    rather than depending on this diff.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (first invocation hit a transient
  concurrent-daemon failure; the clean re-run was green, final state: `UP-TO-DATE` success).

## Review follow-up (this pass)

Resolved from the phase-40 review findings:

1. This `REPORT.md` created (was missing) — the PROMPT's definition-of-done deliverable.
2. `docs/security-report.md:814` — B1-NET-01 row flipped from `NOT STARTED (planned)` to
   `FIXED` + commit + REPORT pointer.
3. `docs/phase-status.md` — phase-40 row added; cross-cutting `.done` inventory updated
   (`phase-35`..`phase-40`).
4. `docs/ARCHITECTURE.md` — "WebDAV sync" anchor extended with an "Implemented in phase-40" note.
5. `WebDavHrefResolver.kt` — redundant dot-segment rejection removed (origin gate already
   bounds the host); in-origin `..` hrefs no longer false-rejected; tests added.
6. `WebDavSyncService.kt` — connection-time backstop gate now surfaces through the same
   friendly "Sync refused" message as the resolver (consistent exception handling).
7. Test-coverage caveat (end-to-end "no Basic header on a non-configured host"): not
   practicable in the repo's pure-JVM layout without pulling `android.util.Base64` into a
   seam; the ordering invariant (origin gate strictly before header attach,
   `WebDavSyncService.kt:147` < `:164`) is the enforcement. Documented, not refactored.
8. Explicit non-alarming 3xx message added for the download path.

## Out of scope (left to their own phases)

- B1-NET-02 (LocalSend pairing, phase-41), B1-NET-03 (plugin manifest trust, phase-42),
  B1-NET-05 non-WebDAV transports (phase-52), B1-NET-07 (size cap / timestamp ordering /
  `remoteFolderName` URL-encoding) — separate findings, separate phases. The PROMPT
  constraint "do not fix OTHER security findings in this phase" was respected.