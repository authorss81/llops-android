# Phase 260 — Storage: Room FK/migration/WAL/quarantine/transactions + crypto leftovers — REPORT

## 1. Evidence table (claim / reality / status / evidence)

| # | PROMPT claim | Reality at HEAD | Status | Evidence |
|---|---|---|---|---|
| 1 | CRITICAL `NoteflowDatabase.kt:485` unconditional `.fallbackToDestructiveMigration()` + SCHEMA_VERSION=9 silently wipes vault on newer/edge versions | Confirmed: the builder carried the unconditional fallback | FIXED — fallback REMOVED entirely (fail-closed throw; stricter than the suggested `...OnDowngrade()`-only, which would still wipe on downgrade) | `NoteflowDatabase.kt` `getDatabase`: no `fallbackToDestructiveMigration` token anywhere in the file; restore path's `checkRestoredSchemaNotNewer` guard refuses newer backups before any swap (unchanged). Pinned by `Phase260StorageTest.no destructive migration fallback…` |
| 2 | HIGH `Entities.kt` no `ForeignKey` (orphans survive torn deletes) | Confirmed: no FK annotations | COMPENSATED at app layer, schema change DEFERRED — adding FKs alters the Room schema hash and requires a migration, forbidden by the phase constraint (`No Room schema change / migration`). Orphan paths closed in code instead (row 4) | `Entities.kt` untouched (verified via `git status`); `NoteRepository.deletePageRowsPermanently` deletes strokes+layers+embeds+**versions**+page in ONE `withTransaction` |
| 3 | HIGH missing composite index `(sectionId,pinned,updatedAt)` for `Daos.kt:84` | Confirmed: `pages` carries single-column indices only | DEFERRED — same constraint: a new `@Index` is a schema change requiring a migration. Documented for a future schema-version bump; the existing `sectionId` index serves the `getPagesForSection` filter. No silent behavior change | `Entities.kt` untouched; no query change |
| 4 | HIGH `deletePagePermanently` + `emptyTrash` not transactional; files deleted before DB rows | Confirmed (pre-fix body: file `delete()` calls, then 4 row deletes, no transaction; versions rows never deleted) | FIXED — collect → ONE transaction → post-commit file removal; `note_versions` orphan closed; notebook/section deletes restructured to the same shape; `emptyTrash` = one transaction + best-effort `VACUUM` outside it | `NoteRepository.kt`: `collectPageFilesForDelete`, `deletePageRowsPermanently` (5 deletes in `db.withTransaction`), `deleteNotebook`/`deleteSection`/`deletePagePermanently`/`emptyTrash` rewritten. Pinned by `Phase260StorageTest` (atomic-transaction + vacuum pins) |
| 5 | HIGH `Daos.kt:164` hard deletes with no VACUUM | Confirmed: no VACUUM anywhere | FIXED (best-effort, post-commit, outside the transaction — VACUUM cannot run inside one; silent on failure, vault stays correct either way) | `NoteRepository.kt` `emptyTrash`: `db.openHelper.writableDatabase.execSQL("VACUUM")` in `runCatching` |
| 6 | HIGH delete-path `contains("imports/")` substring instead of `SourceFilePathPolicy.confine` | Confirmed: `path.contains("imports/") \|\| path.contains("exports/")` gated source deletes; voice blobs gated by name suffix only | FIXED — new pure-JVM `services/PageDeleteFilePolicy.kt`: source files via `SourceFilePathPolicy.confine` (strict imports-root descendant), voice blobs via audio-name shape + strict `voice_notes`-dir containment. Side benefit: legacy plaintext `.m4a` confined to the voice dir is now destroyed with the page (old code removed `.enc` only, leaking plaintext) | `PageDeleteFilePolicy.kt` (new); `NoteRepository` uses it at all delete sites; repo-wide grep: zero `contains("imports/")` in production code outside the policy's KDoc. Behavior-pinned by `Phase260StorageTest` (6 JVM tests incl. the `/tmp/x/imports/y` lookalike the old gate deleted) |
| 7 | MEDIUM `dispose()` swallows `wal_checkpoint` BUSY → stale HMAC baseline; no `wal_autocheckpoint` | Partially confirmed: the checkpoint cursor was stepped but its `(busy, log, checkpointed)` result never read. The "stale baseline" half was already mitigated — the session-end re-arm HMACs main + `-wal` (`DatabaseSecurityHelper` streams both), so uncheckpointed frames stay authenticated | FIXED the inspection gap + armed autocheckpoint explicitly: new `runWalCheckpointFull` reads column 0 (busy) with one immediate retry, shared by `dispose()` and `NoteRepository.checkpointWal()` (now returns `Boolean`); `RoomDatabase.Callback` executes `PRAGMA wal_autocheckpoint=1000` on every open (per-connection state was previously never set) | `NoteflowDatabase.kt`: `runWalCheckpointFull`, `WalAutocheckpointCallback`, `dispose()` calls it; `NoteRepository.checkpointWal(): Boolean`. `Phase136TamperBaselineCadenceTest` dispose pin updated to the helper |
| 8 | MEDIUM `quarantineCorruptDatabase` ignores `renameTo`, ms-timestamp collision, flag-after-rename kill window; `isDatabaseCorruptException` overbroad `malformed` substring | All confirmed | FIXED — one shared `quarantineSingleFile` primitive (flag raised BEFORE renames; collision-probed target `<name><suffix>` then `-1…`; `renameTo` checked with length-verified copy fallback; source never deleted); classifier narrowed to the two exception types + two full SQLite diagnostics | `NoteflowDatabase.kt`: `quarantineSingleFile`, reordered `quarantineCorruptDatabase` + migrate catch (caller passes the SAME timestamp to `quarantineMigrateFailed(dbFile, tempFile, timestamp)` so flag == suffix); classifier KDoc + body. `CorruptionClassifierTest` +1 narrowing test; `B1Db02MigrationFailureTest` catch pin updated to the flag-first ordering |
| 9 | MEDIUM retention TOCTOU (`count` vs `prune` concurrent creators) | NOT REPRODUCIBLE at HEAD: `createNoteVersion` already runs insert + `countVersionsForPage` + `pruneVersionsForPage` inside ONE `db.withTransaction` (phase-149), which serializes concurrent creators on Room's transaction mutex | No change needed; verified, not re-broken | `NoteRepository.kt` `createNoteVersion` transaction block (unchanged) |
| 10 | MEDIUM `getAll*ForReencrypt` unbounded (pages/strokes/embeds; versions already paged) | Confirmed: `migrateFieldRecordAad`, `reencryptPlaintextFields`, `migrateLegacyPlaintextNoteBodies`, `migrateLegacyPlaintextVoiceNotes` all loaded whole tables | FIXED — three new paged DAO queries (`ORDER BY rowid ASC LIMIT/OFFSET`, stable across in-place updates; plain `@Query` additions = no schema change) and all four sweeps loop over `REENCRYPT_BATCH_SIZE` (100) windows | `Daos.kt`: `getPagesForReencryptPaged`, `getStrokesForReencryptPaged`, `getEmbedsForReencryptPaged`; `NoteRepository.kt` four sweeps paged. Pinned by `Phase260StorageTest.re-encrypt sweeps page…` |
| 11 | Crypto leftover: `PBEKeySpec` char[] never `clearPassword` (`EncryptionService.kt`) | Confirmed (both `deriveKey` + `deriveKeyLegacyRaw`) | FIXED — try/finally `keySpec.clearPassword()` on both paths. Residual (documented in code): the normalized `String` is immutable and unwipable — the universal JVM-PBKDF2 residual; returned keys must still be zeroized by callers (unchanged contract) | `EncryptionService.kt` both derive functions |
| 12 | Crypto leftover: `NoteRepository.kt` `Log.w e.message` leaks path/marker | Confirmed (page-body save race log interpolated `e.message`, which embeds absolute vault paths) | FIXED — routed via `FailureLogPolicy.safeLogMessage` (class-name token only, B2-LOG-03 pattern) | `NoteRepository.kt` `updatePageBody` catch; `FailureLogPolicy` import added |
| 13 | `exportSchema=true` but no committed `/schemas` JSON | STALE CLAIM — `app/schemas/.../NoteflowDatabase/{7,8,9}.json` exist and `9.json` covers all 8 tables incl. `rotationDegrees` (3 hits). Only pre-v7 histories are absent, which is normal | No action; claim corrected here | `ls app/schemas/...` + `grep tableName 9.json` |

