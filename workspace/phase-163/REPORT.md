# Phase 163 — "Don't show again" must actually persist for the data-recovery screens

Status: `DONE` (after review-fix round) · Commits: `02921ab`, `dc050b2` (original), review-fix commit on 2026-08-19.

## Root cause (why the checkbox didn't stick)

- `MainActivity.kt`'s two data-recovery composables — `CorruptionRecoveryScreen` and
  `KeystoreKeyLostScreen` — **never had** the "Don't show again" control the phase
  prompt assumed (the prompt's "L1256-1271 checkbox" referenced a state that no
  longer exists in the tree). The only "Don't show again" UI in the app is the
  per-session `IntegrityBannerCard` (deliberately session-only, phase-87/B1-DB-6).
- There was therefore **no UI path at all** that could persist a recovery-screen
  dismissal. The screens could only be exited via restore/start-fresh, so the
  "screen keeps reappearing" complaint was, in effect, a missing feature rather
  than a non-persisting boolean.

## What phase-163 ships

1. **Keyed dismissal, never a bare boolean** — new pure-JVM
   `services/RecoveryDismissalPolicy.kt` decision table: a dismissal is keyed to
   the recovery *event* timestamp (corruption quarantine stamp / recorded
   keystore-lost event), so the SAME event stays dismissed across cold start but
   a NEW event (fresh timestamp / a different lost wrapper alias) always re-shows.
   An un-keyable legacy event (timestamp ≤ 0) fails closed and can never be
   silenced permanently.
2. **UI wired** (review-fix) — both recovery screens now render a
   "Don't show again for this … event" checkbox + Dismiss button at the bottom,
   routed through `viewModel.dismissCorruptionRecovery(dontShowAgain)` /
   `viewModel.dismissKeystoreKeyLostRecovery(dontShowAgain)`.
3. **Persistence** — `DatabaseSecurityHelper` prefs (`.commit()`, R2-B1D-01-style
   atomicity) record the dismissed event timestamp per recovery-family; a NEW
   `setCorruptionDetected` stamp (fresh timestamp) re-arms every gating site
   (`NoteflowViewModel` constructor, open-failure handler, decrypt-failure
   escalation).
4. **Cleanup** — `clearCorruptionDetected` drops the dismissed key with the event;
   the keystore-lost restore and start-fresh paths now call
   `clearKeystoreLostDismissal` (which also drops the recorded event identity).
5. **Single event identity** (review-fix) — `DekReadResult.AuthRequired` now
   carries the same non-secret `wrapperAlias` as `KeyLost`, and all three
   keystore-lost detection sites key the dismissal off that one `readDekResult()`
   result instead of re-reading the blob (`currentWrapperAlias()` removed).

## Untouched (per constraints)

- The phase-87 DB-integrity tripwire banner + `IntegrityWarningDismissalGate`
  stay strictly per-session.
- No DB schema change, no `.github/workflows/` edits; prefs only.

## Tests

`Phase163RecoveryDismissalTest` (pure-JVM decision table + source wiring pins):
same-event dismissal suppresses; new-event re-shows; un-keyable event fails
closed; dismissal only persistable for a keyable event; both recovery screens
route the checkbox through the ViewModel; corruption-clear drops the dismissed
key; keystore restore + start-fresh clear the event; every detection site uses
the single-read alias.

Verification: `gradle testDebugUnitTest` (full suite) + `gradle assembleDebug`
green (see review-fix commit message for the run result; the one pre-existing
`Phase148UiFailureTextScrubTest` UNC-path failure is untouched).