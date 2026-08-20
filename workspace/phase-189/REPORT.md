# Phase 189 — "Backup to file" / "Backup from file" must keep working immediately after a vault export

**Status: DONE (2026-08-20)**

Fixes the reported session-state corruption left by the export chain that made
a follow-up "Backup to file" and "Backup from file" fail until the app was
reopened (the "notes unreadable / last-used notebook missing" sibling symptom is
phase-182/181 scope and was already fixed).

---

## Root cause (Step 1 — `workspace/phase-189/STEP1_TRACE.md`)

The export chain is **session read-only**: no exporter calls
`closeDatabase`/`reopenDatabase`/`zeroizeKey`, matches the phase-169/182
no-close pins, and cannot by itself corrupt a row. It DID, however, hold exactly
two live-handle dependencies inside `exportBackup` (`ImportExportService.kt:1451`):

1. `repository.checkpointWal()` + `repository.stampDatabaseChecksum(context)` +
   `VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)` run against the
   LIVE main DB file; `checkpointThenCopy` fails CLOSED ("Backup failed: the vault
   database kept changing during the snapshot copy. Please try again.") when a
   concurrent WAL auto-checkpoint rewrites the main file mid-copy for all
   retries.
2. **The staged-snapshot prune helpers re-read the mutable singleton
   `VaultKeyHolder.dek` at PRUNE time** — long after the export decision was
   made — and threw "Backup failed: the vault is locked; cannot bound the
   {version-history,layer} snapshot." when a lock zeroized the DEK in between.

The lock that lands mid-export is `MainActivity.kt:207-209`: ANY `ON_STOP`
(including the SAF export-destination picker, and the screen-off receiver
`:138-144`) calls `viewModel.lock()`, and for a password-protected vault
(`NoteflowViewModel.kt:4688`, `:4747`) that zeroizes the DEK and tears the
session. Because the prunes read `VaultKeyHolder.dek` at prune time inside the
same export run, a lock mid-export failed the CURRENT export and the
immediately-following "Backup to file"/"Backup from file" stayed broken until
the identity was re-seeded (a restart) — exactly the reported symptom.

## Fix (Step 2)

- New pure-JVM `services/ExportSessionPolicy.kt`:
  - `pinnedPruneDek(key, holderDek)` resolves the export DEK ONCE at export
    start and returns a **COPY** — a mid-export zeroization of the source array
    can never null out the snapshot passphrase.
  - `zeroize(snapshot)` — the zeroization contract for the pin.
  - Fixed backup texts centralized (`KEEP_CHANGING_ERROR`,
    `LOCKED_SNAPSHOT_ERROR`; user-visible wording unchanged).
- `exportBackup` resolves the pin at export start —
  `ExportSessionPolicy.pinnedPruneDek(key) { VaultKeyHolder.dek }
  ?: throw IllegalStateException(ExportSessionPolicy.LOCKED_SNAPSHOT_ERROR)` —
  passes it to BOTH staged prunes, and zeroizes it in a `finally` immediately
  after them (`ImportExportService.kt:1480-1509`). The prunes now take
  `(stagedDb, dek)` and contain **zero `VaultKeyHolder` references**
  (`pruneStagedSnapshotVersions` `:2762`, `pruneStagedSnapshotLayers` `:2724`).
- The export runs entirely against the staged copy under the SAME key it was
  handed; a lock that lands mid-export can no longer fail the current backup nor
  poison the next one.

## Regression evidence (Step 3)

- `Phase189ExportSessionStateLossTest` (7 tests, pure JVM + source pins):
  - the pin is a DISTINCT copy holding the export key's bytes;
  - zeroizing the live array mid-export does not null the pin (backup proceeds);
  - the holder fallback still yields a copy, immune to a zeroized holder too;
  - locked vault with no key and no holder degrades to the fail-closed refusal;
  - `zeroize` clears the pin after use (null is a safe no-op);
  - source pins: exportBackup resolves/passes/zeroizes the pin, both prunes are
    keyed by the `dek` param with zero `VaultKeyHolder`, fixed texts centralized
    with no inline duplicate strings.
- Updated source pins to the pinned-DEK structure:
  - `Phase137BackupCopyConsistencyTest` (fail-closed text now routes through
    `ExportSessionPolicy.KEEP_CHANGING_ERROR`; wording asserted unchanged);
  - `Phase149NoteVersionsRetentionTest` + `Phase150CanvasRenderBudgetTest`
    (prunes keyed by `dek` param, singleton-decoupled).
- `gradle assembleDebug` — **green** (app debug APK built).
- `gradle testDebugUnitTest` — **2509 total, 2 pre-existing flakes only**
  (`Phase148UiFailureTextScrubTest` UNC-path + `WikiLinkParserCacheUnitTest`
  cancellation — both documented as passing in isolation per AGENTS.md).
- Targeted run of `Phase189ExportSessionStateLossTest` +
  `Phase149NoteVersionsRetentionTest` + `Phase150CanvasRenderBudgetTest` +
  `Phase181ExportReturnNotebookRestoreTest` — all green.

## Files touched

- `app/src/main/kotlin/com/authorss81/noteflow/services/ExportSessionPolicy.kt` (new — pure-JVM DEK pin + fixed backup texts).
- `app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt` (`exportBackup` pin + zeroize-finally; both prune signatures take `dek`; KDoc updates).
- `app/src/test/java/com/authorss81/noteflow/Phase189ExportSessionStateLossTest.kt` (new).
- `app/src/test/java/com/authorss81/noteflow/Phase137BackupCopyConsistencyTest.kt`, `Phase149NoteVersionsRetentionTest.kt`, `Phase150CanvasRenderBudgetTest.kt` (pins updated).
- Docs: `docs/ARCHITECTURE.md` (phase-189 note + services table), `docs/phase-status.md` (phase-189 → DONE).

No schema change, no migration, no new dependencies, `.github/workflows/` untouched, base-APK-size rule intact.