# Phase 37: Markdown & Hybrid Editor Experience [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a Markdown editor (`ui/screens/EditorScreen.kt`), a Markdown preview
(`ui/screens/MarkdownPreviewScreen.kt`, with web-search/plugins menus), voice
notes (time-synced `.m4a`, Phase 07/16), and a plugin framework. **Read
`docs/phase-status.md` first** — do not regress existing editor/preview/plugin
behavior.

**THE GOAL:** move the writing experience toward a **hybrid block editor** —
live inline Markdown rendering that stays raw-edit-friendly, plus rich callouts,
media embeds with audio-waveform scrubbing, and delightful interactive
checkboxes — all real and performant.

## 1. Live Inline Markdown Rendering (hybrid block editor)
- Move toward a **seamless WYSIWYG/hybrid block editor**: headers, code blocks,
  lists, and **LaTeX/math** formulas format inline while keeping the **raw syntax
  easily editable on tap** (tap a block → edit raw; tap away → rendered).
- Keep it honest: if a full block editor is too large for one phase, implement a
  solid first slice (inline rendering of headings/bold/italic/code/lists/links/
  math in the editing view) with clear documentation of what is deferred. Do NOT
  fake rendering — rendered output must match the preview engine's output.
- Pure-JVM tests: a block-tokenizer/rendering slice that formats Markdown
  snippets identically to the preview path.

## 2. Rich Callouts & Media Embeds
- **Styled callout cards**: Note, Warning, Tip, Quote (typed Markdown fenced
  callouts, e.g. `> [!NOTE]`-style) rendered as styled cards in preview AND
  editable in the hybrid editor.
- **Audio waveform timeline scrubbing** for voice notes: render the existing
  `.m4a` waveform and allow **tap/drag scrubbing** to a position (reuse the
  existing voice-note playback + the Phase 07 time-sync); fix the Phase-30
  flagged O(n²) waveform perf (`B2-DOS-03`) while here.
- **Interactive checkboxes with celebratory micro-animations**: `- [ ]` /
  `- [x]` toggle in the editor, with a subtle scale/check animation (respect
  reduce-motion).

## 3. Integration
- Keep the existing plugins menus (web search, OCR, plugins) working in the
  hybrid editor. No regressions to encryption, `ClipboardGuard`, FLAG_SECURE.

## Definition of done
- Live inline Markdown formatting works for the listed constructs; raw syntax
  editable on tap; output matches the preview renderer (test-verified).
- Callout cards (Note/Warning/Tip/Quote) render styled and stay editable.
- Voice-note waveform scrub works and is no longer O(n²) (`file:line` evidence).
- Checkboxes toggle with a micro-animation respecting reduce-motion.
- `gradle testDebugUnitTest` + `gradle assembleDebug` pass.
- REPORT.md: what is fully implemented vs. deferred slice, perf evidence.

## Constraints
- Do NOT fake rendering: rendered output must equal the preview engine's output.
- No new permissions. Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- No heavy new deps without justification; LaTeX rendering (if added) must be
  offline (e.g. a local parser — no network, no key).
- Never log decrypted note content. Keep `ClipboardGuard` + FLAG_SECURE intact.