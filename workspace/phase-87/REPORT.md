# Phase 87 — B1-DB-6 (LOW): WAL frames outside the tamper HMAC + one-tap permanent disable

## Finding (from `docs/security-report.md`)

`DatabaseSecurityHelper.computeDatabaseHmac` (`DatabaseSecurityHelper.kt:49-65` at audit time)
streamed only `noteflow.sqlite`, but the vault runs `WRITE_AHEAD_LOGGING`
(`NoteflowDatabase.kt:358` → `JournalMode.WRITE_AHEAD_LOGGING` in the current tree at
`NoteflowDatabase.kt:415`), so committed-but-uncheckpointed data lives in `-wal`, which the
HMAC never covered. Two exploitable halves:

- **(a) WAL coverage gap** — a WAL-only mutation committed between two checkpoints edited/forged
  data undetected because the target file never covered the modified bytes.
- **(b) one-tap disable downgrade** — `verifyDatabaseIntegrity` re-baselines when the stored
  checksum is missing (that half = B1-CRYPTO-06, phase-91, untouched here), AND the tamper
  banner's "Don't show again" checkbox at `NoteflowViewModel.kt:974-981` set
  `settings.databaseIntegrityCheckEnabled = false` **permanently**; a plain "OK" additionally
  persisted `settings.databaseIntegrityWarningDismissed = true`, so one tap could permanently
  kill the vault's only tamper tripwire.

## Fix — what changed (file:line, before → after)

### 1. WAL-aware tamper HMAC

- **Before:** `DatabaseSecurityHelper.computeDatabaseHmac` looped its own `dbFile.inputStream()`
  over ONLY the main file.
