# Phase 139: Clipboard scrub coverage — every note-content copy surface is tracked (or lock clears the clip unconditionally) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-B1P-01) and `docs/phase-status.md` + `docs/ARCHITECTURE.md`.
This phase closes the B2-UI-2 gap: lock-time clipboard scrub only covers 2 of
the app's copy surfaces.

## Source finding (OPEN, MEDIUM)

**R2-B1P-01** — `ClipboardGuard.recordCopy()` is stamped at exactly two sites —
`OcrResultDialog.kt:149` and `MediaEmbedComponents.kt:353`. `lock()` scrubs via
`scrubIfOwnCopy` (`NoteflowViewModel.kt:3649`) which denies when
`mostRecentCopyAtMs == 0L` (`ClipboardGuard.kt:50-54`,
`ClipboardScrubPolicy.kt:32-33`). The markdown editor is a platform text field
(`HybridMarkdownEditor.kt:219`) whose long-press → Select-all → Copy writes
decrypted note body to the system clipboard with NO `recordCopy()` stamp; the
OCR dialog's own `SelectionContainer` (`OcrResultDialog.kt:121`) has the same
untracked native Copy.

## The fix (where & how)

- Route every note-content copy through ONE shared stamping helper: intercept
  the editor selection via `LocalTextToolbar`/custom `SelectionContainer` (both
  `MarkdownPreviewScreen`/`HybridMarkdownEditor` and OCR dialog), OR — given the
  app's threat model — clear the primary clip unconditionally in `lock()`
  instead of only "own copy within window" (remove the `0L` deny in `lock()`;
  keep `ClipboardScrubPolicy.shouldScrub` for the windowed decision on ON_PAUSE).
- Grep-pin that every clipboard write in `ui/` is preceded by
  `ClipboardGuard.recordCopy()`.

## Verification

- New/updated pure-JVM unit tests: `B2Ui2ClipboardScrubTest` extended — an
  editor SelectionContainer copy is stamped; lock clears an untracked foreign
  copy when unconditional-clear is chosen (or the intercept path is source-pinned).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-139/REPORT.md`.

## Definition of done

- R2-B1P-01 closed with `file:line` before/after evidence.
- A decrypted note body copied via the editor selection is scrubbed on lock
  (no window), matching the OCR/embed behavior.
- No platform-API floor regression (API 26-28 empty-setPrimaryClip path kept).

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep FLAG_SECURE and
  `allowBackup=false` intact.
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.