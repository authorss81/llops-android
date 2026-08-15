# Phase 60 — B1-PLAT-4: Auto-lock is ON by default and lock fires on display-off + continuous idle

**Commit:** see git log (phase-60)
**Date:** 2026-08-15
**Finding:** `B1-PLAT-4` (MEDIUM) — `docs/security-report.md:330-336`

## What was wrong (before)

The vault's lock boundary was only reachable through three narrow gates, and the
auto-lock was disabled out of the box:

1. `SettingsManager.kt:218-219` — `autoLockTimeoutSeconds` defaulted to `0` = OFF.
   A fresh install had no foreground inactivity bound at all.
2. `MainActivity` lifecycle observer — `viewModel.lock()` ran ONLY on `ON_STOP`
   (`ON_PAUSE` just scrubbed the clipboard). Display-off on a no-keyguard/tablet
   device can pause an activity without stopping it, so on resume the SAME
   unlocked notes were shown.
3. `MainActivity` pointerInput handler — even with a timeout configured, the idle
   window was evaluated only on the NEXT user touch once it had elapsed. A
   foregrounded vault left on a desk stayed readable until someone touched it.
4. `MainActivity.kt:113-117` — FLAG_SECURE was applied only in non-debug builds,
   so a debug build exposed the decrypted vault via screenshots / recents thumbnails.

Exploit: the user leaves a no-keyguard device foregrounded and walks away ⇒
decrypted note content readable indefinitely by a shoulder-surfer / casual
physical attacker with zero interaction.

## The fix (what & where)

### 1. New pure-JVM policy: `app/.../services/AutoLockPolicy.kt` (NEW)

Single owner of the numbers + the decision (testable on the CI runner):

```kotlin
const val IDLE_CHECK_INTERVAL_MS: Long = 1_000L
const val DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS: Int = 300
fun shouldAutoLock(nowMs: Long, lastActivityAtMs: Long, timeoutSeconds: Int): Boolean {
    if (timeoutSeconds <= 0) return false
    return nowMs - lastActivityAtMs >= timeoutSeconds * 1000L
}
```

