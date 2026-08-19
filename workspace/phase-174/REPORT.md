# Phase 174 — Reading & authoring UX

**Date:** 2026-08-19
**Status:** DONE
**Scope:** note-stats footer · reader-mode outline quick-jump rail · wiki-link `[[` autocomplete (+ slash-menu insert)

## What was asked
`workspace/phase-174/PROMPT.md` — three additive UX features on `MarkdownPreviewScreen`:
1. A small note-stats bar (word count / reading time / char count) under the editor.
2. An outline quick-jump rail in reader mode (heading index → scroll to heading).
3. Wiki-link `[[` autocomplete in the hybrid editor + a slash-menu insert entry.

## What shipped

### Feature 1 — note-stats footer
- **`services/NoteStatsFormatPolicy.kt`** (pure-JVM decision table): locale-safe `NumberFormat.getIntegerInstance` counts ("1,234" en-US, "1.234" de-DE), `~N min read` ceiling to whole minutes with a 1-minute floor for any content (`readingTimeMinutes(seconds)`), blank-note → `null` line, `MIN_MATERIAL_LENGTH_DELTA=8` recompute guard, `STATS_DEBOUNCE_MILLIS=250`.
- **Wiring** (`MarkdownPreviewScreen.kt:433-458`): `LaunchedEffect(page.id)` + `snapshotFlow { contentText }` + `debounce(250)` + `shouldRecomputeStats` guard → `TextToolsAnalyzer.analyze` (the existing single-pass O(n) analyzer, REUSED — never a new tokenizer, never a per-keystroke re-tokenize) → `statsLabel` → a static, non-animated `Text` footer rendered in every view mode (EDIT/PREVIEW/SPLIT), hidden under reduce-motion and for blank notes.
- Show: `1,234 words · ~6 min read · 5,678 chars`.

