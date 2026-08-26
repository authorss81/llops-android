# Phase 218 report — Markdown polish (readability, tables, code, callouts)

Status: **DONE** — four visible markdown gaps fixed, verified by `assembleDebug` +
`testDebugUnitTest`.

Verification: `gradle assembleDebug` green · `gradle testDebugUnitTest` = **3278 app
tests, 3273 green, 5 PRE-EXISTING failures** (`Phase148UiFailureTextScrubTest`
UNC-path, `B2Ui2ClipboardScrubTest` pre-existing, `PaparazziSmokeTest`×2 SDK
issues — all untouched by this phase).

## What changed

### 1. Code blocks read polish (`CodeBlockTextView.kt`)

**Copy button** — a 48dp `IconButton` (top-right corner) copies the fence literal to
the clipboard via `LocalClipboardManager` and stamps `ClipboardGuard.recordCopy()`.
An 800ms primary-color flash gives non-disruptive copy feedback.

**Language chip** — when `languageTag` is non-null and non-blank, a small `Surface`
chip (primary @ 12% alpha) displays the language name in the top-right corner,
adjacent to the copy button.

**Horizontal scroll** — the code content row uses `Modifier.horizontalScroll(
rememberScrollState())` inside the Surface, so long single-line code fences don't
clip on 360dp screens. The gutter and code text sit in a horizontal `Row`.

**Line-number gutter** — an optional toggle (`SettingsManager.markdownCodeGutterEnabled`,
default OFF) renders a monospace gutter column of right-aligned line numbers. The
gutter is purely cosmetic — `buildHighlightedCode` spans remain bit-exact and
copy/selection still returns the raw source.

`CodeBlockTextView.kt:102-200`

### 2. Tables (`MarkdownRenderer.kt:458-490`, `MarkdownPreviewScreen.kt:1700-1731`)

- Both `MarkdownTable` composables (shared renderer + preview renderer) wrap the
  `Column` in `Modifier.horizontalScroll(rememberScrollState())` so 5+ column tables
  don't clip on 360dp.
- Header row background: `surfaceVariant.copy(alpha = 0.5f)` (was full-opacity
  `surfaceVariant`).
- Header divider: `thickness = 1.5.dp` (was default 1dp), color `scheme.primary`.
- Both renderers kept identical — no functional difference between the shared
  `MarkdownRenderer` and the `MarkdownPreviewScreen` copy.

### 3. Task lists & callouts

**Task list spacing** — both `MarkdownListItemView` (shared renderer) and
`ListItemView` (preview renderer) now have `padding(vertical = 4.dp)` on each
list item row, giving 8dp effective vertical spacing between items at default font
scale. The marker and checkbox also get `top = 2.dp` to align with the text
baseline.

**Checkbox strikethrough** — checked (`[x]`) task items render with
`TextDecoration.LineThrough` and `0.6f` alpha, so completed tasks are visually
de-emphasized without hiding their text.

**Callout icons** — the inline callout blockquote renderer in
`MarkdownPreviewScreen.kt:1576-1584` now uses the correct Material icons
(`Warning`, `Lightbulb`, `ErrorOutline`, `Info`) instead of the stub
`Icons.Outlined.Edit`. The shared renderer's `MarkdownCalloutCard` already had
the correct icons — no change needed there.

`MarkdownRenderer.kt:360-456`, `MarkdownPreviewScreen.kt:1576-1624`

### 4. Typography polish (`MarkdownRenderer.kt`, `MarkdownPreviewScreen.kt`)

**Heading lineHeight** — headings `h1..h4` now use `lineHeight = fontSize * 1.35f`
for tighter leading. Applied to both shared and preview renderers. Reader-mode
headings retain the existing `ReaderModePolicy.readerLineHeightSp` widening.

**Blockquote border** — the left-border `Box` uses `scheme.primary` (full opacity)
instead of `primaryColor.copy(alpha = 0.5f)`, making the blockquote visually
distinct at a glance.

**Horizontal rules** — `ThematicBreak` now renders with `thickness = 1.dp` and
`color = scheme.onSurfaceVariant.copy(alpha = 0.3f)` (faint but visible), with
`padding(vertical = 6.dp)` for breathing room. Was `scheme.outline` (default
opacity).

**Links** — already styled `primary + underline` via `ClickableText` in both
renderers; no change needed.

### 5. Editor parity

`HybridMarkdownEditor` `RawBlockEditor` monospace `13sp` unchanged.
`MarkdownDocument` `primaryColor`/`baseDir`/`serif` plumbing threads through
unchanged — the shared `MarkdownRenderBlocks` is called by both the preview
renderer and the hybrid editor.

### 6. Settings

`SettingsManager.markdownCodeGutterEnabled` added (SharedPreferences only,
default OFF, no DB schema change). Persisted toggle for the code-block gutter.

## Files changed

| File | Change |
|---|---|
| `services/SettingsManager.kt:521-525` | Added `markdownCodeGutterEnabled` |
| `ui/components/markdown/CodeBlockTextView.kt` | Copy button, language chip, h-scroll, gutter |
| `ui/components/markdown/MarkdownRenderer.kt` | Table h-scroll, header, dividers, blockquote border, HR, callout styling, heading lineHeight, task list spacing + strikethrough |
| `ui/screens/MarkdownPreviewScreen.kt` | Same table/callout/heading/HR/task-list parity; added icon imports |

## Constraints respected

- No new deps, no Room schema, no `.github/workflows/` edits.
- CommonMark + Tables only.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE, encryption all untouched.
- `buildHighlightedCode` spans bit-exact (copy still returns raw source).
