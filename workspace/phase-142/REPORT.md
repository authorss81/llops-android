# Phase 142 report — Network/stream I/O: capped readers + idle guard + port-aware allow-lists

Status: **DONE** (review fixes applied 2026-08-18, see below).

## Findings fixed (all three OPEN findings closed)

### R2-B1N-01 (LOW) — LocalSend sender capped mid-stream reads
- `LocalSendSender.kt:264-266` register probe now reads `LocalSendBodyReadPolicy.readText(it, REGISTER_BODY_LIMIT)` instead of `readText().take(2048)`.
- `LocalSendSender.kt:454-464` both `inputStream` (success, `SUCCESS_BODY_LIMIT` 8192) and `errorStream` (`ERROR_BODY_LIMIT` 512) in `httpPost` are capped mid-stream.
- Shared bounded reader: new pure-JVM `services/localsend/LocalSendBodyReadPolicy.kt` — aborts with `ResponseTooLargeException` on the first window that crosses the cap, so the accumulating StringBuilder never exceeds `limit + READ_BUFFER_CHARS` (no truncate-after-slurp).
- Tests: `LocalSendBodyReadPolicyTest` (11).

### R2-B1P-04 (LOW) — `copyBounded` idle-progress bailout
- `BoundedStreamCopier.kt:44-59` adds the 16-consecutive-idle-reads bailout (`MAX_CONSECUTIVE_IDLE_READS = 16`), mirroring the phase-81 `AttachmentIngestPolicy` sibling (`++idleReads > 16 → IOException`). A contract-legal hostile ContentProvider stream returning 0 forever no longer hot-spins the IO thread.
- Tests: `BoundedStreamCopierTest` (9), incl. the zero-progress stream firing after exactly `MAX_CONSECUTIVE_IDLE_READS + 1` reads and an interleaved-zero-progress stream copying fully.

### R2-B1N-05 (INFO) — (scheme, host, effective-port) allow-lists
- New pure-JVM `services/HostPortAllowList.kt`: entries normalized to `(scheme, host, effective-port)` triples (reusing `WebDavHrefResolver.Origin`). Bare `host`/`host:port` entries default to `https://host:443` (TLS-only); full `http(s)://host[:port]` entries use their own scheme/effective port; unparseable entries → null (fail closed, additive-only — the old host-only entry form is retained and documented).
- `CompileTimePluginPinStore.kt:99-115` `isAllowedDownloadHost` now gates on the port-aware triple. The shared `isHostAllowListed` gateway (`:216`) delegates to `HostPortAllowList.matches`.
- `PluginManifestFetcher.kt:130` (`HttpsManifestTransport`) host gate; `PluginDownloader.kt:147` gate — both now scheme+host+port aware, refusing `https://<allowed-host>:8443/...`.
- Tests: `HostPortAllowListTest` (16), `CompileTimePluginPinStoreTest` (+1), `PluginDownloaderTest` (+1), `HttpsManifestTransportTest` (+1, real TLS server, proving refusal happens before any connection opens).

## Verification (review-fix state)

- `gradle :app:testDebugUnitTest` — **1954 tests, 0 failures / 0 errors / 0 skipped**.
- `gradle :app:assembleDebug --rerun-tasks` — **BUILD SUCCESSFUL** (57/57 tasks executed). The first plain invocation hit the documented transient packaging flake; a clean `--rerun-tasks` run passed.

## Review findings (2026-08-18) — applied

The initial phase-142 commit (`269847b`) shipped with 3 RED unit tests; all were fixed:

1. **F2 (code defect, `WebDavHrefResolver.originOf`)**: `URL("https:///no-host")` parses with an EMPTY host (`url.host == ""`), which the `?: throw` null-check let through, so `originOfOrNull` returned a non-null `Origin("https","",443)` instead of null. Fixed at `WebDavHrefResolver.kt:50-58`: an http(s)-scheme check plus a `.isBlank()` host check now make any empty-host / non-http(s) URL throw `IllegalArgumentException`, so `originOfOrNull` (and thus `HostPortAllowList.normalizeEntry`/`matches`) fails closed. Before: `normalizeEntry("https:///no-host")` → `Origin(https,"",443)` (test RED). After: `null`.
2. **F3 (same test, latent)**: `normalizeEntry("ftp://host:21")` returned a non-null `Origin("ftp","host",21)` because `effectivePort` returned the explicit port before any scheme check; the same scheme gate in `originOf` now rejects it (it was previously masked by the first assertion failing first).
3. **F4 (test defect, `LocalSendBodyReadPolicyTest.kt:95`)**: the slurp-pin regex `\.take\s*\(\d+\)` over-matched legitimate list-chunking calls (`batches.take(40)`, `octets.take(3)`) in `LocalSendSender`. Now it only bans the slurp-then-truncate shape `readText(...).take(n)`.
4. **F5 (test defect, `BoundedStreamCopierTest` InterleavedZeroInputStream)**: the fixture returned 0 FOREVER once `pos` hit an 8192 boundary (a zero read never advanced pos), so the idle-bailout fired and the interleaving case was never exercised. Now one zero-read per boundary is emitted via a `pendingZeroRead` flag and the stream keeps progressing.
5. **F6**: this REPORT.md (was missing).
6. **F7**: `docs/phase-status.md` row 142 updated from `NOT STARTED` → `DONE`; ARCHITECTURE.md "Implemented in phase-142" note appended.
7. **F8**: missing final newlines added to `HostPortAllowList.kt` / `LocalSendBodyReadPolicy.kt` (`.editorconfig` `insert_final_newline`).
8. **F9**: dead `removePrefix("https://").removePrefix("http://")` in the no-`://` bare-host branch of `HostPortAllowList.normalizeEntry` removed (unreachable by construction).

## Constraints honored

No DB schema change, no `.github/workflows/` edits, no new dependencies. Plugin fail-closed pin posture unchanged (allow-list is permissive-additive only). No keys, passwords, or decrypted note content logged.