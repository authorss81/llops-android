# Phase 218 — Markdown Polish (readability, tables, code, callouts)

## Goal
Make markdown notes read like a real editor — fix the 4 most visible markdown gaps without adding new deps. Theme-aware, large-font safe, 360dp safe.

## Context — verified anchors
- **Render stack:** `ui/components/markdown/MarkdownRenderer.kt:102` CommonMark `Parser.builder().extensions(TablesExtension)` shared instance; `MarkdownPreviewScreen.kt:335-946` three modes `EDIT/SPLIT/PREVIEW` + `HybridMarkdownEditor.kt:66` `MarkdownBlockTokenizer.tokenize` → `MarkdownRenderBlocks` vs `RawBlockEditor` + `WikiLinkSuggestionPopup`; `CodeBlockTextView.kt:33` `Surface(surfaceVariant, RoundedCornerShape 8)` static code block; `CodeHighlightPolicy` additive spans.
- **Known issues:** code blocks `fillMaxWidth` static (217 will make canvas embeds resizable — this phase fixes *markdown* code blocks read path); tables hard-clip on 360dp (no `horizontalScroll`); task checkboxes `AnimatedCheckmark.kt` work but list spacing cramped at fontScale 1.3; callout cards `MarkdownCalloutCard` exist but blockquote `> TIP/WARN` parsing via `MarkdownBlockTokenizer.calloutOf` not visually distinct at glance; horizontal rules faint.
- **Out of scope:** new markdown syntax, Mermaid, LaTeX — use existing CommonMark + Tables only.

## Tasks
1. **Code blocks read polish:** in `CodeBlockTextView.kt:33` — add language chip already exists, now add copy button hit-area 48dp (reuse `ClipboardGuard`), line-number gutter optional toggle persisted `SettingsManager.markdownCodeGutterEnabled`, horizontal scroll for long lines (`Modifier.horizontalScroll(rememberScrollState())` inside Surface) so 360dp doesn't clip; keep `buildHighlightedCode` spans bit-exact.
2. **Tables:** wrap `MarkdownTable` in `Modifier.horizontalScroll` + `weight` container so 360dp doesn't clip; header row `surfaceVariant 0.5` + bold; `1.5dp` dividers `Divider` same as `MarkdownRenderer` grid color. Test: 5-col table fits 360dp via scroll.
3. **Task lists & callouts:** tighten list item spacing `8dp` vertical, checkbox `AnimatedCheckmark` 20dp touch target (already) + strikethrough for `[x]` at `0.6` alpha; callouts map `NOTE/TIP/IMPORTANT/WARNING/CAUTION` to distinct `containerColor` + `icon` (reuse `Icons.Outlined.Info/Warning`) behind `calloutOf` already parsed.
4. **Typography polish:** heading `h1..h6` scale `1.8→1.0` via `TypeScale`, `lineHeight 1.35`, `letterSpacing` as today; `blockquote` left border `3dp primary`; `hr` `1dp onSurfaceVariant 0.3`; `a` links `primary + underline` with `ClickableText` already. Large-font safe: no fixed `height`, use `wrapContentHeight` + `maxLines` only where ellipsis intended.
5. **Editor parity:** `HybridMarkdownEditor`RawBlockEditor` monospace `13sp` stays; ensure `MarkdownDocument` primaryColor/baseDir/serif plumbing still threads.

## Constraints
- No new deps, no Room schema, no `.github/workflows/` edits. Keep CommonMark + Tables only.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi screenshot `MarkdownPreview PREVIEW light+dark+fontScale1.3` added showing table/code/callout; REPORT.md with before/after crops.

