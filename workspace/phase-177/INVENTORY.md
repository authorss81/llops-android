# Phase 177 — Step 1: Plugin ecosystem inventory (file:line map)

Authoritative map of the whole plugin surface, captured during phase-177 review.
Everything below was read from the working tree on this phase's start commit
(`HEAD`), not from docs.

## 1. Framework contract (SDK module)

`plugin-sdk/src/main/kotlin/com/authorss81/noteflow/plugins/`

| Anchor | What |
|---|---|
| `FrameworkPlugin.kt:15-24` | `PluginAvailability` sealed (Ok / Unavailable(reason) / Unknown) |
| `FrameworkPlugin.kt:58-101` | `interface NoteflowPlugin` — `manifest`, `id/name/description/version/capabilities` (derived), `availability`, `onEnable` (:84), `onDisable` (:87), `onConfigChanged` (:90), `selfCheck` (:93), `deleteDownloadedAssets` (:100) |
| `FrameworkPlugin.kt:118-141` | `interface AssistantPlugin` — model lifecycle + tasks |

The app-local `plugins/NoteflowPlugin.kt` holds the capability SERVICE interfaces
(not the base type):
`TextTransformPlugin` (:14), `WebSearchPlugin` (:56), `ExportPlugin` (:91),
`ClipSharePlugin` (:134), `TextToolsPlugin` (:162), `LanguageDetectionPlugin`
(:185), `WebCapturePlugin` (:213), `DictationPlugin` (:273), `ReadAloudPlugin`
(:327), `DictionaryPlugin` (:482), `WeatherPlugin` (:514),
`OutlineGeneratorPlugin` (:554), `ScreenshotNotePlugin` (:588),
`FileTransferPlugin` (:678).

## 2. Runtime registry + router

`app/src/main/kotlin/com/authorss81/noteflow/plugins/PluginRegistry.kt` (907 lines)

| Anchor | What |
|---|---|
| `:74-82` | `PluginRegistry` ctor: `enableStore`, `settingsStore` (default `InMemoryPluginSettingsStore`), `plugins` (default `defaultPlugins()`), `installStore` (default `AllInstalledInstallStore` — backward-compat "everything installed"), optional factory list, `currentApiLevel`, logger |
| `:84-90` | `basePlugins`, `optionalPlugins` (materialized factories), `extraPlugins` (runtime-installed) |
| `:96-97` | `compiledPlugins` = base + optional + extra, deduped (the FULL shipped set the store catalogs) |
| `:102-103` | `activePlugins` = compiled filtered by `installStore.isInstalled` (THE install gate) |
| `:123-125` | per-process caches: `enabledNotified`, `availabilityCache`, `arbitrationDisabledNotified` |
| `:132-138` | `lifecyclePaused` flag + `isLifecyclePaused` (B1-AUTH-03 vault-lock gate) |
| `:140-168` | `init` — rejects duplicates + manifest-invalid plugins, records `rejectedIds` / `validationErrors` |
| `:183-208` | `onProcessStart` — fires `onEnable` ONLY for already-opted-in plugins (`:194` `if (!enableStore.isEnabled(id)) return`). Boot never enables anything |
| `:223-233` | `pauseLifecycle` — tears down live hooks (vault lock) |
| `:242-246` | `resumeLifecycle` — re-arms; re-fires hooks only for still-enabled plugins |
| `:254-260` | `refreshAvailability` — re-evaluates every active plugin's `availability` (caches; contained) |
| `:271-313` | `setEnabled` — opt-in toggle, guarded refusal, `onEnable/onDisable` firing, arbitration bookkeeping; DEFAULT OFF when absent |
| `:316-329` | `notifyConfigChanged` |
| `:342-363` | `registerRemotePlugin` (restart of a downloadable plugin; does NOT touch install state) |
| `:375-391` | `replaceRemotePlugin` (post-update artifact swap) |
| `:405-432` | `installPlugin` — store "Download"; flips INSTALL state on; never touches enable state (re-install starts REGISTERED/off) |
| `:441-469` | `uninstallPlugin` — store "Delete"; wipe enable + ever-enabled + settings, drop caches, remove extra, mark not-installed |
| `:476` | `allPlugins` = active (installed) only |
| `:479` | `isInstalled` |
| `:482` | `isBuiltIn` |
| `:485-488` | `isRejected` / `validationErrorsOf` |
| `:491` | `isEnabled` |
| `:500-501` | `pluginsForCapability` (installed, non-rejected) |
| `:504-509` | `enabledPlugins` / `availablePlugins` |
| `:516-520` | `resolveEnableOrder` (topo sort) |
| `:530-543` | `resolve` — recomputes ALL derived states fresh (availability + deps + conflicts) |
| `:546` | `stateOf` |
| `:575-595` | `containedAvailability` — while `lifecyclePaused` reports Unavailable WITHOUT invoking plugin code (:584-587) |
| `:672-691` | `computeConflictLosers` — deterministic exclusive-capability arbitration |
| `:698-776` | `deriveState` — REGISTERED/DISABLED split on `everEnabled` (:730-740); requires `userEnabled` for AVAILABLE/ENABLED/UNAVAILABLE |
| `:805-841` | `refusalReasonForEnable` — refuses rejected/missing-dep/conflict-loser |
| `:854-894` | `defaultPlugins()` — **installed set, NOT enabled set**. 18 built-ins, listed at :855-893 |
| `:904-906` | `AllInstalledInstallStore` back-compat default |

