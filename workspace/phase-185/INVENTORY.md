# Phase 185 — Step 1 Inventory: extracted-text PREVIEW render sites

User visual review: the gallery card preview shows raw Markdown artifacts
(`# 📅 Journal - 2026-08-19`, `**`, `> [!NOTE]`, `- [ ]`), making the card look
unparsed. Before writing the scrubber, every surface that renders `pages.extractedText`
as a **card/snippet preview** is catalogued so the scrubber is reused consistently.

## Scanned scope

`app/src/main/kotlin/com/authorss81/noteflow/` — every read of `page.extractedText`
(36 hits) plus the derived-snippet producers (`GraphPreviewPolicy.previewSnippet`,
`CommandPaletteMath.makeSnippet`, `WikiLinkParser.createSnippet`, `BacklinksInspector`).

## Card / snippet previews that render RAW markdown (in scope — scrub these)

| # | Site | file:line | Current text source | Render style |
|---|------|-----------|---------------------|--------------|
| 1 | Gallery grid card (the review target) | `ui/components/GalleryView.kt:202` | `page.extractedText?.trim().orEmpty()` | `bodySmall`, `maxLines = 3`, Ellipsis (phase-184 layout; prompt's `:174-183` line anchor moved during phase-184) |
| 2 | Kanban card body | `ui/components/KanbanBoardView.kt:269-277` | `page.extractedText` (when non-blank) | `bodySmall`, `maxLines = 2`, Ellipsis |
| 3 | Calendar day card body | `ui/components/CalendarView.kt:245-253` | `page.extractedText` (when non-blank) | `bodySmall`, `maxLines = 2`, Ellipsis |
| 4 | Knowledge-graph node peek card | `services/graph/GraphPreviewPolicy.kt:48` `previewSnippet` — called from `ui/viewmodel/NoteflowViewModel.kt:4314` | first-3-lines + 240-char cap of `extractedText` | single `bodySmall` block in the bottom card |

Home tab search results (`HomeScreen.kt:1203-1204` global search + deep search)
route into the SAME four views above (`GalleryView:1343`, `KanbanBoardView:1344`,
`CalendarView:1345`, list `NotePageCard`), so scrubbing #1-#3 covers search too.

## Deliberately NOT scrubbed (documented)

- `NotePageCard` list view (`ui/screens/HomeScreen.kt:2541`) — shows title/type/tags
  only, never `extractedText`; nothing to scrub.
- `VersionHistoryBottomSheet.kt:196-201` — this is the FULL snapshot content pane,
  not a preview snippet; restoring/judging a version is more honest with raw text,
  and the phase-158 reader-mode gate keeps it out of the gallery styling. Out of scope.
- `CommandPaletteMath.makeSnippet` (`services/graph/CommandPaletteMath.kt:119`) +
  `WikiLinkParser.createSnippet` (`services/WikiLinkParser.kt:368-392`) +
  `BacklinksInspector` snippet rendering (`ui/components/BacklinksInspector.kt:169,229`) —
  these render the matched CONTEXT around a query term, not the note's opening text;
  scrubbing there would shift the character window relative to the match. Out of scope;
  noted for a future review if a user reports raw syntax in those panes.
- `MarkdownPreviewScreen` / `HybridMarkdownEditor` / `WebSearchDialog` snippets — real
  renderers/other-domain content, never `extractedText` card previews. Out of scope.

## Plan

New pure-JVM `services/MarkdownPreviewScrubber.kt` (`scrub(markdown: String?): String`):
strip heading `#`, `**bold**`/`*italic*`, `` `code` ``, `![img](url)` → alt,
`[link](url)` → link text, `- [ ]`/`- [x]` → `•`, leading `> ` (+ `[!NOTE]`-style
callout tags), collapse whitespace/newlines to a compact single-space snippet, trim.
Wire into #1 (prompt spec: `remember(page.extractedText) { scrub(it) }`, `maxLines = 4`,
Ellipsis, `bodySmall`, `lineHeight 16sp`), #2, #3, and #4 (`previewSnippet` routes through
the same scrubber so the graph peek matches the cards). DISPLAY-ONLY — never writes back.