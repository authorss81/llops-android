# Plugin SDK — InkFlow plugin authoring contract

This is the contract for **Phase 12+ plugin developers** (OCR, web search,
productivity, on-device AI, brush engines). The plugin **infrastructure itself**
shipped in Phase 11; real plugins layered on top must follow this document.
Anything that violates it will be rejected, refused, or contained — not crash
the app.

The authoritative code lives in `com.authorss81.noteflow.plugins` under
`app/src/main/kotlin/com/authorss81/noteflow/`.

---

## 1. Lifecycle contract

A plugin implements `NoteflowPlugin`. Its lifecycle hooks are invoked by the
registry, **in dependency order** and **guarded** (a throwing hook is logged and
contained — never propagated):

| Hook | When | Guarantees |
|------|------|------------|
| `availability(context)` | re-evaluated on every registry resolution (Polled) | NEVER stale. A revoked permission or lost dependency flips the derived state immediately. |
| `onEnable(context, settings)` | on first opt-in, on a disable→re-enable cycle in the same process, and at cold start if already enabled (`onProcessStart`, once per process) | idempotent; runs with deps already enabled; cheap. A throwing `Throwable` (incl. `Error`) is contained. |
| `onDisable(context, settings)` | when the user turns the plugin off, or when conflict arbitration demotes it to a loser (at most once per arbitration round) | release resources, cancel background work. A throwing `Throwable` is contained. |
| `onConfigChanged(context, settings)` | after a user changes a `plugins.<id>.<key>` setting | re-read settings, react. |
| `selfCheck(context)` | "Test now" in Settings → Plugins | deep, cheap self-test; defaults to `availability`. |
| `transformText(...)` etc. | per-capability serving interface call | see § 3; runs inside the manager's guard, off the main thread when routed via `withPluginAsync`. |

### Derived lifecycle states

The registry derives a single state per plugin on every change (see
`PluginLifecycleState`). Don't track state yourself — read it from the registry.

```
REGISTERED → ENABLED → AVAILABLE          (happy path)
                │        └→ UNAVAILABLE   (device gate / dependency / permission)
                └→ DISABLED               (user-turned-off, or conflict arbitration)
REJECTED                                  (invalid manifest — never enabled/routed)
```

- `REGISTERED` — installed, never enabled (off by default).
- `ENABLED` — opted in, requirements met, availability **unknown** (e.g. your
  `availability()` returned `Unknown` because there was no `Context`).
- `AVAILABLE` — opted in, requirements met, `availability()` returned `Ok`.
  **Only AVAILABLE plugins are routed.**
- `UNAVAILABLE` — opted in but cannot run here (return `Unavailable(reason)`).
- `DISABLED` — off: user disabled it, or capability-conflict arbitration chose it
  as loser (see § 3).
- `REJECTED` — the manifest failed validation (see § 2).

### `availability(context)` — tri-state, never lie

```kotlin
sealed class PluginAvailability {
    data object Ok                                // can serve right now
    data class Unavailable(val reason: String)    // cannot serve; reason shown to user
    data object Unknown                           // can't tell yet (e.g. no Context)
}
```

Return `Unknown` when you genuinely cannot evaluate without a `Context` (e.g. an
AGSL-dependent brush plugin on a contextless JVM path). Returning `Unavailable`
with a clear reason is how permission-loss surfaces — the registry derives
`UNAVAILABLE` with your reason, and recovers automatically when you return `Ok`
again.

---

## 2. Manifest schema & validation

`PluginManifest` is the single source of truth (id/name/version/capabilities are
derived from it). Validation happens at registry construction — an invalid
plugin is **REJECTED with a reason**, never a crash.

```kotlin
data class PluginManifest(
    val id: String,                          // reverse-DNS, globally unique, non-blank
    val name: String,                        // non-blank
    val version: SemanticVersion,            // parse "Major.Minor.Patch" strictly
    val minSupportedApi: Int,                // >= 1; > device API → REJECTED
    val description: String,                 // non-blank
    val capabilities: Set<PluginCapability>, // must be non-empty
    val permissions: Set<PluginPermission> = emptySet(),  // declared for visibility
    val dependencies: Set<String> = emptySet(),           // ids of OTHER plugins
    val requiresCapabilities: Set<PluginCapability> = emptySet()
)
```

Validation rules (reject with reason on any violation): blank id/name/description,
negative version components, `minSupportedApi > device`, empty capabilities,
self-dependency, and registry-level **duplicate ids** (later registration wins
rejection). If a plugin declares a dependency on an id that isn't installed,
it stays installed-but-unusable (`UNAVAILABLE`) and cannot be enabled.

