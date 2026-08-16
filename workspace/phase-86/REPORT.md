# Phase 86 — B1-NET-07 (LOW) — WebDAV download: no size cap, "latest" chosen by XML order, `remoteFolderName` URL-unsafe

2026-08-16 · finding source: `docs/security-report.md` B1-NET-07, batch 1 (data-in-transit & network)

> **Addendum (review-fix, same lineage).** The phase-86 initial commit (`73d7339`) shipped with a
> **logic inversion** in `newestBackupHref` (see "Review-addendum: the shipped inversion" below) plus
> three test-only defects, and marked `.done` without the mandatory green test run. The original
> phase-86 commit never passed `gradle testDebugUnitTest` (6 of the new 19 tests failed). This
> repository state — the `llops: phase-86 review fixes` commit after `73d7339` — closes
> that gap: the inversion is corrected, the test defects are fixed (the production code they guarded is
> unchanged apart from a too-large message wording tweak + a partial-file cleanup), the mandatory
> `workspace/phase-86/REPORT.md`, `docs/security-report.md`, `docs/phase-status.md` and
> `docs/ARCHITECTURE.md` updates are now present, and the full suite is green.

## The vulnerability (before/after)

**Before** (evidence in the finding, `WebDavSyncService.kt` pre-fix)

1. **"Latest" = XML document order, not timestamp**: after the `Depth: 1` PROPFIND, the code ran
   `zipRegex.findAll(xmlResponse).toList()` and picked `matches.last()` — the last href in the server's
   XML document order. A malicious (or simply Nextcloud-ordered) server returning non-chronological hrefs
   silently served an OLDER backup for "Download & Restore" (data rollback).
2. **Unbounded download**: the `.nfb` GET was streamed with `input.copyTo(output)` and NO size limit — a
   malicious server could stream an unbounded body into the app's cache (`webdav_download_import.nfb`):
   disk-exhaustion DoS.
3. **`remoteFolderName` URL-unsafe**: the configured folder name was interpolated into every URL path
   without percent-encoding (and without validation), so a folder name like `../../Other` (or
   `%2e%2e%2f`) routed uploads/downloads at unintended server paths (path traversal into other server
   directories).

**After** — the whole remote-listing → download slice is a pure-JVM decision table
`services/WebDavRemoteListingPolicy.kt` (empty class-dependency: plain `java.io`/`java.time`/`java.lang`,
API-26 floor via `java.time`, no fallback needed):

1. **Maximum filename timestamp** — `newestBackupHref` (`:91`) picks the href whose FILENAME carries the
   maximum epoch-millis across both name generations:
   - legacy `noteflow_vault_backup_<epochMillis>.nfb` (phase-06 format) → the digits are the millis
     (`filenameTimestampMillis` `:107`, `LEGACY_MILLIS_REGEX`);
   - current `noteflow_vault_backup_<yyyy-MM-dd>_<token>.nfb` (B2-CRYPTO-06) → midnight-UTC millis of
     the ISO date (`DAY_STAMP_REGEX`).
   Unparseable names score the lowest (`Long.MIN_VALUE`) and are only selected when nothing else carries a
   timestamp; same-timestamp ties break deterministically on the full href, never on XML position. The
   listing parse (`findBackupHrefs`, `BACKUP_HREF_REGEX`) is byte-identical to the pre-fix `zipRegex` so
   both name generations keep matching. Wired at `WebDavSyncService.kt:290-297` (replaces
   `zipRegex`/`matches.last()`).
2. **Bounded download** — `copyBounded` (`:174`) streams over a fixed 64 KiB buffer and checks the running
   total INSIDE the loop (`if (total > maxBytes)`), throwing the typed
   `DownloadTooLargeException` mid-stream on the first chunk that crosses the cap — the target file can
   never exceed `MAX_DOWNLOAD_BYTES` (400 MB, aligned with the restore path's
   `ImportExportService.MAX_BACKUP_INPUT_BYTES`). A contract-breaking zero-progress stream fails loudly
   after `IDLE_READ_LIMIT` consecutive empty reads (no busy-spin). Wired at
   `WebDavSyncService.kt:335-339` (replaces `input.copyTo(output)` in the GET path);
   `DownloadTooLargeException` is caught `:359-362` with a clean message.
