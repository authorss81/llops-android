# Phase 15: Plugin pack — privacy-first on-device AI & media (keyless)

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a working plugin framework (Phase 10), OCR + Web Search plugins
(Phase 11), and pure-JVM productivity plugins (Phase 14). This phase adds
on-device AI and media plugins. ALL keyless — no API keys. Privacy-first:
everything runs on-device or on free keyless endpoints.

Add ALL of the following. Each must WORK — no stubs.

## Plugin 1: Dictation plugin (speech-to-text)
- Use Android's built-in **SpeechRecognizer** (offline models when available) to
  insert text into the editor. No API key.
- Voice activation must be explicit (a mic button) — never ambient.
- Parsing/UI-assembly logic pure-JVM and unit-tested; the SpeechRecognizer glue
  is platform-only.
- Handle "offline not available" by surfacing a clear message.

## Plugin 2: Read-aloud plugin (text-to-speech)
- Use Android's built-in **TextToSpeech** engine to read a selected passage.
- Respect a quiet mode (SilentToggle) and never auto-play.
- Pure-JVM testable: passage splitting into TTS-chunk boundaries, queueing logic.
- No API key, no new permission (uses existing app capabilities).

## Plugin 3: On-device translation plugin
- Use **ML Kit Translation** (on-device, free, keyless) to translate a selected
  passage. Models download once, offline after.
- Bundled model strategy: lazy-download on first use with explicit user consent
  and clear progress. Do NOT bundle large models in the APK.
- Pure-JVM testable: build a small translator interface with a fake impl for
  unit tests; ML Kit impl behind it.
- Fallback: if model download fails/offline, surface clear error, do not crash.

## Plugin 4: Offline AI assistant plugin (local LLM)
- Integrate an on-device small LLM via **llama.cpp** GGUF (or LiteRT-LM if
  simpler). Model is NOT bundled: user downloads a small model (~100–300 MB) on
  first use with consent + progress; store under app-private files (not cache).
- Capabilities: summarize a note, extract action items, answer questions about
  note content. Pure-JVM testable: conversation/summary prompt assembly logic
  unit-tested with a fake inference engine.
- Must respect offline-first: works with no network after model download.
- Low-end guard: only enable on devices meeting `DeviceCompatibilityManager`
  high-tier; otherwise show "assistant unavailable on this device".

## Plugin 5: Screenshot → note plugin
- Capture a screenshot of the current canvas/note (via `PixelCopy` or view
  draw) and save as an image note, or OCR it with the existing Phase-11 OCR
  plugin to make it text-searchable.
- Reuse existing OCR + export paths — do not duplicate.
- Pure-JVM testable: the "screenshot metadata + OCR flow" decision logic.

## Definition of done
- `gradle assembleDebug` succeeds. ML Kit translate + llama.cpp (or LiteRT-LM)
  deps allowed for this phase; SpeechRecognizer/TTS are platform APIs.
- `gradle testDebugUnitTest` passes with pure-JVM tests per plugin (TTs chunking,
  prompt assembly, translator-interface fake, screenshot-flow logic).
- All five are registered, individually toggleable in settings, reachable in UI.
- No model weights committed to git; no hardcoded API keys; no new permissions
  beyond what ML Kit translate may require (it needs none) — verify.
- `docs/PLUGINS.md` updated with the five plugins as examples.

## Constraints
- Only ML Kit Translation and the chosen LLM runtime (llama.cpp/LiteRT-LM) may be
  added. No other new deps. No new permissions.
- No network except explicit user-initiated model downloads.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- No fake/stub behavior — every plugin actually works.
- Model downloads go to app-private storage with size checks and free-space
  guards; abort cleanly if insufficient space.