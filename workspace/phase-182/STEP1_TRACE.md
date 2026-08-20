# Phase 182 — Step 1: Reproduce + inventory (2026-08-20)

Symptom re-reported: after exporting and returning to the home page, note pages
show the title `Unreadable (decryption failed)` and the contents don't show.
Phase-169 (2026-08-19) claimed this fixed; this step re-verifies BOTH phase-169
causes in the CURRENT tree and audits every export path for a residual trigger.

## 1. Phase-169 cause (a) — restore re-key can no longer silently strand rows  — CLOSED

`ImportExportService.kt`:
- `reencryptFieldOutcome` (`:1296-1319`) — sealed `FieldReencryptOutcome`
  (`:1274-1283`): a value that is structurally ciphertext BUT whose decrypt under
  the OLD DEK fails (`AuthFailed`) is now distinct from `Migrated`/`LeavePlaintext`.
- `migrateTable` (`:2818-2854`) counts `AuthFailed` per `(table,column)`
  (`:2833-2842`, cursor closed in `finally` `:2844-2846`) and THROWS
  `RestoreReEncryptionException(table, column, failed)` (`:2847-2849`) BEFORE any
  `UPDATE` write-back (the writes happen only in `:2850-2852` AFTER the throw
  check) — so a decrypt-failing row now fails the restore loudly instead of being
  left under the old DEK after the SQLCipher re-key.
- `validateAndPrepareRestoredDb` catches it (`:2411`), quarantines the rejected
  temp DB, rethrows — the live vault is never swapped (`migrateFieldCiphertexts`
  only ever runs on the candidate temp DB, `:2786-2816`).

VERDICT: a cross-key restore that would orphan a row is now a loud restore failure
(never a silent install of permanently unreadable pages).

## 2. Phase-169 cause (b) — marker can no longer be persisted as a real title/body  — CLOSED

`NoteRepository.kt`:
- `updatePageBody` `:614-628` — refuses `isUnreadableMarker(body.trim())`
  (`:620-622`, trimmed so a trailing newline cannot bypass it) BEFORE
  `requireEncryptionKey()`/encrypt.
- `renamePage` `:817-...` — refuses `isUnreadableMarker(rawTitle)` (`:824-826`).
- `updatePageTitleAndTags` `:838-...` — refuses `isUnreadableMarker(rawTitle)`
  (`:842-844`).
- All three throw the typed `UnreadableContentWriteException` (`services/`), leaving
  the still-recoverable original ciphertext intact.
- `DecryptFailurePolicy.isUnreadableMarker` is EXACT-match (`DecryptFailurePolicy.kt:58`)
  and `UNREADABLE_ROW_GUIDANCE` (`:53-55`) is the fixed, marker-free, actionable text.

VERDICT: a rename/save of a page whose decrypted value IS the marker is refused;
the UI surfaces the guidance. Both phase-169 causes are genuinely closed in the
current tree (confirmed by the existing `Phase169ExportImportRoundTripTest.kt` pins —
12 tests, still green).

## 3. Export-path audit — does ANY export read/re-encrypt/write page fields, trigger a re-key, or close/reopen the DB with a different DEK?

`ImportExportService.kt` (all six):
| Path | What it does to the live vault | Writes page fields? | Closes/reopens DB? |
|---|---|---|---|
| `exportBackup` `:1390` | `repository.checkpointWal()` + `stampDatabaseChecksum()` (`:1417-1418`) then byte-verbatim DB snapshot copy (`VaultSnapshotCopyPolicy.checkpointThenCopy`, `:1419`) → pack + encrypt. Retention/layer prunes run on the STAGED COPY (`:1431-1437`), never the live vault. | NO (no field read/decrypt/re-encrypt at all — the DB file is copied verbatim) | NO live-DB close/reopen; same session DEK throughout |
| `exportVaultToZip` `:2931` | Reads `pages`, `getStrokesForPage`, `getLayersForPage`, `getCanvasItemsForPage`; renders per-page PNG/PDF into the zip (`:2967-3013`). | NO | NO |
| `exportNoteToHtml` `:3127` | Reads `page`, `getStrokesForPage`; renders HTML (`:3137-3185`). | NO | NO |
| `exportVaultToHtmlZip` `:3197` | Loops `exportNoteToHtml(context, page, repository)` per page (`:3218`). | NO | NO |
| `exportObsidianVaultZip` `:3319` | Reads `pages`, `getStrokesForPage`; renders .md + .svg (`:3356-3361`). | NO | NO |
| `exportPageToPsd` `:3384` | Reads `getStrokesForPage`/`getLayersForPage`; renders PSD off-page bitmaps. | NO | NO |

