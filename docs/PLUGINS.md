# Plugins — InkFlow's capability framework

InkFlow's high-level features (OCR, web search, AI assistants, file transfer,
export, text transforms) are built as **plugins**: small, isolated, verifiable
modules that register a well-defined *capability* and are routed to by a central
manager. The core app never hardcodes which plugin serves a feature — it asks the
framework "who serves X?" and gets back an answer (or a loud failure).

This document explains the architecture. **Plugin authors MUST also read
`docs/PLUGIN_SDK.md`** — the machine-readable contract (manifest schema,
lifecycle, error handling, namespacing, migration, testing) that a Phase 12+
plugin must follow.

## Why a plugin framework?

Phases 2–9 made the app honest: every claimed feature either works or is removed.
Future features must not be bolted into the core where they become untestable and
unmaintainable. The plugin framework gives every future feature:

- a **single registration point** (compile-time discovery),
- **user opt-in** (a plugin is off until the user enables it in Settings → Plugins),
- **capability gating + dependency resolution** (availability, deps and conflicts
  are re-derived on every change — never stale),
- **guarded invocation** (a throwing/buggy plugin is contained and surfaced as a
  typed result, never a crash),
- **loud failure** (a request that cannot be served returns a clear user-facing
  message — it never silently degrades or crashes),
- **observability** (per-plugin derived state, reason, version, last invocation
  result, "Test now" self-check),
- **JVM unit-testability** (the framework core has no Android dependencies).

## Package layout

Everything lives in `com.authorss81.noteflow.plugins`:

| File | Purpose |
|------|---------|
| `PluginCapability.kt` | The sealed extension points (`TextTransform`, `OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export`) — each with an `exclusive` flag. |
| `PluginManifest.kt` | `PluginManifest`, `SemanticVersion` (`Major.Minor.Patch`), `PluginPermission`, and the manifest validator. |
| `NoteflowPlugin.kt` | The plugin interface (manifest-derived identity, tri-state `availability`, lifecycle hooks) + per-capability *serving interfaces* (`TextTransformPlugin`, …). |
| `PluginLifecycle.kt` | `PluginLifecycleState` (REGISTERED/ENABLED/AVAILABLE/UNAVAILABLE/DISABLED/REJECTED), derived `PluginStateInfo`, enable result + enable-order resolution types. |
| `PluginRegistry.kt` | Compile-time discovery, manifest validation, topological enable order, deterministic conflict arbitration, `resolve()` derived states, guarded lifecycle. |
| `PluginManager.kt` | Guarded capability routing returning `PluginResult` (Success/Failure/Unavailable) + `PluginFailureReason`, `withPluginAsync`, self-check, invocation records. |
| `PluginDiagnostics.kt` | Diagnostics surface: per-plugin state, version, last invocation outcome, "Test now". |
| `PluginEnableStore.kt` | Persistence abstraction for per-plugin opt-in (incl. "ever enabled"). |
| `PluginSettings.kt` | Namespaced per-plugin settings (`plugins.<id>.<key>`) + `PluginSettingKey`. |
| `PluginLogger.kt` | Logging abstraction (NoOp for JVM tests; Android logcat impl in the app). |
| `Rot13TransformPlugin.kt` | The one real plugin — a working end-to-end proof of the wiring. |

Supporting pieces outside the framework package:

| File | Purpose |
|------|---------|
| `services/SettingsManager.kt` | Stores `plugin_enabled_<id>`, `plugin_ever_enabled_<id>` and namespace-`plugins.<id>.<key>` settings (persist across restarts). |
| `services/SettingsPluginEnableStore.kt` | Production `PluginEnableStore` adapter. |
| `services/SettingsPluginSettingsStore.kt` | Production `PluginSettingsStore` adapter. |
| `ui/components/PluginSettingsDialog.kt` | Settings → Plugins (state + reason + version + diagnostics "Test now" + refusal-aware toggles). |
| `ui/viewmodel/NoteflowViewModel.kt` | Wires registry/manager/diagnostics into the app + `transformNoteText()`. |

## Core concepts

### `PluginCapability` — the extension points

A capability is a unit of behavior a plugin can provide: a sealed `object` with a
`key`, a user-facing `label`, and an `exclusive` flag. Non-exclusive capabilities
(like `TextTransform`) may be served by several plugins at once; **exclusive**
ones (`OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export`) may be served by
exactly one enabled plugin at a time — conflicts are arbitrated deterministically
(higher version; tie → earlier registration) and the loser is disabled with a
reason.

Today `TextTransform` is implemented; the others are *declared extension points*
that fail loudly until a real Phase 12+ plugin ships.

### `PluginManifest` & `NoteflowPlugin` — what a plugin is

```kotlin
class MyPlugin : NoteflowPlugin, TextTransformPlugin {
    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.mything",
        name = "My Thing",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Does the thing.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet(),        // declared for visibility
        dependencies = emptySet(),       // ids of required plugins
        requiresCapabilities = emptySet()// capabilities another plugin must serve
    )
    override fun availability(context: Context?) = PluginAvailability.Ok
    override fun onEnable(context: Context?, settings: PluginSettings) { /* warm up */ }
    override fun transformText(text: String): String = /* … */
}
```

`id`, `name`, `description`, `version` and `capabilities` are **all derived from
the manifest** — one source of truth that the registry validates at construction.
`availability()` is **tri-state** (`Ok` / `Unavailable(reason)` / `Unknown`) and
re-evaluated on every resolution, so permission loss or dependency loss flips the
derived state immediately.

