# Phase 25: API-key integration + downloadable local-LLM plugin + APK build [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework and a plugin store (Phases 10–11, 21) and a
**runtime downloadable-plugin infrastructure (Phase 22: download → signature-verify →
load)**. It already has an on-device assistant plugin (Phase 16:
`plugins/assistant/` `MediaPipeLlmEngine`/`OnDeviceAssistantPlugin` — local LLM
inference).

**THE KEY CHANGE THIS PHASE:** the MediaPipe `tasks-genai` engine (~150 MB of
native libs) MUST NOT stay baked into the base APK — it is exactly the bloat you
are eliminating. This phase moves the local-LLM feature behind the Phase-22
downloadable-plugin runtime so users who never use the AI assistant don't carry
its size or pay its startup cost. This phase (1) adds SECURE API-key integration
for optional cloud AI plugins, (2) **relocates the local LLM engine out of the
base app into a downloadable plugin**, and (3) triggers an APK build that is
SMALLER as a result.

## 1. Secure API-key integration (for cloud AI plugins)
- Provide a secure **API key manager**: keys stored ENCRYPTED at rest
  (AndroidKeyStore-wrapped key, AES-GCM — reuse the app's existing
  `EncryptionService`/`VaultKeyHolder` patterns; never plaintext, never
  SharedPreferences plaintext, never logged).
- UI: a "Keys" section in settings to add/edit/delete keys for providers (e.g.
  OpenAI-compatible, Anthropic, or any generic "API URL + key" provider) with
  masked input, a visibility toggle, and delete. Keys are per-provider,
  namespaced, and NEVER exported.
- Integration: an optional **Cloud AI plugin** that uses a configured key to call
  a generic chat-completions endpoint (real HTTP, IO dispatcher, user-initiated),
  clearly labeled "cloud — your text leaves the device". It must be OFF by
  default, only usable when a key is set, with clear consent wording before the
  first call. If no key → plugin shows "unavailable, add API key in settings".
- Security rules: TLS only (https), no key in URLs/logs, error messages never
  echo the key, timeout + cancellation, response size limits. REVIEW the key
  handling twice (self-review pass) — nothing may go wrong.
- Pure-JVM tests: key encryption/decryption round-trip (with a fake secure store
  seam), masked-input formatting, request-payload builder (no key leakage in
  body/logs), endpoint validation (https-only, no injection).

## 2. Local LLM — move OUT of the base APK into a downloadable plugin
- The base app currently ships `libs.mediapipe.tasks.genai` (~150 MB native) in
  `app/build.gradle.kts`. **Remove it from the base app's dependencies** so the
  base APK no longer contains the LLM engine. Report the base-APK size before/after
  in REPORT.md (this is a headline result).
- Deliver the local LLM as a **downloadable plugin artifact** consumed by the
  Phase-22 runtime: build it in a SEPARATE Gradle module (e.g. `plugins/llm/`)
  that depends on `tasks-genai` and implements the Phase-10 `NoteflowPlugin`
  interface + the Phase-22 capability facade (model file in app-private storage,
  size/free-space guards, summarize/ask capabilities, low-end gating). It is
  downloaded, signature-verified and installed only on explicit user consent, and
  is OFF by default.
- Keep the plugin-side engine code (`MediaPipeLlmEngine`, `AssistantModelDownloader`,
  `AssistantStoragePolicy`) but relocate it into the plugin module. The base app
  keeps only a thin "AI assistant unavailable — install from plugin store" stub
  that routes to the store.
- If the runtime in Phase 22 is not yet mergeable for this (blocked), STOP and mark
  the phase `.blocked` with an honest REPORT.md — do NOT re-bake the engine into
  the base app as a fallback.
- Pure-JVM tests: model-file guard logic, storage-policy decisions, prompt building,
  plugin availability reporting (no network, no engine needed).

## 3. Trigger an APK build
- Ensure `gradle assembleDebug` and `gradle testDebugUnitTest` pass.
- Build a release APK: `gradle assembleRelease` (or trigger the existing
  `release.yml` workflow via `gh workflow run`). Document the artifact name and
  path in this phase's REPORT.md (see DoD).
- The base APK must be measurably smaller than the previous release (report the
  delta; tasks-genai no longer in it).
- The APK will be analyzed in a later phase (Phase 28) — ensure it is
  downloadable as an artifact (build locally in this job and note where the
  artifact lives, or ensure release.yml produces one).

## Definition of done
- API key manager works: add/edit/delete keys, encrypted at rest, never logged.
- Cloud AI plugin registered, off-by-default, consent-gated, functional with a
  key, unavailable without one. Keys never in URLs/logs/errors.
- Key handling reviewed twice (document both review passes).
- `tasks-genai` REMOVED from the base app; the local LLM ships as a downloadable,
  signature-verified plugin consumed via the Phase-22 runtime; base APK is
  measurably smaller (delta in REPORT.md).
- `gradle assembleDebug` + `gradle testDebugUnitTest` + `gradle assembleRelease`
  all succeed; REPORT.md lists the release APK artifact path + size before/after.
- `docs/` documents the key-manager security model and the downloadable-LLM model.

## Constraints
- NO hardcoded keys. NO new INTERNET permission (already present for WebDAV/other
  plugins). Network only on IO dispatcher, user-initiated, https-only.
- Do NOT change the DB schema (keys live in the secure store / encrypted prefs,
  never in the DB plaintext).
- Do NOT edit `.github/workflows/`.
- Never bypass `ClipboardGuard`. Never log decrypted content, keys, or secrets.
- Be honest: cloud AI is labeled cloud; local LLM is offline. Do not blur them.
- Do NOT fall back to re-baking the LLM engine into the base app if the
  downloadable runtime is not ready — mark `.blocked` instead.