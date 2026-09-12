# Phase 258 — Markdown editor consolidation + blank-reopen sync (REPORT)

Goal: kill the two-editor duplication (ship ONE whole-document editor) and fix
the blank-on-reopen / stale-non-empty-overwrite external-sync defect while
keeping the block tokenizer's `replaceBlock`/`replaceContentRun`/`toggleCheckbox`
API intact for phase-259's incremental perf work.

## Evidence table

| # | Evidence (prompt) | File:line | Status |
|---|---|---|---|
| CRITICAL | All 3 edit surfaces compose `WholeMarkdownEditor`; `HybridMarkdownEditor` (375 lines) is dead code — delete it, do NOT ship both | `ui/screens/MarkdownPreviewScreen.kt` imports only `WholeMarkdownEditor` (no Hybrid import); `HybridMarkdownEditor.kt` DELETED (`git rm`); new/updated source pins assert the file is absent | **FIXED** |
| CRITICAL | Stale async-load heal: empty→non-empty-only leaves a NON-empty stale snapshot on screen and the next flushSave writes it back over newer DB content | `MarkdownPreviewScreen.kt` adopt effect now routes through `MarkdownSyncPolicy.shouldAdoptExternal` (all-direction version-token: `contentText == savedContent && initialContent != savedContent`) | **FIXED** |
| HIGH | flushSave on dispose keyed `Unit` — a rapid A→B page switch flushes B's closure with stale A state | `DisposableEffect(page.id)` (was `DisposableEffect(Unit)`) | **FIXED** |
| MEDIUM | Slash-command snippets appended at document end (`contentText += ...`) instead of the caret | `SlashCommandMenuPopup.onSelectCommand` → `MarkdownSyncPolicy.insertAtCaret(contentText, caretOffset, "\n"+snippet)` + one-shot `selectionOverride` | **FIXED** |
| MEDIUM | Wiki-link picker insert appends / trims-end instead of inserting where the user writes | `WikiLinkPickerDialog.onSelect` → `insertAtCaret` | **FIXED** |
| MEDIUM | Whole vs Hybrid wiki-insert duplicate | Hybrid deleted; single wiki flow remains (Whole in-field popup + `WikiLinkPickerDialog`) | **FIXED** |
| MEDIUM | Caret observable + controllable in the editor so at-caret insert can reposition after the splice | `WholeMarkdownEditor`: local `TextFieldValue`, `onSelectionChanged(TextRange)`, one-shot `selectionOverride`, applies exactly once, then consumed by the host a frame later | **FIXED** |

## What shipped

1. **`services/MarkdownSyncPolicy.kt` (new, pure JVM)** — `shouldAdoptExternal`
   version-token gate + `insertAtCaret` (surrogate-pair-safe splice; null or
   out-of-range offset → legacy end append). Everything off the UI thread and
   side-effect free, mirroring `ReaderModePolicy`/`WikiSuggestionPolicy`.
2. **`WholeMarkdownEditor.kt`** — internal `TextFieldValue`; adopt effect applies
   external `value` + optional clamped one-shot caret override (no-op when text +
   selection unchanged, preserves IME composition), reports the applied selection.
3. **`MarkdownPreviewScreen.kt`** — shared `EditorPane` for the EDIT and both
   SPLIT panes (`key(page.id)` per surface); adopt effect + caret state +
   override-consumer `LaunchedEffect`; slash + wiki inserts at the caret;
   `DisposableEffect(page.id)` flush; adopt inert while a phase-158 share append
   is staged.
4. **Deleted `HybridMarkdownEditor.kt`** — no production callers remain.
5. **Tests** — `Phase258MarkdownSyncTest` (10: adopt decisions in every
   direction, dirty-never-clobber, no-change never-adopts, splice/appends/clamps,
   mid-pair surrogate guarantee, editor wire + delete pins). `Phase151...PerfTest`
   editor path repointed to `WholeMarkdownEditor.kt` and now asserts
   `HybridMarkdownEditor.kt` is gone. `Phase243` comment detail updated.

## Verification

- `gradle :app:compileDebugKotlin` green.
- `gradle :app:testDebugUnitTest` green — targeted classes 37 tests passed;
  full suite passed (0 failures).
- `gradle :app:assembleDebug :app:lintDebug` green (lint 0 errors).
- Runs used `--no-configuration-cache` (AGP config-cache serialization).

## Deliberate non-goals

- Tokenizer `replaceBlock`/`replaceContentRun`/`toggleCheckbox`/`MarkdownDocument`
  public API untouched (phase-243/246/259 target it directly).
- `MarkdownRenderer.kt` `MarkdownCheckboxCursor`/`MarkdownRenderBlocks` are now
  only self-referenced (dead but harmless, `internal`) — deletion deferred.
- `B2Ui2ClipboardScrubTest` comment mentions Hybrid's historical copy site; kept
  as phase-139 provenance (the pending copy channel it documents moved into
  `WholeMarkdownEditor`'s `OutlinedTextField`, same native-copy surface).

No schema change, no new dependencies, `verification-metadata.xml` and
`.github/workflows/` untouched, base-APK-size rule intact.