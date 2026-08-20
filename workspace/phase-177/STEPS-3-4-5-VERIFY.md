# Phase 177 — Steps 3-5: state accuracy / enable-disable-delete / journal verification

Confirms the store + settings show the TRUE on/off state, every lifecycle action
is correct, and the diagnostics/journal surfaces are honest. Evidence with
file:line. Gaps found were pure-JVM-test gaps only (nothing in production
behavior needed a fix) — see the new `Phase177PluginEcosystemReviewTest` that pins them.

## Step 3 — On/off state accuracy (single source of truth)

### Store rows
`PluginStoreController.rows` (`plugins/store/PluginStoreController.kt:112-124`) builds each
`StoreRow` from: `registry.isInstalled` (:116), `registry.resolve(context)` states (:113,
recomputed fresh — availability, deps, conflicts), and the installed plugin instance
(:119-121). The `installed` flag and the state presence are therefore NEVER independent:
- not-downloaded ⇒ `state == null`, `plugin == null` (row shows "Not downloaded", Download button — `PluginStoreDialog.kt:364-392`);
- downloaded ⇒ `state != null`, `plugin != null` (row shows the status label + Enable/Disable/Delete — `:393-522`).

### Store button semantics (`PluginStoreDialog.kt:483-502`)
`wantOn = state == REGISTERED || DISABLED`; the button says "Enable" iff the row is OFF,
"Disable" iff it is ON. This is derived from the SAME state the router uses
(`PluginManager.resolvePlugin` requires `enabled == true`, `PluginManager.kt:197`), and the
derived state's `enabled` field comes straight from `enableStore.isEnabled`
(`PluginRegistry.kt:730`). Single source of truth = the persisted `plugin_enabled_<id>` key
(`SettingsManager.kt:447-448`). No stale/duplicated state: every refresh re-runs
`registry.resolve` and re-reads `isEnabled` (`NoteflowViewModel.refreshPluginStates`,
`NoteflowViewModel.kt:467-480`; store refresh, `:551`/`:583`/`:607`).

### Settings dialog (`PluginSettingsDialog.kt:115-128`)
Switch `checked = enabledIds[plugin.id] == true` — read directly from the same enable store
(`NoteflowViewModel.pluginEnabledIds`, `:319-320`, refreshed on toggle/open). UNAVAILABLE but
enabled can still be toggled OFF. REJECTED rows show the refusal inline when toggled.

### Per-state labels (both surfaces, truthful about ON/OFF)
`PluginStoreDialog.statusLabel` (`:653-661`) and `PluginSettingsDialog.stateLabel` (`:62-71`):
- ON (enabled): `AVAILABLE`→"Active", `ENABLED`→"Enabled — verifying", `UNAVAILABLE`→"Unavailable"
- OFF (disabled): `REGISTERED`→"Available — off", `DISABLED`→"Disabled"
- `REJECTED`→"Rejected"

An installed row is therefore ALWAYS exactly one of the ON group or OFF group — never both,
never neither — because `deriveState` (`PluginRegistry.kt:698-776`) branches on
`enableStore.isEnabled` before availability evaluation (`:730-740`).

**Pinned by:** `Phase177PluginEcosystemReviewTest.store rows are the single source of truth at every lifecycle stage` — asserts every catalog row at 6 lifecycle stages matches installStore + enableStore, off/on mutual exclusion, `row.state == null ⇔ not installed`, and the Enable-affordance-vs-on/off equivalence.

## Step 4 — Enable / disable / delete correctness

### Enable
`PluginRegistry.setEnabled(id, true)` (`PluginRegistry.kt:271-313`) — refuses with a typed
reason when rejected / dep unmet / required capability unserved / conflict loser
(`refusalReasonForEnable` :805-841); otherwise persists opt-in (`enableStore`), fires the
wired `onEnable` once per process, re-derives state, and routing starts serving the plugin
(`PluginManager.withPlugin` → `Success`). Survives restart: `onProcessStart` re-fires the
hook for persisted-enabled plugins (`:199-201`). Pinned by `PluginOffByDefaultTest` (restart
persistence, `:71-89`) and `PluginStoreLifecycleTest` (routing serves after enable, `:98-112`).

### Disable
`setEnabled(id, false)` — persists OFF, fires `onDisable` only for a live hook
(`enabledNotified.remove` guard, `:300-307`), routing returns `Failure(NONE_ENABLED)`.
Data/settings KEPT (re-enableable). Pinned by `PluginStoreLifecycleTest.disable keeps data`
(`:166-185`) and `disabled plugin is skipped by capability routing` (`:187-201`).

