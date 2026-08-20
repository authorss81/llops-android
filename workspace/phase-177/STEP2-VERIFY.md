# Phase 177 — Step 2: "Off by default" is REALLY enforced (verification)

Status: VERIFIED — no plugin is on by default. Evidence below with file:line.

## Checks performed

### 1. Persisted default is OFF (opt-in)
- `SettingsManager.isPluginEnabled` reads `prefs.getBoolean("plugin_enabled_<id>", false)`
  (`services/SettingsManager.kt:447-448`) — an absent key is FALSE. The only writer is
  `setPluginEnabled` (`:450-453`), which is called ONLY by the user-facing toggles
  (`PluginStoreDialog.kt:489`, `PluginSettingsDialog.kt:123`) via
  `NoteflowViewModel.setPluginEnabled` (`NoteflowViewModel.kt:498-502`). No boot/start/restore
  path writes it.

### 2. `installPlugin` flips INSTALL on, NEVER enable
- `PluginRegistry.installPlugin` (`plugins/PluginRegistry.kt:405-432`) touches only
  `installStore.setInstalled(plugin.id, true)` (`:428`) — it never reads or writes the
  enable store. A (re)install therefore starts REGISTERED (off). Pinned by
  `PluginStoreLifecycleTest.deleted plugin can be re-downloaded and starts registered`
  (`app/src/test/java/com/authorss81/noteflow/PluginStoreLifecycleTest.kt:229-242`).

### 3. `defaultPlugins()` is the BACKWARD-COMPAT INSTALLED set, not an enabled set
- `PluginRegistry.defaultPlugins()` (`PluginRegistry.kt:854-894`) constructs instances only;
  it contains 17 built-ins. Built-ins default to INSTALLED (via
  `AllInstalledInstallStore`, `:904-906`, and `SettingsPluginInstallStore:15-16`
  `isInstalled = !isPluginUninstalled`, absent key ⇒ installed) but NOT enabled. "Installed
  but off" is the documented Phase 126 policy (see `docs/PLUGINS.md`).
- The store marks only non-built-ins as OPTIONAL/not-installed
  (`PluginStoreCatalog.kt:79` `optional = !registry.isBuiltIn(id)`), so CaseChangePlugin is
  the single optional bundled plugin that starts "Not downloaded".

### 4. `resolvePlugin` requires `enabled == true` for routing
- `PluginManager.resolvePlugin` (`plugins/PluginManager.kt:182-212`): capability routing
  filters declarers on `states[it.id]?.enabled == true` (`:197`) and returns
  `Failure(NONE_ENABLED)` when none are opted in (`:198-205`). Pinned by
  `PluginOffByDefaultTest` (capability refusal before opt-in, `PluginOffByDefaultTest.kt:153-174`)
  and `PluginStoreLifecycleTest.disabled plugin is skipped by capability routing`
  (`PluginStoreLifecycleTest.kt:187-201`).

### 5. Bootstrap/resume/refresh never auto-enable
- `onProcessStart` (`PluginRegistry.kt:183-208`): `if (!enableStore.isEnabled(id)) return`
  at `:194` — only already-opted-in plugins get their `onEnable` hook. It is ALSO fully
  skipped while the vault is locked (`lifecyclePaused`, `:189`).
- `resumeLifecycle` (`:242-246`) delegates to `onProcessStart` — same gate.
- `refreshAvailability` (`:254-260`) only re-caches availability; it never writes opt-in.
- `startPluginLifecycle` in the ViewModel (`NoteflowViewModel.kt:376-411`) calls
  `resumeLifecycle` then `refreshPluginStates` — neither enables anything.
- Pinned by `PluginOffByDefaultTest.no lifecycle hook runs before explicit opt-in`
  (`PluginOffByDefaultTest.kt:123-151`).

### 6. No plugin self-enables
- Repo-wide grep: the only callers of `setPluginEnabled` (and of the underlying
  `PluginEnableStore.setEnabled`) are the two UI dialogs + the ViewModel. No plugin's
  `onEnable` body calls it. The 18 `onEnable` implementations (`plugins/*/*.kt`) only store
  settings / start engines.

## Result
Every plugin in `compiledPlugins` + the store catalog is OFF on a fresh install/upgrade.
Nothing to fix. The catalog's remote (downloadable) entries (`plugins.mlkit`, `plugins.llm`)
are not even installed by default — the base APK serves no OCR/Translation/Assistant
capability until the user installs the signature-verified artifact
(`registry.defaultPlugins`, `PluginRegistry.kt:865-876`; `runtime/GeneratedMlKitPluginPin.kt`,
`runtime/GeneratedLlmPluginPin.kt`).

## Test evidence (run on this phase)
```
gradle :app:testDebugUnitTest --tests "*PluginOffByDefaultTest*"
   --tests "*PluginStoreLifecycleTest*" --tests "*PluginLifecycleStateMatrixTest*"
   --tests "*PluginFrameworkTest*" ...
BUILD SUCCESSFUL in 1m 42s
```