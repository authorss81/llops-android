# Phase 44 — B1-DB-4 (HIGH) + B1-AUTH-06 (MEDIUM) Fix: note BODIES stop living as PLAINTEXT files

**Status:** DONE
**Findings:** 
- **B1-DB-4 (HIGH)** — "Markdown/text note BODIES are stored as PLAINTEXT files in
  `filesDir/noteflow/imports` even when a master password is set"
  (`docs/security-report.md:256-262`).
- **B1-AUTH-06 (MEDIUM)** — "`.md`/`.txt` note bodies and imported text files are
  stored in cleartext on disk while only the DB columns are encrypted"
  (`docs/security-report.md:464-470`).

Both findings share the same root cause: a **companion-file design** where the
note body is dual-stored as a plaintext `.md`/`.txt` file (authoritative) *and*
as the field-encrypted `extractedText` column. The phase-44 fix removes the
plaintext copy entirely — the encrypted column becomes the ONLY body store.

## The defect (before)

- `ImportExportService.persistFile` (`ImportExportService.kt:70-76`) wrote every
  imported `.md`/`.txt`/DOCX→md/HTML→md/Obsidian body verbatim to
  `filesDir/noteflow/imports/<sanitized-NOTE-TITLE>_<ts>.md` (also leaking the
  note TITLE as a filename).
- MainActivity's text editor treated the file as authoritative: it read the file
  back on open (`MainActivity.kt:311-319` compact, `:421-427` expanded) and
  rewrote it with `File(path).writeText(newText)` on every save (`:339`,
  `:436`), re-encrypting the DB copy nowhere.
- HomeScreen text/md/docx imports (`HomeScreen.kt:217,221-228,231-240`),
  journal/daily + WikiLink page creation (`NoteflowViewModel.kt:1799,1829`),
  and HTML/Obsidian ZIP imports (`ImportExportService.kt:1901-1911`,
  `1940-1950`, `2131-2142`) all persisted a plaintext body file.
- `WikiLinkParser.readFullText` (`WikiLinkParser.kt:243-261`), the
  BacklinksInspector convert-to-wikilink (`BacklinksInspector.kt:224-241`) and
  `DocumentTextExtractor.extractTextFromDocument` (`:20-24`) read note bodies
  from those plaintext files.
- Result (B1-DB-4 exploit): with `run-as`/root/a forensic image, the FULL text of
  every markdown/text note is recoverable without ever touching the SQLCipher
  vault, the DEK or the master password — the vault encryption was a complete
  no-op for this entire note class. B1-AUTH-06 is the same defect, nearly
  identical evidence, and is fixed by this same change.

## The fix (what changed, file:line)

### 1. NEW `services/NoteBodyVaultPolicy.kt` — the storage policy, pure JVM

Single source of truth for "where may a note body live":
- `isNoteTextBodySource(path, sourceFileType)` — true only for "text"-typed
  pages or `.md`/`.txt` file names; PDF/image sources and exported artifacts are
  never treated as note bodies.
- `resolveBodyForDisplay(extractedText, sourceFilePath, sourceFileType)` — the
  body presented/ scanned. Prefers a surviving legacy plaintext source file ONLY
  because it was the pre-fix authority (a direct disk edit made before the
  migration epoch); otherwise returns the decrypted column. No persistence side
  effects.
- `deleteLegacyNoteTextBody(...)` — removes the legacy plaintext body file;
  called only AFTER the body is safely written to the encrypted column.

### 2. The single body WRITE path — `data/db/Daos.kt` + `data/repository/NoteRepository.kt`

- New DAO `NotePageDao.updatePageBody(id, extractedText, updatedAt)`
  (`Daos.kt:164-166`) — the only query a note body may be persisted at rest
  through.
- `NoteRepository.updatePageBody(id, body)` (`NoteRepository.kt:383-397`)
  encrypts the body with `EncryptionService.encryptField(..., "pages", id,
  "extractedText")` (AES-256-GCM, per-record AAD, 12-byte IV, 128-bit tag) when
  the DEK is present and calls the DAO. Replaces every `File(...).writeText(...)`
  plaintext write. Blank bodies still become a real AEAD payload (phase-108
  convention), so even an empty note carries an integrity tag.

### 3. One-time legacy migration (existing vaults) — `NoteRepository` + `NoteflowViewModel`

- `NoteRepository.migrateLegacyPlaintextNoteBodies()` (`NoteRepository.kt:399-445`)
  — for every page holding a text-body source file: read the FILE body, write it
  into the encrypted column FIRST (only if it differs from the decryptable
  column; an undecryptable column is skipped and its file is NEVER deleted),
  then delete the plaintext file. Returns `LegacyBodyMigrationResult(rowsMigrated,
  filesDeleted, filesRemaining)`.
- New `SettingsManager.noteBodyPlaintextMigrated` flag (`SettingsManager.kt:42-48`)
  — set only when `filesRemaining == 0` (complete), so a partial run (e.g. a locked
  file) re-runs on a later unlock. Uses the exact `fieldAadMigrated` one-time-flag
  pattern from B2-CRYPTO-09.
