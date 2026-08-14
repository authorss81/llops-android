# Phase 26: Lightweight plugin ecosystem — 5 safe compile-time plugins [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened hybrid plugin framework (Phases 10–11, 22), a downloadable
runtime (Phase 23), dynamic updates (Phase 24) and the ink→shape plugin (Phase 25).
**Read `docs/plugin-architecture.md`** — under the hybrid model these plugins are
**compile-time** because they are pure-JVM or tiny-keyless-HTTP and add only a few
KB to the base APK.

## Add ALL five (each must WORK — no stubs)
1. **Dictionary plugin** — keyless `dictionaryapi.dev` (no key, JSON) with an
   offline fallback to a small bundled word list. Inserts "word — definition" into
   the note. Pure-JVM test: JSON parse + offline fallback.
2. **Weather plugin** — keyless Open-Meteo (no key). Dated weather snapshot;
   location-free by default (fixed default city or coarse lat/lon from a setting;
   NO GPS permission). Pure-JVM test: forecast JSON parse.
3. **Unit converter plugin** — pure-JVM conversion (length/mass/temperature/
   currency-basic) inline in the editor ("2 km to mi" → insert result). Fully
   offline, zero deps. Tests: conversion-matrix correctness.
4. **Outline/checklist generator plugin** — from selected text, generate a
   structured outline or checkbox list. Pure Kotlin. Tests: grouping/indent.
5. **Citation formatter plugin** — format a pasted URL/title into clean Markdown
   `[title](url)` (fetch title via HTTPS or plain-text fallback). Pure-JVM test:
   payload building.

## Integration requirements
- Register all five in the Phase-10 registry (`PluginRegistry.defaultPlugins()`),
  each individually toggleable in the Phase-21/23 store, `isAvailable()` reflects
  real availability (e.g. network plugin unavailable offline).
- Network only on `Dispatchers.IO`, user-initiated, clear offline/error states
  (reuse the web-search/weather error patterns). Offline-first: graceful offline
  path each.
- Add each plugin to `docs/PLUGINS.md` as a full example.

## Definition of done
- `gradle assembleDebug` succeeds (keyless HTTP deps allowed; NO ML Kit barcode,
  NO native OCR, NO LLM — those are downloadable, NOT here).
- `gradle testDebugUnitTest` passes with pure-JVM tests per plugin (see above).
- All five plugins reachable via the store, toggleable, functional; no fake
  recognition/conversion/results. Offline paths genuinely work.
- Base-APK size delta from this phase is minimal (report it — should be a few
  hundred KB max from HTTP/JSON libs, not MB).

## Constraints
- Permissions: NO new permissions (no GPS, no network-state). Network on IO
  dispatcher only, user-initiated only, no background sync.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Never bypass `ClipboardGuard` for copy actions. No logging of content/keys.
- Keep the plugin boundary clean: plugin logic lives in its plugin package, not in
  the core ViewModel/screens.
- Do NOT add heavy deps to the base app (ML Kit, native engines) — defer those to
  downloadable plugins.