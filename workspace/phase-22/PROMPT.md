# Phase 22: Plugin ecosystem — add more meaningful plugins [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a working, hardened plugin framework (Phases 10–11) and an existing
plugin set (OCR, web search, export, share-clip, dictation, read-aloud,
translation, on-device assistant, screenshot, text tools, language detection,
web capture — Phases 12/15/16) plus a plugin store (Phase 21).

This phase grows the plugin ecosystem with NEW, meaningful, REAL plugins suited
to a privacy-first note-taking app. Every plugin must follow the Phase-11 SDK
contract (`docs/PLUGIN_SDK.md`): manifest, lifecycle, error isolation, namespaced
settings, pure-JVM-testable core, reachable UI.

## Add ALL of these (each must WORK — no stubs):
1. **QR/Barcode scanner plugin** — scan a QR/barcode with the camera (use a real
   library: ML Kit barcode-scanning, approved for this phase) and insert the
   result (URL/text/contact) as a note link or text. Permission: CAMERA (add only
   this, explain it). Pure-JVM test: result-format handling.
2. **Dictionary plugin** — word lookup via a keyless dictionary API
   (dictionaryapi.dev is free, no key, JSON) with an offline fallback to a small
   bundled word list. Shows definition/example, lets you insert a "word —
   definition" line into the note. Pure-JVM test: JSON parse + offline fallback.
3. **Weather plugin** — keyless Open-Meteo API (no key, free) to insert a dated
   weather snapshot (location-free by default — use a fixed default city or
   coarse lat/lon from a setting, privacy-first: no GPS unless user opts in).
   Pure-JVM test: forecast JSON parse.
4. **Unit converter plugin** — pure-JVM conversion (length/mass/temperature/
   currency-basic) inline in the editor ("2 km to mi" → insert result). Fully
   offline, zero deps. Pure-JVM tests: conversion matrix correctness.
5. **Outline/checklist generator plugin** — from selected text, generate a
   structured outline or checkbox list. Pure Kotlin. Tests: grouping/indent.
6. **Citation formatter plugin** — format a pasted URL/title into a clean
   Markdown citation `[title](url)` with fetch title (reuse HTTP client), or
   plain-text fallback. Pure-JVM test: payload building.

## Integration requirements
- Register all six in the Phase-10 registry, each individually toggleable in the
  Phase-21 plugin store, `isAvailable()` reflects required permissions/presence.
- Network only on `Dispatchers.IO`, user-initiated, with clear offline/error
  states (reuse the web-search/weather error patterns). Offline-first: each has
  a graceful offline path.
- Add each plugin to `docs/PLUGINS.md` as a full example.

## Definition of done
- `gradle assembleDebug` succeeds (ML Kit barcode + any keyless HTTP deps are
  approved for this phase; no other new deps).
- `gradle testDebugUnitTest` passes with pure-JVM tests per plugin (see above).
- All six plugins reachable via the plugin store, toggleable, and functional.
- No fake recognition/conversion/results. Offline paths genuinely work.

## Constraints
- Permissions: ONLY CAMERA for the QR plugin (justify in code comment). Do NOT
  add GPS/network-state permissions.
- Network only on IO dispatcher, only user-initiated. No background sync.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Never bypass `ClipboardGuard` for copy actions. No logging of content/keys.
- Keep the plugin boundary clean: plugin logic lives in its plugin package, not
  in the core ViewModel/screens.