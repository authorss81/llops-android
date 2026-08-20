# Phase 185: GalleryView — scrub raw Markdown syntax from card preview snippets [NOT STARTED]

You are working on **InkFlow/Noteflow**. User visual review: the gallery card preview shows
raw Markdown artifacts (`# 📅 Journal - 2026-08-19`, `**`, `> [!NOTE]`, `- [ ]`) making
the card look unparsed. Relevant code: `ui/components/GalleryView.kt:174-183`.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-185 step N: <desc>" && git push`
after EVERY step.

## Step 1 - Inventory (commit it)
- Read `ui/components/GalleryView.kt:174-183` and check where else extracted text
  previews are shown (list view `NotePageCard` in HomeScreen, search results, etc.)
  so the scrubber is reused consistently.
- COMMIT this step.

## Step 2 - Add a pure-JVM Markdown preview scrubber
- New pure-JVM `services/MarkdownPreviewScrubber.kt` (or extend an existing policy):
  strip `#`/`##` heading markers, `**bold**`, `*italic*`, `` `code` ``, `![img](url)`,
  `[link](url)` -> link text, `- [ ]`/`- [x]` task boxes -> `•`, leading `> ` blockquote
  markers, and collapse whitespace/newlines to a compact single-space snippet; trim.
  Preserve meaningful text; never leak raw syntax. API 26+ pure JVM, unit-testable.
- Use it in GalleryView's preview (`remember(page.extractedText) { scrub(it) }`),
  `maxLines = 4`, `overflow = TextOverflow.Ellipsis`, `bodySmall`, lineHeight 16sp.
- COMMIT this step.

## Step 3 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests for the scrubber: headings/bold/italic/code/images/links/tasks/
  blockquotes all scrubbed, plain text unchanged, empty input safe.

## Definition of done
- Card previews show clean readable text (no `#`, `**`, `>`, `[ ]` artifacts).
  `workspace/phase-185/REPORT.md` before/after + tests.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. No new dependencies. No DB schema change.
- Scrubber is DISPLAY-ONLY — never writes back to the stored extracted text.