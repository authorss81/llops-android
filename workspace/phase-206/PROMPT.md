# Phase 206: Kill Perpetual Pollers — Event-Driven Idle/Thermal/Audio Clocks [PERF/BATTERY]

**Goal:** Remove four always-running timer loops that burn CPU while the app idles, replacing each with event-driven or human-rate scheduling. All sites verified.

1. **Choreographer frame pump leaks past canvas teardown and STACKS per editor open** — `AnnotationCanvas.kt:794-818`: `LaunchedEffect(Unit)` posts a SELF-REPOSTING `Choreographer.FrameCallback` (re-posts at `:814`, posted at `:817`); cancelling the LaunchedEffect does NOT unregister it (zero `removeFrameCallback` call-sites repo-wide). Every editor visit adds another immortal 60-120 Hz loop doing `recordFrameTime` + a thermal-status service call + tier re-evaluation PER FRAME even on a static untouched note.
   **Fix:** own the callback in `DisposableEffect` with `choreographer.removeFrameCallback` in `onDispose`; only re-post while a stroke is actively being drawn (or sample thermal status at ≤1 Hz). Source-pin test: `removeFrameCallback` present; reposting gated.

2. **Auto-lock 1 s poll runs even when auto-lock is OFF** — `MainActivity.kt:392-407` `while (authenticated) { delay(AutoLockPolicy.IDLE_CHECK_INTERVAL_MS /* =1000 */) }`; `shouldAutoLock` (`AutoLockPolicy.kt:37`) returns false forever when timeout==0 yet the loop keeps waking. Activity stamping is ALREADY event-driven (`MainActivity.kt:511-517`).
   **Fix:** compute `delay(deadline - now)` per iteration (one wake per idle window, re-armed when `lastActivityAtMs` changes); do not arm at all when `timeoutSeconds <= 0`. Extend `AutoLockPolicy` (pure JVM) with the schedule computation + tests.

3. **Voice playback position polled at 20 Hz into a StateFlow** — `VoiceNoteManager.kt:381-389` emits `_playbackPositionMs` every 50 ms → waveform/seek-bar recomposition 20×/s for the whole session; humans read a seek bar at ~1-2 Hz.
   **Fix:** poll at 200-250 ms, or drive from `withFrameNanos` only while playback is active AND the player card is composed. Behavior unchanged for drag-seek accuracy (clamp to `player.currentPosition` on interactions).

4. **Lockout countdown ticks 1 Hz for up to 15 minutes** — `NoteflowViewModel.kt:3492-3504` second-by-second wakes purely to animate a minute-granularity countdown.
   **Fix:** emit coarse milestones (minute boundaries: `delay(remaining % 60_000)`) plus a final zero transition. Keep the exact-zero clearing behavior (`settings.lockoutUntilEpochMs = 0L`).

## DoD
`gradle assembleDebug` + `testDebugUnitTest` green; extended pure-JVM `AutoLockPolicyTest`/lockout-schedule tests; source pins: `removeFrameCallback` wired, no unconditional `delay(IDLE_CHECK_INTERVAL_MS)` poll remains, playback tick ≥200 ms constant documented. `workspace/phase-206/REPORT.md` with per-loop before/after wake math (e.g. wakeups/hour idle before vs after). No schema change, no workflow edits.
