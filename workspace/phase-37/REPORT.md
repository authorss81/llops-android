# Phase 37 — Markdown & Hybrid Editor Experience: REVIEW FIX REPORT

> Fixes applied for the phase-37 review findings (build broken, unreachable
> features, no tests, missing docs). Every FINDING from the review is addressed
> and verified below with `file:line` evidence and build/test results.

## 1. Build was broken → now compiles and assembles

The Phase-37 commit `eec811c` shipped code that did **not** compile:
`gradle :app:compileDebugKotlin` failed (`BUILD FAILED`).

Fixes (`app/src/main/kotlin/com/authorss81/noteflow/`):
- `ui/components/markdown/MarkdownRenderer.kt:327` — destructuring declaration was
  annotated with a type (`val (a, b, c): Triple<...> = when(...)`), which Kotlin
  forbids. Split into an explicitly-typed `calloutStyling` Triple + a plain
  destructuring assignment.
- `ui/components/markdown/MarkdownRenderer.kt` — `Icon(...)` (:343) called without
  `androidx.compose.material3.Icon` imported (only `Card/CardDefaults/...` were);
  added the import.
- `services/MarkdownBlockTokenizer.kt:264` — `Regex.replaceFirst(line) { ... }`
  does not exist in Kotlin (no lambda overload); rewrote `toggleCheckbox` using
  `Regex.find` + manual string rebuild.
- `services/MarkdownBlockTokenizer.kt:311` — `CalloutInfo(type, type, body)`
  passed `CalloutType` where `title: String` was required; now passes the uppercased
  type token.
- `ui/components/markdown/AnimatedCheckmark.kt` — wrong import
  `androidx.compose.ui.draw.graphicsLayer` (unresolved) replaced with
  `androidx.compose.ui.graphics.graphicsLayer`; added missing
  `androidx.compose.material3.MaterialTheme` import.
- `ui/components/markdown/MarkdownRenderer.kt:154` — public `MarkdownDocument`
  exposed internal `MarkdownCheckboxCursor`; marked `MarkdownDocument` `internal`.

Verification: `gradle :app:compileDebugKotlin` (clean) and `gradle :app:assembleDebug`
both **BUILD SUCCESSFUL**.

## 2. Features were dead code → now wired and reachable

The new components were referenced by nothing (review FINDING 3 and 6). Wiring in
`ui/screens/MarkdownPreviewScreen.kt` replaces the plain `OutlinedTextField`
markdown editor in EDIT mode (:544) and both SPLIT-mode editor panes (:583, :631)
with the new `HybridMarkdownEditor`. That makes reachable, in the real markdown
workflow:
- live inline rendering of headings / bold / italic / code / lists / links / math
  blocks (raw syntax still editable: tap the block's edit affordance, or the whole
  heading/rule row, to open the raw multi-line editor; "Done" collapses back);
- styled text-transform plugins / slash commands / OCR / web-search menus are
  untouched (only the text-widget slot changed, so those menus still operate on
  `contentText`);
- typed callout cards (`> [!NOTE|WARNING|TIP|IMPORTANT|QUOTE]`) render as styled
  cards (Note/Warning/Tip/Quote per the spec — IMPORTANT kept as the fourth card);
- interactive checkboxes (`- [ ]` / `- [x]`) toggle with a scale/check
  micro-animation that collapses to a snap under reduce-motion
  (`ui/components/markdown/AnimatedCheckmark.kt:47-56` via `MotionSystem.spec`
  + `LocalReduceMotion`).

The preview side still renders through the existing `MarkdownRenderedContent`, which
is byte-identical in structure to the new `MarkdownRenderer.MarkdownRenderBlocks`
(the renderer was lifted out of that same code); the two now both parse with the
**same** CommonMark `Parser.builder().extensions([TablesExtension])` instance.

## 3. Waveform B2-DOS-03 bounded (no longer O(n²)-draw)

`ui/components/AudioPlaybackCard.kt:209-214` now draws
`WaveformPeakMath.downsample(embed.waveformAmplitudes)` — min/max decimation to a
fixed `uiMaxBars = 220` budget
(`services/WaveformPeakMath.kt:24-46`), so a very long `.m4a` never produces an
unbounded draw loop. Peaks are preserved (min/max, not averaging). Tap/drag scrub
already existed (`AudioPlaybackCard.kt:218-224`) and now maps through
`scrubTargetMs`/`progressFraction`.

## 4. Tests added (pure JVM, all green)

- `app/src/test/.../MarkdownBlockTokenizerTest.kt` — 12 tests: classification,
  byte-for-byte source round-trip, checkbox candidate/toggle stability, safe no-ops,
  callout typing, block replacement.
- `app/src/test/.../MarkdownInlineMathTest.kt` — 6 tests: `$...$` / `$$...$$` runs,
  code-span exclusion, unclosed-`$` safety, ordered disjoint runs, triple-backtick
  handling.
- `app/src/test/.../WaveformPeakMathTest.kt` — 6 tests: bounded downsample output,
  transient-peak preservation, scrub/progress clamping.

`gradle :app:testDebugUnitTest` → **838 tests, 0 failed, BUILD SUCCESSFUL**.

## 5. Documentation deliverables now in place

- `workspace/phase-37/REPORT.md` (this file).
- `docs/phase-status.md` — phase-37 row updated to `DONE` with verified evidence.
- `docs/ARCHITECTURE.md` — "Markdown" anchor updated to point at the new
  `ui/components/markdown/` renderer/tokenizer.

## 6. Honest slice boundaries (documented in code + here)

- **LaTeX is a highlighter, not a typesetter.** `MarkdownInlineMath`/`MarkdownRenderer`
  emphasise `$...$` runs with a monospace chip and render `$$...$$` blocks as a
  labelled "LaTeX Math Expression" surface; full offline typesetting is deferred
  (offline-only constraint: a real katex/MathView dependency is a heavy new dep that
  needs the base-APK review).
- Lazy list continuations and blank-line-separated "loose" lists tokenize as
  separate paragraph/list blocks; rendering is still identical because every block
  is re-parsed by CommonMark (`MarkdownBlockTokenizer.kt` header comment).