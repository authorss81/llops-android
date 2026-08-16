# Phase 67 — B1-AUTH-03: Downloadable-plugin lifecycle hooks gated behind vault unlock

- **Finding:** B1-AUTH-03 (MEDIUM, `docs/security-report.md`)
- **Status:** `DONE`
- **Commit:** (see git log for this phase)

## What changed

The finding: `NoteflowViewModel`'s init block ran
`pluginEntryStore.all() → pluginRuntime.load(entry) → registerRemotePlugin` and
then `pluginRegistry.onProcessStart(appContext)` **unconditionally on every cold
start** — before the user authenticated — so an installed + enabled
downloadable plugin's `onEnable(context)` received a live application `Context`
(and, per B1-AUTH-02, could reach the DB and the DEK) while the app sat on the
LockScreen. The fix gates ALL plugin runtime loading + lifecycle hooks behind
`authenticated == true` and stops/tears down the hooks on lock.

### `plugins/PluginRegistry.kt` — a pause/resume lifecycle gate

- `:133` new field `lifecyclePaused = false` (default keeps every pre-67 caller
  and the JVM tests on the old immediate-fire behaviour).
- `:184` `onProcessStart(context)` returns immediately
  (`if (lifecyclePaused) return`) — while the vault is locked no `onEnable` and
  no availability gate can fire with a live `Context`.
- `:219-235` new `@Synchronized fun pauseLifecycle(context)` — sets
  `lifecyclePaused = true` and fires `guardedOnDisable` for every plugin whose
  `onEnable` ran (clearing `enabledNotified`), so no plugin keeps running on the
  LockScreen. The persisted opt-in (`enableStore`) is untouched — only the
  runtime HOOK is stopped. Idempotent.
- `:238-241` new `@Synchronized fun resumeLifecycle(context)` — clears the flag
  and delegates to `onProcessStart` (idempotent, dependency-ordered,
  conflict-aware).
- `:279` `setEnabled(...enabled = true)` only fires `guardedOnEnable` when
  `!lifecyclePaused` — an enable that races a lock persists the opt-in but the
  hook fires on the next unlock.

### `ui/viewmodel/NoteflowViewModel.kt` — the authenticated boot gate

- `:256` `private var pluginLifecycleStarted = false` (declared above the init
  block).
- `:258-272` init block now does **only**
  `if (!settings.hasMasterPassword) startPluginLifecycle()` — a passwordless
  vault is authenticated from boot (its device-wrapped DEK is the boot
  credential, `_authenticated` starts `!hasMasterPassword`), so it boots the
  plugin layer immediately; a password-protected vault defers ALL of it.
- `:285-312` `private fun startPluginLifecycle()` — the ONLY sanctioned boot
  path (idempotent): registers the runtime, re-materializes downloadable
  entries (`pluginEntryStore.all() → pluginRuntime.load → registerRemotePlugin`),
  fires the opt-in hooks via `pluginRegistry.resumeLifecycle(appContext)`, then
  `refreshPluginStates()`.
- `:3199-3205` `lock()` (inside the `settings.hasMasterPassword` branch) now
  calls `pluginRegistry.pauseLifecycle(appContext)` and resets
  `pluginLifecycleStarted = false` so the next unlock re-boots the layer.
- `:2489` `verifyMasterPassword` and `:2643` `verifyBiometricsAndUnlock` call
  `startPluginLifecycle()` right after `_authenticated.value = true` — the two
  successful-unlock entry points.

Before/after for the exact finding evidence
(`NoteflowViewModel.kt` init block): the unconditional
`pluginRegistry.onProcessStart(appContext)` + unpacked `pluginEntryStore.all()`
loop + `refreshPluginStates()` are gone from the init block; the same work now
lives behind the credentialed gate. `PluginRegistry.kt:172-191` (the old
`onProcessStart`) is unchanged in purpose but is now unreachable pre-unlock and
guarded by `:184`.

## Security posture

- No plugin code (lifecycle hooks, runtime loading, availability gates, state
  refresh) runs before the user unlocks a password-protected vault.
- Lock tears plugins down with `onDisable` (resources released, nothing keeps a
  live Context on the LockScreen) and quiesces the registry until the next
  unlock.