3. **Single-segment, percent-encoded folder name** — `encodedRemoteFolderSegment` (`:130`) rejects
   blank names, `.`/`..`, any `/` or `\` separator, and control chars (`\u0000`-`\u001f`, `\u007f`), then
   RFC 3986 percent-encodes the name as ONE UTF-8 path segment (`My Folder` → `My%20Folder`). Applied at
   every folder-URL interpolation in `WebDavSyncService.kt` (`:189` PROPFIND/MKCOL prepare, `:226` PUT
   upload, `:270` PROPFIND listing).

## Changes made (review-fix lineage over the 73d7339 tree)

- `app/src/main/kotlin/com/authorss81/noteflow/services/WebDavRemoteListingPolicy.kt`
  - **`newestBackupHref` comparator corrected**: `compareBy` ASCENDING on the timestamp key +
    `maxWithOrNull` (was `compareByDescending` + `maxWithOrNull`, which returns the comparator MINIMUM =
    the OLDEST file). `:91-97`. KDoc updated.
  - **`DownloadTooLargeException` message renamed** to explicitly contain "too large" ("Remote backup
    archive is too large — refusing to download more than N MB (the restore budget).") so the surfaced
    `SyncResult` message and the unit-test contract agree. `:152-155`.
- `app/src/main/kotlin/com/authorss81/noteflow/services/WebDavSyncService.kt`
  - **Partial-file cleanup on the too-large abort**: the `DownloadTooLargeException` catch now
    `runCatching { targetLocalFile.delete() }` before returning the failure — no multi-hundred-MB
    partial `webdav_download_import.nfb` lingers when the user abandons sync. `:359-362`.
- `app/src/test/java/com/authorss81/noteflow/B1Net07WebDavDownloadPolicyTest.kt`
  - `empty listing yields null newest`: `findBackupHrefs(...)` returns a non-null empty list — the test
    now asserts `.isEmpty()` (was `assertNull`, which can never pass). `:104`.
  - `WebDavSyncService streams downloads through the bounded copy`: the source pin no longer demands
    `input.copyTo(output)` vanish from the whole FILE — the upload path
    (`uploadEncryptedVault`, local→server PUT) legitimately and boundedly keeps it; the pin now asserts
    the ONLY remaining `input.copyTo(output)` sits BEFORE `downloadLatestEncryptedVault` (i.e. in the
    upload function), plus `copyBounded(input, output)` wiring and the typed catch. `:256-266`.
  - `oversized response aborts at the cap…`: no code change needed — it now passes because the exception
    message contains "too large".
  - **Newly correct in behavior** (formerly failing): out-of-order href selection, legacy-vs-day
    timestamp ordering, unparseable-name scoring, and bounded-yield abort are all green now that the
    comparator inversion is fixed.
- Docs: `workspace/phase-86/REPORT.md` (this file), `docs/security-report.md` (B1-NET-07 status row →
    `FIXED 2026-08-16`), `docs/phase-status.md` (new phase-86 row, phase-85 row preserved),
    `docs/ARCHITECTURE.md` ("Implemented in phase-86" note appended to the WebDAV sync section).

## Checksum / secrets handling

- No keys, passwords, or decrypted note content are logged anywhere in the changed code; no
  `INTERNET`-permission change; `allowBackup="false"` + `data_extraction_rules.xml` untouched;
  FLAG_SECURE/ClipboardGuard untouched.
- The `.nfb` GET streams ciphertext directly to the cache file under the cap; minimum bytes are ever held
  in heap (one 64 KiB buffer).
- Given the review-fix is a pure logic/test/docs change, the earlier decision table (B1-NET-06/others)
  and DB encryption posture are unaffected. No DB schema change, no migration, no new dependencies,
  `.github/workflows/` untouched.

## Verification

- `gradle :app:testDebugUnitTest --tests com.authorss81.noteflow.B1Net07WebDavDownloadPolicyTest` →
  **BUILD SUCCESSFUL**, 19/19 (0 failures).
- `gradle testDebugUnitTest` → **1532 total, 1 failed**: the single failure is the known pre-existing
  `WikiLinkParserCacheUnitTest` cancellation-timing flake, which **passes in isolation**
  (`gradle :app:testDebugUnitTest --tests com.authorss81.noteflow.WikiLinkParserCacheUnitTest` →
  BUILD SUCCESSFUL). It is documented as pre-existing in phases 67/70/74/76/79/80/81 and is not caused by
  this phase (the B1Net07 delta runs green). This is the same status the phase claimed before marking
  `.done` was wrong: the pre-review tree had **6 B1Net07 failures**, all now fixed.
- `gradle assembleDebug` → **BUILD SUCCESSFUL** (2m 42s, 90 tasks). Debug APK
  `app/build/outputs/apk/debug/app-debug.apk`, 173,783,182 bytes,
  SHA-256 `0eecbb7ed538562ab4457d3fed93aa5969e3fb7da461f71f5ee83993a74f92c0`.

## Out of scope (judged, documented)

- **PROPFIND body read boundary**: `listConn.inputStream.bufferedReader().use { it.readText() }`
  (`WebDavSyncService.kt:282`) is a SEPARATE finding already budgeted (B1-DOS-series; see
  `docs/security-report.md:718`) and is NOT part of B1-NET-07's download-stream cap. Not fixed here.
- **Nested-folder names rejected**: a pre-existing config like `remoteFolderName = "Backups/Sync"` now
  throws `IllegalArgumentException` ("must be a single path segment") because `/` is rejected. That is the
  intended, fail-closed security posture (single-segment guarantees the folder can never escape its own
  path). Users with nested folder names must pick a flat segment. Documented, not a bug.
- **Upload PUT remains `input.copyTo(output)`** (`:233-237`): copying the LOCAL backup up to the server is
  naturally bounded by the (≤ 400 MB budget) local file size and is not a disk exhaustion vector on the
  device. The source pin is scoped accordingly.
- **Tie-break policy for same-day files**: same-day backups (identical midnight-UTC timestamps) break
  deterministically on the href; the final file is picked by the app's restore-dialog flow regardless.