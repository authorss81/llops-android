# Phase 258 — Markdown: whole-doc vs blocks + blank-on-load + dirty guard

## Goal
Kill duplication + blank reopen without losing block features.

## Evidence (main already has `33bbecf` WholeMarkdownEditor + `remember(page.id)` — VERIFY, else implement)
- CRITICAL `MarkdownPreviewScreen.kt:386-395`: `remember(page.id){ initialContent }` captures first (possibly empty/stale) snapshot; `LaunchedEffect(page.id, initialContent){ if(contentText.isEmpty() && initialContent.isNotEmpty()) }` heals empty→non-empty only. Non-empty→different-non-empty (stale extractedText → decrypted body, version restore, share-append) dropped → stale shown → `flushSave` writes stale back (data loss). Fix: `remember(page.id, initialContent)` or `if(initialContent!=contentText && !dirty)` sync + version token.
- HIGH `HybridMarkdownEditor.kt:85`: `remember{ tokenize(value) }` no key; heals only via `LaunchedEffect(value)` gated by `dirty` (`:144-152`): local keystroke sets `dirty=true`, next EXTERNAL value change swallowed (clears dirty, no re-tokenize) → stale/dup. Fix: re-tokenize on external while preserving caret.
- HIGH `MarkdownPreviewScreen.kt:436 DisposableEffect(Unit)`: flushes old page's contentText with new page's save closure on rapid switch. Fix: key by `page.id`.
- CRITICAL architecture: all 3 edit surfaces compose `WholeMarkdownEditor`; `HybridMarkdownEditor` (375 lines, checkbox cursor, same-renderer guarantee) is dead code. Either delete Hybrid + `replaceBlock/replaceContentRun` window (~250 lines) or rewire; do not ship both claiming both.
- MEDIUM: `Whole:34-54` vs `Hybrid:275-298` wiki autocomplete duplicated; slash `1145-1157` appends at end not caret; triple editor wiring `997/1039/1090` should be one `EditorPane()`.

## Files to change
- `ui/screens/MarkdownPreviewScreen.kt`, `ui/components/markdown/HybridMarkdownEditor.kt` or `WholeMarkdownEditor.kt` (pick ONE), `MainActivity.kt` produceState `initialValue = page.extractedText ?: ""`

## Tests
- `Phase258MarkdownSyncTest`: non-empty→newer sync; external-during-dirty not swallowed; dispose flushes correct page id (source pins).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
