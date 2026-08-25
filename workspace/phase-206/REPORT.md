# Phase 206 REPORT — Kill Perpetual Pollers: Event-Driven Idle/Thermal/Audio Clocks

**Date:** 2026-08-25 · **Scope:** PERF/BATTERY · **Schema change:** none · **New deps:** none · **Workflow edits:** none

## What shipped (per prompt item)

### 1. Choreographer frame pump — leaked, stacked per editor open → event-driven, owned, unregistered

**Before** (`AnnotationCanvas.kt`, old `:839-864`): a `LaunchedEffect(Unit)` posted a SELF-REPOSTING
`Choreographer.FrameCallback` (`choreographer.postFrameCallback(this)` inside `doFrame`). Cancelling
the effect did NOT unregister it — there was zero `removeFrameCallback` call-site in the whole repo.
Every editor visit therefore STACKED another immortal 60-120 Hz loop doing, per frame:
frame-time sampling into the wet engine EMA + a `PowerManager` thermal-status service call +
a full tier/fallback re-evaluation — even on a static untouched note.

**After:**
- New owner `ui/components/WetBrushFramePump.kt`. The callback is a class member; `stop()` calls
  `choreographer?.removeFrameCallback(frameCallback)`.
- `AnnotationCanvas.kt:848-861`: the pump is `remember`ed and torn down by
  `DisposableEffect(wetFramePump) { onDispose { wetFramePump.stop() } }` — teardown can no longer leak it.
- The self-repost is GATED: `doFrame` bails on `!active.get()` before re-posting. `active` is armed by
  `wetFramePump.start()` exactly when ink starts flowing (`AnnotationCanvas.kt:1281`, the branch that
  seeds `activePoints`) and disarmed on BOTH gesture end paths (`onDragEnd :1382`,
  `onDragCancel :1533`) plus disposal. Idle gaps reset `lastFrameTimeNanos` so the first delta of a new
  stroke cannot poison the EMA with a huge elapsed value.
- Thermal status + tier re-evaluation are sampled at most once per
  `THERMAL_SAMPLE_INTERVAL_MS = 1_000L` (≤1 Hz), not per frame.
- API gate preserved: below TIRAMISU the pump is a no-op (parity with pre-206 behavior).

### 2. Auto-lock fixed 1 s poll → deadline-scheduled one-shot wakes

**Before** (`MainActivity.kt`, old `:392-407`): `while (authenticated) { delay(1_000); check }` woke
every second while unlocked even when `timeoutSeconds == 0` (auto-lock OFF) made
`shouldAutoLock` return false forever.

**After** (`MainActivity.kt:385-424`):
- Pure-JVM `AutoLockPolicy.nextCheckDelayMs(nowMs, lastActivityAtMs, timeoutSeconds)` returns the sleep
  until the EXACT moment the current idle window elapses — or `null` when disabled, in which case the
  scheduler exits WITHOUT arming any timer.
- One wake per idle window; each wake recomputes from the LATEST `lastActivityAtMs` stamp, so touches
  during a sleep simply push the next wake out (worst case ≈ one extra wake per window while actively
  drawing, not per touch).
- `IDLE_CHECK_INTERVAL_MS` is DELETED from `AutoLockPolicy.kt` (structurally impossible to reintroduce
  the fixed poll).
- Security posture unchanged (phase-60/B1-PLAT-4): the lock fires AT the deadline without needing
  another touch; fresh baseline stamped at every unlock; `pointerInput` touch handler still only stamps;
  the `ACTION_SCREEN_OFF` instant-lock receiver is untouched.

### 3. Voice playback position 20 Hz StateFlow poll → documented 200 ms tick

**Before** (`VoiceNoteManager.kt:400-409` old): `_playbackPositionMs.value = player.currentPosition`
every 50 ms → seek-bar/waveform recomposition 20×/s for the whole session.

**After:** top-level documented const `PLAYBACK_POSITION_POLL_MS: Long = 200L` (`VoiceNoteManager.kt:24`)
used at `:419` (5 Hz). Drag-seek accuracy unchanged: `seekTo()` publishes the exact target position to
the flow immediately (`:461-467` region) and the next tick re-syncs to `player.currentPosition`.
The actual security/behavior gates (`lockoutActive()`, unlock button) read `settings.lockoutUntilEpochMs`
directly and never depended on this cadence for correctness either way — this loop was purely UI-rate.

### 4. Lockout countdown 1 Hz ticker → minute milestones + exact-zero transition

**Before** (`NoteflowViewModel.startLockoutTicker` old `:3551-3568`): `delay(1000)` per iteration —
up to ~900 wakes to animate a minute-granularity countdown over a 15-minute lockout.