## 2. Schema-inventory note (why Entities.kt is untouched)

The phase constraint `No Room schema change / migration` forbids exactly the two
schema-shaping fixes the PROMPT lists (FK cascades, composite index): either one
changes the Room schema hash, which fails the open unless `SCHEMA_VERSION` is
bumped with a tested `Migration`. Both are recorded as DEFERRED for the next
schema-version bump (rows 2–3 above); the data-loss halves (torn deletes,
orphaned `note_versions`) are closed in code without touching the schema.

## 3. Files changed

- `data/db/NoteflowDatabase.kt` — fallback removed; `WalAutocheckpointCallback`;
  `runWalCheckpointFull`; `dispose()` inspects busy; flag-first quarantines via
  `quarantineSingleFile`; narrowed `isDatabaseCorruptException`;
  `quarantineMigrateFailed(..., timestamp)` param (default preserves old callers/tests).
- `data/db/Daos.kt` — 3 paged re-encrypt queries (queries only, no schema change).
- `data/repository/NoteRepository.kt` — `collectPageFilesForDelete`,
  `deletePageRowsPermanently`, atomic notebook/section/page/trash deletes,
  post-commit VACUUM, 4 paged sweeps, `checkpointWal(): Boolean`,
  `FailureLogPolicy` log line.
