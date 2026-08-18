# Phase 149 report — `note_versions` retention + paged decrypt (R2-b2b4-DOS-01)

Status: **DONE** — finding closed. Verified `gradle testDebugUnitTest` = 2027 app tests
(2026 green; 1 PRE-EXISTING failure — see below) + `gradle :app:assembleDebug` green.

## Finding fixed

### R2-b2b4-DOS-01 (MEDIUM) — `note_versions` grows without any retention/prune and is decrypted wholesale into heap

**Before** (from `docs/security-report-round2.md:865`):
- A full title + full `extractedText` snapshot is written on EVERY manual save
  (`MarkdownPreviewScreen.kt:287`), on the autosave path (`:770`), and BEFORE every
  on-device translation replace (`:848-853`).
- `NoteRepository.createNoteVersion` (`NoteRepository.kt:1412-1430`) inserts each full
  body with NO pruning; `NoteVersionDao` (`Daos.kt:290-304`) has `getVersionsForPage`
  (no `LIMIT`), `getAllVersionsForReencrypt` (whole table), only `deleteVersionsForPage`
  (whole-page wipe) — no count/age/TTL retention query anywhere.
- Opening history decrypts EVERY stored body at once: `NoteRepository.getNoteVersions`
  (`:1432-1442`) → `VersionHistoryBottomSheet.kt:38-42` materializes every row.
- The backup writer serializes the whole table (`ImportExportService.kt:1253`), so
  monotonic growth inflates every export/WebDAV upload forever; cross-device restore
  re-encrypts the whole table in heap (`getAllVersionsForReencrypt`).

**Exploit / Reproducer:** Restore a crafted vault backup holding ~5,000 rows × ~50 KB
bodies, then open Editor → Version History → ~250 MB of decrypted bodies materialize in
heap → OOM/freeze. The same state accrues benignly on any long-lived vault.

**After (phase-149):**