`app/src/main/kotlin/com/authorss81/noteflow/plugins/PluginManager.kt` (319 lines)

| Anchor | What |
|---|---|
| `:12-33` | `PluginFailureReason` (NO_PLUGIN_INSTALLED / NONE_ENABLED / DEVICE_UNAVAILABLE / DEPENDENCY_UNMET / CONFLICT / PLUGIN_ERROR / NOT_VERIFIED) |
| `:41-49` | `PluginResult` sealed (Success/Failure/Unavailable) |
| `:51-55` | `PluginCheckResult` (selfCheck outcome) |
| `:108-115` | `withPlugin` — guarded sync routing |
| `:125-134` | `withPluginAsync` — Dispatchers.Default routing |
| `:140-168` | `selfCheck` — real availability self-test, recorded to journal |
| `:182-212` | `resolvePlugin` — routing rules: (1) no declarer → NO_PLUGIN_INSTALLED (:187), (2) `states[it.id]?.enabled == true` filter (:197) → none → NONE_ENABLED (:198), (3) first AVAILABLE winner (:207) → else Unavailable |
| `:214-261` | `invokeGuarded` / `invokeGuardedSuspend` — Throwable containment, journal + last-result recording |
| `:306-318` | `record` — bounded persisted journal via `PluginInvocationJournal.Store` |

## 3. Store catalog + controller

`app/src/main/kotlin/com/authorss81/noteflow/plugins/store/PluginStoreCatalog.kt` (120 lines)

| Anchor | What |
|---|---|
| `:23-41` | `PluginStoreEntry` — unified catalog row (bundled vs remote via `entry.source`) |
| `:57-81` | `PluginStoreCatalog` — bundled entries from `registry.compiledPlugins`; `optional = !registry.isBuiltIn(id)` (:79) |
| `:86-91` | `entries()` — bundled first + persisted REMOTE entries (`entryStore.all()`) |
| `:94-95` | `entryFor` |

`app/src/main/kotlin/com/authorss81/noteflow/plugins/store/PluginStoreController.kt` (325 lines)

| Anchor | What |
|---|---|
| `:56-65` | `DownloadOutcome` (Installed / NeedsConsent / Failed) |
| `:67-71` | `DeleteOutcome` |
| `:73-83` | `UpdateCheckOutcome` |
| `:85-101` | `UpdateOutcome` |
| `:104-109` | `StoreRow(entry, installed, state, plugin)` |
| `:112-124` | `rows()` — installed = `registry.isInstalled` (:116), state = fresh `registry.resolve` (:113), plugin = installed instance |
| `:136-167` | `download` — bundled → offline definition install; remote → `NeedsConsent` unless consented, then installer.install |
| `:171-175` | `grantRemoteConsent` |
| `:178-215` | `downloadBundled` — `registry.installPlugin(plugin, context)` (:200) |
| `:223-245` | `delete` — `plugin.deleteDownloadedAssets(context)` (:227) BEFORE `registry.uninstallPlugin` (:229); remote artifact + entry blob deleted (:233-235) |
| `:255-269` | `checkForUpdates` — never offers downgrades |
| `:283-319` | `update` — `userApproved` gate (`:290`), fresh-manifest fetch, verified swap |

`PluginInstallStore` interface: `plugins/store/PluginInstallStore.kt`.
`RemotePluginInstaller`: `plugins/store/RemotePluginInstaller.kt` (consent +
download/verify/load/delete-artifact).
`PluginUpdateCoordinator`: `plugins/store/PluginUpdateCoordinator.kt`.

## 4. Persistence (SettingsManager + stores)

`app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`

