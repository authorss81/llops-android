# Phase 25: API-key integration + local LLM plugin (skip if done) + APK build [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework and a plugin store (Phases 10–11, 21). It
already has an on-device assistant plugin (Phase 16: `plugins/assistant/`
`MediaPipeLlmEngine`/`OnDeviceAssistantPlugin` — local LLM inference). This phase
(1) adds SECURE API-key integration for optional cloud AI plugins, (2) confirms
the local LLM plugin (skip re-implementation if it already exists and works), and
(3) triggers an APK build.

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

## 2. Local LLM plugin — SKIP if already implemented
- Check `plugins/assistant/` (OnDeviceAssistantPlugin, MediaPipeLlmEngine,
  AssistantModelDownloader, AssistantStoragePolicy). If it exists and is
  functional (registered, toggleable, model-download flow, offline inference
  behind `DeviceCompatibilityManager`), then DO NOT re-implement it — just verify
  it with `file:line` evidence and document it as complete.
- Only if it is missing/broken, implement it (local GGUF via LiteRT/llama.cpp,
  model downloaded on consent to app-private storage, size/free-space guards,
  summarize/ask capabilities, low-end gating). Prefer to skip if present.

## 3. Trigger an APK build
- Ensure `gradle assembleDebug` and `gradle testDebugUnitTest` pass.
- Build a release APK: `gradle assembleRelease` (or trigger the existing
  `release.yml` workflow via `gh workflow run`). Document the artifact name and
  path in this phase's REPORT.md (see DoD).
- The APK will be analyzed in a later phase (Phase 28) — ensure it is
  downloadable as an artifact (build locally in this job and note where the
  artifact lives, or ensure release.yml produces one).

## Definition of done
- API key manager works: add/edit/delete keys, encrypted at rest, never logged.
- Cloud AI plugin registered, off-by-default, consent-gated, functional with a
  key, unavailable without one. Keys never in URLs/logs/errors.
- Key handling reviewed twice (document both review passes).
- Local LLM plugin verified (or implemented if truly missing) — evidence in
  `file:line`.
- `gradle assembleDebug` + `gradle testDebugUnitTest` + `gradle assembleRelease`
  all succeed; REPORT.md lists the release APK artifact path.
- `docs/` documents the key-manager security model.

## Constraints
- NO hardcoded keys. NO new INTERNET permission (already present for WebDAV/other
  plugins). Network only on IO dispatcher, user-initiated, https-only.
- Do NOT change the DB schema (keys live in the secure store / encrypted prefs,
  never in the DB plaintext).
- Do NOT edit `.github/workflows/`.
- Never bypass `ClipboardGuard`. Never log decrypted content, keys, or secrets.
- Be honest: cloud AI is labeled cloud; local LLM is offline. Do not blur them.