# Phase 126: All plugins OFF by default [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE CHANGE:** make **all plugins disabled by default** — every plugin,
including bundled/compiled ones, must be **opt-in**. A fresh install (or an
upgrade) must not run any plugin until the user explicitly enables it.

## What to do
- Audit the plugin enablement defaults in `plugins/` registry and
  `SettingsManager` (`plugin_enabled_<id>`, `defaultPlugins()`,
  `PluginRegistry`, `PluginStoreController`, `SettingsPluginInstallStore`).
- Change every default to **disabled** — including the currently
  default-enabled compiled plugins (per `docs/PLUGINS.md` and
  `docs/ARCHITECTURE.md`; e.g. `CaseChangePlugin` and any others in
  `defaultPlugins()`).
- Handle the **upgrade path safely**: existing users who already enabled
  plugins keep their choice (only *new/never-touched* plugins default OFF).
  Never silently disable something the user explicitly enabled; never silently
  enable anything.
- Keep the Plugin Store / settings UI accurate: show the true (disabled)
  state, with a clear "Enable" action.
- Update docs (`docs/PLUGINS.md`, `docs/ARCHITECTURE.md`) to state the
  off-by-default policy.

## Verification
- Pure-JVM unit tests: fresh-state default = all disabled; explicit enable
  persists; upgrade with prior explicit enable keeps it on; no plugin runs
  before explicit opt-in.
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure).

## Definition of done
- All plugins off by default for fresh installs; explicit user choices
  preserved; UI and docs accurate.
- `workspace/phase-126/REPORT.md` committed with file:line evidence.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies. Never log keys/decrypted content. Keep `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE intact.