**After:** new pure-JVM `services/LockoutTickerPolicy.nextWakeDelayMs(remaining)` aligns each wake to
the next TRUE minute boundary of the remaining time (`remaining % 60_000`; a whole-minute remainder
waits a full minute). `NoteflowViewModel.startLockoutTicker` (`:3552-3578`) sleeps on that schedule;
the exact-zero clearing behavior is preserved verbatim: on expiry the flow gets `_lockoutRemainingMs = 0`
and `settings.lockoutUntilEpochMs = 0L` is still written once.
Honesty note: between milestones the displayed "Xm Ys" can hold a value up to 59 s ABOVE the true
remaining time (it updates at true boundaries) — the countdown never UNDERSTATES, so the LockScreen
unlock button (`enabled = !isLockedOut`) can never enable early. The real lockout gate
(`lockoutActive()`) reads settings directly and was always independent of this flow.

## Wake math — before vs after (wakeups/hour)

| Loop | Before | After |
|---|---|---|
| Choreographer pump, idle editor open (static note) | 60-120 Hz ⇒ **216,000-432,000/hr** (+1 thermal service call + 1 tier eval per frame) | **0/hr** (pump runs only while ink flows) |
| Choreographer pump, active drawing session | same as above | 60-120 Hz ONLY while stroking (needed), thermal ≤1 Hz instead of per-frame |
| Auto-lock ON (300 s), foreground idle | 3,600/hr | **~13/hr** (12 window expiries + final lock) then 0 after lock |
| Auto-lock OFF | 3,600/hr (pure waste) | **0** (no timer armed) |
| Voice playback 10-min session | 20 Hz ⇒ **12,000/session** | 5 Hz ⇒ **3,000/session** |
| Lockout countdown, 15 min | ~900 wakes | **15** (minute milestones; last performs zero transition) |
| Lockout countdown, 30 s backoff | ~30 wakes | **1** (single wake at expiry) |

## Tests

- New `Phase206EventDrivenTimersTest` (**13 tests**, all green — JXML `tests="13" failures="0"`):
  - pure JVM: `AutoLockPolicy.nextCheckDelayMs` (OFF ⇒ null; exact-window math; elapsed ⇒ 0 clamp;
    future stamp never negative; Int.MAX timeout overflow-free; constant deleted),
    `LockoutTickerPolicy` (expiry ⇒ null; boundary alignment incl. exact-minute/30 s/1 ms cases;
    15-minute simulation proving **15 wakes vs ~901** loop passes pre-206);
  - source pins: `removeFrameCallback` wired in `stop()`; `doFrame` gated BEFORE its re-post;
    AnnotationCanvas creates NO raw Choreographer callbacks anymore and drives start/stop from gestures +
    DisposableEffect teardown; no `IDLE_CHECK_INTERVAL_MS` / unconditional interval poll anywhere in
    MainActivity; auto-lock-OFF exit pinned; documented `PLAYBACK_POSITION_POLL_MS = 200L` present with
    `delay(50)` gone; lockout ticker routes through the policy, keeps exact-zero clearing and no `delay(1000)`.
- Updated `B1Plat04AutoLockTest`: the phase-60 pin `inactivity auto-lock is a continuous poll...` is
  rewritten as `inactivity auto-lock is deadline-scheduled, not a next-touch check` — same invariants
  (policy-routed decision, `viewModel.lock()` over the window, baseline refresh, touch handler stamps only,
  never gated behind next touch) + new assertions that the fixed-interval poll is gone.
- Full suite: `gradle testDebugUnitTest` → **2814 tests, 3 failures, all pre-existing/environmental**:
  `Phase148UiFailureTextScrubTest` (UNC-path failure documented in AGENTS.md) and
  `PaparazziSmokeTest` ×2 (paparazzi layoutlib init crash inside
  `app.cash.paparazzi.internal.Renderer.configureBuildProperties` — clean-stash-reproduced in
  workspace/phase-205/REPORT.md; identical signatures here; unrelated to this diff's timer changes).
- `gradle assembleDebug`: green.

## Verification anchors (file:line, post-change)

- `ui/components/WetBrushFramePump.kt` (new): gated `doFrame`, `removeFrameCallback` in `stop()`, ≤1 Hz thermal.
- `AnnotationCanvas.kt:848-861` (pump remember + DisposableEffect dispose), `:1281` (start at ink),
  `:1382`/`:1533` (stop on end/cancel).
- `services/AutoLockPolicy.kt:53` (`nextCheckDelayMs`; `IDLE_CHECK_INTERVAL_MS` deleted);
  `MainActivity.kt:403-411` (deadline scheduler; OFF exits without arming).
- `services/VoiceNoteManager.kt:16-24` (documented const), `:419` (tick).
- `services/LockoutTickerPolicy.kt` (new); `NoteflowViewModel.kt:3566-3572` (milestone delay + zero transition).

## Out of scope (deliberate)

- The phase-196 motion-PREDICTION frame loop (`withFrameNanos` in `AnnotationCanvas.kt:635`) also wakes
  per frame while composed, but unlike the leaked Choreographer callback it lives in a `LaunchedEffect`
  coroutine that IS cancelled on disposal (cancellation-safe) — different defect class, untouched here.
- The phase-205 render-side LASER fade clock already runs only while trails exist (event-scoped).
