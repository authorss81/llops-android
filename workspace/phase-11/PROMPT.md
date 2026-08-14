# Phase 11: Robust plugin infrastructure (hardening the Phase-10 foundation) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Phase 10 built the initial plugin framework (registry, capabilities,
settings, one sample plugin). This phase HARDENS that foundation BEFORE the real
plugin packs (Phases 12+ OCR/web search, productivity, on-device AI, brush
engine) are layered on. The goal: a plugin system that is safe, observable,
isolated, and upgradeable — not a veneer that breaks as soon as many plugins
exist.

Do NOT add any real OCR/web-search/AI/brush plugins here. This phase makes the
infrastructure itself production-grade. Add regression tests and a documented,
testable contract. Do NOT edit `.github/workflows/`.

## 1. Plugin lifecycle (robust states)
- Extend `NoteflowPlugin` with a real lifecycle beyond enable/disable:
  `onEnable()`, `onDisable()`, `onConfigChanged()`, plus explicit
  `isAvailable(context)` that can return a reason. Model states:
  `REGISTERED → ENABLED → AVAILABLE → UNAVAILABLE` and `DISABLED`.
- `PluginRegistry` must compute, on every change, a derived state per plugin and
  expose it (for the settings UI and for capability routing). No stale state:
  if a plugin's dependency/permission disappears, its state must update.
- Unit test the full state transition matrix (enable/disable/permission-loss/
  dependency-loss → correct derived state).

## 2. Error isolation (a failing plugin must NOT crash the app)
- `PluginManager` routes a capability request to the enabled plugin inside a
  guarded invocation: catch plugin exceptions, log them (never raw content),
  surface a clear user-facing error, and return a typed `PluginResult` (Success /
  Failure(reason) / Unavailable) instead of throwing out of the manager.
- A malicious/buggy plugin (throwing `RuntimeException`, returning null, hanging
  on the main thread) must be contained. Add a unit test proving an exception in
  one plugin does not propagate to the caller.
- Enforce that plugin work can run off the main thread safely.

## 3. Dependencies & conflict detection
- Plugins may declare dependencies on OTHER plugins or on capabilities
  (`requiresCapabilities: Set<PluginCapability>`). The registry must:
  - resolve a valid enable-order (topological sort),
  - refuse to enable a plugin whose requirements are unmet (clear reason),
  - detect capability CONFLICTS (two enabled plugins claiming the same exclusive
    capability) and pick a deterministic winner, reporting the loser as disabled
    with a reason.
- Unit test: dependency resolution, unmet-dependency refusal, and conflict
  arbitration all behave correctly and deterministically.

## 4. Versioning, manifest & migration contract
- Give each plugin a machine-readable `PluginManifest` (id, name, version
  `Major.Minor.Patch`, minSupportedApi, capabilities, permissions, deps, and a
  human description). The registry validates a manifest (duplicate ids, missing
  required fields, incompatible api) and rejects invalid plugins with a reason —
  NOT a crash.
- Define the future upgrade path: plugin `version` bump + a documented
  "how a plugin migrates its own stored settings" convention. Persisted plugin
  settings must be namespaced per-plugin (`plugins.<id>.<key>`) so two plugins
  never collide.

## 5. Observability & diagnostics
- Add a `PluginDiagnostics` surface: for each plugin, expose current state,
  version, last invocation result, and a "test now" action that runs a
  self-check and reports Success/Failure. Surface this in the existing plugin
  settings screen (NOT dead UI).
- Log plugin lifecycle events (enable/disable/failure) without content.

## 6. Plugin SDK + documentation
- Produce a real, small Plugin SDK contract header (a README-style doc in the
  `plugins` package, or `docs/PLUGIN_SDK.md`) that a Phase-12+ developer follows:
  lifecycle contract, manifest schema, error handling, namespacing, testing
  pattern. Update `docs/PLUGINS.md` to point to it.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes, including new tests for:
  - lifecycle state transition matrix,
  - error isolation (throwing plugin contained),
  - dependency resolution + conflict arbitration,
  - manifest validation (invalid plugin rejected with reason),
  - plugin settings namespacing (no collision between two plugins).
- The settings screen shows per-plugin state, reason, version, diagnostics
  "test now" — all reachable.
- `docs/PLUGIN_SDK.md` written; `docs/PLUGINS.md` updated.
- No new third-party dependencies. Do NOT change the DB schema. Do NOT edit
  `.github/workflows/`. Do NOT ship real OCR/web-search/AI/brush plugins here.

## Constraints
- NO dynamic (runtime-loaded) APK plugins — compile-time registration only
  (Phase 10 rule stands).
- Be honest: this must be a genuinely robust, tested foundation. If a piece
  cannot be made real and tested this phase, omit it rather than fake it.
- Never log plugin content, keys, or decrypted note data.