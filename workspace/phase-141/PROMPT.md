# Phase 141: Export/share hygiene — staging deleted on every outcome + chooser-gated share + no note-title metadata [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1P-02, R2-B1P-03, R2-b2b3-LOG-04) and `docs/phase-status.md`
+ `docs/ARCHITECTURE.md`. This phase removes the plaintext-export linger paths
and the un-choosered share + title-metadata echo.

## Source findings (all OPEN, LOW)

1. **R2-B1P-02** — A CANCELLED whole-vault PLAINTEXT export leaves the entire
   decrypted vault in app cache; the failed-write path deletes the staging copy
   instead. `SaFExporter.kt:75-97` deletes staging only when
   `result.resultCode == RESULT_OK` (`:88`); cancel (`:94-97`) and
   result-without-data (`:91-93`) call `done(false)` without touching `file`.
   Whole-vault plaintext kinds stage under `cacheDir/*_exports`
   (`ImportExportService.kt:2552,2430,2164,2359`). Success deletes even when the
   SAF `copyTo` FAILED (`:81-89`, `ok==false`).
2. **R2-B1P-03** — Export Engine uses ACTION_SEND WITHOUT a chooser:
   `EditorScreen.kt:1383-1387` takes the raw intent from
   `ExportExportHelper.shareFile` (`ExportEnginePlugin.kt:60-72`,
   `FLAG_GRANT_READ_URI_PERMISSION` + FileProvider URI via
   `file_paths.xml:11` `cache-path exports/`) and calls `startActivity` directly.
   A default handler + dismissed sheet leave plaintext in the grantable root.
3. **R2-b2b3-LOG-04** — `ExportEnginePlugin.kt:66-71` publishes the note title
   as `EXTRA_SUBJECT = file.name` (`ExportPayloadAssembler.kt:95` names files
   `${sanitizeBaseName(title)}.$ext`) → title echoed into every share target +
   Android share-history.

## The fix (where & how)

- **R2-B1P-02:** Delete the staging file in cancel/no-data branches too —
  deletion in a `finally` after `done(...)`; delete only when `ok == true`.
  Optionally sweep stale same-name stagings at export start.
- **R2-B1P-03:** Route through `Intent.createChooser(...)` so the target is
  always user-chosen, and delete the export file once the share is
  delivered/dismissed (transfer-then-delete, as in `SaFExporter`).
- **R2-b2b3-LOG-04:** Drop `EXTRA_SUBJECT` or make it the generic "Exported
  note" (never the note title / filename-derived subject).

## Verification

- New/updated pure-JVM + source-pin unit tests: SaFExporter deletes staging on
  EVERY outcome (ok, ok-but-copy-failed, cancel, no-data) with a fake
  ActivityResult seam; createChooser source pin for the Export Engine send; no
  `EXTRA_SUBJECT = file.name` remaining.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-141/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- No plaintext export staging survives a cancel/no-data path; share targets are
  always user-chosen; note titles no longer leave via share metadata.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.