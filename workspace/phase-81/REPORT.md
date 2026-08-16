# Phase 81 — B2-DOS-05 (MEDIUM) — Attachment/import ingestion slurps attacker- or user-supplied files wholly into heap (`readBytes`/`readText` on the full file)

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-05, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before** — every cited site did an unbounded, whole-file read with zero in-read cap:

- `EditorScreen.kt:720` (photo embed) and `:215`/`:237` (custom-background and paper-texture
  pickers) each did `contentResolver.openInputStream(uri)?.use { it.readBytes() }` — the picker
  URI's ENTIRE content was accumulated into an unbounded heap `ByteArrayOutputStream` before any
  size decision. Reachable via the exported `ACTION_SEND */*` MainActivity (B1-PLAT-2) or any
  picker: a 500 MB "photo"/PDF/zip fully `readBytes()`-ed → OOM on a 1 GB device at embed/import
  time, repeatable as a trivial DoS.
- `NoteflowViewModel.kt:2041` (`sourceZip.readBytes()`) — the legacy/direct backup-restore path
  read the whole vault ZIP into heap before piping bytes to `ZipInputStream`.
- `DocumentTextExtractor.kt:35` (`file.readText()` for `text` type) and `:49-67`
  (`extractPdfText` did `file.readBytes()` on the WHOLE PDF, then re-copied it into a `String` —
  two full-size heap copies) had no cap except the `else < 1_000_000` branch at `:40`.
- `NoteBodyVaultPolicy.kt` / `WikiLinkParser.kt` legacy plaintext-file-body reads (`file.readText()` /
  `f.readText()`) were equally unbounded on the legacy body-file resolver path.

**After** — a single pure-JVM decision table enforces caps DURING the read (not post-slurp):

- **New `services/AttachmentIngestPolicy.kt`**: `MAX_ATTACHMENT_BYTES = 25 MB`,
  `READ_BUFFER_BYTES = 64 KiB`. `boundedReadBytes(input, maxBytes)` is a bounded streaming loop
  over a fixed 64 KiB buffer whose running byte total is compared against the budget on EVERY
  chunk, throwing `ImportArchivePolicy.ImportSizeLimitException` mid-stream on the first chunk
  that crosses the cap — the heap accumulator can never exceed the budget plus one read buffer.
  `readTextHead(file, maxBytes = MAX_ATTACHMENT_BYTES)` does a head-bounded, prefix-preserving
  UTF-8 text read (a multi-byte character split at the boundary decodes lossily to a single
  replacement char — the byte prefix is preserved, no over-read past the budget), returning
  empty for missing/unreadable/empty files.