Retention cap (newest 20 per page, pruned in the insert transaction):
- New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/NoteVersionRetentionPolicy.kt`
  — `MAX_VERSIONS_PER_PAGE` = 20, `DECRYPT_BATCH_SIZE` = 20, `REENCRYPT_BATCH_SIZE` = 100,
  the pure retention decision (`decideRetention`) and the shared prune SQL
  (`PRUNE_KEEP_NEWEST_SQL` = `DELETE FROM note_versions WHERE pageId = ? AND id NOT IN
  (SELECT id FROM note_versions WHERE pageId = ? ORDER BY timestampMs DESC LIMIT ?)`).
- `NoteRepository.createNoteVersion` (`NoteRepository.kt:1436`) now wraps
  `insertVersion(version)` + `pruneVersionsForPage(pageId, MAX_VERSIONS_PER_PAGE)`
  inside ONE `db.withTransaction` — a rapid save/autosave/translate loop can never
  accumulate an unbounded table.
- `NoteVersionDao` (`Daos.kt`) gained `pruneVersionsForPage`, `getVersionsForPagePaged`,
  `getVersionsForReencryptPaged`, `countVersionsForPage`, `getDistinctVersionPageIds`.

Paged decrypt (no wholesale in-heap materialization):
- `NoteRepository.getNoteVersions` (`NoteRepository.kt:1465`) now pages newest-first
  (`getVersionsForPagePaged(pageId, DECRYPT_BATCH_SIZE, offset)`) and decrypts
  batch-by-batch; the pre-fix whole-table `getVersionsForPage(pageId)` read is gone.
- `NoteRepository.getNoteVersionsPaged` (`:1492`) exposes a single bounded window.
- `VersionHistoryBottomSheet.kt` materializes lazily: keeps the pinned guarded
  `viewModel.getNoteVersions(page.id)` for the first window and streams further windows
  via `viewModel.getNoteVersionsPaged` (`NoteflowViewModel.kt:3711`) through a near-end
  sentinel as the list scrolls. `endReached` stops fetching past the tail.
- Both whole-table re-key sweeps (`migrateFieldRecordAad` + `reencryptPlaintextFields`)
  now page via `getVersionsForReencryptPaged` — `getAllVersionsForReencrypt().forEach`
  is gone from both. (`getAllVersionsForReencrypt` itself is kept for compatibility, as
  the prompt requested.)

Capped backup writer + restore sanitize:
- `ImportExportService.exportBackup` calls `repository.pruneVersionsToRetention()`
  BEFORE its checkpoint-then-copy — a legacy vault that outgrew the cap before this
  deploy stops inflating every export forever; the archive never serializes a page's
  retained-but-oversized history.
- `validateAndPrepareRestoredDb` runs the new `sanitizeRestoredNoteVersions(db)` under
  the candidate key (right after `sanitizeRestoredStrokeGeometry`), BEFORE the re-key /
  `migrateFieldCiphertexts` steps — it trims each page to the newest 20 via the policy's
  `PRUNE_KEEP_NEWEST_SQL`, so a crafted ~5,000-row × ~50 KB-body archive can never OOM
  the process on history open or restore. Missing-table tolerance mirrors the strokes
  sanitizer (`shouldPropagateRestoreStripFailure`).

Schema-compatible: **no migration** (retention cap + DAO `LIMIT` are schema-compatible per
the phase constraint).

## New tests

`app/src/test/java/com/authorss81/noteflow/Phase149NoteVersionsRetentionTest.kt` (14):

- Policy budgets & pure decision: cap constants, `exceedsCap`/`pruneCountForPage`,
  `decideRetention` keeps exactly the newest N and drops exactly the oldest tail
  (deterministic), the prune SQL shape (`NOT IN` + bound `LIMIT`), the paged-select shape.
- Fake-DAO model of `createNoteVersion`: saving 5,000 snapshots keeps at most the newest
  20 per page; `getNoteVersions` paging reads every window ≤ `DECRYPT_BATCH_SIZE` and
  never materializes the whole table in one heap read; a page already under the cap is
  untouched.
- Source wiring pins: `createNoteVersion` prunes inside the transaction; `getNoteVersions`
  uses the paged reader and the direct whole-page read is gone; the shared decrypt
  decision still routes through `decryptFieldForDisplay` (B1-DB-8 pin preserved); both
  reencrypt sweeps page; the backup writer prunes before its checkpoint; the restore
  sanitizer runs the same retention SQL under the candidate key; the bottom sheet is lazy
  and keeps the Phase-134-guarded pinned read.

## Verification

- `gradle :app:testDebugUnitTest --tests Phase149NoteVersionsRetentionTest` — green (14/14).
- `gradle :app:testDebugUnitTest` — **2027 tests, 2026 green, 1 failure**:
  `Phase148UiFailureTextScrubTest.scrubForUi strips userinfo collapses URL paths and
  redacts absolute paths` (assert at `Phase148UiFailureTextScrubTest.kt:234`, the UNC-share
  redaction). **PRE-EXISTING and unrelated to phase-149: reproduced on a clean stash of
  this branch** (`git stash` → same test fails → `git stash pop`). The finding in phase-148
  is separately documented as `DONE`; per the "do not fix OTHER findings" constraint the
  UNC-path redaction gap is left in place for a later phase and recorded here.
- `gradle :app:assembleDebug` — **green**.
- Pinned suites re-verified green: `B1Db08DecryptFailureTest`, `Phase134LockVaultInflightTest`,
  `B2Ui1LockedFlushTest`, `R2B1C03DekHexScopingTest`, `Phase137BackupCopyConsistencyTest`,
  `B2Dos01StrokeGeometryTest`, `BlankFieldEncryptionTest`.

## Residuals / observations (out of phase scope)

- `migrateTable` (`ImportExportService.kt:2504`) still reads a whole column of a restored
  table in heap during cross-device field re-key; with `sanitizeRestoredNoteVersions`
  running first, the `note_versions` portion is bounded to 20 rows/page and the archive is
  already bounded to ≤400 MB decompressed by `BackupBudgetPolicy` — no longer the OOM
  vector of this finding.
- The pre-existing `Phase148UiFailureTextScrubTest` UNC-path assertion failure is tracked
  here (see above); needs a follow-up phase, not this one.

## Review fixes (2026-08-18, applied after the phase review → commit `llops: phase-149 review fixes`)

The review surfaced six findings; five were fixed in code:

1. **Initial history-open was not actually lazy** (MED). `getNoteVersions` looped over every
   LIMIT/OFFSET batch and decrypted the WHOLE history into one heap list, so the bottom sheet's
   "first window" still materialized everything. Fixed: `getNoteVersions` (NoteRepository.kt:1461)
   now returns ONLY the newest bounded initial window (`getVersionsForPagePaged(pageId,
   DECRYPT_BATCH_SIZE, 0)`); the sheet streams further windows via `getNoteVersionsPaged`. No code
   path decrypts the whole table on open anymore.
2. **Export pruned the LIVE vault** (MED). `pruneVersionsToRetention()` ran on the live repository
   DB BEFORE the snapshot copy — a failed/cancelled export (WebDAV/LocalSend included) permanently
   deleted user history even though no archive was produced. Fixed: the prune now runs on the
   STAGED snapshot copy, opened with the in-memory DEK (`pruneStagedSnapshotVersions`,
   ImportExportService.kt) AFTER `checkpointThenCopy`; the live vault is never touched by export.
   `pruneVersionsToRetention`/`getDistinctVersionPageIds` deleted.
3. **Retention tie-break non-determinism** (LOW). Same-millisecond snapshots made
   `ORDER BY timestampMs DESC LIMIT` arbitrary. Fixed: all prune + paged SQL now orders by
   `timestampMs DESC, rowid DESC` (insertion order); new pure-JVM test pins the tie-break model.
4. **Orphaned whole-table DAO methods** (LOW). `getVersionsForPage` + `getAllVersionsForReencrypt`
   left as unbounded API any caller could re-invoke. Fixed: deleted; only the paged variants exist.
5. **Parallel SQL literals + test-only helpers** (LOW). `SELECT_PAGED_DESC_SQL` was a drift-prone
   copy of the DAO query and `decideRetention`/`pruneCountForPage` were dead production code.
   Fixed: `@Query(NoteVersionRetentionPolicy.SELECT_PAGED_DESC_SQL)` /
   `@Query(NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_ROOM_SQL)` make the policy the SINGLE SQL
   source; `decideRetention`/`pruneCountForPage` deleted; `exceedsCap` + `countVersionsForPage`
   wired into `createNoteVersion` (count-gated prune). `PRUNE_KEEP_NEWEST_SQL` (raw `?`) is shared
   by restore + export sanitizers via `pruneVersionPagesToRetention`.
6. **INFO (not code)** — feedback on export side effects is covered by fix 2; documented here.

Verification after fixes: `gradle testDebugUnitTest` = 2029 tests / 2028 pass / the same single
pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure (untouched); `gradle assembleDebug`
green. Tests updated: `Phase149NoteVersionsRetentionTest` 16 (was 14: +`rowid` tie-break,
+initial-window bound, staged-snapshot export pin, single-source SQL pins).

## Definition of done

- [x] R2-b2b4-DOS-01 closed with `file:line` before/after evidence (above +
      `docs/security-report-round2.md`).
- [x] Version History is bounded: no table-wide decrypt on open, no unbounded growth per
      page, no unbounded backup serialization.
- [x] `gradle testDebugUnitTest` (only the documented pre-existing Phase148 failure) +
      `gradle assembleDebug` green.
- [x] Docs updated: `docs/phase-status.md` row DONE, `docs/ARCHITECTURE.md` note,
      `docs/security-report-round2.md` finding marked FIXED in phase-149.
- [x] No DB schema change / migration, no new dependencies, no `.github/workflows/` edits.