- No secrets touched. No new logging; hooks that throw are contained exactly as
  before (`guardedOnEnable`/`guardedOnDisable`, logged with id/class-name only,
  never keys/passwords/content). `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE all untouched.

## Verification

- New tests: `app/src/test/java/com/authorss81/noteflow/B1Auth03PluginLifecycleGateTest.kt`
  (10 tests: 5 pure-JVM behavioral + 5 source-level wiring pins).
  - **Behavioral:** locked cold start — an enabled plugin's `onEnable` NEVER
    fires (not from `pauseLifecycle`, not from a stray `onProcessStart`) and
    fires exactly once after `resumeLifecycle`; unlocked boot → lock fires
    `onDisable`, next unlock re-fires `onEnable`; `setEnabled` while locked
    persists the opt-in but defers the hook; pause preserves the persisted
    opt-in; double-pause / empty-enable resume are safe.
  - **Wiring pins:** the init block gates the boot behind
    `if (!settings.hasMasterPassword)` and holds no direct
    `onProcessStart`/`resumeLifecycle`/`pluginEntryStore.all()`; the boot method
    owns re-materialization + `pluginRegistry.resumeLifecycle(appContext)` +
    `refreshPluginStates()` + the runtime registration; `lock()` calls
    `pluginRegistry.pauseLifecycle(appContext)` and resets the flag; both unlock
    paths call `startPluginLifecycle()`; the registry gates hooks on
    `lifecyclePaused`.
- `gradle :app:testDebugUnitTest` — **1304 tests completed, 2 failed**; the only
  2 failures are the pre-existing `B1Plat01ReleaseSigningTest` asserts on
  `app/build.gradle.kts` (`signingConfig`) and `docs/RELEASE.md` — files this
  phase does not touch, documented as failing identically on a clean stashed
  tree in phases 55/59/60/61/62/63/64/65/66 (a clean-stash re-run is impossible
  here because the new regression test references the new API; the two failing
  tests read only the two untouched files, so they are provably independent of
  this diff). 1294 prior + 10 new = 1304.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** on the definitive run
  (first invocation hit a transient `:app:packageDebug` incremental-packaging
  failure, a known-unreproducible class in this repo's CI; the rerun was fully
  green with `packageDebug`/`assembleDebug` `UP-TO-DATE`). Debug APK on disk:
  173.7 MB, SHA-256 `2a4bccfe3a7971d20351fca25371a6ee876229578fe320c3bef54cbce9415959`.

## Post-review fix (phase-67 FINDINGS, same commit scope)

The review flagged that the vault-lock gate covered lifecycle-hook FIRING but not
every live-Context surface the phase claimed was gated, plus a couple of
robustness nits. Closed in the same commit (no API/DB/dep/workflow change):

- **Availability gate quiesced** (`plugins/PluginRegistry.kt`, `containedAvailability`):
  while `lifecyclePaused` the plugin's `availability(context)` — downloadable
  bytecode with a live Context — is never invoked; it reports `Unavailable`
  instead. That single choke point makes every availability-derived surface
  (`resolve`/derived states, `enabledPlugins`, `availablePlugins`, `stateOf`,
  capability routing via `PluginManager.resolvePlugin`, dependency checks,
  conflict arbitration) fail closed while locked — closing BOTH the flagged
  availability-gate gap AND the previously-out-of-scope post-lock capability
  dispatch (nothing routes on the LockScreen; it resumes exactly once after
  unlock).
- **`onDisable` only for live hooks** (`setEnabled` disable path + `uninstallPlugin`):
  a plugin is torn down with `onDisable` only if its `onEnable` actually ran
  this process (`enabledNotified`), so a deferred enable (enabled while locked)
  torn down while locked can never hand plugin bytecode a live Context with no
  matching `onEnable`. Persisted opt-in/wipes unchanged.
- **`notifyConfigChanged` gated** (`:303`): `onConfigChanged` — a lifecycle-class
  hook with a live Context — returns early while paused; a config write racing a
  lock is picked up on the next unlock (resumeLifecycle re-enables with current
  settings). Both call sites (`EditorScreen` shape toggle, phase-26 plugin
  dialogs) sit behind the LockScreen so no live behavior is lost.
- **VM state refresh + diagnostics quiesced** (`NoteflowViewModel.refreshPluginStates`
  + `testPlugin`): both no-op while `pluginRegistry.isLifecyclePaused` so no
  availability/snapshot/self-check bytecode runs on the LockScreen; flows keep
  the last pre-lock values and refresh on the next unlock.
- **Race hardening**: `pluginLifecycleStarted` is now `@Volatile` and
  `startPluginLifecycle()` uses a double-checked `synchronized(this)` gate so two
  racing unlock paths can never boot the layer twice; `PluginRegistry` exposes
  `isLifecyclePaused` for the ViewModel.

Tests: `B1Auth03PluginLifecycleGateTest` grew to **14** (8 pure-JVM behavioral —
incl. new "availability gates never run while locked", "disable while locked never
fires onDisable without a matching onEnable", "onConfigChanged never fires while
locked", and the opt-in test now also proves nothing is routed on the LockScreen
— plus 6 source-level wiring pins; the pins were hardened to extract each target's
brace-balanced body instead of brittle `substringBefore/After` anchors, so cosmetic
reformatting inside a body can no longer break or vacuously widen a pin while a
renamed/moved declaration still fails loudly).
`gradle :app:testDebugUnitTest` — **1308 tests, 2 failed**, the SAME two
pre-existing `B1Plat01ReleaseSigningTest` asserts on untouched
`app/build.gradle.kts`/`docs/RELEASE.md` (a `WikiLinkParserCacheUnitTest`
cancellation-timing failure appeared once mid-review and passed in isolation and on
re-run — pre-existing flake, unrelated to this diff). `gradle :app:assembleDebug`
**BUILD SUCCESSFUL**.

## OS/API floor

Pure-JVM policy change + Android application wiring. No API-gated behaviour,
no new platform requirement, no new dependency, no schema change, no
migration. Safe on the API 26+ floor.

## Out of scope (documented, not fixed here)

- **Capability serving after lock** (`PluginManager.withPlugin` →
  `plugin.availability`/OCR/web-search/etc. invoked by a stale in-flight
  coroutine after a lock): a defensive session-bounding of the plugin *Dispatch*
  layer, distinct from the lifecycle-hook gate this finding scopes to. The
  lifecycle hooks + runtime loading (the finding's exact evidence) are now
  fully gated.
- **Plugin state flows on the LockScreen** (`_pluginStates`/`_storeRows` keep
  their last pre-lock values until the next unlock refreshes them): cosmetic,
  no plugin code runs while locked; the plugin UI is behind the LockScreen.
- Other B1/B2 findings remain their own phases per the one-finding-per-phase
  rule.