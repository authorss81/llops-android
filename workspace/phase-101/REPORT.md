# Phase 101 — B2-DOS-11 (LOW): Backlink/tag-hierarchy/knowledge-graph builders re-scan the whole vault per panel open

## Finding (from `docs/security-report.md`, B2-DOS-11)

- `WikiLinkParser.kt:90-110` `findBacklinks` — for each of `allPages`, `getFullTextForPage`
  re-read the note file (`:59-76`) and ran `extractWikiLinks` + a per-page word-boundary regex.
- `WikiLinkParser.kt:125-163` `buildTagHierarchy` — same full-vault reads + regex, plus a
  recursive tree build whose recursion depth == number of `/`-segments in attacker-controlled
  tags (unbounded).
- `KnowledgeGraphScreen.kt:83-84` — materialised the full page list each time; each panel open
  rebuilt the edge index from scratch.
- Exploit: on a multi-thousand-note vault, opening Backlinks / Knowledge Graph triggers
  O(notes × avg-note-KB) file I/O + regex scanning, recomputed from scratch on every visit —
  multi-second freezes on 2-core devices.

## Fix implemented

### `app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt` (rewritten)

Resource-exhaustion guards (`:62-67`):

| Constant | Value | Purpose |
|---|---|---|
| `MAX_SCAN_PAGES` | 2000 | LIMIT on the scanned set (most-recently-updated pages) |
| `MAX_TAG_TREE_DEPTH` | 12 | depth bound on `/`-segment tag trees |
| `MAX_TAGS` | 20 000 | bound on distinct tags collected |
| `MAX_TEXT_CACHE_ENTRIES` | 200 | LRU bound on cached full text |
| `MAX_BACKLINK_CACHE_ENTRIES` | 200 | LRU bound on cached backlink results |

Epoch-scoped caches (all guarded by `cacheLock`, `:69-79`):

- `fullTextCache` — `LruBoundedMap<String,String>` (200) for decoded page text
  (`getFullTextForPage`, `:180-197`).
- `backlinksCache` — `LruBoundedMap<String, EpochEntry<Pair<List,List>>>` (200)
  (`findBacklinks`, `:227-247`).
- `tagHierarchyEntry` / `edgesEntry` — single cached `List<TagNode>` / `List<WikiLinkEdge>`
  (`buildTagHierarchy` `:308-322`, `buildWikiLinkEdges` `:377-412`).

Cache lifecycle:

- `invalidateCaches()` (`:131-139`) bumps `cacheEpoch`, clears every cache. Wired into
  `NoteRepository.invalidateSearchCorpus()` (`NoteRepository.kt:58`) — the existing hook that
  fires on vault lock, key replacement **and every page mutation** — so the "per unlock epoch"
  boundary is guaranteed and no decrypted cache survives a lock.
- Store steps re-check the epoch captured at call start; epoch-mismatch results are discarded
  (`:190-195`, `:240-245`, `:315-320`, `:405-410`) so plaintext never becomes resident after a
  lock and partial results are never cached.
- `invalidateTextCache(pageId)` (`:142-146`) drops one page's cached text after a direct file
  edit that bypasses the repository.

Bounding + cancellation:

- `computeBacklinks` (`:249-290`), `computeTagHierarchy` (`:324-371`) and `buildWikiLinkEdges`
  (`:383-404`) all run on `Dispatchers.Default` inside `withContext`, call
  `currentCoroutineContext().ensureActive()` per page, and scan only
  `allPages.take(MAX_SCAN_PAGES)`.
- Tag-tree depth bounded: `split('/').take(MAX_TAG_TREE_DEPTH)` (`:352`) caps both tree depth
  and the recursive `MutableTagNodeBuilder.toTagNode()` walk (`:420-429`).
- Empty results are cached too (no `isNotEmpty` guard) so repeated opens of an empty vault do
  not rescan.

Exposed internal `CacheMetrics` counters + `resetCacheMetrics()` (`:104-120`) let pure-JVM tests
prove "no re-scan" objectively.

### Callers updated (signatures now suspend, no `Context`)