- `>=` boundary: the poller sleeps exactly one interval and still fires.
- `0`/negative = "Off" (the Security dialog's `0 to "Off"` option still works).

### 2. Auto-lock ships ENABLED — `services/SettingsManager.kt:221-225`

`get()` now reads `AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS` instead of
`0`, so a fresh install locks after 5 minutes of foreground inactivity. The
Security&Encryption dialog (`ui/components/Dialogs.kt` dropdown `0/60/300/900`)
maps the new default to "5 min" already — no UI change needed.

### 3. Lock on display-off — `MainActivity.kt` (runtime broadcast)

New `screenOffReceiver` (a `BroadcastReceiver` watching `Intent.ACTION_SCREEN_OFF`)
locks the instant the display goes off, independent of pause-vs-stop semantics:

- registered in `onCreate` (`registerReceiver`, plain form below API 33; on
  API 33+ the documented system-broadcast `Context.RECEIVER_EXPORTED` form);
- deregistered in `onDestroy` behind a `screenOffReceiverRegistered` guard
  (a config-change rebuild cannot double-unregister; a failed registration sets
  the flag back and the app continues — ON_STOP lock + the poller still guard);
- `onReceive` fires **only** while `viewModel.authenticated.value` is true.
- `ACTION_SCREEN_OFF` is a protected system broadcast delivered to
  runtime-registered receivers on every supported API level (26 = minSdk), so no
  OS-floor fallback/notice is required (AGENTS.md hardware reality).

### 4. Inactivity auto-lock is continuous — `MainActivity.kt` (LaunchedEffect)

```kotlin
LaunchedEffect(autoLockTimeoutSeconds, authenticated) {
    if (!authenticated) return@LaunchedEffect
    lastActivityAtMs = System.currentTimeMillis()   // fresh baseline each unlock
    while (viewModel.authenticated.value) {
        delay(AutoLockPolicy.IDLE_CHECK_INTERVAL_MS)
        if (AutoLockPolicy.shouldAutoLock(
                nowMs = System.currentTimeMillis(),
                lastActivityAtMs = lastActivityAtMs,
                timeoutSeconds = autoLockTimeoutSeconds
            )
        ) { viewModel.lock(); return@LaunchedEffect }
    }
}
```

The `pointerInput` handler is now timestamp-**only** (`lastActivityAtMs = …` on
every touch); the lock decision is never gated on the next touch again. After a
lock the effect returns (single fire); restart on any unlock restamps the
baseline, so the old post-unlock stale-timestamp re-lock trap cannot recur. Both
the poller and the touch handler run on the main thread — no synchronization issue.

### 5. FLAG_SECURE unconditionally — `MainActivity.kt:137`

```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
```

The `if (!BuildConfig.DEBUG) addFlags … else clearFlags` carve-out is deleted;
there is no debug exception to the screenshot/recents ban.

### Deliberately NOT chosen: lock on `ON_PAUSE`

The finding allows "lock on ON_PAUSE **or** screen-off broadcast". Gating the
lock on every pause was rejected because pausing also fires for legitimate
system overlays — the API 19+ SAF destination pickers of phase-59, the
biometric prompt, the system share sheet — where an unconditional lock would
break those flows. The screen-off receiver + continuous idle poll close the
finding's stated vectors without that regression. `ON_STOP` → lock is retained.

## Checksums / secrets handling

- No keys, passwords, decrypted note content, or vault paths are logged or
  persisted by anything in this phase.
- No new network permission, no new dependency, no schema change, no migration.
- `allowBackup="false"`, `ClipboardGuard`, web-app `FLAG_SECURE`-adjacent logic
  and the `.github/workflows/` are untouched.

## Verification

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.B1Plat04AutoLockTest"`
  → GREEN (10/10).
- `gradle testDebugUnitTest` → **1206 tests**, 2 failed — both pre-existing
  `B1Plat01ReleaseSigningTest` assertions on `docs/RELEASE.md` /
  `app/build.gradle.kts` (the debug-keystore-fallback RELEASE.md wording), which
  phase-59 already proven unrelated by failing identically on a clean stashed
  tree; this diff touches neither file. New tests added = 10 (1196 → 1206).
- `gradle assembleDebug` → **GREEN** (first invocation had a transient
  Gradle-daemon failure — the known CI flake; the final run is fully
  `BUILD SUCCESSFUL`). Debug APK 173.7 MB, SHA-256
  `d2c2eae7a8737026f5eb65e0f0eff84cd0300d27279240ef675aa78930b664df`.

## Tests added (`B1Plat04AutoLockTest`, 10)

Pure-JVM behavior of `AutoLockPolicy` (enabled-by-default 5 min; 0/negative =
off; `>=` boundary incl. clock-noise/future-baseline; large-timeout no-overflow)
plus source-level wiring pins for the activity/settings (FLAG_SECURE
unconditional + no debug carve-out; ACTION_SCREEN_OFF registered + auth-gated +
deregistered in onDestroy; poller keyed on `(autoLockTimeoutSeconds,
authenticated)` routes through `shouldAutoLock` and `viewModel.lock()`; the
`pointerInput` touch handler contains no `viewModel.lock()` and no `timeoutMs`;
`SettingsManager` reads `AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS` and the
old `getInt("auto_lock_timeout_seconds", 0)` is gone).

## Out of scope (documented, not fixed)

- `ON_PAUSE`-scoped locking — explicitly rejected, see above.
- Keyguard-state broadcast (`ACTION_USER_PRESENT`) — subsumed by the
  screen-off hook (on a keyguard device the app is already locked the moment the
  screen goes off); no separate hook added.
- The pre-fix post-unlock stale-timestamp behavior is a phase-30 quirk already
  superseded by the poller's baseline restamp.
- `B1-PLAT-7` (`phase-61`) remains a separate finding; nothing here touches it.