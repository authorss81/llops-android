# Phase 22: Downloadable-plugin runtime + lightweight ecosystem [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework (Phases 10–11), an existing plugin set
(Phases 12/15/16) and a plugin store UI (Phase 21).

**THE CORE GOAL OF THIS PHASE (read first):** plugins must NOT increase the
base APK size and must NOT force features onto users they will never use.
Heavy/native features (camera OCR/QR, large ML engines, LLM) must ship as
**downloadable, signature-verified plugins** fetched over HTTPS only when the
user explicitly installs them. Lightweight features (pure-JVM or small-keyless-HTTP)
stay compile-time because they cost only a few KB.

So this phase delivers TWO things:
1. **A REAL runtime plugin-loading infrastructure** — the mechanism that lets the
   app download, verify, install and load plugin code at runtime without baking it
   into the APK.
2. **Five NEW lightweight plugins** that are safe to ship compile-time (they are
   pure-JVM or tiny keyless-HTTP and add negligible size).

## Part A — Downloadable-plugin runtime (PRIORITY — this is the hard part)
Build the infrastructure that allows download-and-load without base-APK growth:

1. **`PluginDownloader`** — downloads a plugin artifact (a signed plugin APK/AAR
   containing DEX) over HTTPS to app-private storage. TLS only. Only user-initiated
   (from the Phase-21 store). Size + free-space guards. Resume/cancel. Never to
   shared storage.
2. **Signature verification (MANDATORY, security-critical)** — before ANY plugin
   code is loaded, verify the downloaded artifact's signature against a pinned
   certificate hash embedded in the host app (constant, not user-editable).
   Reject with a clear error if the signature does not match the pinned hash or if
   the artifact is tampered. `file:line`-documented. This is what makes downloaded
   code safe enough for an encrypted-notes app.
3. **`RuntimePluginLoader`** — loads the verified DEX at runtime via
   `DexClassLoader` + a `ClassLoader` for the plugin package, instantiating plugins
   through the existing `NoteflowPlugin` interface via reflection. Keep the
   Phase-10/11 registry API the same for compile-time plugins so existing plugins
   are untouched.
4. **Capability isolation** — plugin code NEVER receives direct handles to the
   Room DB, keystore, `EncryptionService`, or decrypted note content. It only gets
   a narrow `PluginContext` capability facade (e.g. `insertText`, `showResult`,
   `httpGet(httpsOnly)`, `readSelection`), explicitly whitelisted per capability.
   Document the exact surface in `docs/PLUGINS.md`.
5. **Consent + enablement** — first download requires explicit user consent with
   clear wording ("this plugin adds features from a third party; it is
   signature-verified"). Downloaded plugins are OFF by default and toggleable in
   the Phase-21 store. Persisted list of installed plugins + their SHA-256 + pinned
   cert hash; integrity re-checked on every load.
6. **Store wiring** — extend the Phase-21 `PluginStoreDialog` (and its
   ViewModel/controller) so remote plugins show download/install/delete/disable
   states, reuse the existing compile-time catalog for the built-ins.
7. **Tests (pure-JVM)** — signature-verify accept/reject (valid cert, wrong cert,
   tampered bytes), download-to-install happy path (fake transport), tamper
   rejection before load, capability-facade deny-by-default.

## Part B — Five lightweight plugins (safe compile-time, ~KB each)
1. **Dictionary** — keyless `dictionaryapi.dev` (no key, JSON) with an offline
   fallback to a small bundled word list. Inserts "word — definition" into the
   note. Pure-JVM test: JSON parse + offline fallback.
2. **Weather** — keyless Open-Meteo (no key). Dated weather snapshot; location-free
   by default (fixed default city or coarse lat/lon from a setting; NO GPS
   permission). Pure-JVM test: forecast JSON parse.
3. **Unit converter** — pure-JVM conversion (length/mass/temperature/currency-basic)
   inline in the editor ("2 km to mi" → insert result). Fully offline, zero deps.
   Tests: conversion-matrix correctness.
4. **Outline/checklist generator** — from selected text, generate a structured
   outline or checkbox list. Pure Kotlin. Tests: grouping/indent.
5. **Citation formatter** — format pasted URL/title into clean Markdown
   `[title](url)` (fetch title via HTTPS or plain-text fallback). Pure-JVM test:
   payload building.

**QR/barcode scanning is DELIBERATELY NOT a compile-time plugin** (ML Kit barcode
is a heavy native dep that would bloat the base APK). It is deferred to a later
downloadable plugin using the Part-A runtime — note this in the REPORT.md. Do NOT
add ML Kit barcode to the base app.

## Integration requirements
- Part-B plugins register in the Phase-10 registry, each individually toggleable
  in the Phase-21 store, `isAvailable()` reflects real availability.
- Network only on `Dispatchers.IO`, user-initiated, clear offline/error states
  (reuse existing patterns). Offline-first: graceful offline path each.
- Part-A runtime lives under `plugins/runtime/` (or equivalent), clearly separated
  from compile-time plugin code.
- Update `docs/PLUGINS.md` with: runtime model, pinned-cert verification, capability
  facade surface, the downloadable-vs-compile-time split.

## Definition of done
- `gradle testDebugUnitTest` passes (Part-A pure-JVM tests + per-plugin tests).
- `gradle assembleDebug` succeeds WITHOUT adding ML Kit barcode / native OCR / LLM
  to the base app. Base APK size delta from this phase is minimal (report it).
- Part-A: download → verify → load → execute works end-to-end with a small test
  plugin artifact; tampered/wrong-signature artifacts are rejected before any code
  runs (`file:line` evidence).
- Part-B: all five plugins reachable, toggleable, functional; no fake results;
  offline paths genuinely work.
- REPORT.md records: pinned cert hash handling, capability surface, base-APK size
  before/after, and the deferred downloadable plugins (QR, LLM).

## Constraints
- Permissions: NO new permissions for Part B (no GPS, no network-state). Camera is
  NOT added in this phase.
- Never log keys, passwords, decrypted content, or downloaded artifact contents.
- Signature pinning is a compile-time constant — never user-editable, never
  bypassable in release builds.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Do NOT bypass `ClipboardGuard` for copy actions.
- Keep the plugin boundary clean: plugin logic lives in its plugin package; the
  runtime lives in `plugins/runtime/`.