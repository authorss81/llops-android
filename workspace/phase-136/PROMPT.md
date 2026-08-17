# Phase 136: Startup tamper-verify baseline cadence — checkpoint + re-arm at session end so a verified pass means something again [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-B1D-01) and `docs/phase-status.md` + `docs/ARCHITECTURE.md`.
This phase fixes the B1-DB-6 regression-of-design: the WAL-aware HMAC tripwire
is structurally guaranteed `Mismatch` after any write since the last baseline
stamp, so real tampering hides in the noise.

## Source finding (OPEN, MEDIUM)

**R2-B1D-01** — WAL journal mode `NoteflowDatabase.kt:415`; HMAC streams main +
`-wal` (`DatabaseHmacPolicy.kt:42-66`, `DatabaseSecurityHelper.kt:49-72`);
verify runs once per process at init (`NoteflowViewModel.kt:1138-1150` →
`1202-1223` → banner `MainActivity.kt:321-332`). The ONLY re-arm sites are
event-driven (`NoteflowViewModel.kt:1116,1490,1517,1561,2634,3348`,
`HomeScreen.kt:586,1398`) — none on the normal write path. `lock()`
(`NoteflowViewModel.kt:3638-3694`) and `NoteflowDatabase.dispose()` (`:431-436`)
never checkpoint + re-arm. Repro: create master password (baseline armed) →
edit a note → next start shows the "Database integrity check failed" banner for
an ordinary edit; `Mismatch` can never again be trusted as a tamper signal.

## The fix (where & how)

- Checkpoint + re-arm the baseline at every session end — in `lock()` (only when
  a master-password vault is being locked) and in `NoteflowDatabase.dispose()` —
  OR verify only at a quiescent checkpointed state (fresh unlock, post-checkpoint,
  before writes). Mirror the existing re-arm call sites' exact mechanism
  (e.g. `HomeScreen.kt:586`/`NoteflowViewModel.kt:3348`) so main+wal are both
  checkpointed into the stamp.
- Keep the per-session dismissal + re-enable UX intact
  (`IntegrityWarningDismissalGate`).
- Re-assert the B1-DB-6 coverage tests against the new cadence
  (`B1Db06WalCoverageAndDismissalTest`).

## Verification

- New/updated pure-JVM unit tests proving: a vault used since its baseline
  (write → lock → reopen) does NOT trip the banner; a genuine tamper still
  trips it; the checkpoint+re-arm runs on both `lock()` and `dispose()` (source
  pins).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-136/REPORT.md`.

## Definition of done

- R2-B1D-01 closed with `file:line` before/after evidence.
- Ordinary edits no longer produce a false `Mismatch`; real tampering still does.
- No existing test regressed (esp. `B1Db06WalCoverageAndDismissalTest`).

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`
  and the fail-closed lock model intact.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.
