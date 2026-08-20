# Phase-182 REPORT — Export/Home-return regression re-fix (2026-08-20)

Re-fix of the user-reported phase-169 regression: after export (and Home
return), note titles must NOT become `Unreadable (decryption failed)`.

## Step 1 — reproduce + pipeline inventory (`STEP1_TRACE.md`)

Both phase-169 mechanisms were verified CLOSED in the current tree:

- **(a) cross-key restore** — `ImportExportService.reencryptFieldOutcome`
  (`:1296-1319`, `FieldReencryptOutcome` `:1274-1283`): `AuthFailed` classified
  per row; `migrateTable` (`:2818-2854`) throws `RestoreReEncryptionException`
  BEFORE any `UPDATE` write-back, caught at `:2411`. Migration only ever runs
  on the staged temp DB, never the live vault.
- **(b) marker-overwrite** — `NoteRepository.updatePageBody` (`:614-628`),
  `renamePage` (`:817-826`), `updatePageTitleAndTags` (`:838-844`) all refuse
  the trimmed render marker and throw `UnreadableContentWriteException`.

Export paths are all read-only passthroughs:
`exportBackup` (`:1390`, `checkpointWal()` + `stampDatabaseChecksum()` at
`:1417-1418`, byte-verbatim snapshot), `exportVaultToZip` (`:2931`),
`exportNoteToHtml` (`:3127`), `exportVaultToHtmlZip` (`:3197`),
`exportObsidianVaultZip` (`:3319`), `exportPageToPsd` (`:3384`). None read/
decrypt/re-encrypt page fields, none close/reopen the DB.

**Residual gaps found:**
- **GAP 1**: `NoteRepository.createNoteVersion` (`:1578-1609`) had NO
  `isUnreadableMarker` guard — the editor captures the DISPLAYED marker into the
  `note_versions` snapshot, from where it rides into backup/HTML/Obsidian export
  metadata as real title/body.
- **GAP 2**: no export-then-read round-trip proof across a lock()/unlock()
  session boundary.
- Plus the separate user-visible bug: "Export Document as PDF" used the
  memory-bounded visible window (`pdfPageBitmaps.size`) as `totalPages`, so
  multi-page PDFs / tall images exported only the pages that happened to be
  rasterized, and never passed `sourceFilePath` so the per-page source fallback
  inside `exportDocumentAsPdf` could not draw the missing pages.

## Step 2 — GAP 1 fix: version-snapshot marker guard

`NoteRepository.createNoteVersion` now refuses the trimmed marker in title or
extractedText with `UnreadableContentWriteException` BEFORE encryption+insert
(original ciphertext stays intact; matches the phase-169 live-page guards
exactly). `NoteflowViewModel.createNoteVersion` (`:3799`) catches the typed
guard and surfaces `DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE` via snackbar —
a snapshot of an unreadable page is never stored, never silent.

## Step 3 — Export Document as PDF fix

- New pure-JVM `services/DocumentPdfExportPolicy.kt`:
  `pageCountForExport(sourcePdfTotalPages, maxStrokePageToExport)` =
  `max(1, sourcePages, strokesBased)` — the windowed bitmap cache is
  deliberately NOT an input (it undercounts by construction).
- `EditorScreen` PDF export call site (`:1742-1757`) now computes
  `totalPages` from `pdfTotalPages` via the policy and passes
  `sourceFilePath = page.sourceFilePath`, so `exportDocumentAsPdf`'s loop
  (`for (pageIdx in 0 until count)`) re-renders every out-of-window page
  from the PDF (`renderPdfPageToBitmap`) or tall image (`decodeImageSampled`),
  falling back to the template background — no page can come out blank.

## Step 4 — regression proof

`gradle assembleDebug` green. `Phase182ExportReadLockBoundaryTest` (9 tests):

- **export→Home→re-enter**: password vault re-derives the SAME DEK on every
  unlock (PBKDF2 deterministic) — a real AES-GCM round trip decrypts the
  unchanged ciphertext to the original plaintext, never the marker.
- a mismatched DEK fails GCM auth (failed-closed), never silently installing a
  stranded row.
- `createNoteVersion` refuses the marker (title + extractedText, guard before
  encrypt) and the VM surfaces the guidance — source pins.
- `ImportExportService` never calls `closeDatabase`/`reopenDatabase` — exports
  are read-only passthroughs — source pin.
- `DocumentPdfExportPolicy` never undercounts (120-page source with a 4-page
  window ⇒ 120 pages; stroke pages pull the floor up; degenerate inputs floor
  at 1).
- editor call site uses the policy fed by `pdfTotalPages` and threads
  `sourceFilePath`; the old `maxOf(1, pdfPageBitmaps.size)` is gone — source
  pins.
- export loop iterates `0 until count` with per-page source fallback +
  template background — source pins.

Full suite: **2424 tests, 1 pre-existing `Phase148UiFailureTextScrubTest`
UNC-path failure** (untouched; reproduced in isolation on clean code —
matches the documented pre-existing failure; `WikiLinkParserCacheUnitTest`
timing flake passes in isolation). No schema change, no new deps,
`.github/workflows/` untouched, base-APK-size rule intact.