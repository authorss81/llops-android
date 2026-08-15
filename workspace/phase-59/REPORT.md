# Phase 59 — B1-PLAT-3: Whole-vault exports no longer write plaintext to public Downloads

**Commit:** (see git log)
**Date:** 2026-08-15
**Finding:** `B1-PLAT-3` (MEDIUM) — `docs/security-report.md:321-327`

## What was wrong (before)

Every whole-vault / single-item export wrapper automatically copied its output to the
world-readable public `/storage/emulated/0/Download`:

- `HomeScreen.kt:479-489` `onExportObsidianVault` → plaintext `.md` vault zip
- `HomeScreen.kt:490-500` `onExportHtmlVault` → plaintext HTML site zip
- `PsdExportService.kt:95-102` rendered ink layers → PSD copied to public Downloads
- `HomeScreen.kt:451-475,1191-1202` backup archives (device-keyed and
  password-protected) also landed in public Downloads
- `ImportExportService.kt` had the same `getExternalStoragePublicDirectory` copy
  after `exportAnnotatedPage`, `exportDocumentAsPdf`, `exportVaultToZip`,
  `exportNoteToHtml`, `exportVaultToHtmlZip`, `exportObsidianVaultZip` (6 call sites)

Exploit: ONE tap wrote the ENTIRE vault in decrypted plaintext into shared
Downloads with no password, no confirm, no "unencrypted" warning; the files
persisted after the vault was cleared.

## The fix (the "better" branch chosen)