- Wired into `NoteflowViewModel.initializeDataCore` (`NoteflowViewModel.kt:1193-1218`)
  right after the field-AAD pass: runs on the DEK (never plaintext), and after
  any row change calls `repository.checkpointWal()` + `repository.stampDatabaseChecksum(appContext)`
  (B1-DB-6-aware) so the DB-file HMAC is re-armed over the migrated rows and a
  later `verifyDatabaseIntegrity` can't false-flag "tampered".

### 4. Editor open/save now DB-only — `MainActivity.kt`

Both layouts (compact `:319-362`, expanded `:420-458`):
- open: the `produceState` reads
  `NoteBodyVaultPolicy.resolveBodyForDisplay(page.extractedText, page.sourceFilePath, page.sourceFileType)`
  instead of `File(path).readText()`;
- save: `onSaveContent` calls the new `viewModel.saveMarkdownNoteBody(page, newText)`
  — the `File.writeText` blocks and their `rememberCoroutineScope`/`NonCancellable`
  plumbing are deleted. `saveMarkdownNoteBody` (`NoteflowViewModel.kt:1874-1895`)
  writes the encrypted column in the ViewModel scope (survives the editor's
  composition teardown — the exact Phase-05 race the old NonCancellable write
  papered over) and then deletes any legacy plaintext file. No plaintext fallback
  on failure; the failure is surfaced via the existing snackbar.

### 5. Imports stop writing plaintext bodies

- `HomeScreen.kt:218-240` — DOCX→md and `.md`/`.txt` imports now call
  `addPage(sourceFilePath = null, sourceFileType = "text", extractedText = <body>)`;
  the `persistFile` call is gone. PDF/image imports (the `else` branch, `:241-299`)
  still persist their binary source — those are legitimate non-note-body files.
- `ImportExportService.importHtmlFile` (`:1888-1914`), `importHtmlZipOrFolder`
  (`:1919-1958`), `importObsidianVaultZip` pass 2 (`:2121-2147`): the `.md`
  companion write is deleted; bodies go to the encrypted column. Obsidian ZIP
  **attachments** (pass 1, images) remain real files — media, not note bodies.
- `NoteflowViewModel` journal/daily (`:1826-1836`) and WikiLink page creation
  (`:1858-1867`): `sourceFilePath = null`, body in `extractedText`.

### 6. Readers that followed the files now follow the column

- `WikiLinkParser.readFullText` (`WikiLinkParser.kt:243-263`): the legacy file
  coalesce is now gated by `NoteBodyVaultPolicy.isNoteTextBodySource` and only
  happens if the file STILL exists (pre-migration vault); it is documented as a
  migration, never a new storage location.
- `BacklinksInspector.kt:222-245` (convert-to-`[[WikiLink]]`): reads the body via
  `resolveBodyForDisplay`, does the in-memory replace, and calls
  `viewModel.saveMarkdownNoteBody` (encrypted column + legacy-file delete)
  instead of `File(path).readText()/writeText(...)`.
- `DocumentTextExtractor.extractTextFromDocument` (`:20-32`): text pages now
  resolve via `resolveBodyForDisplay`; only genuine PDF/image files are read.

### 7. Relative markdown images keep working — `MarkdownPreviewScreen.kt`

A text page no longer has a source file, so `baseDir` (used for `![alt](img.png)`
/ `![[img.png]]`) now falls back to the imports directory — the same single
folder imported attachments were always written to. New computed
`val baseDir` (`:156-163`) feeds all six render sites (`:550,561,592,611,643,662`).

## Tests

New pure-JVM `app/src/test/java/com/authorss81/noteflow/NoteBodyVaultPolicyTest.kt` — 12 tests:

- **Classification:** "text"-typed pages and `.md`/`.txt` suffixes are note-body
  sources; PDF/image sources and blank/null paths are never.
- **Display resolution:** a surviving legacy file wins over the column (it was
  the pre-fix authority); missing file → column; after deleting the legacy file
  the column is the only visible body (the exploit-closure regression test);
  image pages' body is the column, never the image file; blank everything → "".
- **Deletion:** a surviving legacy file is removed (and its path returned); a
  PDF/image source is never touched; a missing text file returns null.

## Verification

Run on the CI Linux runner (system gradle 8.13, AGP 8.7.3):

- `gradle testDebugUnitTest` — **965 tests, 0 failures** (92 result files; 953 in
  phase-43 + 12 new). `NoteBodyVaultPolicyTest`: 12/12 green.
  `WikiLinkParserCacheUnitTest` (which intentionally feeds temp `.md` text files
  to backlink scanning) is green — the legacy-coalesce path is preserved.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (`:app:packageDebug`,
  `:app:mergeExtDexDebug`).

## Before/after (vulnerability path closure)