- **After:** new pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/DatabaseHmacPolicy.kt`:
  `walFile()` (`:34`) names the `-wal` companion; `streamDbAndWal(mac, dbFile)` (`:41-65`)
  streams the main file then `-wal` (when a file) through the SAME initialised `Mac` and returns
  the total bytes consumed. `DatabaseSecurityHelper.computeDatabaseHmac` (`DatabaseSecurityHelper.kt:50-65`)
  now routes every baseline computation through it (`:60`, returns `null` when 0 bytes consumed).
  A WAL-only mutation is now detected at the next verification; a cleanly-emptied `-wal`
  contributes byte-identical state to an absent one (verified by test), so a fully-checkpointed
  vault never false-positives by itself.

  **Baseline-arming site audit** (every place that writes `db_hmac_checksum` now covers
  `main + wal`; because each arm already checkpoints first or reads a closed raw file, a freshly
  armed baseline covers `(main + empty/absent wal)` and any post-arm frame is captured):

  | Arm site | Pre-existing hygiene |
  |---|---|
  | `NoteflowViewModel.initializeData` body-migration (`NoteflowViewModel.kt:1337-1339`) | `repository.checkpointWal()` before `stampDatabaseChecksum` |
  | `NoteflowViewModel.initializeData` voice-migration (`:1364-1366`) | `checkpointWal()` before stamp |
  | `NoteflowViewModel.setMasterPassword` (`:2455-2459`) | `reencryptPlaintextFields` + `checkpointWal()` before stamp |
  | `NoteflowViewModel.exportEncryptedBackupToZip` (`:3115-3117`) | `checkpointWal()` before stamp |
  | `HomeScreen` device-keyed backup (`HomeScreen.kt:529-531`) | `checkpointWal()` before stamp |
  | `HomeScreen` password backup (`HomeScreen.kt:1320-1322`) | `checkpointWal()` before stamp |
  | `ImportExportService.restoreFromZip` → `rearmBaselineFromFile` (`ImportExportService.kt:1805`) | temp DB closed + re-keyed via raw SQLCipher (rollback journal mode), main complete |
  | `NoteflowDatabase.migratePlaintextIfNeeded` (`NoteflowDatabase.kt:250`) | raw SQLCipher export, closed file |
  | `NoteflowViewModel.startFreshAfterCorruption` (`:1065`) | brand-new empty vault (no WAL) |

  No new checkpoint wiring was required — all arms were already quiescent; the fix closes the
  coverage gap itself.

### 2. Per-session (never permanent) banner dismissal

- **Before:** `NoteflowViewModel.kt:1091-1098` wrote `settings.databaseIntegrityCheckEnabled =
  false` when the checkbox was ticked (permanent) and always set
  `settings.databaseIntegrityWarningDismissed = true`; the init verify gated the banner on the
  persisted `!settings.databaseIntegrityWarningDismissed` (`:1084`).
- **After:**
  - New pure-JVM `app/src/main/kotlin/com/authorss81/noteflow/services/IntegrityWarningDismissalGate.kt`
    — an in-memory per-session latch (`sessionDismissed` `@Volatile`): `mayShow()`, `onDismiss(dontShowAgain)`
    (`dontShowAgain` only suppresses the current session), `onReenable()`. One instance lives in
    the ViewModel for the process lifetime, so every launch starts a new undismissed session.
  - `NoteflowViewModel.kt`: field `integrityWarningDismissal` (`:1034-1038`); init verify gates on
    `integrityWarningDismissal.mayShow()` (`:1091`); `dismissDatabaseIntegrityWarning` (`:1106-1109`)
    ONLY calls `onDismiss(dontShowAgain)` + clears the banner — it never touches
    `databaseIntegrityCheckEnabled`; `setDatabaseIntegrityCheckEnabled(true)` calls
    `integrityWarningDismissal.onReenable()` (`:1115`).
  - The persisted `databaseIntegrityWarningDismissed` latch is no longer read OR written anywhere
    in the ViewModel (zero `settings.databaseIntegrityWarningDismissed` references; the SettingsManager
    accessor is left in place untouched — validator convention, nothing consumes it).
  - Honest UX: the checkbox is relabelled "Don't show again this session" (`MainActivity.kt:362`),
    per AGENTS.md "never silent degradation".
  - The settings switch `setDatabaseIntegrityCheckEnabled(false)` (an explicit user setting, reachable
    only from Settings) remains a legitimately-persistent control — out of scope for the banner fix.

## Constraints honoured

- No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.
- `allowBackup=false`, ClipboardGuard, FLAG_SECURE intact; no keys/passwords/decrypted content logged.
- API 26+ floor: both new policies are pure `java.io`/`javax.crypto` (the HMAC streams over the same
  `Mac` API Android has exposed since API 1; no newer-API requirement, no fallback needed).

## Out of scope (documented, NOT touched)

- **B1-CRYPTO-06** fail-open re-baseline at `DatabaseSecurityHelper.verifyDatabaseIntegrity`
  (`DatabaseSecurityHelper.kt:147-152`, stored==null → `updateStoredChecksum` + `return true`) is its
  own phase-91 finding. This phase deliberately leaves it, so the same arm-site semantics apply
  unchanged.
- **B1-DB-8 / B1-CRYPTO-10 / B2-CRYPTO-09** field-layer integrity findings — separate phases.
- The residual "legitimate post-arm write then clean-close checkpoint changes the main file ⇒ a
  startup verify flags it" behavior is inherent to a whole-file tripwire on a live vault (pre-existing,
  acknowledged in the finding), and is exactly why the dismissal must be per-session so the user is
  not walked into permanently turning the tripwire off.

## Checksum / secrets handling

- The HMAC continues to use the non-extractable AndroidKeyStore key (`noteflow_db_hmac_key`);
  `DatabaseSecurityHelper` unchanged there. The new `DatabaseHmacPolicy` only consumes an
  already-initialised `Mac` — it never touches keys, passwords, or note content; the bytes it
  hashes are the encrypted SQLCipher files (main + WAL) at rest, so no plaintext leaks into the
  HMAC either.

## Tests

New `app/src/test/java/com/authorss81/noteflow/B1Db06WalCoverageAndDismissalTest.kt` (16 tests):

- **WAL coverage (behavioral, pure JVM):** `a WAL frame mutation is detected at the next
  verification`; `appending a WAL file after the stamp is detected`; `removing the WAL after a
  stamp that covered it is detected`; `main-file mutation with a WAL present is still detected`;
  `identical file states verify cleanly`; `an empty WAL contributes the same bytes as an absent
  WAL`.
- **Per-session dismissal (behavioral):** fresh session may show; a plain OK does not suppress the
  banner for the session; don't-show-again suppresses for the rest of the session ONLY; a fresh
  launch re-arms; re-enable clears the session dismissal; a two-session model proving a
  don't-show-again tap never permanently disables the check.
- **Source pins:** `DatabaseSecurityHelper` streams through `DatabaseHmacPolicy.streamDbAndWal`
  and the old main-file-only inline loop is gone; the dismiss function never flips/reads
  `databaseIntegrityCheckEnabled` or `databaseIntegrityWarningDismissed`; the VM has zero
  `settings.databaseIntegrityWarningDismissed` references and gates the init banner on the session
  gate; the checkbox is labelled "Don't show again this session".

## Verification output

Full results appended in the Addendum below (ran 2026-08-16 on the GA runner, gradle 8.13).

## Addendum — verification results

Ran on the GA runner (gradle 8.13) — see this table, then the commands below.

| Check | Command | Result |
|---|---|---|
| New test class in isolation | `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B1Db06WalCoverageAndDismissalTest"` | BUILD SUCCESSFUL — `tests="16" failures="0" errors="0"` |
| Full unit suite | `gradle testDebugUnitTest` | BUILD SUCCESSFUL — aggregate `1548` tests, `0` failures, `0` errors (1532 pre-existing + 16 new; the historic `B1Plat01ReleaseSigningTest` 2-assert gap also green this run) |
| Debug APK | `gradle assembleDebug` | BUILD SUCCESSFUL (first run hit a transient failure; the incremental re-run succeeded, `90 actionable tasks: 90 up-to-date`) — `app/build/outputs/apk/debug/app-debug.apk` 165.8 MiB, SHA-256 `2a84a63a4a29526aa2d66476a8569ec9c99a1851f571ed4e0a53f230aba5d8f1` |