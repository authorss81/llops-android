# Phase 151: Markdown main-thread performance — linear inline-math scan + incremental hybrid-editor tokenization [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-b2b5-FEA-02, R2-b2b5-FEA-03) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase removes the two quadratic/incremental-markdown
main-thread performance holes.

## Source findings (both OPEN, MEDIUM)

1. **R2-b2b5-FEA-02** — `MarkdownInlineMath` quadratic scans on adversarial
   paragraphs: `MarkdownInlineMath.kt:36` `findClosingBackticks` does a linear
   scan to end-of-string per backtick run (`:47-61`, with a
   `substring(...).all{}` allocation per step `:50`) → O(n²) for a line with
   many uncloseable runs; `insideAny` is `codeRanges.any { index in it }`
   (`:117-118`) → O(codeRanges) per `$` (O(n·R)). Reachable on the MAIN THREAD
   during composition (`MarkdownRenderer.kt:574-576` `remember(text,
   primaryColor)` per paragraph, which calls `findCodeRanges`/`findMathRuns`
   `:634-635`). A 100 KB adversarial paragraph stalls the UI for seconds.
2. **R2-b2b5-FEA-03** — The hybrid editor re-tokenizes the whole document per
   keystroke on the main thread: `HybridMarkdownEditor.kt:86-87`
   (both `remember(text) { MarkdownBlockTokenizer.blocks(text) }` AND
   `checkboxCandidates(text)` recompute per keystroke; `checkboxCandidates`
   internally calls `blocks(content)` a second time `MarkdownBlockTokenizer.kt:
   239`); `:117` `replaceBlockSource` does `content.lines()` +
   `ArrayList(lines)` + `joinToString("\n")` over the whole document
   (`MarkdownBlockTokenizer.kt:285-294`); `:133` `cursorOrder =
   remember(index) { candidates.filter { it.blockIndex == index } }` —
   O(blocks × checkbox-candidates) per keystroke.

## The fix (where & how)

- **R2-b2b5-FEA-02:** Make `findClosingBackticks` a single left-to-right pass
  (compute closing runs once) and replace the `insideAny` linear scan with a
  binary-searched/interval-merged range index (`MarkdownInlineMath.kt:36-118`).
- **R2-b2b5-FEA-03:** Tokenize only the edited block (or debounce the full
  re-tokenize off-main), keep `checkboxCandidates` cached per tokenization
  pass (no second `blocks` call), and pre-index candidates by `blockIndex`
  instead of `filter` per block (`HybridMarkdownEditor.kt:86-133`,
  `MarkdownBlockTokenizer.kt:239,285-294`).

## Verification

- New/updated pure-JVM unit tests: a pathological backtick-run paragraph parses
  in linear (not quadratic) time — use a length scaling assertion; `insideAny`
  uses an interval index; hybrid editor's per-keystroke path tokenizes one block
  (source pin: no full-document `lines()`+join on the keystroke path within a
  timing/source assertion).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-151/REPORT.md`.

## Definition of done

- Both findings closed with `file:line` before/after evidence.
- Inline-math parsing is linear; typing in a 50 KB / 5k-line note no longer
  full-tokenizes + line-splits the document per keystroke on the main thread.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Output must be
  byte-identical (source round-trip preserved): the tokenizer must still produce
  the exact same blocks/checkbox candidates.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.