| Step | Before (phase-43 tree) | After (this phase) |
|---|---|---|
| Import a `.md`/`.txt`/DOCX/HTML/Obsidian note | `persistFile` wrote `<note-title>_<ts>.md` PLAINTEXT to `filesDir/noteflow/imports` | Body stored ONLY in the field-encrypted `extractedText` column; `sourceFilePath = null`; zero plaintext body files |
| Open a text note (either layout) | `File(path).readText()` from the plaintext file | `resolveBodyForDisplay` → encrypted column (legacy file only coalesced transiently if it still exists) |
| Save an edit | `File(path).writeText(newText)` — plaintext rewritten every time | `viewModel.saveMarkdownNoteBody` → AES-GCM `extractedText` column via `updatePageBody`; legacy file deleted afterwards |
| Journal / daily / WikiLink page creation | `persistFile` for the template body | body in the encrypted column only |
| Existing (pre-fix) vault first unlocks | plaintext note files remain at rest | one-time `migrateLegacyPlaintextNoteBodies` moves file→column (encrypted) then deletes the files; WAL checkpointed + HMAC re-stamped; flag set on completion |
| Backlinks convert-to-`[[WikiLink]]` | `file.readText()`/`file.writeText()` — plaintext edit | in-memory replace → `saveMarkdownNoteBody` (column), invalidate caches |
| Attacker with run-as/root/forensic image | reads every note verbatim from `imports/` | `imports/` holds only PDF/image/attachment artifacts — no readable note body (and no title-bearing `.md` name) |

## Checksum / secrets handling

- No keys, passwords or decrypted content are logged or added; no new logging.
  The only new artifacts are the policy helper, the body DAO/repo path, the
  migration, and tests.
- The one-time migration is the sole DB-mutating add; it is intentionally
  bracketed by `checkpointWal()` + `stampDatabaseChecksum(appContext)` so the
  tamper tripwire stays accurate — no "Don't show again" pathway is touched.
- `allowBackup="false"`, `data_extraction_rules.xml`, `ClipboardGuard`, FLAG_SECURE
  intact. `.github/workflows/` untouched.
- **No DB schema change, no migration, no new dependency.** (The encrypted column
  the fix relies on already exists; `updatePageBody` writes it with the existing
  `encryptField` machinery.)

## Out of scope (observed, not fixed — B1-DB-4/AUTH-06 only)

- **B1-DB-5** (docs/security-report.md:264-270): HTML/Obsidian ZIP readers still
  use unbounded `zis.readBytes()` (no byte-count/entry caps) — separate phase-?;
  untouched here.
- **B1-DB-13** (`NoteflowDatabase.kt` partial-write / torn `File.writeText`
  finder; report line ~573): the markdown editor's data-integrity half. The
  truncate-then-write race disappears with the file write, but the migration's
  delete-then-retry loop and the save path are not reviewed as a separate
  finding — noted for the B1-DB-13 phase.
- **B1-DB-14** (~line 581, cancellable import leaving orphaned `persistFile`d
  files): the still-persisted PDF/image/attachment import files can still be
  orphaned by a mid-import lock. Text-body exposures are gone; the remaining
  orphan corpus is image/pdf artifacts only.
- Title leakage via *filename* is gone for note bodies; the **logcat exception
  paths** quoted in B1-AUTH-06 evidence (`Log.e("ImportExportService", ..., e)`)
  are a separate logging sanitization concern (B2-LOG-01 family) and are not
  touched by this phase.
- Obsidian markdown images that referenced attachments by RELATIVE paths inside
  the archive still resolve only as long as the flattened attachments live in
  `imports/` (the pre-existing flattening behavior, now covered by the
  `MarkdownPreviewScreen` baseDir fallback); a future pass could re-hydrate
  per-note directory structure.

## Files changed

- NEW `app/src/main/kotlin/com/authorss81/noteflow/services/NoteBodyVaultPolicy.kt`
- `app/src/main/kotlin/com/authorss81/noteflow/data/db/Daos.kt` (`updatePageBody` :164-166)
- `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`
  (`updatePageBody` :383-397, `migrateLegacyPlaintextNoteBodies` :399-445,
  `LegacyBodyMigrationResult` bottom-of-file)
- `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
  (`noteBodyPlaintextMigrated` :42-48)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
  (migration `:1193-1218`; journal/wiki page creation `:1826-1836`, `:1858-1867`;
  `saveMarkdownNoteBody` `:1874-1895`; import)
- `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt` (compact `:319-362`, expanded `:420-458`)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (docx/md/txt import `:218-240`)
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`
  (`importHtmlFile`, `importHtmlZipOrFolder`, `importObsidianVaultZip` pass 2)
- `app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt` (`readFullText` :243-263)
- `app/src/main/kotlin/com/authorss81/noteflow/services/DocumentTextExtractor.kt` (`extractTextFromDocument` :20-32)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/BacklinksInspector.kt` (convert-to-wikilink :222-245)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/MarkdownPreviewScreen.kt` (baseDir `:156-163` + 6 render sites)
- NEW `app/src/test/java/com/authorss81/noteflow/NoteBodyVaultPolicyTest.kt` (12 tests)
- `docs/security-report.md` (B1-DB-4 + B1-AUTH-06 rows → FIXED, truth table :819-820),
  `docs/phase-status.md` (phase-44 row), `docs/ARCHITECTURE.md` (body-storage note)