- `ui/screens/KnowledgeGraphScreen.kt:87` — edge index now uses
  `WikiLinkParser.buildWikiLinkEdges(active)` (cached, cancellable) instead of the 20-line
  manual per-open scan (old `:79-100`); the edge build itself is cancelled when the
  `LaunchedEffect` leaves composition. Removed now-unused `LocalContext` import / `val context`.
  `Dispatchers`/`withContext` are still used by the physics loop at `~:180` — untouched.
- `ui/components/TagExplorerView.kt:48` — `buildTagHierarchy(pages)` (no `Context`); the
  `LaunchedEffect` teardown cancels an in-flight build when the panel closes. Removed
  `LocalContext`/`context`.
- `ui/components/BacklinksInspector.kt:43-56` — `findBacklinks(activePage, allPages, forceRefresh)`;
  `rememberCoroutineScope` teardown cancels the build on sheet close. After the
  convert-to-`[[WikiLink]]` direct file edit flow (`:232-242`) the cached full-text for that page
  is dropped (`WikiLinkParser.invalidateTextCache(match.page.id)`) and the scan is forced fresh
  (`refreshBacklinks(forceRefresh = true)`). Removed `LocalContext`/`context`.

### Cache-invalidation hook

- `data/repository/NoteRepository.kt:58` — `invalidateSearchCorpus()` now also calls
  `WikiLinkParser.invalidateCaches()`. This hook already fired on lock/zeroize/key-replace and
  every page mutation, so no new ViewModel lock plumbing was needed (reuses the phase-78
  pattern).

### Pure functions preserved

- `extractWikiLinks` (`:150-164`) and `extractTags` (`:166-171`) — signatures/behavior
  unchanged, so `MarkdownPreviewScreen` and `ImportExportService` call sites are unaffected.

## Tests

New `app/src/test/java/com/authorss81/noteflow/WikiLinkParserCacheUnitTest.kt` (9 tests, pure-JVM):

1. repeated backlinks opens reuse the cached scan without re-reading files (validated via
   `CacheMetrics`: `backlinkRecomputes==1`, `fileReads==2` across two opens, source files deleted
   before the 2nd open);
2. a new epoch (lock/vault-change) invalidates the caches and forces a fresh scan;
3. backlinks scan set is capped at `MAX_SCAN_PAGES` (a page beyond the cap is never scanned);
4. backlinks scan still finds links inside the cap window;
5. `buildTagHierarchy` cached across repeated panel opens (`tagRecomputes==1`);
6. tag tree depth is bounded for a 40-segment attacker-controlled `#a/b/c/...` tag
   (result rejected / depth ≤ `MAX_TAG_TREE_DEPTH`);
7. wiki-link edges cached across repeated graph opens (`edgeRecomputes==1`);
8. edges scan set is capped;
9. a cancelled build propagates `CancellationException` and never caches a partial result.

## Verification output

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.WikiLinkParserCacheUnitTest"`
  → **BUILD SUCCESSFUL** (9/9).
- `gradle :app:testDebugUnitTest` → 620 tests, **1 failed**:
  `EncryptionAndServiceTest.testEncryptDecryptCycle`
  (`NullPointerException: EncryptionService.decrypt, parameter encryptedBase64`).
  **Confirmed pre-existing**: reproduced on a clean tree at commit `e617dbe` (phase-100) with
  `git stash` before any phase-101 change was applied. Unrelated to this finding, documented not
  fixed (out of scope — a different phase).
- `gradle assembleDebug` → **BUILD SUCCESSFUL**.

## Checksum / secrets / platform-floor handling

- No secrets, keys or decrypted content are ever logged; `CacheMetrics` counts are integers only.
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE: untouched.
- Cached plaintext is dropped on lock via the existing `invalidateSearchCorpus()` hook; in-flight
  scans started before a lock that finish after it are discarded at store time (epoch mismatch).
- No DB schema change, no new dependencies, `.github/workflows/` untouched.
- API floor: no new API usage; all work is coroutine/cache logic. `Dispatchers.Default` is
  available on API 26+. No fallback notice required.

## Out of scope (documented, not fixed)

- `EncryptionAndServiceTest.testEncryptDecryptCycle` pre-existing failure (see above).
- `plugins/llm/LocalLlmPlugin.kt:150,242` "Condition is always 'true'" compiler warnings —
  pre-existing, outside Batch-2 DoS scope.
- Other B2 batch findings remain in their own phases.