### Delete (uninstall)
`PluginRegistry.uninstallPlugin` (`PluginRegistry.kt:441-469`): tears down live hooks,
`enableStore.wipe` (off + ever-enabled reset), `settingsStore.removeAll` ⇒
`SettingsManager.wipePluginState` (`SettingsManager.kt:495-506`) removing ALL of
`plugin_enabled_`, `plugin_ever_enabled_`, `plugin_uninstalled_`, `plugin_entry_`,
`plugin_download_consent_`, `plugin_update_previous_`, `plugin_invocation_journal_` and every
`plugins.<id>.*` key; drops caches; marks not-installed last (`:465`) so store+registry agree.
`PluginStoreController.delete` (`PluginStoreController.kt:223-245`) calls
`plugin.deleteDownloadedAssets(context)` FIRST (:227) and, for remote entries, deletes the
downloaded artifact + persisted entry blob (:233-235). After delete the store row is
"Not downloaded"; a re-download starts REGISTERED (off) — `PluginStoreLifecycleTest.kt:229-242`.
**Pinned for the asset wipe:** `Phase177PluginEcosystemReviewTest.delete invokes deleteDownloadedAssets exactly once and never for a refused delete`.

### Delete confirmation (EVERY delete path)
Repo grep: the ONLY delete path in the app is the Plugin Store ("Delete" button,
`PluginStoreDialog.kt:503-512`), and it is ALWAYS confirmation-gated — it sets
`pendingDeleteId` (:504) which renders the destructive warning dialog
(`:535-558`: "Delete plugin? … This REMOVES the plugin completely: its settings are wiped and
any downloaded models/assets are deleted. … Delete/Cancel") and only calls
`viewModel.storeDelete` on confirm (:549-552). `storeDelete` (`NoteflowViewModel.kt:593-611`)
has no other UI caller. No other screen (Settings → Plugins, diagnostics, editor) can delete.

### Rejected / unavailable plugins
- REJECTED: the store hides the Enable/Disable toggle entirely (`state != REJECTED` gate,
  `PluginStoreDialog.kt:483`), shows Delete, and the registry refuses opt-in
  (`refusalReasonForEnable`, `PluginRegistry.kt:807-809`). Pinned by
  `Phase177PluginEcosystemReviewTest.rejected plugins cannot be enabled and resolve rejected`.
- UNAVAILABLE: impossible to *show* an Enable affordance for an unavailable plugin, because
  UNAVAILABLE derives only when the plugin is ALREADY enabled (`PluginRegistry.kt:730-740`),
  so the store shows "Disable" instead — a device-incompatible plugin can be toggled OFF but
  its "Enable" path resolves to UNAVAILABLE with the honest reason if re-enabled (pinned
  matrix semantics, `PluginLifecycleStateMatrixTest.kt:144-155`).

## Step 5 — Invocation journal + diagnostics

- `PluginManager` records EVERY guarded invocation via `PluginInvocationJournal.record`
  (`PluginManager.kt:306-318`) — bounded (`MAX_JOURNAL_ENTRIES = 20`,
  `services/PluginInvocationJournal.kt:37`), persisted under `plugin_invocation_journal_<id>`
  (own key family, NOT reachable via the plugin settings API,
  `services/SettingsManager.kt:509-521`), scrubbed on both write and render. Serialized under
  a lock (`PluginManager.kt:96`) so concurrent calls never lose entries.
- Wired end-to-end: `pluginManager = PluginManager(registry, logger, pluginInvocationJournalStore)`
  (`NoteflowViewModel.kt:242`); Settings → Plugins reads `viewModel.pluginJournals`
  (`PluginSettingsDialog.kt:170-190`, refreshed on open via `refreshPluginFlows()` :49).
- Delete wipes the journal with everything else (`SettingsManager.kt:504`); disable keeps it
  (history of what happened while enabled).
- `selfCheck` (`PluginManager.kt:140-168`) runs the plugin's OWN deep self-test against a real
  context and records the result; the "Test now" action (`NoteflowViewModel.testPlugin`
  :505-514, `PluginDiagnostics.testNow` `plugins/PluginDiagnostics.kt:35`) surfaces it in the
  settings dialog. Verified by `PluginDiagnosticsRowPolicyTest` for honest last-invocation
  rendering and `PluginInvocationJournalPolicyTest` for bounded/scrubbed journal discipline.

## Conclusion
Production behavior verified correct on all review axes. Three genuinely missing test pins
were added (row-state single source of truth, asset-wipe-on-delete, rejected-enable refusal);
all pass.