---

## 3. Dependencies & capability conflicts

### Declaring dependencies

- `dependencies = setOf("com.…base")` — the named plugin must be installed,
  enabled **and AVAILABLE** before yours can serve. The registry computes a valid
  **enable order** (topological sort, cycles detected) and **refuses to enable**
  a plugin whose requirements are unmet, with a clear reason.
- `requiresCapabilities = setOf(PluginCapability.OCR)` — some other enabled +
  AVAILABLE plugin must serve the capability. A capability requirement also adds
  an ordering edge, so the serving plugin's AVAILABLE state is always known.

### Exclusive capabilities & conflict arbitration

Some capabilities are `exclusive` (`OCR`, `WebSearch`, `FileTransfer`,
`Assistant`, `Export`): only **one** enabled, available plugin may serve them at
a time. When two enabled plugins claim an exclusive capability the registry
picks a **deterministic winner**:

1. higher `version` wins;
2. tie → **earlier registration order** in `PluginRegistry.defaultPlugins()`.

The loser's derived state is `DISABLED` with reason "conflicts with `<winner>`",
and `conflictWinnerId` is set. Enabling such a plugin is **refused** in advance
with the same deterministic result. This only punishes real overlap — enabling a
plugin while it's the only candidate always succeeds; disabling a conflict winner
re-arbitrates immediately.

### The `ShapeFromInk` capability contract

`ShapeFromInk` (non-exclusive) lets a plugin convert a freehand ink stroke into a
crisp geometric shape **on explicit user command** — it is orthogonal to the
canvas's built-in draw-end auto-snap (`ShapeRecognitionHelper.trySnapShape`),
which must not be bypassed or duplicated by the plugin.

A plugin that serves it implements the `ShapeFromInkPlugin` serving interface
(declared in `plugins/NoteflowPlugin.kt` — it is a plain interface, not a
`NoteflowPlugin` subtype; implement it on a class that ALSO implements
`NoteflowPlugin`, like the reference plugin does):

```kotlin
interface ShapeFromInkPlugin {
    fun convertToShape(rawStroke: Stroke): ShapeFromInkOutcome
}
```

Contract:

- `convertToShape` must be **fast and pure** (pure JVM geometry, no I/O, no
  network, no model inference) — it is routed off the main thread via
  `withPluginAsync`, but the core must stay trivially unit-testable.
- Return one of the sealed `ShapeFromInkOutcome` values:
  - `Success(kind: ShapeKind, snappedStroke: Stroke, replaceOriginal: Boolean)`
    — the stroke was confidently a `LINE` / `RECTANGLE` / `ELLIPSE` / `ARROW`;
    `snappedStroke` carries the crisp geometry (tool switched to the matching
    shape tool, original color/width preserved), and `replaceOriginal` is the
    plugin's *recommendation* (from its `plugins.<id>.keepOriginal` setting).
  - `NotAShape` — the ink matched no shape. **Never** mutate or "fake" a shape
    for strokes that don't fit; returning `NotAShape` is the honest contract and
    the UI must surface it as a clear "didn't look like a shape" message.
  - `Error(message)` — unexpected failure (never a thrown exception).
- The **canvas never reaches into plugin geometry code** — callers route through
  `PluginManager.withPluginAsync(PluginCapability.ShapeFromInk, …)` exactly like
  any other capability and only touch `ShapeFromInkPlugin`/`ShapeFromInkOutcome`
  types. The result must be applied through the host's normal undoable stroke
  history so a conversion is one undo away.
- A plugin that is not opted-in or not device-available must make the feature
  surface as unavailable (`NO_PLUGIN_INSTALLED` / `NONE_ENABLED` /
  `Unavailable`) — never a silent no-op on the Convert button.
- Reference implementation: `plugins/inktos/InkToShapePlugin` +
  `plugins/inktos/InkToShapeGeometry` (pure JVM, 25 unit tests).

---

## 4. Versioning & settings migration

### Versioning

- Versions are strict `Major.Minor.Patch` (`SemanticVersion`, comparable).
- Bump **`version`** for ANY behavior or settings change. The `version` bump is
  the migration signal (below). Higher `version` also wins exclusive-capability
  conflicts — bump deliberately.

### How a plugin migrates its own stored settings

Persisted plugin settings are **namespaced per plugin** — every key lives under
`plugins.<id>.<key>` (`PluginSettingKey.key(id, key)`), so two plugins can never
collide. Your plugin reads/writes only its own slice, via the `PluginSettings`
handle passed into every lifecycle hook.