### Feature 2 — outline quick-jump rail
- **`services/HeadingScrollIndex.kt`** (pure-JVM): `build((text, level)…)` skips blank texts, disambiguates duplicates with stable occurrence suffixes ("Notes" → "Notes (2)"), and maps labels ⇄ registered content offsets; `clearOffsets` keeps headings, drops stale measurements.
- **Wiring** (`MarkdownPreviewScreen.kt`): reader mode collects the ALREADY-parsed CommonMark `Heading` nodes via a document-order DFS (`collectHeadingNodes` — identical order to RenderBlocks' rendering, so node⇄position identity is stable; Node equality is identity). The index is built ONCE per document. A file-local `HeadingMeasureScope` (`LocalHeadingMeasure` CompositionLocal) registers each heading's `boundsInRoot().top − viewport top + current scroll` as its content offset during layout. The anchored `ReaderOutlineRail` (fixed 168dp → phase-166 360dp overflow-safe; collapsible; nested-scroll list capped at 300dp) taps a label → `scrollState.scrollTo` (reduce-motion) / `animateScrollTo`.
- No re-parse anywhere — the same parse powers both render and jump.

### Feature 3 — wiki-link `[[` autocomplete
- **`services/WikiSuggestionPolicy.kt`** (pure-JVM): prefix-then-substring ranking (case-insensitive), case-insensitive dedup, hard cap `MAX_SUGGESTIONS=6`, titles containing `[`/`]`/`|` are never offered (`breaksWikilinkSyntax`), excluded titles (e.g. current note) suppressed, `.md`/`.txt` suffixes normalized, `wikilinkSnippet` emits `[[Title]]` or the canonical alias form `[[Raw.md|Clean]]` when the raw title carries a suffix, `locateQuery` returns the bounds of the last unterminated `[[…`.
- **Candidates** = the cached bounded search corpus, TITLES ONLY: `NoteflowViewModel.cachedWikiLinkTitles()` reads `repository.cachedCorpus()` (bounded 1500-row epoch-cached decrypted corpus — the SAME source as palette/search). Zero new DB reads per keystroke; locked vault → empty list → no popup, no crash (fail-closed).
- **In-editor popup** (`HybridMarkdownEditor.kt` `RawBlockEditor`): a `LaunchedEffect` calls `WikiSuggestionPolicy.locateQuery` on each keystroke; when a live `[[…` region exists and the query is non-empty, the titles load ONCE on first engagement (`onWikiLinkQueryEngaged`), and `WikiLinkSuggestionPopup` (anchored 72dp over the field, ≤240dp) replaces the ENTIRE `[[…` region with the canonical snippet (never a mid-keystroke jump).
- **Slash menu** (`SlashCommandMenu.kt`): new first-entry "Insert Wiki Link" (`Icons.AutoMirrored.Outlined.ListAlt`) enabled while a `onInsertWikiLink` callback is supplied; opens the FLAG_SECURE `WikiLinkPickerDialog` (`ui/components/WikiLinkSuggestions.kt`) with a filter field — picks append `"\n[[…]]\n"` via `wikilinkSnippet` and save.

## Security & privacy posture
- TITLES ONLY leave the vault for suggestions — never body text; nothing is logged.
- `WikiLinkPickerDialog` uses the established `secureDialogProperties()` (FLAG_SECURE on release builds).
- Suggestions fail closed when locked (`lockedWikiTitles` is empty while not authenticated; cleared on lock).
- The stats footer exposes no decrypted content beyond aggregate local counts (already visible word-adjacent UI).
- No `INTERNET`, no new dependencies, no schema change, `allowBackup` untouched.

## Tests
30 new pure-JVM unit tests, all green:
- `Phase174NoteStatsFormatPolicyTest` (10): reading-time ceil/floor, locale-safe grouping (en-US `1,234` vs de-DE `1.234`), blank→null, pluralization, full-line join, latency guard.
- `Phase174HeadingScrollIndexTest` (7): doc order, blank skip, duplicate-suffix labels + per-occurrence offsets, non-negative coercion, out-of-range ignore, clearOffsets, rebuild/reset.
- `Phase174WikiSuggestionPolicyTest` (13): prefix-over-substring, case-insensitivity, dedup first-seen, blank-query cap, hard cap, syntax-breaking exclusion, excluded titles, suffix normalization, snippet forms (`[[Meeting]]` / `[[Meeting.md|Meeting]]`), `locateQuery` (unterminated last run, closed links ignored, blank).

Full suite: `gradle testDebugUnitTest` → **2396 tests**, 1 pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure (untouched — reproduced on a clean stash in prior phases). `gradle assembleDebug` → **green**. `gradle :app:compileDebugKotlin` warning-free for the new code (only pre-existing deprecations remain; the `debounce` FlowPreview use is locally `@OptIn`'d).

## Honest scope notes (deferred, not silent)
- The outline rail is reader-mode/preview only and reads the feature-doc headings; it does NOT parse the editor text live, and it does not follow in-note gallery nesting — matches the PROMPT (already-parsed document, no re-parse).
- Wiki-link autocomplete picks a whole title; inline typing of arbitrary `[[target]]` with no vault match still works today (the editor's existing bracket handling is unchanged) — only the suggestion surface is new.
- Stat recompute is length-delay guarded, so the footer may lag a fast 1–7 char backspace by 250ms by design.

## Files
- Added: `services/NoteStatsFormatPolicy.kt`, `services/HeadingScrollIndex.kt`, `services/WikiSuggestionPolicy.kt`, `ui/components/WikiLinkSuggestions.kt`, `Phase174NoteStatsFormatPolicyTest.kt`, `Phase174HeadingScrollIndexTest.kt`, `Phase174WikiSuggestionPolicyTest.kt`.
- Edited: `ui/screens/MarkdownPreviewScreen.kt` (stats pipeline, heading measure scope + local, wiki-title load/clear-on-lock, 3 editor call sites, picker dialog, stats footer, outline rail, `collectHeadingNodes`, `ReaderOutlineRail`), `ui/components/markdown/HybridMarkdownEditor.kt` (`wikiLinkTitles`/`onWikiLinkQueryEngaged` params, popup in `RawBlockEditor`), `ui/components/SlashCommandMenu.kt` (`onInsertWikiLink` + "Insert Wiki Link" entry), `ui/viewmodel/NoteflowViewModel.kt` (`cachedWikiLinkTitles`).