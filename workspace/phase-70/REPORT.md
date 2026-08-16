# Phase 70 — B2-LOG-02: `app_startup.log` unbounded append (no cap, rotation, or pruning)

## Finding

`docs/security-report.md` B2-LOG-02 (MEDIUM). `AppStartupLogger.appendToFile` was
`FileWriter(logFile, true)` — append-only with no length check, no rotation, no delete —
so weeks of events grew `app_startup.log` unboundedly on the same partition as the
encrypted vault (ENOSPC pressure on the supported low-RAM/2-core class) and retained
crash history indefinitely. `PrivacyCrashReporter` (contrast) at least wiped its
`noteflow_sanitized_crash.log` at 500KB. The dead `getLogs`/`clearLogs` accessors also
retained an unguarded read/dump surface with no caller.

## What changed (file:line)

- **NEW `app/src/main/kotlin/com/authorss81/noteflow/services/StartupLogPolicy.kt`**
  (pure JVM, `java.io.File` only): the single budget + rotation decision table.
  - `LOG_FILE_NAME` (`:46`), `BACKUP_SUFFIX = ".1"` (`:52`),
    `MAX_LOG_BYTES = 500_000L` (`:62` — same ~500KB budget
    `PrivacyCrashReporter.writeLogToFile` already uses), `MAX_LOG_FILES = 2` (`:73`).
  - `wouldExceedCap(currentBytes, incomingBytes)` (`:91`) — the strict rotate decision
    taken BEFORE a write, so the ACTIVE file can never grow past the cap (barring a
    single line over the cap on its own, which startup event lines never are).
  - `rotateForAppend(dir)` (`:101`) — keep-last-N semantics: drop the oldest retained
    generation (the `.1` slot), move the active file into the backup slot.
  - `pruneOnInit(dir)` (`:117`) — clears any leftover active/backup file that exceeds
    the cap, so a process killed mid-rotation cannot leave an over-budget file behind.
  - KDoc (`:10-40`) documents the finding, and the dead-code clause: `getLogs`/`clearLogs`
    are removed; any future "export/share logs" UI MUST sanitize log text before it
    leaves the device.
- **REWRITEN `app/src/main/kotlin/com/authorss81/noteflow/utils/AppStartupLogger.kt`**
  - `init` (`:44-52`): schedules `StartupLogPolicy.pruneOnInit(appContext.filesDir)` on
    the background log executor (no main-thread file I/O, phase-08 constraint kept).
  - `appendToFile` (`:67-84`): `if (logFile.exists() && StartupLogPolicy.wouldExceedCap(...))
    StartupLogPolicy.rotateForAppend(...)` BEFORE the `FileWriter(logFile, true)` append;
    failure path still logs a fixed message only (never the exception object —
    phase-48/B2-LOG-01 posture).
  - `getLogs`/`clearLogs` (dead code, old `:65-88`) **deleted**; the private
    `LOG_FILE_NAME` constant moved into the policy (single source of truth).
  - Phase-48 invariants retained: no uncaught-exception handler, no `printStackTrace`,
    no raw dump, no throwable forwarded to logcat.
- `MainActivity.kt:133` unchanged — the only `logEvent` call is the fixed-string
  `"MainActivity.onCreate started"` (the per-lifecycle-event logging cited in the finding
  was already reduced by later phases).

**Before / after (the vulnerability path):**

```
Before  AppStartupLogger.kt:53-63  FileWriter(logFile, true) — append-only, no length
                                   check, no rotation, no delete ⇒ filesDir grows
                                   indefinitely; raw crash blocks retained forever.
After   AppStartupLogger.kt:71-80  StartupLogPolicy.wouldExceedCap/rotateForAppend gate
                                   the write ⇒ active log never exceeds 500KB; keep-last-
                                   N=2 caps total retention at 2 × 500KB; pruneOnInit on
                                   every init; raw-dump path excluded since phase-48.
```

## Verification

- `gradle :app:testDebugUnitTest` → **1353 tests completed, 1350 green, 3 failed**.
  - The 2 `B1Plat01ReleaseSigningTest` failures are the documented pre-existing asserts
    on `docs/RELEASE.md` + `app/build.gradle.kts` signing (present and untouched since
    phase-55; reproduced identically in isolation — neither file is touched by this phase).
  - `WikiLinkParserCacheUnitTest` (cancellation-timing) failed once in the full run and
    PASSES in isolation — the pre-existing flake already documented in phases 40/67;
    unrelated to this diff.
  - New `B2Log02StartupLogRotationTest`: **14 tests green** (behavior + source pins).
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (57 tasks; debug APK 173,743,694 B,
  SHA-256 `ce7542bcc7a0dd4b0ec02679d55b4143014a9748330411b4316a5cf0ae99fda7`).

## New test coverage (`app/src/test/.../B2Log02StartupLogRotationTest.kt`, 14 tests)

Behavior (pure JVM, mirrors the AppStartupLogger append cycle line-for-line):
- The append gate never lets the active log exceed `MAX_LOG_BYTES` under 25k sustained
  event writes.
- Keep-last-N: after sustained writes exactly 2 log files exist and the TOTAL retained
  bytes are ≤ 2 × cap; the backup generation itself respects the cap.
- Generation retention: the active holds the newest content, the `.1` slot the previous
  generation, the oldest is dropped.
- First-ever write does not rotate; `wouldExceedCap` boundary table.
- Prune-on-init drops an oversized leftover active/backup and leaves under-budget files
  byte-identical.
- Constants contract + the 500KB budget matches `PrivacyCrashReporter`'s existing cap.

Source pins:
- AppStartupLogger's append path routes through `StartupLogPolicy.activeFile` /
  `wouldExceedCap` / `rotateForAppend` with the gate BEFORE the `FileWriter`; `init`
  calls `pruneOnInit`.
- `getLogs`/`clearLogs`/`readText()`/`"No logs available."` removed; no re-declared
  `LOG_FILE_NAME` (single source = the policy).
- Phase-48 posture re-pinned (no `setDefaultUncaughtExceptionHandler`, `printStackTrace`,
  `logCrash`, or `, e)` in AppStartupLogger).
- The policy file is pure JVM (no `android.`/DB/keystore references), so it can never
  touch the vault, DEK, passwords, or decrypted content.

## Checksums / secrets handling

- No keys, passwords, decrypted note content, or app-private paths are ever written to
  the startup log (event lines are timestamps + fixed strings).
- The policy and logger only ever log fixed-string failure messages; exceptions are
  never passed to logcat (phase-48 posture, source-pinned).
- `allowBackup=false`, `ClipboardGuard` and FLAG_SECURE untouched.

## Out of scope (documented, not fixed here)

- B2-LOG-03 (ImportExportService `Log.e/w(…, e)` full-exception logcat leaks) — own phase
  (phase-71 per the security-report pipeline table).
- B2-LOG-07 (jank/lifecycle metadata in logcat) — own phase (phase-111).
- `PrivacyCrashReporter`'s wipe-at-500KB cap was already bounded; its internal
  `noteflow_sanitized_crash.log` is a separate file and untouched.
- The `JankStatsHelper` logging and `MainActivity` `MonitorJank` are B2-LOG-07 territory,
  not this finding.

## Constraints honored

- No DB schema change, no migration (REPORT note: not required — this fix is
  file-retention policy only).
- No new dependencies. `.github/workflows/` untouched.
- No other security finding fixed (the discovered related items above are documented
  and left to their own phases).