| Anchor | Key / behavior |
|---|---|
| `:447-448` | `isPluginEnabled` = `prefs.getBoolean("plugin_enabled_<id>", false)` — **absent ⇒ OFF** |
| `:450-453` | `setPluginEnabled` = `putBoolean("plugin_enabled_<id>")` (+ marks ever-enabled when true) |
| `:457-461` | `hasPluginEverBeenEnabled` = `"plugin_ever_enabled_<id>"` default false |
| `:463-465` | `clearPluginEverEnabled` = remove key |
| `:471-477` | `isPluginUninstalled` / `setPluginUninstalled` = `"plugin_uninstalled_<id>"` default false (= installed; back-compat) |
| `:483-487` | `plugin_download_consent_<id>` — remote first-download consent |
| `:495-506` | `wipePluginState` — removes ALL of: `plugin_enabled_`, `plugin_ever_enabled_`, `plugin_uninstalled_`, `plugin_entry_`, `plugin_download_consent_`, `plugin_update_previous_`, `plugin_invocation_journal_`, + every `plugins.<id>.*` prefix key |
| `:509-521` | `plugin_invocation_journal_<id>` — own key family (NOT reachable via plugin settings API) |
| `:524-535` | `plugin_entry_<id>` — persisted remote catalog-entry blob (+ `allPluginEntryIds` :537-542) |
| `:547-559` | `plugin_update_previous_<id>` — update rollback root |
| `:561-567`+ | `plugins.<id>.<key>` namespaced settings (via `PluginSettingKey`) |

Store adapters (`services/`):
- `SettingsPluginEnableStore.kt` — `isEnabled/setEnabled/hasEverBeenEnabled`; `wipe` (:23-26) = setPluginEnabled(false) + clearPluginEverEnabled. Wrapped by `PluginEnableStore` interface (`plugins/PluginEnableStore.kt`).
- `SettingsPluginInstallStore.kt` — `isInstalled = !settings.isPluginUninstalled(id)` (:15-16); `setInstalled(false)` ⇒ `plugin_uninstalled_<id>=true` (:18-20).
- `SettingsPluginSettingsStore.kt` — `plugins.<id>.<key>`; `removeAll` ⇒ `wipePluginState` (:39-41).
- `SettingsPluginInvocationJournalStore.kt` — journal read/write.
- `SettingsPluginEntryStore` — persisted remote entries (`services/SettingsPluginEntryStore.kt`).

## 5. Invocation journal + diagnostics + row policies

| File | Anchor | What |
|---|---|---|
| `services/PluginInvocationJournal.kt` | `:34`, `:37`, `:80`, `:114`, `:122`, `:146` | object; `MAX_JOURNAL_ENTRIES=20`; `record` (bounded), `newestFirst`, `renderLine` (scrubbed), `Store` interface |
| `plugins/PluginDiagnostics.kt` | `:11`, `:27`, `:35` | `snapshot(appContext)`; `testNow` → `PluginManager.selfCheck` |
| `services/PluginStoreRowPolicy.kt` | — | metadata line: max 3 capability labels, shipping bucket + size |
| `services/PluginStoreCardPolicy.kt` | — | 2-line collapsed descriptions, `MAX_SUMMARY_CHARS=100` |
| `services/PluginDiagnosticsRowPolicy.kt` | — | footer (capabilities/enabled/state) + `scrub(reason)` + `lastInvocationLine` |
| `services/PluginCapabilityDirectory.kt` | — | store capability browser rows |

## 6. UI surface

`app/src/main/kotlin/com/authorss81/noteflow/ui/components/PluginStoreDialog.kt` (723 lines)

| Anchor | What |
|---|---|
| `:63-74` | collects rows/busy/progress/messages/consent/updates/update-all |
| `:76` | `pendingDeleteId` — destructive-delete confirmation state |
| `:364-425` | not-installed: "Not downloaded" + Download; installed: status label (:395), update banner, bundled/managed note |
| `:454-514` | action row: `!installed` → Download button (:459-468); installed → Update button when offered (:473-482), Enable/Disable toggle (:483-502, `wantOn = state==REGISTERED||DISABLED`, hidden for REJECTED), Delete button (:503-512) → sets `pendingDeleteId` (confirmation, NOT direct delete) |
| `:516-522` | "Delete removes it completely; a re-download starts fresh (off)." |
| `:535-558` | **Delete-confirmation AlertDialog** — title "Delete plugin?", body warns settings wiped + models/assets deleted, Delete/Cancel |
| `:563-588` | remote first-download consent dialog |
| `:595-650` | per-update approval dialog (never silent) |
| `:653-661` | `statusLabel` — AVAILABLE→"Active", ENABLED→"Enabled — verifying", UNAVAILABLE→"Unavailable", DISABLED→"Disabled", REGISTERED→"Available — off", REJECTED→"Rejected" |