Chose the SAF rewrite (the finding's recommended fix), not just a warning banner:

1. **No export ever auto-writes to shared storage.** Every export service wrapper
   now keeps its output in app-private `cacheDir`. The six
   `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` copies in
   `ImportExportService.kt` and the one in `PsdExportService.kt` are deleted.

2. **Every user-facing export goes through the system SAF destination picker.**
   New reusable composable `ui/components/SaFExporter.kt`
   (`rememberSaFExporter`) launches `ACTION_CREATE_DOCUMENT` with the export's MIME
   + suggested name. On a successful write it deletes the cacheDir staging copy
   (transfer-then-delete). API floor: `ACTION_CREATE_DOCUMENT` exists since API 19,
   below minSdk 26 — no fallback needed (per AGENTS.md hardware reality).

3. **Whole-vault plaintext kinds require a bold pre-export warning.** New pure-JVM
   `services/ExportDestinationPolicy.kt` classifies every kind
   (`ENCRYPTED_BACKUP`, `OBSIDIAN_VAULT`, `HTML_SITE`, `VAULT_ZIP`, `PAGE_PNG`,
   `PAGE_WEBP`, `PAGE_PDF`, `DOCUMENT_PDF`, `NOTE_HTML`, `LAYERED_PSD`). The three
   vault-plaintext kinds (`OBSIDIAN_VAULT`, `HTML_SITE`, `VAULT_ZIP`) get a bold
   red "Export is NOT encrypted" consent dialog BEFORE the picker opens. The
   encrypted backup and single-page renders go straight to the picker (that picker
   IS the consent) — but no file may silently appear in shared storage.

4. **UI export routes wired through the exporter** — HomeScreen (plain backup,
   password backup, Obsidian, HTML site, notebook/section vault zips) and
   EditorScreen (PNG, WebP, page PDF, document PDF, note HTML, PSD, section vault
   zip). LocalSend's `buildPayloadFile()` (which shares the same export functions)
   is intentionally unchanged — its cacheDir bytes are sent over the network, not
   written to public storage.

## File:line evidence

Changed:

| File | After |
|------|-------|
| `services/ExportDestinationPolicy.kt` | NEW — kind table `:33-54`, `mimeType` `:57`, `requiresPlaintextWarning` `:75`, `suggestedFileName` `:88`, warning consts `:107-114`, `postExportGuidance` `:117` |
| `ui/components/SaFExporter.kt` | NEW — exporter `:42-53`, `rememberSaFExporter` `:56`, picker `ACTION_CREATE_DOCUMENT` `:119`/`:151`, transfer-then-delete `:88`, warning gate `:100-142` |
| `services/ImportExportService.kt` | 6 public-Downloads copies removed (`getExternalStoragePublicDirectory` gone repo-wide) |
| `services/PsdExportService.kt` | Downloads copy removed; PSD stays in `cacheDir` (rebuilt cleanly after a mangled mid-edit state) |
| `ui/screens/HomeScreen.kt` | `rememberSaFExporter(scope)` + all 6 export/backup flows route through `exporter.export(...)` |
| `ui/screens/EditorScreen.kt` | `rememberSaFExporter(scope)` + all 7 export flows route through `exporter.export(...)` |

After-state verification:

- `grep -r "getExternalStoragePublicDirectory\|DIRECTORY_DOWNLOADS" app/src/main` →
  **only** the explanatory doc comment in `ExportDestinationPolicy.kt` (no production code).
- `grep -r "Downloads:" app/src/main` → none (all "saved to Downloads" snackbars replaced).

## Tests

New tests (`app/src/test/java/com/authorss81/noteflow/`):

- `ExportDestinationPolicyTest.kt` (11) — pure-JVM MIME/warning/file-name/guidance
  rules for every `ExportKind`:
  - every kind requires a user-picked destination + non-blank MIME;
  - whole-vault kinds → `application/zip`; per-item MIMEs correct;
  - only the 3 whole-vault plaintext kinds and everything-except-backup are
    flagged unencrypted;
  - suggested names pass through / fall back; warning & post-export guidance text.
- `B1Plat03ExportConsentTest.kt` (5) — source pins:
  - no `getExternalStoragePublicDirectory`/`DIRECTORY_DOWNLOADS` in production code
    (comment lines stripped before scanning);
  - export wrappers keep output in `cacheDir`;
  - SaFExporter uses `ACTION_CREATE_DOCUMENT` + `CATEGORY_OPENABLE` + MIME +
    `EXTRA_TITLE` + transfer-then-delete;
  - warning gate keyed on `requiresPlaintextWarning` with bold/error styling;
  - HomeScreen + EditorScreen wire every flow through `exporter.export(...)` with
    the right `ExportKind`.

## Verification output

- `gradle testDebugUnitTest` → **1196 tests completed, 2 failed.**
  The 2 failures are `B1Plat01ReleaseSigningTest` `release guide forbids
  distributing debug-signed builds` (`B1Plat01ReleaseSigningTest.kt:161`) and
  `debug buildType keeps AGP auto generated debug keystore` (`:104`). Both were
  **proven pre-existing**: they fail identically on a clean `git stash` tree
  (they assert on `app/build.gradle.kts` / `docs/RELEASE.md` content, unrelated to
  this finding). The new B1-PLAT-3 tests pass; all 16 new + existing tests are
  green.
- `gradle assembleDebug` → **BUILD SUCCESSFUL.** Debug APK on disk
  `app/build/outputs/apk/debug/app-debug.apk` (173.7 MB). NOTE: the first
  invocation had a transient unrelated daemon/parallelism failure; the retry and
  the final full run are green (same transient-run history as phases 50/55/56).

## Checksum / secrets handling

- No key/password/decrypted-content logging introduced; no changes to
  `allowBackup=false`, `ClipboardGuard`, or FLAG_SECURE.
- Exports stay encrypted (backup) or flow through an explicit user consent +
  picker (plaintext); nothing new writes to public storage without a user action.

## Out-of-scope / other findings observed

- `B1Plat01ReleaseSigningTest` 2 failures (pre-existing, unrelated — `docs/RELEASE.md`
  + `app/build.gradle.kts` assertions). Not fixed per "one finding per phase" scope.
- LocalSend send flow intentionally keeps cacheDir payloads (sends over network,
  human-accepted on the far side) — not a public-storage write.
- No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.