- **`EditorScreen.kt`** — all 3 picker `readBytes()` sites now route through
  `AttachmentIngestPolicy.boundedReadBytes(stream)` (`:236`, `:263`, `:829`) with a dedicated
  `catch (ImportArchivePolicy.ImportSizeLimitException)` branch producing a truthful, non-alarming
  snackbar per site ("Background image is too large (max 25 MB)" / "Paper texture is too large
  (max 25 MB)" / "Photo is too large to attach (max 25 MB)") — never silent degradation. The
  pre-existing manual `inputStream?.close()` leak on the paper-texture path was removed (now a
  `use` block).
- **`NoteflowViewModel.restoreEncryptedBackupFromZip`** (`:3145-3147`) — `sourceZip.readBytes()`
  replaced with `FileInputStream(sourceZip).use { AttachmentIngestPolicy.boundedReadBytes(it,
  ImportExportService.MAX_BACKUP_INPUT_BYTES) }` — the legitimate 400 MB `MAX_BACKUP_INPUT_BYTES`
  restore budget is preserved (large real vaults still restore) but the read is now bounded
  in-flight rather than slurped-first.
- **`DocumentTextExtractor.kt`** — rewritten: `MAX_EXTRACT_BYTES = 25 MB` (PDF operator scan) and
  `MAX_TEXT_HEAD_BYTES = 1 MB` (text head, preserving the old `else < 1_000_000` behavior's
  spirit). The `text` branch reads via `readTextBounded(file)` (`:66`/`:71`) and `extractPdfText`
  via `readFirstBytesBounded(file, MAX_EXTRACT_BYTES)` (`:86`) — a head-only streaming reader over
  a 64 KiB buffer (`readFirstBytesBounded`, `:152`). No `file.readBytes()`/`file.readText()`
  remains.
- **`NoteBodyVaultPolicy.kt:64`** and **`WikiLinkParser.kt:274`** — legacy file-body reads now use
  `AttachmentIngestPolicy.readTextHead(...)` (falls through to the encrypted column / empty when
  blank/failed), removing the `file.readText()`/`f.readText()` unbounded slurps.

## File:line evidence (commit after)

| Site | Before | After |
|---|---|---|
| `ui/screens/EditorScreen.kt` custom-bg picker | `:215-…` `openInputStream(uri)?.use { it.readBytes() }` (whole file) | `:236` `AttachmentIngestPolicy.boundedReadBytes(stream)` + size-limit snackbar |
| `ui/screens/EditorScreen.kt` paper-texture picker | `:237-…` unbounded `readBytes()` + leaked manual `inputStream?.close()` | `:263` `boundedReadBytes(stream)` (now `use`-blocked) + size-limit snackbar |
| `ui/screens/EditorScreen.kt` photo embed | `:720` `openInputStream(uri)?.use { it.readBytes() }` | `:829` `boundedReadBytes(stream)` + "Photo is too large" snackbar |
| `ui/viewmodel/NoteflowViewModel.kt` backup restore | `:2041` `sourceZip.readBytes()` | `:3145-3147` `FileInputStream(sourceZip).use { AttachmentIngestPolicy.boundedReadBytes(it, MAX_BACKUP_INPUT_BYTES) }` |
| `services/DocumentTextExtractor.kt` text branch | `:35` `file.readText()` | `:66`: `readTextBounded(file)` over `MAX_TEXT_HEAD_BYTES` (1 MB) |
| `services/DocumentTextExtractor.kt` PDF branch | `:49-67` `file.readBytes()` + full `String` copy | `:86` `readFirstBytesBounded(file, MAX_EXTRACT_BYTES)` (25 MB head) |
| `services/NoteBodyVaultPolicy.kt` | `file.readText()` legacy body read | `:64` `AttachmentIngestPolicy.readTextHead(file)` |
| `services/WikiLinkParser.kt` | `f.readText()` legacy body read | `:274` `AttachmentIngestPolicy.readTextHead(f)` |
| `services/AttachmentIngestPolicy.kt` | — | NEW pure-JVM decision table: 25 MB cap, 64 KiB buffer, `boundedReadBytes` (abort mid-stream via `ImportArchivePolicy.ImportSizeLimitException`), `readTextHead` (head-bounded) |

## Checksums / secrets handling

- No keys, passwords, or decrypted note content are logged; no new `INTERNET` usage.
  `allowBackup=false` + `data_extraction_rules.xml`, FLAG_SECURE, ClipboardGuard untouched.
- **No DB schema change, no migration required** — ingestion-time bounding only, nothing persisted.
- No new dependencies (pure JDK `java.io`/`kotlin.io`). `.github/workflows/` untouched. No
  `gradle/verification-metadata.xml` change (no new artifacts resolved).
- API 26+ floor: the fix is pure `java.io` — identical on every supported API, no newer-API
  requirement, no fallback needed (AGENTS.md hardware-reality rule satisfied with a fallback-free
  pure-JVM change).

## Verification

- `gradle testDebugUnitTest` — **1470 tests, 0 failures, 0 errors** (all classes green; the new
  `B2Dos05AttachmentIngestTest` passes; prior baseline was 1436, no regressions).
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (57 tasks; 64 KiB-bounded ingestion paths are
  in the debug APK).

New tests (`app/src/test/java/com/authorss81/noteflow/B2Dos05AttachmentIngestTest.kt`):

1. `over-budget stream aborts mid-read without exceeding the heap budget` — DripInputStream proves
   the finding's exploit is closed: Content-Length is irrelevant because the read is streamed; the
   abort yield is strictly `< total` (never drained the stream — catches a `readBytes()` regression)
   and `<= cap + one read buffer`.
2. `a stream exactly at the cap is acceptable` — boundary check that `> cap` (not `>=`) governs.
3. `a small stream still round-trips` — non-regression of the happy path.
4. `an empty stream yields an empty result` — degenerate case.
5. `head read never returns the whole body of an oversized file` — 1 MB file read under a 64 KiB
   head budget returns only a budget-length PREFIX of the real file (verifies both the bound and
   prefix-preservation).
6. `head read with a zero budget returns empty` — degenerate.
7. `a small file head-read returns the full content` — non-regression.
8. `a missing file head-read returns empty` — fail-open to empty, not crash.
9. `DocumentTextExtractor text extraction is bounded to the 1 MB text head` — source + behavior pin.
10. `DocumentTextExtractor finds a PDF operator inside a giant PDF without slurping` — 25 MB PDF-head
    scan finds the operator marker without reading the whole file.
11. `EditorScreen pickers no longer call readBytes on the picker stream` — source pin: exactly 3
    `AttachmentIngestPolicy.boundedReadBytes` references and no raw `.readBytes()` after
    `openInputStream`.
12. `backup restore no longer slurps the whole zip then bounds it to the backup budget` — source pin
    on `NoteflowViewModel`: no `sourceZip.readBytes()`, bounded read with `MAX_BACKUP_INPUT_BYTES`.
13. `DocumentTextExtractor no longer contains raw readBytes or readText calls` — source pin.
14. `legacy body readers use the bounded head read` — source pin on `NoteBodyVaultPolicy` +
    `WikiLinkParser`: `readTextHead`, no `file.readText()`/`f.readText()`.

## Out-of-scope (documented, not fixed here)

- The OTHER B2-DOS findings remain separate phases: 06 (PSD layer buffering — next), 07 (backup
  in-heap), 08 (PROPFIND unbounded read), 09 (RDP recursion).
- `VoiceNoteCrypto` full-blob read is pre-gated by `MAX_BLOB_BYTES` (phase-54 B1-DB-3);
  `ProtobufBrushLoader.loadFromInputStream/loadFromFile` have no external wired callers;
  `ImportExportService.readUriBytes` is already bounded during read (phase-55 B1-DB-5, 200 MB import
  / 400 MB backup caps); `WebDavSyncService` XML `readText()` is B1-NET-07 scope. None touched here.

## Phase-81 review fixes (post-audit, same discovering lineage)

Applied after the phase review (see FINDINGS): 

1. **HIGH — `NoteRepository.migrateLegacyPlaintextNoteBodies` (`:515`)** still did a wholesale
   `file.readText()` on legacy plaintext bodies (the SAME surface the phase claimed to have
   bounded) — at unlock it read the full file, wrote it into the column, then DELETED the file.
   Now: unreadable/empty files are skipped, files > `MAX_ATTACHMENT_BYTES` (25 MB) are refused
   (never read into the column, never deleted — `filesRemaining++`), and within-cap bodies are read
   head-bounded via `AttachmentIngestPolicy.readTextHead` (full content for files <= cap).
2. **MEDIUM — WebDAV restore over-budget archives** (`restoreEncryptedBackupFromZip`) failed with a
   generic "Failed to restore…" dialog message. The callback is now `(Boolean, String?) -> Unit` and
   `ImportSizeLimitException` fails CLOSED with a truthful non-alarming "Backup is too large to
   restore (max 400 MB)" message (`WebDavSyncDialog` surfaces it).
3. **LOW — truthful docs**: "UTF-8-continuation-safe" overclaimed; a multi-byte char split at the
   cap decodes lossily to one replacement char. Docs corrected (ARCHITECTURE / phase-status /
   security-report / REPORT).
4. **LOW — `NoteBodyVaultPolicy.resolveBodyForDisplay`**: a legacy body > `MAX_ATTACHMENT_BYTES`
   previously returned a truncated head, and that head could be written back to the column (then the
   full file deleted) on the next save. Now oversized bodies fall through to the full encrypted
   column and the file is left untouched — consistent with the migration.
5. **LOW — main-thread reads**: `EditorScreen`'s 3 picker bounded reads now run under
   `withContext(Dispatchers.IO)`.
6. **LOW — busy-spin guard**: `boundedReadBytes` no longer loops forever on a contract-breaking
   stream that returns 0 from a non-empty read; it throws `IOException` after 16 idle reads.
7. **LOW — stronger source pin**: the EditorScreen picker pin now regex-matches the raw
   `openInputStream(...)?.use { ...readBytes() }` shape instead of the near-vacuous per-file
   substring check; added a migration source pin.

Verification: `gradle testDebugUnitTest` — 1471 tests, 0 failures (B2Dos05AttachmentIngestTest now
15); `gradle :app:assembleDebug` green. No schema change, no new deps, `.github/workflows/` untouched.