Repo-wide DB-touch search inside the export surface: the ONLY `checkpointWal` /
`stampDatabaseChecksum` hits in `ImportExportService.kt` are `:1417-1418` inside
`exportBackup`. No `closeDatabase`, no `NoteflowDatabase.dispose()`, no
`reopenDatabase`, no re-key, no AAD rebind anywhere in the six exporters — the
WebDAV sync service and LocalSend payload paths likewise never touch the live DB
(verified separately). Nothing in the export path can alter the effective DEK or
strand a row.

## 4. The residual export-return trigger the user still hits

The pages-are-unreadable symptom AFTER export + Home return is therefore NOT
"the export corrupted the row". Concretely:

1. Export opens the SAF picker / backgrounds the app ⇒ `MainActivity.kt:207-210`
   calls `viewModel.lock()` on `ON_STOP`.
2. Phase-181 made that lock() a session-preserving no-op for PASSWORDLESS vaults
   (`NoteflowViewModel.kt:4738` gates the whole teardown on `hasMasterPassword`),
   so a passwordless export-return keeps the DEK + rows readable — verified.
3. For a PASSWORD-protected vault the lock() teardown still runs; the re-unlock
   re-derives the SAME DEK from the stored credential blob
   (`unwrapMasterDek` `:3591-3615`; both unlock paths `verifyMasterPassword`
   `:3534-3574` / `verifyBiometricsAndUnlock` `:3743-3771`), reopens the DB with
   the SAME DEK-derived passphrase and re-reads the SAME rows ⇒ decrypts.

So on the CURRENT tree a healthy export-return keeps every row readable. The
symptom survives ONLY when a page was ALREADY unreadable before the export (a
legacy pre-phase-169 orphan in a live vault, or a row damaged outside the app):
the marker re-renders on every re-read, and the user attributes it to the export.
For that class phase-169 already refuses every save/rename of the marker. TWO real
gaps remain and are fixed here:

- **GAP 1 (`note_versions` unguarded marker capture).** `NoteRepository.createNoteVersion`
  (`:1578-1609`) persists `title`/`extractedText` verbatim with NO
  `isUnreadableMarker` guard. The editor captures version snapshots from the
  DISPLAYED (marker) title/body of an unreadable page, so the marker string is
  written as REAL data into `note_versions` — the last un-guarded marker-persist
  surface (it then rides into the version history UI and crosses into backup/HTML/
  Obsidian export metadata). Fix: refuse marker titles/bodies there too
  (`UnreadableContentWriteException`) + surface the guidance in the VM wrapper.
- **GAP 2 (proof seam).** There is NO export-then-read round-trip test pinning the
  invariant "a normal export never turns a readable page into the marker" across a
  lock()/unlock() session boundary. Step 4 adds it.

## 5. Fix direction (Steps 2-3)
- Close GAP 1: marker guard in `createNoteVersion` + VM catch → guidance.
- Add the export-read + lock/unlock boundary round-trip test (a normal export is a
  read-only passthrough; re-issuing the SAME DEK must decrypt the SAME rows to
  plaintext, never the marker).
- Fix "Export Document as PDF" (Step 3): EditorScreen call site
  (`:1732-1744`) must pass `sourceFilePath = page.sourceFilePath` and compute
  `totalPages = maxOf(1, pdfTotalPages, strokesMaxPage + 1)` so multi-page PDFs/
  tall images render every page's source background instead of only the visible
  window.