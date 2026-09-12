# Phase 270 — Plugins: optional default + delete atomicity + payload path (REPORT)

## 1. What shipped

Three plugin-store hardening fixes, all pure Kotlin/Compose, no schema change,
no new dependencies, no `.github/workflows/` edits, `verification-metadata.xml`
untouched:

1. **MEDIUM — optional plugins no longer report as installed on fresh installs.**
   `SettingsManager.isPluginUninstalled` defaulted absent keys to `false`, so
   `SettingsPluginInstallStore.isInstalled(casechange)` was `true` on a fresh
   install ("Available — off" instead of "Not downloaded"). New pure-JVM
   `services/PluginInstallDefaults.kt` owns the decision table
   (`OPTIONAL_NOT_INSTALLED_BY_DEFAULT = {…casechange}`, key-exists-aware
   `resolveInstalled`); `SettingsManager.isPluginInstalledWithDefaults`
   (`SettingsManager.kt:1022`) applies it, and `SettingsPluginInstallStore`
   (`SettingsPluginInstallStore.kt:13-17`) delegates with an injectable
   `optionalIds` set. Built-ins keep the legacy absent = installed default
   (no migration); an explicit persisted key always wins, so already-downloaded
   optionals stay installed across the upgrade.
2. **LOW — store Delete is atomic against a throwing asset wipe.**
   `PluginStoreController.delete` called `plugin.deleteDownloadedAssets`
   unguarded: a throw left the registry installed with half-deleted assets.
   Now try/catch + fixed-code log `code=DELETE_ASSETS_FAILED` (B2-LOG-04: never
   the exception text) and the registry uninstall still runs
   (`PluginStoreController.kt:223-245`).
3. **HIGH-conditional — payload extraction is zip-slip confined.**
   `PluginArtifactStorage.extractPayload` joined the reserved-prefix target with
   `File(root, target)`, so `assets/<reserved>/../../evil` passed the allow-list
   yet escaped the payload root. Every entry now routes through the new pure-JVM
   `services/PluginPayloadPathPolicy.resolveTarget` (any `..` segment rejected
   outright — including in-root relocations that could forge the
   `.payload-<hash>` extraction marker — plus canonical strict-descendant
   containment as defense-in-depth, mirroring the `PluginDownloader.kt:170`
   gate); escaping entries are skipped, never written
   (`PluginArtifactStorage.kt:106-118`). The `lib/` path was already safe
   (`substringAfterLast('/')`) and stays so.

`plugins/PluginRegistry.kt` verified no-change: `installPlugin` flips only the
install flag (never touches the enable store — install ≠ enable holds) and
`uninstallPlugin` already wipes opt-in + ever-enabled + namespaced settings
before marking uninstalled.

## 2. Evidence table (claim / reality / status / evidence)

| Claim | Reality | Status | Evidence |
|---|---|---|---|
| Optional ids default !installed unless key exists | `PluginInstallDefaults.defaultInstalled` = `id !in OPTIONAL…`; `resolveInstalled` returns the default when `!keyExists`, else `!storedUninstalled` | DONE | `services/PluginInstallDefaults.kt:14-40`; `SettingsManager.kt:1015-1034`; `SettingsPluginInstallStore.kt:11-21` |
| Off-by-default + loud-fail preserved | `plugin_enabled_<id>` default still `false`; install never enables; routing still fails `NO_PLUGIN_INSTALLED`/`NONE_ENABLED`/`UNAVAILABLE` | DONE, pinned | Existing `PluginStoreLifecycleTest` (9) + `Phase177PluginEcosystemReviewTest` (3) green, untouched |
| Delete failure still uninstalls | Throwing `deleteDownloadedAssets` is caught, fixed-code logged, uninstall proceeds | DONE | `PluginStoreController.kt:227-235`; `Phase270PluginsTest.delete with throwing asset wipe still uninstalls` |
| `../` payload rejected | `..`-segment reject + canonical strict-descendant gate; unguarded `File(root, targetName)` write gone | DONE | `services/PluginPayloadPathPolicy.kt:27-50`; `PluginArtifactStorage.kt:110-118`; `Phase270PluginsTest` payload cases + source pins |
| No schema / deps / workflow changes | `git diff HEAD --stat`: 6 prod files, 2 new policy files, 0 gradle/manifest/db files | DONE | `git diff HEAD --stat` (no `*.gradle.kts`, `AndroidManifest.xml`, `Daos.kt`, `.github/`) |

## 3. Tests

- New `Phase270PluginsTest` (8): fresh-prefs optional NOT installed +
  built-in installed; explicit key precedence; decision-table unit cases;
  throwing-wipe delete → `Deleted` + uninstalled + `DELETE_ASSETS_FAILED`
  logged; delete source pin; legit `lib/`+`assets/` targets resolve inside
  root; `../` escapes + in-root `..` relocation rejected; extraction source pin.
- Updated `SettingsPluginInstallStoreTest` (6): the old
  "absent key = installed" assertion encoded the bug for the optional
  CaseChange id — split into built-in-absent-installed (back-compat) +
  optional-absent-NOT-installed (phase-270 fix); remaining round-trip/restart/
  per-plugin cases unchanged and green.
- Full suite: `gradle :app:testDebugUnitTest` **3875 tests, 0 failures/errors**.
- `gradle :app:assembleDebug` green; `gradle :app:lintDebug` **0 errors**
  (109 warnings / 19 info, pre-existing).
- Mid-work catch: the first traversal test used
  `assets/<reserved>/../../evil.so`, which normalizes *inside* the root
  (`root/evil.so`) — the strengthened policy now rejects ANY `..` segment, so
  even in-root marker-forge shapes are skipped; test updated to cover both the
  true escape (`../../../evil.so`) and the in-root relocation.

## 4. Constraints / hard-rule compliance

- `allowBackup="false"` untouched; no plaintext rows (journal stays in its own
  key family, wiped on delete as before); fail-closed throughout (unknown ids
  refuse, escapes skip, wipe failure uninstalls rather than half-deleting).
- Base-APK rule intact: no new dependencies; both new files are pure
  Kotlin (`java.io.File` only).
- No architectural change (no package restructure, no nav/DB/crypto rewrite) —
  no user approval required.
