# Phase 259 — WikiLinkParser shadow + graph stale + search gaps

## Goal
Close crash/DoS/stale paths in wiki/graph/search.

## Evidence
- CRITICAL `utils/WikiLinkParser.kt:29-31`: `Regex("\\b$page\\b")` per page WITHOUT `Regex.escape(page)` → title `C++`/`[TODO]` throws `PatternSyntaxException` (crash) / ReDoS. Fix: escape + precompile.
- HIGH `utils/WikiLinkParser` duplicates `services/WikiLinkParser` (which has MAX_SCAN_PAGES=2000, MAX_LINKS_PER_PAGE=200, MAX_TOTAL_EDGES=100k, epoch cache, Default dispatcher). Any `utils` import bypasses hardening. Fix: delete shadow or make internal + delegate.
- HIGH `extractWikiLinks` unbounded (100k `[[x]]` → OOM); HIGH `extractTags` public unbounded; MEDIUM code-fence/HTML/math false edges; MEDIUM `pagesFingerprint` 100KB alloc per panel (hash it).
- HIGH `KnowledgeGraphScreen.kt:209` `LaunchedEffect(Unit)` builds once → stale after edit/rename. Key on `currentSearchCorpusGeneration`. MEDIUM O(n²) layout no `ensureActive`; per-frame HashMap allocs; `rememberUpdatedState` missing for nodes in tap lambda.
- HIGH `TagExplorerView.kt:46` `LaunchedEffect(notebookId, tags)` omits page mutations → stale hierarchy; no lock observer (keeps decrypted hierarchy while locked).
- MEDIUM `NoteRepository.kt:637` deepSearch records decrypt failures toward corruption threshold; `289` no `ensureActive` per batch; `pageMatches` skips `tags` column; fuzzy scans full bodies per keystroke.
- MEDIUM `MarkdownBlockTokenizer.kt:249` fence Regex per block; table false-positives; `replaceBlock/ContentRun` full `joinToString` per keystroke (100KB alloc/char); `lineIndexAtByte` char-vs-byte/`\r\n`/emoji desync.

## Files to change
- `utils/WikiLinkParser.kt`, `services/WikiLinkParser.kt`, `ui/screens/KnowledgeGraphScreen.kt`, `ui/components/TagExplorerView.kt`, `data/repository/NoteRepository.kt`, `services/MarkdownBlockTokenizer.kt`

## Tests
- `Phase259WikiGraphSearchTest`: escaped title no-crash; caps enforced; graph rekeys on corpus generation (source pins).

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
