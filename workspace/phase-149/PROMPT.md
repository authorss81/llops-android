# Phase 149: note_versions retention + paged decrypt — bound the version snapshot table and never decrypt it wholesale [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-b2b4-DOS-01) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase bounds the unbounded `note_versions` growth
and its in-heap wholesale decrypt.

## Source finding (OPEN, MEDIUM)

**R2-b2b4-DOS-01** — A full title + full extractedText snapshot is written on
EVERY manual save (`MarkdownPreviewScreen.kt:287`), autosave (`:770`), and
BEFORE every on-device translation replace (`MarkdownPreviewScreen.kt:848-853`),
with NO pruning in the insert path (`NoteRepository.createNoteVersion`
`NoteRepository.kt:1412-1430`). `NoteVersionDao` (`Daos.kt:290-304`) has
`getVersionsForPage` (no `LIMIT`), `getAllVersionsForReencrypt` (whole table),
only `deleteVersionsForPage`. Opening history decrypts EVERY stored body at once
(`NoteRepository.getNoteVersions` `:1432-1442` →
`VersionHistoryBottomSheet.kt:38-42`); backup serializes the whole table
(`ImportExportService.kt:1253`); cross-device restore re-encrypts the whole
table in heap. A crafted backup with ~5,000 rows × ~50 KB bodies → ~250 MB in
heap on Version History open → OOM.

## The fix (where & how)

- Cap retained versions per page (keep newest N, e.g. 20; prune the oldest in
  `createNoteVersion` — new `NoteVersionDao.pruneVersionsForPage(pageId,
  keepNewest)` + call it inside the insert transaction).
- LIMIT + batch/paginate the decrypt in `getNoteVersions` (a `LIMIT :limit
  OFFSET :offset` DAO read), with lazy row materialization in
  `VersionHistoryBottomSheet` (only decrypt/load the visible window).
- Trim/cap the version table in the backup writer (bounded per-page snapshot)
  so monotonic growth stops inflating every export forever; keep
  `getAllVersionsForReencrypt` but cap/paginate it too.

## Verification

- New/updated pure-JVM unit tests: `createNoteVersion` prunes to N newest with a
  fake DAO; `getNoteVersions` pages (LIMIT/OFFSET) and never materializes the
  whole table; `VersionHistoryBottomSheet` lazily materializes; backup writer
  records only the capped window. Confirm seeded/old rows still load.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-149/REPORT.md`.

## Definition of done

- R2-b2b4-DOS-01 closed with `file:line` before/after evidence.
- Version History is bounded: no table-wide decrypt on open, no unbounded
  growth per page, no unbounded backup serialization.

## Constraints

- NO DB schema change in the sense of adding columns/migrations — a retention
  cap + DAO `LIMIT` is schema-compatible. If a migration is unavoidable, then
  USER APPROVAL REQUIRED (flag it; do not proceed without approval).
- Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.