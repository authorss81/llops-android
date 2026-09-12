# Phase 259 — WikiLinkParser shadow + graph stale + search gaps — REPORT

## 1. Claim / reality / status / evidence

| # | PROMPT claim | Reality | Status | Evidence |
|---|---|---|---|---|
| 1 | CRITICAL `utils/WikiLinkParser.kt:29-31` unescaped per-page `Regex("\b$page\b")` → crash/ReDoS | Confirmed at HEAD: per-page `Regex("\\b$page\\b", IGNORE_CASE)` with no escape; `C++`/`[TODO]` throws `PatternSyntaxException` | FIXED | `utils/WikiLinkParser.kt:67-80` — ONE precompiled regex over `Regex.escape`-d titles (`:73`), capped to 2000 titles; production code never used the shadow (only `WikiLinkAndTagParserUnitTest`), so the facade is `@Deprecated` with delegation, not deleted |
| 2 | HIGH `utils` shadow duplicates `services` hardening and bypasses it | Confirmed: shadow re-implemented extraction with no caps/cache/dispatcher; all 25 prod references resolve to `services.WikiLinkParser` | FIXED | `utils/WikiLinkParser.kt:59` `extractWikiLinks` maps the capped fence-aware services scan; `:40-55` `extractTags` bounded at construction (`MAX_TAGS_PER_TEXT = 20000`, `:37`); KDoc (`:10-35`) directs new code to services |
| 3 | HIGH `extractWikiLinks` unbounded (100k `[[x]]` → OOM) | Services already capped per-page (phase-152); utils shadow was NOT | FIXED | `services/WikiLinkParser.kt` cap unchanged; `utils/WikiLinkParser.kt:59` inherits it via delegation — pinned by `per-page link cap enforced on both parsers` |
| 4 | HIGH `extractTags` public unbounded | Confirmed: `extractTags = extractTagsBounded(text, Int.MAX_VALUE)` | FIXED | `services/WikiLinkParser.kt:298` now `extractTagsBounded(text, MAX_TAGS)` (20000); pinned behaviorally (30k-tag doc capped) |
| 5 | MEDIUM code-fence/HTML/math false edges | Confirmed: no fence awareness anywhere | FIXED (fences; HTML/math = boundary) | `services/WikiLinkParser.kt:147` `fenceRanges` (line-based ``` / ~~~ , unclosed-fence-till-EOF, fast path when no marker); applied in `extractWikiLinks` + `extractTagsBounded`; utils facade reuses it. HTML-comment/math false positives NOT handled — documented boundary (same remark as before: positions must not shift, so only fence ranges were safe) |
| 6 | MEDIUM `pagesFingerprint` 100KB alloc per panel | Confirmed: raw `id:updatedAt;` string as cache key | FIXED | `services/WikiLinkParser.kt:119-139` SHA-256 hex (fixed 64 chars, pure-JVM `MessageDigest`); equality semantics preserved (hash of identical material); pinned (`page fingerprint is a fixed-size hash`: 64-hex, `""` empty, distinct lists differ) |
| 7 | HIGH `KnowledgeGraphScreen.kt:209` `LaunchedEffect(Unit)` stale after edit/rename | Confirmed | FIXED | `KnowledgeGraphScreen.kt:214-215` collects `repository.searchCorpusGenerationFlow` and keys `LaunchedEffect(corpusGeneration)`; flow bumped under lock in `invalidateSearchCorpus` + `clearPlaintextCaches` (`NoteRepository.kt:264-271`) — mutation AND lock/re-key rebuild |
| 8 | MEDIUM O(n²) layout, no `ensureActive` | Confirmed: per-node `edgeRefs.count{}`, per-node `starting.first{}`, no cancellation | FIXED | Degree map in one edge pass + `startingByPage` map (`KnowledgeGraphScreen.kt:310-320`); `ensureActive()` before/after the `Dispatchers.Default` layout (`:300`,`:304`, imports `:89-90`) so a corpus bump aborts the stale build |
| 9 | MEDIUM per-frame HashMap allocs | Confirmed: `nodeById` + `filteredById` rebuilt per frame | FIXED (2 of 3) | `nodeById` (`:422`) + `filteredById` (`:430`, keys `nodes, filterTags, requireAllTags, focusResult`) remember-hoisted; `shownPositions` stays per-frame by design (folds the animated settle-tween progress). Phase-152 pin updated to the hoisted form; Phase-210 pin updated to the hoisted verdict expression |
| 10 | `rememberUpdatedState` missing for nodes in tap lambda | Confirmed: `pointerInput(Unit)` iterated first-composition `nodes` (empty until build) | FIXED | `KnowledgeGraphScreen.kt:399` `currentNodes` + tap loop `:631` |
| 11 | HIGH `TagExplorerView.kt:46` stale hierarchy (keys omit page mutations), no lock observer | Confirmed: keys `(notebookId, tags)` only; lock race only handled post-read | FIXED | `TagExplorerView.kt:47-50` collects generation + `authenticated`, keys all four; unauthenticated short-circuits to `emptyList()` BEFORE any read (`:51-56`), existing post-read guard kept |
| 12 | MEDIUM `NoteRepository.kt:637` deepSearch records decrypt failures toward threshold | Confirmed: used recording `decryptPageIfNeeded` | FIXED | `NoteRepository.kt:680-682` `ensureActive()` per batch + `.mapNotNull { decryptPageOrNullForCorpus(it) }` (ledger-silent, marker-free — same semantics as the corpus path, phase-88 review) |
| 13 | `289` no `ensureActive` per batch | Confirmed for deepSearch | FIXED | Same hunk `:680`; cancellable by the VM shared search Job |
| 14 | `pageMatches` skips `tags` column | Confirmed: title + extractedText only | FIXED | `VaultSearchPolicy.kt:100` exact tags tier + `:102` fuzzy tags tier; pinned (`tags column matches exact queries`) |
| 15 | Fuzzy scans full bodies per keystroke | Confirmed: `subsequenceDensity(query, body)` whole-body per non-matching page | FIXED | `FUZZY_BODY_SCAN_CAP = 8192` (`VaultSearchPolicy.kt:47`); exact probes still whole-body (pinned: tail needle at offset 20000 matches); title/tags fuzzy uncapped (short by construction) |
| 16 | MEDIUM `MarkdownBlockTokenizer.kt:249` fence Regex per block | Confirmed: `Regex(...)` compiled per fenced block per tokenize | FIXED | Precompiled `closingBacktickFenceRe`/`closingTildeFenceRe` (`:84-85`) + opener-length check preserved via new `openLen` param; pinned (no `val closing = Regex(`) |
| 17 | Table false-positives | Confirmed: delimiter regex matched bare `---`, so `a \| b` + `---` → TABLE | FIXED | Both table sites require `"|"` in the delimiter line (`:218`, `:257`); pinned both ways (prose not TABLE, real table still TABLE). Inline-code-span pipes remain a boundary (same as pre-259) |
| 18 | `replaceBlock/ContentRun` full `joinToString` per keystroke | Confirmed present; NOT removed | ACCEPTED BOUNDARY | The joined `content` IS the editor's new text — the alloc is inherent to producing it. Keystroke path already avoids re-split + full re-tokenize + full candidate rescan (phase-151/243 machinery, untouched). No honest fix without changing the `MarkdownDocument.content` contract |
| 19 | `lineIndexAtByte` char-vs-byte/`\r\n`/emoji desync | Partially confirmed | FIXED (contract + clamp) | Offsets are UTF-16 char units into `doc.content` (Compose/Kotlin consistent — surrogate pairs safe); `tokenize` normalizes to `\n`-joined content so the +1 separator math holds for offsets derived from `doc.content` (documented `:585-590`); negative start keeps the phase-243 no-op contract, upper end clamps (`:612-617`). A `\r\n` SOURCE's offsets were never valid input (pre-existing); callers operate on `doc.content` |

## 2. Tests

- New `Phase259WikiGraphSearchTest` (17: 11 behavior + 6 source-pin groups) — all green.
- Updated stale pins to the new mechanisms (behavior-verified, same-or-stronger invariant):
  - `Phase152FeatureDataBoundsWiringTest` per-frame memoization → remember-hoisted map.
  - `Phase210GraphDepthPinsTest` focus-dimming pipeline → hoisted verdict expression.
- Full suite: `gradle :app:testDebugUnitTest` **3719 / 0 failures / 0 errors / 0 skipped** (3702 baseline + 17 new).
- `gradle :app:assembleDebug` green; `gradle :app:lintDebug` 0 errors.

## 3. Non-goals / boundaries

- No Room schema change / migration; no new dependencies (only `java.security.MessageDigest` + `kotlinx.coroutines.flow` StateFlow, both platform/JVM-bundled); no `.github/workflows/` edits; `allowBackup=false` untouched; `verification-metadata.xml` untouched.
- CommandPalette scorer shares `FuzzyMatch` but has its own pre-lowered hot path — out of scope (phase-209 discipline), noted for a follow-up, not silently changed.
- HTML-comment/`$$`-math false edges, inline-code pipes, `\r\n`-source offsets: documented boundaries above, not regressions (pre-259 behavior preserved).

## 4. Files changed

`utils/WikiLinkParser.kt` (facade+escape+delegate), `services/WikiLinkParser.kt`
(hash fingerprint, tag cap, fence ranges + filtering), `ui/screens/KnowledgeGraphScreen.kt`
(generation rekey, degree/lookup maps, hoisted verdicts, tap ref, ensureActive),
`ui/components/TagExplorerView.kt` (generation+auth keys, lock clear),
`data/repository/NoteRepository.kt` (generation flow, ledger-silent cancellable deepSearch),
`services/VaultSearchPolicy.kt` (tags tier, fuzzy body cap),
`services/MarkdownBlockTokenizer.kt` (precompiled fences + openLen, pipe gate ×2, offset contract+clamp),
tests: new `Phase259WikiGraphSearchTest`, pin updates in `Phase152FeatureDataBoundsWiringTest` + `Phase210GraphDepthPinsTest`.