### Derived lifecycle states (no stale state)

`PluginRegistry.resolve(context)` recomputes every plugin's state fresh on every
call, folding in opt-in, device availability, dependencies and conflict
arbitration:

```
REGISTERED → ENABLED → AVAILABLE          (happy path)
                │        └→ UNAVAILABLE   (device / permission / dependency)
                └→ DISABLED               (user off, or conflict arbitration)
REJECTED                                  (invalid manifest)
```

Only `AVAILABLE` plugins are routed. See the lifecycle matrix tests.

### `PluginResult` — never throw at the UI

Every capability request returns a sealed `PluginResult<T>` with a
machine-readable `PluginFailureReason`:

```kotlin
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
    data class Unavailable(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
}
```

### `PluginManager` — the guarded router

```kotlin
pluginManager.withPlugin(PluginCapability.TextTransform, context) { plugin ->
    (plugin as TextTransformPlugin).transformText(text)
}                     // → PluginResult<String>, never a thrown exception
```

Routing rules, checked in order:

1. no installed **valid** plugin declares the capability → `Failure(NO_PLUGIN_INSTALLED)`,
2. declared but none opted-in → `Failure(NONE_ENABLED)`,
3. opted-in but derived state is not `AVAILABLE` → `Unavailable` (device /
   dependency / conflict, with the specific reason),
4. otherwise the winner's action runs **guarded**: any `Exception` (incl.
   `RuntimeException`) or null return becomes `Failure(PLUGIN_ERROR)` that names
   the plugin and exception class — recorded in diagnostics, never a crash.

`withPluginAsync` runs the same guard on `Dispatchers.Default` so work never
blocks the main thread.

### Diagnostics & observability

`PluginDiagnostics` exposes per plugin: current derived state + reason, version,
last invocation outcome (success / failure summary with exception class name
only) and a **"Test now"** `selfCheck` action. This is wired into Settings →
Plugins — not dead UI. Lifecycle events (enable/disable/config/failure) are
logged without content (ids, names and exception class names only).

## How a feature uses a plugin (the shipped example)

The Markdown editor (`MarkdownPreviewScreen`) shows a **Plugins** menu in the top
bar. It does **not** reference `Rot13TransformPlugin`. Menu items are grayed out
when the plugin is not yet opted-in (or not device-available), so a disabled
plugin never fails on click; enabling happens in Settings → Plugins.

A plugin transform is never applied silently. Selecting a runnable plugin opens a
confirmation dialog, and applying it first writes a snapshot of the current text
into **Version History** ("Before running `<plugin>`") so the original wording is
always one restore away before the transformed text is saved.

## Adding a NEW plugin

1. Read `docs/PLUGIN_SDK.md` (the authoring contract) **first**.
2. Create `class MyPlugin : NoteflowPlugin, ServingInterface` in
   `com.authorss81.noteflow.plugins`, with a valid manifest and honest
   `availability()`.
3. Add it to `PluginRegistry.defaultPlugins()` — that single list is the whole
   discovery mechanism (compile-time only; no dynamic APK loading, ever).
4. It is now visible in Settings → Plugins (with its derived state, reason,
   version and "Test now"), off by default, and automatically reachable from
   every feature wired to its capability.
5. Add tests mirroring the plugin test classes (`PluginFrameworkTest`, the
   lifecycle matrix, dependency/conflict and manifest tests).

## Adding a NEW capability

1. Add an `object` to the `PluginCapability` sealed class (mark it `exclusive`
   if only one engine may serve it at a time).
2. Define the serving interface next to `TextTransformPlugin` in `NoteflowPlugin.kt`.
3. Route it through `PluginManager` in the feature and in the ViewModel; surface
   the result in the UI (a `when` over Success/Failure/Unavailable).
4. Document the contract in `docs/PLUGIN_SDK.md` so future plugins implement it
   consistently.
5. If no plugin actually serves it yet, the capability is a *declared extension
   point* — `PluginManager` will fail loudly with "No plugin is installed" until a
   real one lands (that is the honest state; we do not fake capabilities).

## Design rules (non-negotiable)

- **Compile-time registration only.** No runtime-loaded APK plugins. No
  `ServiceLoader`/reflection surprises — the `defaultPlugins()` list is the API.
- **No new third-party dependencies** in the framework package.
- **Opt-in by default.** `SettingsManager.isPluginEnabled` defaults to `false`.
- **Derived state is never stale.** `resolve()` recomputes availability, deps and
  conflicts on every change; permission loss immediately flips `UNAVAILABLE`.
- **Guarded everywhere.** Route invocations AND lifecycle hooks are contained —
  an exception in one plugin never crashes the app. Never log plugin content,
  keys, or decrypted note data.
- **Namespaced settings.** Per-plugin settings live under `plugins.<id>.<key>` so
  two plugins never collide.
- **Loud failure, never silent.** Every unservable request returns a typed
  result with a message the UI can show.
- **No surprising content mutation.** Plugin-driven text transforms are confirmed
  by the user and snapshot into Version History before they overwrite a note.
- **No hardcoded single-plugin logic** in the framework — features address
  capabilities and serving interfaces, not concrete plugin classes.
- **Tests are mandatory.** The Phase 10/11 suites must stay green: discovery,
  persistence, routing, disabled-skip, unavailable-capability failure,
  once-per-process `onEnable`, dependency/conflict arbitration, manifest
  validation, error isolation, settings namespacing, and the plugin's output.