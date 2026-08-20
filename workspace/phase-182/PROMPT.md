# Phase 182: Re-fix — after export, note titles must NOT become "Unreadable (decryption failed)" [NOT STARTED]

You are working on **InkFlow/Noteflow**. Phase-169 (DONE) hardened the fail-closed
decryption path, but USER REPORTS it STILL happens: after exporting and returning to the
home page, note pages show the title "Unreadable (decryption failed)" and the contents
don't show. This is a REGRESSION re-fix phase.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, and `workspace/phase-169/REPORT.md`
first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-182 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Reproduce + inventory (commit it)
- Phase-169 confirmed two real causes: (a) cross-key restore silently strands
  un-re-keyable rows (`migrateTable` left a decrypt-failing ciphertext row after the
  SQLCipher re-key) and (b) a marker-overwrite data loss (saving/renaming the
  displayed `UNREADABLE_MARKER` replaces the still-recoverable original ciphertext).
  Verify BOTH are actually closed in the current tree:
  - `ImportExportService.migrateTable` (`:2811-2834` area) — does a decrypt-failing
    row now fail the restore loudly instead of being left behind?
  - `DecryptFailurePolicy` (`services/DecryptFailurePolicy.kt:46`) — is the marker
    never persisted as a real title/body? Do `NoteRepository.updatePageTitleAndTags`
    (`:837`), `renamePage` (`:816`), `updatePageBody` refuse to persist the marker?
- Find the SPECIFIC export-return trigger the user still hits: `exportBackup`
  (`:1390`), `exportVaultToZip` (`:2931`), `exportNoteToHtml` (`:3127`),
  `exportVaultToHtmlZip` (`:3197`), `exportObsidianVaultZip` (`:3319`),
  `exportPageToPsd` (`:3384`). Does ANY export path read/re-encrypt/write page
  fields, or trigger a re-key, that could leave a row decrypt-failing on the NEXT
  read? Check whether the export completion closes/rebuilds the DB (WAL
  checkpoint / `closeDatabase` / reopen) and whether the reopen uses a different
  DEK.
- COMMIT this step with the full trace (before/after export rows).

## Step 2 - Fix the regression
- Whatever the specific trigger: after ANY export completes and the app returns to
  the home page, the SAME DEK + SAME row must decrypt as before the export. If a
  reopen/checkpoint path can alter the effective DEK or strand a row, fix it so it
  cannot (fail loudly before any vault mutation, or keep the DEK pinned for the
  session).
- Harden the marker-overwrite path: a rename/save of a page whose decrypted value
  IS the marker must be REFUSED (never persisted as real data), and the UI must
  surface the recovery/restore guidance per `DecryptFailurePolicy` — never silently
  overwrite recoverable ciphertext.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Re-run/extend `Phase169ExportImportRoundTripTest` (the pinned reproducer) — the
  missed-rekey scenario must still fail loudly, and the new export-then-read
  round-trip test must prove a normal export NEVER turns a readable page into the
  marker.

## Definition of done
- Reproducer identified + fixed; after export + Home return, every previously
  readable page still reads; marker can never be persisted as real data.
- `workspace/phase-182/REPORT.md`: trigger root-cause, fix, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Never write plaintext while locked. Never log keys or decrypted content.