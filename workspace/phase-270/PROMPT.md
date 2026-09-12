# Phase 270 — Plugins: optional default + delete atomicity + payload path

## Goal
Keep off-by-default + loud-fail; fix install-default + half-state + traversal.

## Evidence (PASS to not regress: `SettingsManager.kt:735` enabled=false default; `PluginRegistry:272 install≠enable`, `:441 uninstall` full wipe + `SettingsManager:783` keys wipe + `PluginStoreDialog:566` confirm + `Controller:223` asset-delete-first; `PluginEntry.kt:180` capability→facade whitelist + TLS-only; `ArtifactSignatureVerifier:75` sha256-constant-time + full-signer-set + pin-then-validity; classloader `PluginFrameworkClassLoader:61` + `ArtifactStaticScan:99` + `RuntimePluginLoader:92` id match; `PluginManager:73` NO_PLUGIN_INSTALLED/NONE_ENABLED/UNAVAILABLE loud; downloader HTTPS/host/size/canonical guards)
- MEDIUM fresh installs report optional (e.g. CaseChangePlugin) as installed (`isPluginUninstalled` default false → `isInstalled=true`) → "Available—off" instead of "Not downloaded". Fix: optional ids default `!installed` unless key exists (or seed `plugin_uninstalled_<optional>=true` first run).
- LOW `PluginStoreController.kt:227 deleteDownloadedAssets` unguarded → throw leaves registry installed + assets half-deleted. Fix: try/catch + `code=DELETE_ASSETS_FAILED` log (B2-LOG-04 pattern).
- HIGH-conditional `PluginArtifactStorage.kt:94-115`: `assets/` target `removePrefix` without canonical containment (`File(root,target)` may escape via `assets/allowed/../../evil`); `lib/` safe via `afterLast('/')`. Fix: `target.canonicalPath.startsWith(root.canonicalPath)` guard (cf. `PluginDownloader.kt:170`).

## Files
- `services/SettingsManager.kt`, `services/SettingsPluginInstallStore.kt`, `plugins/store/PluginStoreController.kt`, `services/PluginArtifactStorage.kt`, `plugins/PluginRegistry.kt`

## Tests
- `Phase270PluginsTest`: optional default not-installed (JVM); delete failure still uninstalls (JVM); `../` payload rejected (JVM canonical test).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
