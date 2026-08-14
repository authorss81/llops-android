# Phase 111 — B2-LOG-07 (INFO) Logcat activity/timeline fingerprinting via JankStats + per-lifecycle log events

Finding fixed: `B2-LOG-07` — `JankStatsHelper` logged screen names and per-frame
timing to logcat on every frame slower than 16 ms (constant spam on low-end
devices), and `MainActivity` logged every lifecycle event
(`ON_RESUME/ON_PAUSE/ON_STOP`). A logcat observer (adb, dumpstate, device-owner
debug) could reconstruct an activity/timeline profile — when the user opens and
leaves the app, session length, and when the device is under load — which is
privacy-relevant behavioral metadata for a local-first notes app. The constant
jank lines also fed B2-LOG-02's unbounded log growth.

## What changed (file:line)

### Jank diagnostics gated behind `BuildConfig.DEBUG`
`app/src/main/kotlin/com/authorss81/noteflow/utils/JankStatsHelper.kt`:
- **Before:** `MonitorJank` unconditionally attached a
  `Window.OnFrameMetricsAvailableListener` and line 38 called
  `Log.w(TAG, "Jank detected on $screenName! ...")` for every frame over 16 ms.
- **After:**
  - `JankStatsHelper.kt:29` — new gate `fun jankLoggingEnabled(debugBuild: Boolean):
    Boolean = debugBuild`.
  - `JankStatsHelper.kt:37` — `MonitorJank` returns before creating/LocalContext
    work when `!jankLoggingEnabled(BuildConfig.DEBUG)`, so a release APK never
    attaches the frame-metrics listener and can never emit a jank line. Both
    call sites (`MainActivity.kt:111` — was :112 — and
    `EditorScreen.kt:160`) boomerang through this single gate; no call-site edit
    was needed and EditorScreen is covered too (beyond the finding's cited
    `MainActivity.kt:112`).
  - `JankStatsHelper.kt:17` — magic 16f threshold hoisted to
    `JANK_THRESHOLD_MS = 16f`.
  - `JankStatsHelper.kt:32-33` — the log payload moved into the pure-JVM
    `jankFrameMessage(screenName, frameDurationMs, cpuDurationMs)` (pinned by
    unit tests); the `Log.w(TAG, ...)` at `JankStatsHelper.kt:57` is the only
    remaining jank writer and is only reachable when the gate allows it.

### Lifecycle events reduced to a single process-start event
`app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`:
- **Before:** the `LifecycleEventObserver` at line 98 called
  `AppStartupLogger.logEvent(this@MainActivity, "Lifecycle Event: $event")` for
  every life-cycle transition (appended to logcat via `AppStartupLogger.kt:48`).
- **After:** that line is deleted. The observer keeps only its functional work
  (`ON_PAUSE` → `ClipboardGuard.scrubIfOwnCopy`, `ON_STOP` → `viewModel.lock()`).
  The single process-start event that the finding's fix explicitly keeps is the
  `AppStartupLogger.logEvent(this, "MainActivity.onCreate started")` at
  `MainActivity.kt:87` (plus `AppStartupLogger.init`'s "initialized" line at
  `AppStartupLogger.kt:36` — both fire once at process start, timestamped by
  logcat itself, and reconstruct no per-session foreground/background timeline).

### Screen names
The gate reviewer check "never log screen names of screens that reveal note
titles" holds with no change: both `MonitorJank` call sites pass static
literals — `"MainActivity"` (`MainActivity.kt:111`) and `"EditorScreen"`
(`EditorScreen.kt:160`) — never a dynamic/note-title-bearing string.

## Grep verification

```
$ grep -rn "Jank detected" app/src/main app/src/test
app/src/.../utils/JankStatsHelper.kt:33:  "Jank detected on $screenName! ..."   # inside jankFrameMessage, only reachable via gated Log.w(:57)

$ grep -rn "MonitorJank(" app/src/main
EditorScreen.kt:160  MainActivity.kt:111  utils/JankStatsHelper.kt:36   # all through the single gate

$ grep -rn "Lifecycle Event" app/src/main
(no matches)

$ grep -rn "AppStartupLogger.logEvent" app/src/main
MainActivity.kt:87 (onCreate started)  # single process-start event
```

Release APK construction: `jankLoggingEnabled(BuildConfig.DEBUG=false)` returns
false → no listener, no logcat line. Verified **by build** below (the release
variant compiles and is R8-minified with the gate in place).

## Unit tests

`app/src/test/java/com/authorss81/noteflow/JankStatsLoggingGatingTest.kt` (new,
4 tests): `jankLoggingEnabled(false)` is false (release can never emit),
`jankLoggingEnabled(true)` is true (debug keeps the developer diagnostics), the
gate direction is the build flag (not always-on), and `jankFrameMessage` is
deterministic with a static, non-title-bearing screen tag. Total suite went
693 → 697 tests, 0 failures.

## OS/API floor (AGENTS.md hardware reality)

No new Android API is used. The frame-metrics listener already runs only on
`Build.VERSION.SDK_INT >= N` (API 24; below that the listener is `null`, pre-
existing behavior) — everything in this change is a pure Kotlin/`BuildConfig`
gate valid on the API 26+ target floor. Nothing silently degrades and no notice
is needed; the gate is behavior-level and deterministic.

## Checksum / secrets handling

None affected. No key/password/decrypted-note data is added or touched; the
change only removes logcat event traffic. `allowBackup="false"`, `ClipboardGuard`
and `FLAG_SECURE` are untouched (the observer's `scrubIfOwnCopy`/`lock()`
branches are intact).

## Verification

- Targeted: `gradle :app:testDebugUnitTest --tests JankStatsLoggingGatingTest` → BUILD SUCCESSFUL, `JankStatsLoggingGatingTest` = 4 tests, 0 failures.
- Full: `gradle testDebugUnitTest` → BUILD SUCCESSFUL in 49s. Aggregated `app`
  results: **697 tests, 0 skipped, 0 failures, 0 errors** (697 = 693 pre-existing + 4 new).
- Build: `gradle assembleDebug` → BUILD SUCCESSFUL (90 tasks).
- Release-variant proof (release emits no lines by construction):
  `gradle :app:assembleRelease` → BUILD SUCCESSFUL in 5m 2s (R8 minify +
  `lintVital` all green with the release `BuildConfig.DEBUG=false` gate; the
  listener is compiled out in the minified release APK path).

## Out-of-scope / notes

- The `app_startup.log` **file** writer and the crash logger
  (`AppStartupLogger.logCrash`) are untouched: on-device `filesDir` logging is
  the lane of B2-LOG-01/03 (their own phases), not this "logcat timeline
  fingerprinting" finding.
- `EditorScreen` also calls `MonitorJank`; it is covered by the same gate, and
  no other jank lines exist anywhere in `app/src`.
- No DB schema change, no dependency added, `.github/workflows/` untouched.