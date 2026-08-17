# Phase 126 — All plugins OFF by default (strict opt-in)

Status: **DONE** · verified `2026-08-17`

## Summary

**Audit finding: the enablement pipeline already defaulted every plugin to OFF.**
The prompt's premise ("currently default-enabled compiled plugins", e.g.
`CaseChangePlugin`) does not match the current tree: `CaseChangePlugin` is NOT in
`defaultPlugins()` (it is the store's OPTIONAL plugin, downloaded → REGISTERED/off)
and a grep of every `setPluginEnabled` / `setEnabled(...)` call site in
`app/src/main` found only the UI toggles (`PluginSettingsDialog.kt:114`,
`PluginStoreDialog.kt:337`) and `PluginRegistry.setEnabled` itself — there is no
auto-enable path anywhere. Phase-126 therefore (1) **pins the off-by-default
invariants with the pure-JVM tests the prompt demands** over the FULL shipped
`defaultPlugins()` set, (2) documents the explicit off-by-default policy in
`docs/PLUGINS.md` + `docs/ARCHITECTURE.md`, and (3) records the evidence here.

## Audit — where each default is decided (file:line evidence)

| Concern | Where decided | Result |
|---|---|---|
| Per-plugin opt-in persistence | `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt:340-341` — `isPluginEnabled(id) = prefs.getBoolean("plugin_enabled_$id", false)` | Absent key ⇒ **disabled** |
| "Ever enabled" history (REGISTERED vs DISABLED) | `SettingsManager.kt:350-351` — `hasPluginEverBeenEnabled(id)` default `false`; `setPluginEnabled(id,true)` calls `markPluginEverEnabled(id)`. `SettingsManager.kt:343-359` | Upgrade path: explicit prior choice preserved; never-touched = REGISTERED |
| Shipped plugin set | `plugins/PluginRegistry.kt:855-884` — `defaultPlugins()` registers 19 definitions; no instance is pre-enabled by construction | Definitions only |
| Enable write path | `plugins/PluginRegistry.kt:273-314` — `setEnabled` is the ONLY writer; reachable only from `NoteflowViewModel.setPluginEnabled` (`ui/viewmodel/NoteflowViewModel.kt:432-436`) → UI toggles | User-driven only |
| Boot lifecycle | `plugins/PluginRegistry.kt:185-209` — `onProcessStart` skips `if (!enableStore.isEnabled(id))` (`:195`); likewise `resumeLifecycle` (`:244-247`) | No hook fires before opt-in |
| Derived state | `plugins/PluginRegistry.kt:731-740` — `deriveState`: not enabled + never enabled ⇒ `REGISTERED`; enabled-then-off ⇒ `DISABLED` (distinct, reason "Disabled by the user") | Fresh install = all `REGISTERED` (off) |
| Capability routing | `plugins/PluginManager.kt:174-204` — declarers filtered by `states[it.id]?.enabled == true` (`:189`); none opted-in ⇒ `PluginResult.Failure(NONE_ENABLED)` (`:190-198`) | No plugin runs/serves before explicit opt-in |
| Store install (download) | `plugins/PluginRegistry.kt:394-433` — `installPlugin` installs REGISTERED (off): "No state surprises: a re-installed plugin starts REGISTERED (off), because Delete wiped its enable + settings" (`:403-405`); `plugins/store/PluginStoreController.kt:128-135` documents remote install = REGISTERED/off | Download never auto-enables |
| Install persistence default | `services/SettingsPluginInstallStore.kt` + `SettingsManager.isPluginUninstalled` default `false` (`SettingsManager.kt:365-366`) — built-ins active in registry by default, but ENABLED := `plugin_enabled_<id>` only | Installed ≠ enabled |
| Optional plugin factory | `ui/viewmodel/NoteflowViewModel.kt:203` — `optionalPluginFactories = listOf({ CaseChangePlugin() })`; active only when installed | CaseChangePlugin absent until download |
| UI shows true state + Enable action | `ui/components/PluginStoreDialog.kt:331-349` — `wantOn = state == REGISTERED \|\| state == DISABLED` → button reads "Enable"; `ui/components/PluginSettingsDialog.kt:106-119` — Switch reflects `enabledIds[id]`, label "Available — off" for REGISTERED (`:59`) | Accurate disabled state with clear Enable action |

Grep proof of no auto-enable: `setEnabled(`/`setPluginEnabled` in `app/src/main`
→ `NoteflowViewModel.kt:432`, `PluginSettingsDialog.kt:114`, `PluginStoreDialog.kt:337`,
`SettingsPluginEnableStore.kt:16,24`, `SettingsManager.kt:343`, `PluginEnableStore.kt:14`,
`PluginRegistry.kt:273,280,293`. Every write is a user-action path or the registry's own
persistence adapter.

## Changes

### New: `app/src/test/java/com/authorss81/noteflow/PluginOffByDefaultTest.kt` (6 tests)
Pure-JVM regression tests over the full `PluginRegistry.defaultPlugins()` set —
any future code that auto-enables a bundled plugin fails here first:
1. **Fresh install ⇒ every bundled plugin off + REGISTERED.** Every shipped plugin
   is active in the registry, `isEnabled == false`, derived state `REGISTERED`,
   not in `enabledPlugins()`.
2. **Explicit enable persists across a process restart.** Registry over a
   persisted `InMemoryEnableStore`; enable; reconstruct a NEW registry over the
   SAME store ⇒ still enabled, ever-enabled history kept, derived `AVAILABLE`.
3. **Upgrade keeps prior explicit choices; never-touched stay off.** Simulate the
   persisted store of an older-version user who explicitly enabled 2 plugins =>
   fresh registry: those 2 stay enabled (never regress to `REGISTERED`), all
   never-touched stay `REGISTERED`/off.
4. **No lifecycle hook runs before explicit opt-in.** `onProcessStart` fires 0
   `onEnable` calls with nothing enabled; opt-in → exactly 1; off → 1 `onDisable`;
   re-on → 2nd `onEnable`.
5. **No capability is served before explicit opt-in.** For every capability served
   by `defaultPlugins()`, `PluginManager.withPlugin` returns
   `Failure(NONE_ENABLED)` — nothing executes.
6. **Store-wipe (Delete) resets enable AND ever-enabled.** After `wipe`, plugin is
   off, never-enabled, derived `REGISTERED` — reinstall starts fresh/off.

Result: 6 tests, 0 failures (XML: `app/build/test-results/testDebugUnitTest/TEST-com.authorss81.noteflow.PluginOffByDefaultTest.xml`).

### Docs
- `docs/PLUGINS.md` — "Why a plugin framework?" bullet now states the strict
  Phase-126 policy (all plugins OFF by default, never silently enable/disable);
  the framework-principles "Opt-in by default" bullet expanded into the full
  policy with `file:line` anchors + the test pin.
- `docs/ARCHITECTURE.md` — "Plugins" section gains an "Implemented in phase-126"
  note summarizing the audit evidence.
- `docs/phase-status.md` — phase-126 row: `NOT STARTED` → `DONE` with evidence.

## Verification
- `gradle :app:testDebugUnitTest --tests com.authorss81.noteflow.PluginOffByDefaultTest`
  → BUILD SUCCESSFUL (6/6).
- Full `gradle testDebugUnitTest` + `gradle assembleDebug`: see below.

## Constraints
- NO DB schema change, no migration, no new dependencies, `.github/workflows/`
  untouched, `allowBackup=false`/ClipboardGuard/FLAG_SECURE intact (none touched).