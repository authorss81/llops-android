# Phase 15: Plugin pack — productivity & knowledge (pure-JVM, high ROI) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework (Phases 10–11) and real OCR + Web Search
plugins (Phase 12). This phase adds a batch of HIGH-VALUE plugins whose cores are
pure JVM — meaning they are fully unit-testable in CI with no device or native
code. Add them to the Phase 10 registry (following the Phase 11 SDK contract) as
real, working plugins with settings toggles.

Add ALL of the following. Each must WORK — no stubs.

## Plugin 1: Export Engine (highest ROI)
- Export any note to: Markdown (already supported), **HTML**, **PDF**, and share
  out via `ACTION_SEND`. Render canvas pages to **PNG/PDF** using the Android
  built-in `PdfDocument` (zero deps) and `ImportExportService` extensions.
- Use the app's existing CommonMark parser for Markdown→HTML. For HTML→PDF use
  Android's `PdfDocument` (draw simple text layout) — do NOT add a heavyweight
  PDF dependency unless trivial.
- Pure-JVM testable: markdown→HTML conversion and export-payload assembly.
- Reachable from the note/editor menu. Respect existing export paths.

## Plugin 2: Share target ("Clip to InkFlow")
- Register `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents so text, images, and
  files can be "clipped" into a new or existing encrypted note.
- Parsing/validation logic (extract text vs URI vs multipart, size guard) must be
  pure JVM and unit-tested. The manifest/Activity glue is platform-only.
- Must not bypass encryption: received content is stored encrypted like any note.

## Plugin 3: Text tools
- Word/character count, paragraph stats, reading-time, Flesch-Kincaid
  readability, and a simple note-diff. Pure Kotlin, zero deps, fully unit-tested.
- Reachable as a "text tools" action in the editor.

## Plugin 4: Language detection & auto-tagging
- Detect the language of note text using a REAL detector. Recommended: **Lingua**
  (pure JVM, Apache-2.0, 75+ languages, no native code, CI-testable). Use a
  bounded language subset + low-accuracy mode to keep memory sane on low-end.
- Auto-assign a `language` tag on save (respect user override), and expose
  detection as a menu action.
- Unit test: known sample sentences return the correct language.

## Plugin 5: Web page → clean markdown note
- Paste a URL → fetch HTML → extract readable content with **jsoup** (pure JVM)
  → save as a clean Markdown note (companion to the Phase-11 Web Search plugin).
- Network on `Dispatchers.IO`, user-initiated, with clear offline/error handling.
- Pure-JVM testable with MockWebServer or a captured fixture (no real network in
  tests). `INTERNET` permission already exists.

## Definition of done
- `gradle assembleDebug` succeeds (with new pure-JVM deps: Lingua, jsoup —
  these are approved for this phase).
- `gradle testDebugUnitTest` passes, with new tests for each plugin's pure-JVM
  core (export assembly, share parsing, text tools math, language detection,
  HTML→markdown extraction).
- All five are registered, individually toggleable in settings, and reachable in
  the UI — NOT dead.
- `docs/PLUGINS.md` updated with the five plugins as examples.

## Constraints
- Only the listed pure-JVM dependencies (Lingua, jsoup) may be added. No others.
- No network except user-initiated actions in Plugin 5. No new permissions.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- No fake/stub behavior. Every plugin must actually work.
- Respect `ClipboardGuard` on any clipboard copy (none required here unless you
  add copy actions — then use it).