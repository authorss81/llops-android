# Phase 12: Real high-level plugins — OCR + Web Search (NOT FAKE) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework (Phase 10 + Phase 11 infrastructure). This phase implements TWO real,
high-value plugins on top of it. They must WORK — no stubs, no fake recognition,
no placeholder results. Both must be verifiable by unit test and reachable in the
UI through the Phase 10 plugin registry + settings toggles.

The app already has `INTERNET` permission (added for WebDAV) — Web Search may use
it. OCR should work ON-DEVICE (offline). This is the phase that makes the
"plugin system" real.

## Plugin 1: OCR (on-device, offline)

Extract text from images so users can search/export it from notes.

- **Real implementation required.** Choose the honest path that fits the app's
  constraints:
  - Preferred: **ML Kit Text Recognition (on-device)** — `com.google.mlkit:text-recognition` +
    `text-recognition-chinese` if multilingual support is wanted. Runs offline,
    no API key, no INTERNET. Add as a plugin dependency (the app currently has
    no ML deps — adding one here is expected and approved for this phase).
  - Alternative if ML Kit is rejected: a real bundled OCR library. Do NOT ship
    a dictionary-hash "recognizer" like the old `HandwritingRecognitionService`
    (that was removed in Phase 3 for being fake).
- Wire into the existing image/attachment flow: when an image is attached to a
  note, offer "Extract text (OCR)" which runs the model off the main thread
  (`Dispatchers.IO`), shows the recognized text, and lets the user insert it into
  the note or copy it (via `ClipboardGuard.recordCopy` — NEVER bypass it).
- Show real progress/error states. Cancelable.
- Unit test: ML Kit can't run in JVM unit tests — so test the WRAPPING logic
  (input validation, result formatting, error mapping) in pure JVM, and mark the
  model invocation as platform-only. Be explicit about this in tests; do not
  silently skip.

## Plugin 2: Web Search (from a note)

Let users run a web search and insert/cite results into a note.

- **Real implementation required:** call a real, keyless, documented search API
  and display real results. Recommended: **DuckDuckGo Instant Answer API**
  (`https://api.duckduckgo.com/?q=...&format=json&no_html=1`) — free, no key,
  HTTP GET, JSON. (If DuckDuckGo is unreachable in CI, use Wikipedia's search API
  as an alternative — both are keyless.)
- Runs on `Dispatchers.IO`, uses the existing HTTP capability (add a minimal HTTP
  client dependency if needed — e.g. `okhttp`, approved for this phase). Never on
  the main thread.
- UI: from a note or command menu, "Search the web" → shows results list → tap to
  insert a `[title](url)` link into the note. Respect the app's offline-first
  ethos: show a clear "offline — check connection" error, never silently fail.
- Unit test: parse a sample JSON response into result objects (pure JVM, no
  network). Assert empty/error responses are handled.
- Both plugins must be registered in the Phase 10 registry (respecting the
  Phase 11 lifecycle/error-isolation contract), individually
  toggleable in settings, and `isAvailable()` must reflect INTERNET presence for
  Web Search.

## Definition of done
- `gradle assembleDebug` succeeds (with the new plugin deps).
- `gradle testDebugUnitTest` passes — OCR wrapper tests + Web Search parse tests.
- OCR extracts text from a test image on-device (documented; a JVM test asserts
  the wrapper, a manual/CI-emulator note confirms the model runs).
- Web Search returns real results and inserts a link into the note.
- Both are reachable via the plugin settings and work — NOT dead UI.
- `docs/PLUGINS.md` updated with the two plugins as examples of full
  implementations (following the Phase 11 `docs/PLUGIN_SDK.md` contract).

## Constraints
- NO fake/stub behavior. If a feature cannot be made real, do not ship it as
  "done" — mark it clearly and defer.
- Network calls ONLY from `Dispatchers.IO`/worker. No blocking the main thread.
- No copying text to clipboard without `ClipboardGuard`.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- Keep the plugin boundary clean: OCR and Web Search logic live in their plugin
  packages, not in the core ViewModel/screens.