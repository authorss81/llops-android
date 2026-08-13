# Phase 10: Plugin framework — a real, extensible capability system (THE FOUNDATION)

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Phases 2–9 made the app honest, correct, and fast. This phase introduces a
**real plugin architecture** so high-level features (OCR, web search, AI
assistants, file transfer, etc.) can be added as clean, isolated, verifiable
plugins instead of being bolted into the core.

IMPORTANT: this phase builds the FRAMEWORK only. It must include ONE tiny real
plugin that works end-to-end to prove the wiring. Do NOT implement OCR or web
search here — they are Phase 11. Do NOT ship a fake plugin that looks functional
but does nothing.

## What to build

### 1. Plugin model + registry
- A `NoteflowPlugin` interface: `id`, `name`, `description`, `version`,
  `capabilities: Set<PluginCapability>`, `isAvailable(context)`, `onEnable()`.
- A `PluginCapability` sealed type, e.g. `OCR`, `WebSearch`, `FileTransfer`,
  `Assistant`, `Export`. These are the extension points.
- A `PluginRegistry` that discovers plugins (compile-time registration via a
  simple list/ServiceLoader-style mechanism — compile-time is fine and honest;
  do NOT attempt dynamic APK loading) and exposes `enabledPlugins` /
  `available(capability)`.
- Plugin enable/disable persisted in `SettingsManager` (user opt-in per plugin).
- `PluginManager` that routes a capability request to the enabled plugin and
  fails loudly (clear user-facing message) if none is enabled.

### 2. Permissions & capability gating
- Each plugin declares the permissions it needs (e.g. OCR = none extra, Web
  Search = INTERNET which already exists). The manager checks `isAvailable`
  before routing.
- A settings screen entry showing installed plugins, their status
  (available / disabled / unavailable), and toggles. Reachable from the app's
  settings — NOT dead UI.

### 3. One real, tiny plugin to prove the wiring
Implement `Rot13TransformPlugin` or similar trivial-but-REAL utility:
- It MUST do something real end-to-end: e.g. add a "Rot13" option to the export/
  text menu that transforms selected note text. Wire it through the registry and
  settings toggle so it is verifiable.
- A unit test asserts: registering the plugin, enabling it, and invoking its
  capability returns the correct transformed output, and a disabled plugin is
  skipped.

### 4. Public API + documentation
- A clean, small plugin surface (`plugins` package) with a short README-style
  header comment explaining how a future plugin (Phase 11+) integrates.
- Make sure the framework is genuinely usable by future plugins: no dead code,
  no hardcoded single-plugin logic.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes, including new tests for:
  - registry discovery lists the sample plugin,
  - enabling/disabling persists,
  - capability routing invokes the enabled plugin,
  - unavailable capability returns a clear failure (no crash).
- The sample plugin is reachable in the UI and works (not dead).
- `docs/PLUGINS.md` written explaining how to add a new plugin + capability.

## Constraints
- NO dynamic (runtime-loaded) APK plugins — compile-time registration only.
- NO new third-party dependencies for the framework itself.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Do NOT add INTERNET usage beyond what exists (web search is Phase 11).
- Be honest: the framework must be genuinely extensible and tested, not a
  facade. If a part cannot be made real this phase, omit it rather than fake it.