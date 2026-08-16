# Phase 80 — B2-DOS-04 (MEDIUM) — `AppFacadeHost.httpGet` buffering is unbounded during the read

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-04, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before** (`services/AppFacadeHost.kt`, pre-fix `httpGet`):

- The body read was `connection.inputStream.use { stream -> val bytes = stream.readBytes(); if (bytes.size >
  MAX_FACADE_GET_BYTES) { return FacadeResult.Failed(...) }; bytes.toString(UTF_8) }` (old `:86-92`) — the Kotlin
  `readBytes()` slurps the ENTIRE response into an unbounded `ByteArrayOutputStream` in heap *before* the 10 MB cap
  comparison ever runs.
- The only pre-read check used `HttpURLConnection.contentLengthLong` (old `:82-85`), which is **-1 for every
  chunked / unknown-length response** — so it was routinely skipped.
- A granted plugin (the trust anchor for plugins is attacker-controllable per B1-AUTH-01/B1-NET-03) pointing at a
  slow-chunked endpoint therefore accumulated output with ZERO cap enforcement for as long as the server dripped
  data — a single call pinned hundreds of MB in heap and OOM'd the process. Redirect chains had the same unbounded
  read on the final hop (per-hop budget only re-checked after a full slurp).
- (The finding also cited `instanceFollowRedirects = true` — that slice was already fixed by phase-52
  (B1-NET-05): `AppFacadeHost.kt:67` sets `instanceFollowRedirects = false` and every 3xx hop is re-validated /
  followed manually through `StrictRedirectPolicy`. Verified retained; the remaining work here is the in-read cap.)

**After:**

- **Cap enforced DURING the read**: the new pure-JVM `services/FacadeHttpGetPolicy.kt` is the single decision
  table — `MAX_FACADE_GET_BYTES = 10 MB`, `READ_BUFFER_BYTES = 64 KiB`, `readCapped(InputStream): String`.
  It mirrors `WebPageFetcher.readCapped` (`plugins/webcapture/WebPageFetcher.kt:71-89`): a bounded streaming loop
  over a fixed 64 KiB buffer whose running `total` is compared against the cap on EVERY chunk, throwing
  `ResponseTooLargeException` mid-stream on the first read that crosses it. The heap accumulator can therefore
  never exceed the budget, plus at most one read buffer of over-read.
- **Every body read routes through it** (`AppFacadeHost.kt:91-93`): `val body = connection.inputStream.use {
  stream -> FacadeHttpGetPolicy.readCapped(stream) }`. The dead `bytes.size > MAX` post-check on `readBytes()` and
  the private `MAX_FACADE_GET_BYTES` companion are deleted; a `catch
  (e: FacadeHttpGetPolicy.ResponseTooLargeException)` branch (`:100-101`) surfaces the truthful
  `"HTTP GET response too large."` message (the generic `Throwable` catch no longer masks it).
- **Header pre-check retained as early exit** (`AppFacadeHost.kt:82-85`): when Content-Length IS known and over
  budget, the request is refused before the body stream is even opened.
- **Per-hop budget**: the B1-NET-05 manual-redirect posture (`instanceFollowRedirects = false` `:67`, each 3xx
  resolved + re-validated via `StrictRedirectPolicy.resolveNextTlsHop` `:71-76`) is retained, and since every 200
  hop's body goes through `readCapped`, each redirect hop enforces its OWN 10 MB budget — a `3xx` → over-cap final
  hop aborts at that hop.
- **API 26+ floor**: the fix is pure `java.io`/`java.lang` (`InputStream.read`, `ByteArrayOutputStream`,
  `Arrays.fill` in the test) — identical on every supported API, no newer-API requirement, no fallback needed
  (AGENTS.md hardware-reality rule satisfied with a fallback-free pure-JVM change).

## File:line evidence (commit before/after)

