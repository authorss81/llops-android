# Phase 55 — B1-DB-5 (MEDIUM): HTML/Obsidian ZIP import zip-bomb caps

Status: FIXED. Verification: `gradle testDebugUnitTest` 1142 green (0 failures, +13 new),
`gradle assembleDebug` green (debug APK 173,686,930 bytes, SHA-256 `ece3d77b…`).

## Finding recap

`ImportExportService.importHtmlZipOrFolder` and `importObsidianVaultZip` read every archive
entry with unbounded `zis.readBytes()` and the originating `readUriBytes` stream had no size
cap. A crafted zip (nested compression, many large entries) sent via the import picker
(`HomeScreen.kt`) decompressed megabytes→gigabytes into heap → OOM/ANR. The restore path
already had `copyWithLimit` (50MB/file, 200MB total, 100× ratio); the import paths forgot.

## Before (vulnerable)

- `ImportExportService.kt:1791-1792` — `importHtmlZipOrFolder`: `zis.readBytes()` per .html entry, unlimited entries.
- `ImportExportService.kt:1969-1972` — `importObsidianVaultZip` pass 1: `zis.readBytes()` per image, unlimited entries.
- `ImportExportService.kt:1983-1985` — `importObsidianVaultZip` pass 2: `zis.readBytes()` per .md, unlimited entries.
- `ImportExportService.kt:77-83` — `readUriBytes`: `it.readBytes()` with no cap.

## After (fixed) — `file:line`

New pure-JVM policy, `app/src/main/kotlin/com/authorss81/noteflow/services/ImportArchivePolicy.kt`:
budgets = per-entry `MAX_IMPORT_ENTRY_BYTES` 50MB, total `MAX_IMPORT_TOTAL_BYTES` 200MB,
declared-vs-actual expansion ratio `MAX_IMPORT_RATIO` 100× (4KB floor), entry count
`MAX_IMPORT_ENTRY_COUNT` 10,000, archive input `MAX_IMPORT_ARCHIVE_INPUT_BYTES` 200MB.
`claimEntry` (per-entry count), `checkEntryChunk` (per-entry + ratio + total, NO mutation —
settles once per completed entry via `settleEntryRead`, so accounting is exact; the initial
implementation double-counted the cumulative entryBytes per chunk — caught by the new test),
`readEntryBounded`, `ImportSizeLimitException : IllegalStateException`, `inputArchiveOverLimit`.

- `ImportExportService.kt:89-118` — `readUriBytes(context, uri, maxBytes=…)` reads under a hard byte cap (64KB chunks); oversized stream raises `ImportSizeLimitException` (re-thrown past the null-on-error catch so it surfaces). Default = import budget 200MB.
- `ImportExportService.kt:1137` — `MAX_BACKUP_INPUT_BYTES` now public (`const val`), the explicit 400MB cap passed by the two restore callers so legitimate large vaults still restore.
- `ImportExportService.kt:2063-2110` — `importHtmlZipOrFolder`: input capped, every entry `claimEntry`, each consumed entry via `readEntryBounded`, `ImportSizeLimitException` re-thrown.
- `ImportExportService.kt:2250-2311` — `importObsidianVaultZip`: input capped, SINGLE-PASS scan (removed the duplicate two-pass scan so the accounting is exact and every entry is parsed once), images `.enc`/persist + `.md` via `readEntryBounded`, `ImportSizeLimitException` re-thrown.
- `ImportExportService.kt:2030-2062` — `importHtmlFile`: oversize rejects and surfaces (rethrow).
- `HomeScreen.kt:193-…` — `processImportedUris`: the picker's originating `readUriBytes` catch + the HTML/zip dispatch `runCatching` surface `ImportSizeLimitException` as ONE non-alarming snackbar (`"Import skipped: <reason>"`) instead of a silent skip.
- `HomeScreen.kt:148` / `NoteflowViewModel.kt:1805` — restore paths keep passing `MAX_BACKUP_INPUT_BYTES` (400MB).
- `Dialogs.kt:96` — APK-update picker also bounded to 400MB (was unbounded).

## Zip-bomb unit test — `app/src/test/java/com/authorss81/noteflow/B1Db05ImportZipBombTest.kt` (13 tests)

- `archive input over the 200MB cap is refused` — `inputArchiveOverLimit`.
- `entry count beyond the budget is refused after exactly maxEntries claims` — count cap, exact boundary.
- `a real zip with too many entries fails the scan loop cleanly` — 101-entry archive vs 100-budget stops at the budget (no ANR walking the archive).
- `a giant single entry is refused mid-read without materializing the full blob` — REAL 60 MB-of-zeros zip (input ~61 KB): `readEntryBounded` throws the per-entry rejection at ~50 MB with `accounting.totalBytes <= 50MB+8192`, i.e. heap never grew beyond the cap (debug-proven: a raw `ZipInputStream` probe inflates the same 60 MB exactly, so the ≤50MB accounting at throw is the bounded reader stopping early).
- `a forged tiny declared compressedSize trips the ratio guard` / `…uncompressed…` / `an honest entry…` — ratio seal proven as a pure decision on forged declared sizes (a real `ZipInputStream` refuses forged compressed sizes mid-inflate, so this is the extra seal, mirrored from the restore path's `copyWithLimit`).
- `total uncompressed bytes beyond the 200MB cap are refused` — total cap, no mutation on rejection.
- `a legitimate small archive reads through exactly` — happy path round-trips, only consumed entries count toward the total, all entries count toward the scan.
- Source pins (`File.readText` of the sources): no `zis.readBytes()` survives anywhere in `ImportExportService`; both import readers route through `claimEntry`/`readEntryBounded`/`inputArchiveOverLimit`/`ImportSizeLimitException` rethrow; Obsidian has no `// Pass 1`/`// Pass 2`; `readUriBytes` bounded + fail-closed; restore callers keep the 400MB cap; HomeScreen surfaces the snackbar + imports the policy type.

## Checksum / secrets handling

No secrets. No keys, passwords or decrypted content are logged. Rejections are user-facing
budget messages only; internal failures still `Log.e` with no content. `allowBackup=false`,
ClipboardGuard and FLAG_SECURE untouched.

## Hardware-reality floor (API 26+)

No new APIs. Everything is `java.util.zip` + streams, works identically on API 26 through 36.
The caps are per-archive budgets (200MB input, 50MB/file, 200MB total, 10k entries), which are
stricter on low-RAM 2-core devices than on flagships — never silent: every rejection is a
non-alarming snackbar.

## Out of scope (documented, not fixed here)

- `MainActivity.copySharedUris` (the exported `ACTION_SEND */*` handler) STREAMS shared URI
  content to a file via `copyTo` (never a heap `readBytes`), so a shared zip-bomb file is
  copied as bytes on disk, not inflated; the inflation happens only if the user later imports
  that zip through the picker, which is now capped. ClipShare-plugin gating also validates
  before copying. No change needed here; noted for completeness.
- DOCX import parses `word/document.xml` with a streaming XML pull parser over the now-capped
  input (HomeScreen `readUriBytes` default 200MB) — bounded by the input cap.
- B1-DB-6/B1-DB-7/B1-DB-8 and every other B1-DB finding remain assigned to their own phases —
  intentionally untouched.