- `services/EncryptionService.kt` — `clearPassword` on both PBKDF2 paths.
- `services/PageDeleteFilePolicy.kt` — NEW pure-JVM delete-confinement policy.
- Tests: NEW `Phase260StorageTest` (9); updated `CorruptionClassifierTest` (+1
  narrowing test), `B1Db02MigrationFailureTest` (flag-first pin),
  `B1Db03VoiceNoteEncryptionTest` (collect→transact→post-commit pins),
  `Phase136TamperBaselineCadenceTest` (checkpoint-helper pins).

## 4. Verification

- `gradle :app:assembleDebug` — GREEN (Room schema validation passes: no schema change).
- `gradle :app:testDebugUnitTest` — **3731 tests, 0 failures / 0 errors / 0 skipped**
  (incl. the 9 new `Phase260StorageTest` + updated classifier/migration/voice/phase-136 suites).
- `gradle :app:lintDebug` — **0 errors** (124 pre-existing warnings/info).
- `verification-metadata.xml` untouched, `.github/workflows/` untouched, no new
  dependencies, `allowBackup=false` untouched, no plaintext rows introduced.

## 5. Residuals / follow-ups (not introduced here, left for a schema-bump phase)

- Real `ForeignKey CASCADE` + composite `(sectionId,pinned,updatedAt)` index need
  SCHEMA_VERSION 10 + tested Migration (constraint-forbidden here).
- `setCorruptionDetected` persists via `apply()` (async): the flag-first ordering
  closes the rename/flag kill window, but a hard kill inside the `apply()` flush
  window could still lose the flag — same pre-existing pattern as the rest of
  `DatabaseSecurityHelper`; flagged, not changed.
- `VACUUM` runs only on `emptyTrash`, not on single-page deletes (per-delete
  VACUUM would be a latency hit for a rare benefit).
