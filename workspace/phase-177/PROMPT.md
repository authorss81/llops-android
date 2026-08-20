# Phase 177: Plugin ecosystem full review — wiring, opt-in defaults, accurate on/off state, enable/disable/delete correctness [NOT STARTED]

You are working on **InkFlow/Noteflow**. This phase does a COMPLETE review of the
plugin wiring ecosystem end-to-end, verifies every lifecycle action works, fixes
anything missing, and proves nothing broke.

Read `docs/ARCHITECTURE.md`, `docs/PLUGINS.md`, `docs/plugin-architecture.md` and
`docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-177 step N: <desc>" && git push`
AFTER EVERY step (inventory, then each verification/fix). Never sit on uncommitted
work. This phase must not stall.

## Step 1 - Inventory (commit it)
Map the whole plugin surface with file:line:
- `plugins/NoteflowPlugin.kt`, `plugins/PluginManager.kt` (routing,
  `setEnabled`, `installPlugin`, `uninstallPlugin`, `registerRemotePlugin`,
  `replaceRemotePlugin`, `selfCheck`), `plugins/PluginRegistry.kt`
  (defaultPlugins / compiledPlugins / activePlugins / installStore), `plugins/store/PluginStoreCatalog.kt`.
- UI: `ui/components/PluginStoreDialog.kt`, `PluginSettingsDialog.kt`,
  `PluginStoreCardPolicy.kt`, `PluginStoreRowPolicy.kt`,
  `PluginInvocationJournal.kt`, `SettingsPluginInvocationJournalStore.kt`,
  `SettingsPluginSettingsStore.kt`.
- Persistence: the `plugin_enabled_<id>`, `plugin_uninstalled_<id>`,
  `plugin_ever_enabled_<id>`, `plugins.<id>.*` settings in `SettingsManager.kt`.

## Step 2 - Verify "off by default" is REALLY enforced
For EVERY plugin in `compiledPlugins` + the store catalog:
- Confirm the persisted default is OFF (opt-in) — no plugin self-enables on a
  fresh install; `installPlugin` flips state ON only when the user installs, and
  `defaultPlugins()` must be the backward-compat installed set, NOT enabled set.
- Confirm `PluginManager.resolvePlugin` requires `enabled == true` (capability
  routing) and that `PluginRegistry` does not auto-enable on process start /
  lifecycle resume (`onProcessStart`, `resumeLifecycle`, `refreshAvailability`).
- Report ANY plugin that is on by default and FIX it to off-by-default (unless a
  built-in non-optional feature that is not a "plugin" — then document why).

## Step 3 - Verify on/off state is ACCURATE in the store + settings
- The Plugin Store and Plugin Settings dialogs must show the TRUE current state:
  - not downloaded / downloaded / enabled / disabled — each row matches what
    `installStore` + `enabled` actually return (no stale/duplicated state).
  - installed-but-disabled shows "Off", enabled shows "On", never both/neither.
  - Fix any row-state bug (e.g. checked state not driven by the same source of
    truth the router uses). A single source of truth is required.

## Step 4 - Verify enable / disable / delete correctness
- **Enable**: toggles ON, capability routing starts serving the plugin
  (`PluginManager.withPlugin` resolves it), persists, survives restart.
- **Disable**: toggles OFF, routing stops serving it (returns
  `Failure(NONE_ENABLED)` for that capability), persists, survives restart.
- **Delete (uninstall)**: removes the plugin completely — settings wiped
  (`plugin_enabled_<id>` off, `plugin_uninstalled_<id>` set, all `plugins.<id>.*`
  cleared) and downloaded assets deleted (`NoteflowPlugin.deleteDownloadedAssets`).
  After delete, the store shows "not downloaded"; re-install starts fresh (off).
- **Delete confirmation**: deleting MUST pop a warning dialog ("Delete
  plugin? ... REMOVES the plugin completely: settings wiped, downloaded
  models/assets deleted") with Delete/Cancel. Verify this exists and is wired for
  EVERY delete path (PluginStoreDialog + any other UI that can delete). If any
  delete path has NO confirmation, add one.
- **Rejected/unavailable plugins**: show Delete (no Enable), never allow enabling
  something the device can't run.

## Step 5 - Invocation journal + diagnostics accuracy
- `PluginInvocationJournal` records bounded/scrubbed/persisted invocation records
  (`plugin_invocation_journal_<id>`), surfaced in PluginSettingsDialog — verify
  success/failure/last-invocation is honest and matches actual routing.
- `selfCheck` per plugin reflects real availability on this device.

## Step 6 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure, untouched).
- Run the existing plugin tests: `PluginFrameworkTest`, `PluginStoreLifecycleTest`,
  `PluginExecutionSimulationTest`, `PluginDownloaderTest`,
  `PluginInvocationJournalPolicyTest`, `PluginStoreRowPolicyTest`,
  `PluginCapabilityDirectoryTest`, `PluginDiagnosticsRowPolicyTest`,
  `PluginBytecodeIsolationTest`, `CompileTimePluginPinStoreTest` — all green.
- Add any MISSING tests for the gaps you fixed (esp. delete-confirmation wiring,
  off-by-default enforcement, state accuracy).

## Definition of done
- `workspace/phase-177/REPORT.md`: per-plugin table (id | default state |
  store state source | enable/disable/delete verified | confirm-dialog present)
  + every fix with file:line before/after + test list.
- All lifecycle actions verified working; off-by-default enforced; store/settings
  show accurate on/off; delete always asks for confirmation; nothing broke.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies in the base app. No DB
  schema change.
- Keep the PluginRuntime security model intact (pinned-cert verify, static scan,
  capability facade — never direct DB/keystore handles from loaded code).
- Never log decrypted content or keys.
- Base-APK-size rule applies.