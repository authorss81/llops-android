# Phase 69 — B1-AUTH-05 (MEDIUM): `pages.sourceFilePath` is stored unencrypted and never validated — arbitrary file read/write inside the app sandbox

**Status:** `DONE`
**Finding:** [B1-AUTH-05](`../docs/security-report.md:476`) — MEDIUM
**See also:** `docs/phase-status.md` (workspace pipeline row), `docs/security-report.md` B1-AUTH-05 row.

## Summary

A crafted vault-backup restore transplants a DB whose `pages.sourceFilePath`
rows can point anywhere the process can read (voice-note blobs, the crash log,
`shared/`/`exports/` staging, another note's file). Opening such a note surfaced
that file's full contents in the editor/preview (disclosure), and the legacy
note-body write path wrote attacker-chosen bytes to an attacker-chosen path.
The zip entry-*names* were validated at restore (`ImportExportService.kt`), but
the `sourceFilePath` column values loaded from the restored DB were never
re-checked, and no runtime read/write/delete of a stored path was confined.

The fix makes `SourceFilePathPolicy` the single confinement decision for stored
absolute `sourceFilePath` values: a stored path may only ever point at a file
that is a strict canonical descendant of the app-private imports root
(`File(filesDir, "noteflow/imports")`). The confinement is enforced at (a) the
restore boundary, (b) the repository write boundary, and (c) every file reader.

## Evidence (the vulnerable behavior this phase eliminates)

Finding evidence cited `MainActivity.kt:311-341` (`File(page.sourceFilePath).readText()` /
`File(path).writeText(newText)`) and `WikiLinkParser.kt:64-73`, `HomeScreen.kt:217,227-236`
(imports set `sourceFilePath` to the imports path), `ImportExportService.kt:1414-1429`
(restore validates zip entry names, never the column), and `NoteRepository.kt:424-428`
(the deletes were the only thing bounding the path). The codebase has since
evolved: phase-44 (B1-DB-4 / B1-AUTH-06) removed the plaintext `.writeText()`
path — note bodies now live only in the encrypted `pages.extractedText` column —
so the live read/delete surface today is `NoteBodyVaultPolicy.resolveBodyForDisplay` /
`deleteLegacyNoteTextBody`, `WikiLinkParser.readFullText`, the legacy body
migration (`NoteRepository.migrateLegacyPlaintextNoteBodies` / `reencryptPlaintextFields`),
and the non-text source read in `DocumentTextExtractor`. No plaintext `.writeText()`
write path for `sourceFilePath` remains anywhere; the current phase closes every
remaining read/delete surface on stored file references.

## What changed (`file:line`)

### New file — `app/src/main/kotlin/com/authorss81/noteflow/services/SourceFilePathPolicy.kt`
Pure-JVM single decision table (mirrors `InlineImagePathPolicy` phase-68 /
`StrokeGeometryPolicy` phase-50):

- `confine(value: String?, importsRoot: File?): String?` (`:51-64`):
  - null/blank value ⇒ `null` (`:54`);
  - null/non-directory `importsRoot` ⇒ `null`, fail closed (`:53,55`);
  - `isBlocked(value)` (RELATIVE, or any `..` segment in either `/` or `\`)
    ⇒ `null`, refused before any file I/O (`:56`, `isBlocked` `:75-84`) — a
    legitimate stored value is always `File.absolutePath`, so a relative value
    is never a real file reference;
  - canonicalizes the root and the candidate, and the candidate must be a STRICT
    descendant of the canonical root (`isStrictlyInside` `:87-91`) — symlinks
    cannot escape the subtree and the root directory itself is never a source;
  - any canonicalization failure ⇒ `null`.
- `isConfined(value, root)` (`:67`): the boolean form used by the restore
  sanitizer.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt`
`validateAndPrepareRestoredDb` now calls `sanitizeRestoredSourceFilePaths(db,
getImportsDir(context))` (`:1704-1708`) immediately after
`sanitizeRestoredStrokeGeometry` (phase-38) and BEFORE the H3 schema-version read,
re-keying, field migration, transplant, and swap — so a malicious
`sourceFilePath` value never reaches the live vault. The sanitizer
(`:1794-1834`) SELECTs every non-null `pages.sourceFilePath`, and for each value
not `SourceFilePathPolicy.isConfined` under the restored imports root, NULLs
BOTH `sourceFilePath` and `sourceFileType` (a typed source file no longer
exists, so the type is dropped together). A page whose source file legitimately
lived inside the imports directory is untouched. A short-lived missing `pages`
table is tolerated via the existing `shouldPropagateRestoreStripFailure`
(`:1786-1792`); real failures re-throw into the restore-abort path.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`
- Constructor now `NoteRepository(db, importsRoot: File)` `:22-23` — the
  repository owns the imports root and confines at the data-layer boundary.
- `updatePageSource` (`:398-401`): stores only
  `SourceFilePathPolicy.confine(sourceFilePath, importsRoot)`; a relative /
  `..`-traversing / absolute-outside-root value is dropped to null.
- `createPage` (`:557-566`): `sourceFilePath` is confined before the insert —
  a new page can never point a source file outside the imports subtree.
- `migrateLegacyPlaintextNoteBodies` (`:441-445`): reads (and the subsequent
  delete) a legacy body file only via the confined path; an escaping stored path
  is skipped (never read into the column, never deleted).

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/services/NoteBodyVaultPolicy.kt`
- `resolveBodyForDisplay(extractedText, sourceFilePath, sourceFileType, importsRoot = null)`
  `:48-57`: the legacy-file branch reads ONLY
  `SourceFilePathPolicy.confine(sourceFilePath, importsRoot)`; a null root or
  any unconfined value falls back to the encrypted `extractedText` column —
  a stored path is never read outside the imports subtree.
- `deleteLegacyNoteTextBody(sourceFilePath, sourceFileType, importsRoot = null)`
  `:78-89`: deletes only a confined legacy file; null root ⇒ nothing is deleted.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt`
- `getFullTextForPage(page, importsRoot = null)` `:231-250` and `readFullText`
  `:252-274`: the legacy source-file branch reads only a CONFINED path; the
  full-text cache is now keyed by `FullTextKey(pageId, importsRootPath)`
  (`:91`) so a scan cached under one root is never served for a different (or
  null) root.
- `findBacklinks`/`buildTagHierarchy`/`buildWikiLinkEdges` (`:292`, `:393`,
  `:463`) thread `importsRoot` down to `getFullTextForPage`.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/services/DocumentTextExtractor.kt`
`extractTextFromDocument` (`:21-37`): the text-body branch passes the imports
root into `resolveBodyForDisplay`; a NON-text source (PDF/image) is only read
via `SourceFilePathPolicy.confine(page.sourceFilePath, importsRoot)` — a
crafted path on a non-text page is refused too.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
- `repository` is constructed with `ImportExportService.getImportsDir(appContext)`
  (`:126`).
- `updatePageSource` (`:1878-1886`) confines before persisting and mirrors the
  CONFINED value into `_selectedPage` (the in-memory selection can never drift
  from what is stored).
- Both `deleteLegacyNoteTextBody` call sites (deferred-body stash flush `:2219-2222`
  and `flushPendingEditorSaves` `:2913-2916`) pass the imports root, so the
  unlock-time legacy-file delete is confined.
- `buildCommandPaletteIndex` (`:3019-3023`) passes the imports root into
  `WikiLinkParser.buildTagHierarchy`.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`
Both `resolveBodyForDisplay` calls (`:438-444`, `:539-545`) now pass
`ImportExportService.getImportsDir(this@MainActivity)`.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/KnowledgeGraphScreen.kt`,
`app/src/main/kotlin/com/authorss81/noteflow/ui/components/BacklinksInspector.kt`,
`app/src/main/kotlin/com/authorss81/noteflow/ui/components/TagExplorerView.kt`
Each `WikiLinkParser` builder call passes the imports root, so backlinks /
tag hierarchy / graph-edge scans read legacy source files only when confined.

### Tests
- New `app/src/test/java/com/authorss81/noteflow/B1Auth05SourceFilePathTest.kt`
  (17 tests — see Verification).
- Updated `NoteBodyVaultPolicyTest`, `WikiLinkParserCacheUnitTest` to place
  legacy source files under a temp imports root (the confined-read model), and
  `B2Ui1LockedFlushTest:270` to match the split-argument delete call while
  asserting the imports-root confinement is present in the flush path.

## Checksum / secrets handling

- No keys, passwords, or decrypted note content are touched, logged, or stored.
- No new `INTERNET`, backup (`allowBackup=false`), `ClipboardGuard`, or
  FLAG_SECURE behavior changed.
- The policy performs no network I/O; it is PURE JVM (`java.io.File` only), so
  it runs unchanged on the API 26+ floor (no newer-API requirement, no fallback
  or notice needed — AGENTS.md hardware reality satisfied).

## Verification output

`gradle testDebugUnitTest` — **1339 tests, 2 failed**. The 2 failures are the
pre-existing, untouched `B1Plat01ReleaseSigningTest` asserts documented in
phases 55-68 (`docs/RELEASE.md` "never distribute a debug-signed build" +
`app/build.gradle.kts` debug `signingConfig` asserts at
`B1Plat01ReleaseSigningTest.kt:104/161`); they assert on files this diff does
not touch and fail identically on a clean tree — unrelated to this change.

`gradle :app:assembleDebug` — **green**. A first incremental run showed a
transient failure (documented in prior phases' CI flakiness); a forced
`gradle :app:assembleDebug --rerun-tasks` rebuilt all 57 tasks and produced the
debug APK. `gradle :app:assembleRelease` refuses loudly by design (B1-PLAT-1:
no `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` on CI —
"Release build refused: no release keystore configured", see `docs/RELEASE.md`);
that loud refusal is exactly what `B1Plat01ReleaseSigningTest` demands.

New test class `B1Auth05SourceFilePathTest` (17 tests, all green in isolation
and in the full run):

- policy table: blank/null never confine; a null/non-directory root confines
  nothing; RELATIVE and `..`-traversing values (incl. backslash-smuggled `..\..`)
  are blocked before any file I/O; a strict canonical-descendant path confines;
  the root itself and sibling paths do not; a symlink planted under the root
  that points outside is refused while a plain confined sibling confines;
- body-read gate: an escaping or unconfined legacy source path falls back to the
  encrypted `extractedText` column (never read), while a confined `File` under a
  temp imports root is read;
- delete gate: only a confined legacy file is deleted; an escaping path is never
  deleted (the file survives);
- WikiLinkParser gate: a legacy source that is unconfined contributes nothing to
  full text (backlinks/tags cannot see it), a confined one does;
- wiring pins: `NoteRepository` constructor requires the imports root;
  `createPage`/`updatePageSource` confine; `ImportExportService` sanitizes
  unconfined rows at restore; `NoteBodyVaultPolicy` and `WikiLinkParser` readers
  take and use the root; the unlock flush deletes legacy bodies only via a
  confined, imports-root-parameterized call.

## Definition of done

- Vulnerability path closed with `file:line` evidence (before/after above and in
  the source commits).
- OS/API floor: pure-JVM policy; no newer-API requirement, so no fallback or
  notice is needed.
- New tests prove the fix; no existing test regressed (only the 2 documented
  pre-existing `B1Plat01ReleaseSigningTest` failures remain).
- Both requested verification commands run and reported above (tests + debug
  APK); release build verified to fail loudly per the signing gate.
- `workspace/phase-69/REPORT.md` committed.

## Out of scope (documented, NOT fixed — separate phases)

- **Canvas photo embeds** — `MediaEmbedComponents.kt:97/275` feed
  `FullscreenImageDialog`/`decodeBoundedImage` directly from the stored
  `embed.contentUrlOrPath` column; a crafted vault backup could still point that
  column at an arbitrary readable file. Same class of vector, different column —
  already flagged out-of-scope in phase-68; not covered by the B1-AUTH-05
  sourceFilePath finding.
- The **`sourceFilePath` value remains plaintext at rest** (the column is not
  encrypted) — B1-AUTH-05's confidentiality concern is the *file reference*,
  not the note body (bodies moved into the encrypted `extractedText` column in
  phase-44). Encrypting the path column itself is out of scope for this finding.
- **`HomeScreen.kt` imports** (`HomeScreen.kt:217,227-236`) set `sourceFilePath`
  to the imports path at import time — they already produce confined values; no
  change needed beyond the repository boundary (createPage/updatePageSource now
  confine defensively anyway).
- No DB schema change, no new dependency, `.github/workflows/` untouched.
