# Plugins — InkFlow's capability framework

InkFlow's high-level features (OCR, web search, AI assistants, file transfer,
export, text transforms) are built as **plugins**: small, isolated, verifiable
modules that register a well-defined *capability* and are routed to by a central
manager. The core app never hardcodes which plugin serves a feature — it asks the
framework "who serves X?" and gets back an answer (or a loud failure).

This document explains the architecture and how to add your own plugin or
capability.

## Why a plugin framework?

Phases 2–9 made the app honest: every claimed feature either works or is removed.
Future features must not be bolted into the core where they become untestable and
unmaintainable. The plugin framework gives every future feature:

- a **single registration point** (compile-time discovery),
- **user opt-in** (a plugin is off until the user enables it in Settings → Plugins),
- **capability gating** (`isAvailable` checked before any request is routed),
- **loud failure** (a request that cannot be served returns a clear user-facing
  message — it never silently degrades or crashes),
- **JVM unit-testability** (the framework core has no Android dependencies).

## Package layout

Everything lives in `com.authorss81.noteflow.plugins`:

| File | Purpose |
|------|---------|
| `PluginCapability.kt` | The sealed set of extension points (`TextTransform`, `OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export`). |
| `NoteflowPlugin.kt` | The plugin interface + per-capability *serving interfaces* (`TextTransformPlugin`, …). |
| `PluginRegistry.kt` | Compile-time plugin discovery, enable-state, status, availability. |
| `PluginManager.kt` | Routes a capability request to the right plugin, returning a `PluginResult`. |
| `PluginEnableStore.kt` | Persistence abstraction for per-plugin opt-in. |
| `Rot13TransformPlugin.kt` | The one real plugin shipped this phase — a working end-to-end proof of the wiring. |

Supporting pieces outside the framework package:

| File | Purpose |
|------|---------|
| `services/SettingsManager.kt` | Stores `plugin_enabled_<id>` prefs (opt-in survives restarts). |
| `services/SettingsPluginEnableStore.kt` | The production `PluginEnableStore` adapter. |
| `ui/components/PluginSettingsDialog.kt` | Settings → Plugins screen (status + toggles). |
| `ui/viewmodel/NoteflowViewModel.kt` | Wires the registry/manager into the app + `transformNoteText()`. |

## Core concepts

### `PluginCapability` — the extension points

A capability is a unit of behavior a plugin can provide. It is a sealed `object`
with a `key` and a user-facing `label`. Capabilities listed today: `TextTransform`
(implemented), `OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export`
(declared for Phase 12+, but only become real when a plugin ships that serves
them — we do not fake them).

### `NoteflowPlugin` — what a plugin is

```kotlin
interface NoteflowPlugin {
    val id: String            // reverse-DNS, globally unique
    val name: String
    val description: String
    val version: String
    val capabilities: Set<PluginCapability>
    fun isAvailable(context: Context?): Boolean   // device/context gate
    fun onEnable(context: Context?)               // called once on first opt-in
}
```

`isAvailable` is the capability gate — a future GPU-dependent plugin checks AGSL
support here and is surfaced as **Unavailable** in Settings → Plugins. The
`Context?` parameter is nullable **only** so the framework stays JVM-unit-testable
without Robolectric; production callers always pass a real context.

### Serving interfaces — how work actually happens

A plugin must also implement the serving interface for the capabilities it
declares, so the framework can invoke it **without reflection and without
hardcoding the concrete class**:

```kotlin
interface TextTransformPlugin {
    fun transformText(text: String): String
}
```

### `PluginResult` — never throw at the UI

Every capability request returns a sealed `PluginResult<T>`:

```kotlin
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val message: String) : PluginResult<Nothing>()  // user-facing
}
```

### `PluginManager` — the router

```kotlin
pluginManager.withPlugin(PluginCapability.TextTransform, context) { plugin ->
    (plugin as TextTransformPlugin).transformText(text)
}
```

Routing rules, checked in order:

1. no installed plugin declares the capability → `Failure("No plugin is installed…")`,
2. declared but none opted-in → `Failure("…enable one in Settings → Plugins…")`,
3. opted-in but `isAvailable` fails on this device → `Failure("…unavailable on this device")`,
4. otherwise the winner's action runs; any thrown exception becomes a `Failure`
   that names the plugin. A request **never** crashes the app.

## How a feature uses a plugin (the shipped example)

The Markdown editor (`MarkdownPreviewScreen`) shows a **Plugins** menu in the top
bar. It does **not** reference `Rot13TransformPlugin`:

```kotlin
val transformPlugins = viewModel.pluginRegistry.pluginsForCapability(PluginCapability.TextTransform)
transformPlugins.forEach { plugin ->
    DropdownMenuItem(text = { Text("Run ${plugin.name}") }, onClick = {
        when (val result = viewModel.transformNoteText(contentText)) {
            is PluginResult.Success -> { contentText = result.value; flushSave() }
            is PluginResult.Failure -> viewModel.showSnackbar(result.message, isLong = true)
        }
    })
}
```

If the plugin is installed but disabled, the same menu item produces the loud
"enable in Settings → Plugins" failure — real, honest, user-facing.

## Adding a NEW plugin (same capability)

1. Create `class MyTextThingPlugin : NoteflowPlugin, TextTransformPlugin` in
   `com.authorss81.noteflow.plugins`.
2. Add it to `PluginRegistry.defaultPlugins()` — that single list is the whole
   discovery mechanism (compile-time only; no dynamic APK loading, ever).
3. It is now visible in Settings → Plugins, off by default, and automatically
   reachable from every feature wired to `TextTransform`. No UI changes needed.
4. Add a unit test mirroring `PluginFrameworkTest`.

## Adding a NEW capability

1. Add an `object` to the `PluginCapability` sealed class.
2. Define the serving interface next to `TextTransformPlugin` in `NoteflowPlugin.kt`.
3. Route it through `PluginManager` in the feature and in the ViewModel.
4. Document the contract in this file so future plugins implement it consistently.
5. If no plugin actually serves it yet, the capability is a *declared extension
   point* — `PluginManager` will fail loudly with "No plugin is installed" until a
   real one lands (that is the honest state; we do not fake capabilities).

## Design rules (non-negotiable)

- **Compile-time registration only.** No runtime-loaded APK plugins. No
  `ServiceLoader`/reflection surprises — the `defaultPlugins()` list is the API.
- **No new third-party dependencies** in the framework package.
- **Opt-in by default.** `SettingsManager.isPluginEnabled` defaults to `false`.
- **Loud failure, never silent.** Every unservable request returns a
  `PluginResult.Failure` with a message the UI can show.
- **No hardcoded single-plugin logic** in the framework — features address
  capabilities and serving interfaces, not concrete plugin classes.
- **Tests are mandatory.** `PluginFrameworkTest` must stay green: discovery,
  persistence, routing, disabled-skip, unavailable-capability failure, `onEnable`
  once-per-transition, and the plugin's actual output.