| Site | Before | After |
|---|---|---|
| `services/AppFacadeHost.kt` body read | `:86-92` `stream.readBytes(); if (bytes.size > MAX_FACADE_GET_BYTES) ...` — whole body in heap first | `:91-93` `connection.inputStream.use { stream -> FacadeHttpGetPolicy.readCapped(stream) }` — capacity enforced during the read |
| `services/AppFacadeHost.kt` size checks | `:82-85` `contentLengthLong` header pre-check (skipped for chunked, -1) + `:88-90` post-`readBytes()` size check (too late) | `:82-85` header pre-check retained (early exit for known-large) + `:100-101` `catch (FacadeHttpGetPolicy.ResponseTooLargeException)` after `readCapped` |
| `services/AppFacadeHost.kt` constant | `:117-119` private `companion object { const val MAX_FACADE_GET_BYTES }` | deleted — moved to the policy (`FacadeHttpGetPolicy.kt`) |
| `services/AppFacadeHost.kt` redirects | `:67` `instanceFollowRedirects = false` + `:71-76` per-hop `StrictRedirectPolicy` re-validation (B1-NET-05, already shipped in phase-52) | unchanged — verified retained; per-hop 10 MB budget now applies per 200 hop via `readCapped` |
| `services/FacadeHttpGetPolicy.kt` | — | NEW pure-JVM decision table: `MAX_FACADE_GET_BYTES` (10 MB), `READ_BUFFER_BYTES` (64 KiB), `readCapped` bounded streaming loop (abort mid-stream, `ResponseTooLargeException`), mirror of `WebPageFetcher.readCapped` |
| `plugins/webcapture/WebPageFetcher.kt` | — | referenced as the mirror (`:71-89`, unchanged) |

## Checksums / secrets handling

- No keys, passwords, or decrypted note content are logged; no new `INTERNET` usage. `allowBackup=false` +
  `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE untouched. The facade never touches the DB, keystore,
  `EncryptionService`, or decrypted-content handles (deny-by-default host), so no secret path interacts with this
  change.
- **No DB schema change, no migration required** — the facade is a transient network-side read, nothing persisted.
- No new dependencies (the policy is pure JDK `java.io`). `.github/workflows/` untouched. No
  `gradle/verification-metadata.xml` change (no new artifacts resolved).

## Verification

- `gradle testDebugUnitTest` — **1436 tests, 0 failures** (all 132 test classes green; 7 new
  `B2Dos04FacadeGetStreamingCapTest` + the 29 `B1Net05RedirectDowngradeTest` cases pass. Note: the 2
  pre-existing `B1Plat01ReleaseSigningTest` asserts, documented since phase-55 as the only failures, are now GREEN
  because the `b9a0b52` CI repair commit wired the release keystore/`docs/RELEASE.md` — no longer a pre-existing
  failure to report).
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (57 tasks; debug APK 173,760,926 bytes, SHA-256
  `bc94479c0f10fefea0abd19567d663f151884598df2382b3d14f45070f78ead2`).

New tests (`app/src/test/java/com/authorss81/noteflow/B2Dos04FacadeGetStreamingCapTest.kt`, 7):

1. `slow-chunked over-cap body aborts mid-read without exceeding the heap budget` — proves the finding's exploit
   is closed: Content-Length = -1 (chunked), body drips 1 KiB at a time past the cap; asserts `yielded < total`
   (never drained the stream — catches a `readBytes()` regression) AND `yielded <= MAX + READ_BUFFER_BYTES`
   (abort at the budget boundary).
2. `body equal to the cap exactly is acceptable` — boundary check that `total > cap` (not `>=`) governs.
3. `a small response still round-trips` — non-regression of the happy path.
4. `an over-cap Content-Length header is refused before the stream is opened` — the early header pre-check.
5. `a redirect chain enforces the budget on every hop` — 3xx → over-cap final hop aborts at that hop (per-hop budget).
6. `AppFacadeHost no longer slurps the body with readBytes` — source pin: `.readBytes()` is gone from
   `AppFacadeHost.kt`, body reads route through `FacadeHttpGetPolicy.readCapped`, `instanceFollowRedirects =
   false` + `StrictRedirectPolicy` retained.
7. `the policy enforces the cap during the streaming loop` — source pin on `FacadeHttpGetPolicy.kt`:
   `if (total > MAX_FACADE_GET_BYTES)` inside the loop, fixed buffer, 10 MB constant, typed
   `ResponseTooLargeException`.

## Out-of-scope (documented, not fixed here)

- The OTHER B2-DOS findings (05 import slurp, 06 PSD layer buffering, 07 backup in-heap, 08 PROPFIND unbounded
  read, 09 RDP recursion) are separate phases (81-83, 86, and later) — not touched here.
- The plugin-update `plugins/llm` `AssistantModelDownloader.kt` transport is a separate downloadable-module
  artifact (phase-52 already noted it needs the same one-line `instanceFollowRedirects = false` treatment before
  that module ships); it is outside this phase's scope and verification commands.