`app/src/main/kotlin/com/authorss81/noteflow/ui/components/PluginSettingsDialog.kt` (210 lines)

| Anchor | What |
|---|---|
| `:38-49` | collects `pluginEnabledIds` / `pluginStates` / `pluginDiagnosticsEntries` / `pluginJournals`; `refreshPluginFlows()` on open (:49) |
| `:92` | iterates `pluginRegistry.allPlugins` (installed only) |
| `:115-128` | Switch — `checked = enabledIds[plugin.id] == true` (SAME store the router reads); unavailable-but-enabled can still toggle off |
| `:130-151` | state label + scrubbed reason + diagnostics footer |
| `:159-165` | last-invocation line |
| `:170-190` | bounded "Recent activity" journal lines |
| `:192-200` | "Test now" → `viewModel.testPlugin` |

## 7. ViewModel wiring

`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` (4781 lines)

| Anchor | What |
|---|---|
| `:240-243` | `pluginRegistry` (logging store) / `pluginManager` / `pluginDiagnostics` |
| `:247` | `pluginEntryStore = SettingsPluginEntryStore(settings)` |
| `:255-298` | downloadable-runtime stack: artifact storage, downloader, update store, classloader, facade host, update engine, `SignatureVerifiedPluginRuntime`, `DownloadablePluginInstaller` |
| `:299-304` | `pluginUpdateCoordinator` |
| `:306-313` | `pluginStoreCatalog` + `pluginStoreController` (remoteInstaller + updateCoordinator wired) |
| `:319-320` | `_pluginEnabledIds` — seeded from `registry.allPlugins` + `registry.isEnabled` |
| `:322-334` | `pluginStates` / `pluginDiagnosticsEntries` / `pluginJournals` flows |
| `:337-338` | `_storeRows` / `storeRows` |
| `:346-347` | `pluginLifecycleStarted` (per authenticated session) |
| `:349-363` | `init` — boots plugin layer immediately for passwordless vaults; defers otherwise |
| `:376-411` | `startPluginLifecycle` — registers runtime, re-materializes persisted remote plugins (:393-406), `resumeLifecycle` (:409), `refreshPluginStates` |
| `:413-453` | store progress/busy/messages/consent/update flows |
| `:456-465` | `respondStoreConsent` — grants + re-downloads, or surfaces failure |
| `:467-480` | `refreshPluginStates` — **B1-AUTH-03 guard** (:472, skips while locked); refreshes enabledIds/states/diagnostics/journals/rows |
| `:489-491` | `refreshPluginFlows` public wrapper |
| `:498-502` | `setPluginEnabled` → registry + refresh |
| `:505-514` | `testPlugin` — selfCheck off main thread (guarded while locked) |
| `:527-585` | `storeDownload` — busy token, consent for remote first download, progress, message, refresh |
| `:593-611` | `storeDelete` — busy token, `pluginStoreController.delete`, message, main-thread refresh + callback |

## 8. Delete-path audit (Step 4 input)

Grep for every delete/uninstall entry point in `app/src/main/kotlin`:
- `registry.uninstallPlugin` — only called from `PluginStoreController.delete` (`PluginStoreController.kt:229`).
- `storeDelete` (VM) — only called from `PluginStoreDialog.kt:551` (after the confirmation dialog at :535-558).
- `pendingDeleteId` set only at `PluginStoreDialog.kt:504` (Delete button); cleared on dismiss/confirm/cancel.
- `plugin.deleteDownloadedAssets` — called at `PluginStoreController.kt:227` for EVERY delete.

⇒ Exactly ONE delete path exists (PluginStoreDialog) and it is confirmation-gated.

## 9. Optional / remote plugin surface

- `plugins/CaseChangePlugin.kt:20` — the OPTIONAL bundled store plugin (NOT in `defaultPlugins`); store-only, "Not downloaded" initially, re-materialized across restarts via `PluginRegistry.init` (:145).
- Downloadable artifacts: `plugins/mlkit` (OCR + Translation, Phase 175 seed `runtime/GeneratedMlKitPluginPin.kt`), `plugins/llm` (Assistant, Phase 29 seed `runtime/GeneratedLlmPluginPin.kt`). Both are remote entries when installed; the base APK serves NO OCR/Translation/Assistant capability.