Migration convention (documented contract):

1. On `onEnable`/`onConfigChanged`, read `settings.getInt("settings_schema", 0)`.
2. If the stored schema < your current schema, run the migration steps for the
   intervening versions (read old keys, transform, write new keys), then
   `settings.setInt("settings_schema", <current>)`.
3. Never read raw keys with other plugins' prefixes; never write outside
   `plugins.<id>.*`.

Example:

```kotlin
override fun onEnable(context: Context?, settings: PluginSettings) {
    val schema = settings.getInt("settings_schema", default = 0)
    if (schema < 2) {
        // migrate legacy "delay_ms" (schema 1) → "delay_seconds"
        settings.getInt("delay_ms", -1).takeIf { it >= 0 }?.let {
            settings.setInt("delay_seconds", it / 1000)
        }
        settings.setInt("settings_schema", 2)
    }
}
```

---

## 5. Error handling & isolation

The `PluginManager` routes capability requests **guarded**: a `Throwable`
(`Exception`, including `RuntimeException`, or an `Error` such as an
`AssertionError` from a buggy `require/check`), a null return, or an unavailable
plugin never escapes as a crash — the caller gets a typed `PluginResult`.

```kotlin
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
    data class Unavailable(val reason: PluginFailureReason, val message: String) : PluginResult<Nothing>()
}
```

Rules for plugin authors:

- **Fail loudly, never silently.** Any request you cannot serve should fail; do
  not return bogus data.
- **Never block the main thread.** Heavy work must use `withPluginAsync` (runs
  on `Dispatchers.Default`) or your own background dispatcher. A directly-called
  synchronous `withPlugin` runs on the caller's thread — keep it fast.
- **Never `return null` from a serving interface.** The manager treats null as a
  `Failure(PLUGIN_ERROR)`.
- **Do not swallow your own errors silently** — if you `catch`, return a clear
  `Failure`/exception so diagnostics can record it.

Diagnostics (`PluginDiagnostics` + Settings → Plugins) records per plugin: state,
version, last invocation outcome (`OK` / failure summary with the exception
**class name only**), and a "Test now" `selfCheck` action.

---

## 6. Observability & the "never log" rule

- Log lifecycle events (enable/disable/config/failure) **through the framework**
  — the framework logs only plugin ids, names and exception **class names**.
- Your plugin must **never log content**: no note text, no keys, no passwords,
  no decrypted data. Do not put user content into exception messages that would
  be recorded by diagnostics.

---

## 7. Testing pattern

The framework core is pure JVM (no Robolectric). Mirror the existing tests:

- Use `InMemoryEnableStore` / `InMemoryPluginSettingsStore` from
  `PluginTestHarness.kt`, and `TestPlugin` for configurable plugins.
- Build the registry with an explicit `currentApiLevel` (unit tests have
  `Build.VERSION.SDK_INT == 0`).
- Your plugin must validate: valid manifest, discovery in `allPlugins`, disabled
  by default, enable → `AVAILABLE`, unavailable-capability/device failures are
  typed results, and (for a text-transform plugin) correct output.
- Cover the lifecycle matrix, error isolation, dependency refusal/order and
  conflict arbitration — see `PluginLifecycleStateMatrixTest`,
  `PluginErrorIsolationTest`, `PluginDependencyConflictTest`,
  `PluginManifestValidationTest`, `PluginSettingsNamespacingTest`.

Commands (Linux/CI, no wrapper jar in this repo):

```
gradle testDebugUnitTest
gradle assembleDebug
```

---

## 8. Quick checklist before shipping a Phase 12+ plugin

- [ ] `manifest` is valid: non-blank id/name/description, strict version, `minSupportedApi <= 26`+, non-empty capabilities, no self-dependency.
- [ ] id is reverse-DNS and registered exactly once in `PluginRegistry.defaultPlugins()`.
- [ ] `availability()` is tri-state and honest; permission loss returns `Unavailable(reason)` and recovers.
- [ ] Serving interfaces implemented for every declared capability; no reflection, no hardcoded routing.
- [ ] Required `dependencies`/`requiresCapabilities` declared; enable refusal handled in UI.
- [ ] Settings read/written only via the `PluginSettings` slice; `settings_schema` migration in place.
- [ ] Heavy work runs off the main thread; method is fast when called synchronously.
- [ ] Never returns null from a serving interface except as a real failure.
- [ ] No content ever logged; secrets/note text stay out of exceptions.
- [ ] Unit tests added; `gradle testDebugUnitTest` and `gradle assembleDebug` green.