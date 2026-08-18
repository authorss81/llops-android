# Phase 163: "Don't show again" must actually persist for the data-recovery screens [NOT STARTED]

You are working on **InkFlow/Noteflow**. Users report that "Don't show again" on
the data-recovery screens "does not work" — the screen keeps reappearing.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context (find the real bug)
- `MainActivity.kt` has TWO different "Don't show again" flows:
  1. The DB-integrity tripwire banner (L386, L405) → `viewModel.dismissDatabaseIntegrityWarning(dontShowAgain)`
     → `IntegrityWarningDismissalGate` (`services/IntegrityWarningDismissalGate.kt`). This one is
     INTENTIONALLY per-session only (phase-87/B1-DB-6 security decision: a permanent
     dismiss would disable the tamper-evidence tripwire). Do NOT make it permanent.
  2. `CorruptionRecoveryScreen` (MainActivity ~L1011) and `KeystoreKeyLostRecoveryScreen` (~L1104):
     the "Don't show again this session" checkbox (L1256-1271, `dontShowAgain` + `onDismiss`).
- These recovery screens DO have a checkbox. The user says it doesn't stick.
- Investigate where each recovery screen's dismissal decision goes after
  `onDismiss(dontShowAgain)`:
  - Is the flag persisted to `SettingsManager` (a pref) or only held in a
    ViewModel/`remember` latch that dies on process death / rotation?
  - Does the corruption-flag latch (`corruptionTimestamp`, DB-corrupt flags in
    `NoteflowDatabase.kt` / `DatabaseSecurityHelper.kt`) re-arm on every cold
    start regardless of the saved dismissal? (Remember phase-135 made recovery
    state `rememberSaveable`, so rotation may survive — but a restart may not.)
- The intent: once the user has acted (restored / started fresh / explicitly
  chose "don't show again"), the recovery screen must not nag on EVERY launch.
  But the security model must stay intact: do NOT hide real corruption forever —
  distinguish "user already handled this corruption event (same quarantine
  timestamp)" from "a NEW corruption occurred" (new timestamp → must show again).

## Definition of done
- Root cause identified and documented in `workspace/phase-163/REPORT.md`
  (exactly why the checkbox doesn't stick).
- "Don't show again" for the corruption/key-lost recovery screens persists across
  process death AND cold start for the SAME corruption event (key it to the
  quarantine/event timestamp, not a bare boolean).
- A NEW corruption event (new timestamp) ALWAYS re-shows the screen even if an
  old event was dismissed. A restored/fixed vault is not nagged.
- The phase-87 DB-integrity tripwire banner stays per-session (do NOT make it
  permanent — do not touch `IntegrityWarningDismissalGate`'s session semantics).
- Unit tests cover: same-event dismissal persists, new-event re-shows, restore
  path clears dismissal.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT weaken the tamper-evidence tripwire
  or the fail-closed security model.
- Keep UI text/labels unchanged unless a label is wrong.
- Respect AGENTS.md (no DB schema change without approval; prefer prefs).