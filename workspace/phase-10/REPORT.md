# Phase 10 REPORT — Plugin framework (real capability system) [DONE]

Status: **DONE** — builds, tests green, sample plugin live end-to-end.

## What was built

Real, extensible plugin framework in `app/src/main/kotlin/com/authorss81/noteflow/plugins/`:

| File | Role |
|------|------|
| `PluginCapability.kt` | Sealed extension points: `TextTransform` (real), `OCR`, `WebSearch`, `FileTransfer`, `Assistant`, `Export` (declared, unserved — honest). |
| `NoteflowPlugin.kt` | Plugin interface (`id`, `name`, `description`, `version`, `capabilities`, `isAvailable(context?)`, `onEnable`) + `TextTransformPlugin` serving interface. |
| `PluginRegistry.kt` | Compile-time discovery (`defaultPlugins()`), enable-state, `availablePlugins`, `PluginStatus`. No dynamic APK loading. |
| `PluginManager.kt` | Capability routing → `PluginResult.Success` / `Failure` (loud, user-facing). Never throws. |
| `PluginEnableStore.kt` | Persistence abstraction so core is JVM-testable. |
| `Rot13TransformPlugin.kt` | The one real end-to-end plugin (ROT13 cipher over `TextTransformPlugin`). |

Wiring / persistence / UI:

- `services/SettingsManager.kt` — `isPluginEnabled`/`setPluginEnabled` (`plugin_enabled_<id>`, default OFF — opt-in).
- `services/SettingsPluginEnableStore.kt` — production store adapter.
- `NoteflowViewModel.kt:41-64` — `pluginRegistry`, `pluginManager`, `pluginEnabledIds` StateFlow, `setPluginEnabled`, `transformNoteText`.
- `ui/components/PluginSettingsDialog.kt` — Settings → Plugins (status + toggles); reachable from HomeScreen ⋮ menu (`HomeScreen.kt:2357-2362`).
- `ui/screens/MarkdownPreviewScreen.kt:240-273` — Plugins menu in the editor; runs the transform and flushes; failures → snackbar.

## Verification (file:line)

- Framework core is Android-free except the `Context?` param on `isAvailable` (nullable so JVM tests need no Robolectric).
- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**; `PluginFrameworkTest` 9/9 pass (discovery, enable/disable persistence, routing invokes enabled plugin, disabled-skipped with loud failure, unavailable capability no-plugin failure, unavailable-on-device failure, enabled excludes unavailable, `onEnable` once-per-transition, ROT13 round-trip).
- `gradle assembleDebug` — **BUILD SUCCESSFUL**.

## Honesty

- OCR / WebSearch / FileTransfer / Assistant / Export are *declared* extension points only; requesting them yields `PluginResult.Failure("No plugin is installed…")` — no fake implementations.
- R8/release: plugin serving interfaces are referenced via `as?` casts in real call sites; no reflection, no keep rules needed.
- No DB schema change. No new third-party dependencies. No `.github/workflows/` edits. No new INTERNET usage.

## Docs

- `docs/PLUGINS.md` — full integration guide (add a plugin / add a capability) + design rules.
- `AGENTS.md` — truthful Phase 10 bullet added to the post-audit truth table.