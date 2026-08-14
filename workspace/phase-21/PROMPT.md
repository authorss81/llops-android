# Phase 21: Plugin architecture — verify & build the plugin store UI [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework (Phases 10–11: `plugins/` package,
`PluginRegistry`, `PluginManager`, `NoteflowPlugin`, `PluginManifest`,
lifecycle/error-isolation/dependency-resolution) and many real plugins (Phases
12, 15, 16: OCR, web search, export, share-clip, dictation, read-aloud,
translation, assistant, screenshot, text tools, language detection, web
capture).

This phase has TWO jobs: (1) SIMULATE/review the plugin execution path to prove
plugins actually work correctly, and (2) build a proper **Plugin Store UI** with
install/uninstall/disable lifecycle.

## 1. Plugin execution review (ensure plugins work correctly)
- Trace the full path: registry discovery → enable → capability routing →
  invocation → result/error → settings persistence. Verify with `file:line`
  that at least one real plugin (e.g. OCR or export) works end-to-end.
- Simulate: write or extend a JVM integration test that registers plugins,
  enables them, routes a capability, and asserts the plugin result — proving the
  framework is not just scaffold.
- Fix any bug you find in the framework or an existing plugin. No fakes.
- Confirm the Phase-10 rule: compile-time registration only (NO dynamic APK
  classloading) — the "store" below installs/uninstalls plugin DEFINITIONS and
  their persisted state, not loaded bytecode.

## 2. Plugin Store UI (the core feature)
Add a plugin store/manager screen reachable from the app's settings. It lists
ALL known plugins (from the registry + an optional catalog file). For each
plugin card:
- **Status**: available / disabled / unavailable (with reason) / not downloaded.
- **Download button** — for plugins that are not yet installed: fetch/install the
  plugin definition (from a bundled catalog; a real network catalog is allowed
  but must degrade gracefully offline). On success the plugin becomes available
  and appears in the registry.
- **If already downloaded**: show a **Delete** button (removes the plugin
  COMPLETELY: unregisters it, deletes its namespaced settings and any downloaded
  assets/models) and a **Disable** button (temporarily turns it off, keeping its
  data so it can be re-enabled). A disabled plugin shows **Enable** instead.
- Delete and disable must be distinct and honest: delete = gone + settings wiped;
  disable = off but re-enableable, data preserved.
- Persistence via the existing plugin enable/settings stores — NO DB schema
  change unless strictly needed (then explain; prefer existing stores).
- Confirmation dialogs for destructive actions (delete). Progress/error states
  for downloads. Reachable, functional UI — not dead.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes, including the new plugin-execution
  simulation test and tests for store lifecycle logic (download→available,
  disable→disabled+data kept, enable→available, delete→removed+settings wiped)
  in pure JVM.
- Plugin Store screen lists every registered plugin with correct per-plugin
  buttons and states; download/delete/disable/enable all work.
- A disabled plugin is skipped by capability routing; a deleted plugin is absent
  from the registry.
- `docs/PLUGINS.md` updated with the store UI + lifecycle documentation.

## Constraints
- NO dynamic (runtime-loaded) APK/bytecode plugins — compile-time registration
  stands. "Download" installs plugin definitions/assets, not executable APKs.
- NO new third-party dependencies unless a real network catalog requires one
  (justify; reuse the existing HTTP client). No new permissions beyond INTERNET
  (already present) if a network catalog is used.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Never bypass `ClipboardGuard`. Never log plugin content or keys.
- Be honest: if a plugin cannot be downloaded/installed dynamically under the
  compile-time rule, implement the store against the bundled catalog and say so
  explicitly — do not fake a network download that doesn't work.