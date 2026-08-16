# Phase 78 — B2-DOS-02 (MEDIUM) — Vault search re-decrypts the ENTIRE vault on every query (cache deliberately disabled past 1500)

2026-08-16 · finding source: `docs/security-report.md` B2-DOS-02, batch 2 (resource exhaustion / DoS)

## The vulnerability (before/after)

**Before:** `NoteRepository.loadSearchCorpus` (old `:58-76`) loaded the whole active
page set with `db.pageDao().getAllActivePages()` (no `LIMIT`) and cached the decrypted
corpus ONLY when `corpus.size <= searchCorpusMaxPages` (1500). For a vault of 5k+
pages the cache was NOT stored, so every non-blank keystroke (`HomeScreen` 300 ms
debounce → `NoteflowViewModel.searchVault`, one fresh never-cancelled coroutine per
query) re-ran a FULL-VAULT AES-GCM decrypt of every title/body + an O(n) substring
scan — seconds of saturated CPU per keypress on 2-core devices, with concurrent
in-flight searches allowed to pile up.

**After:** a keystroke search NEVER decrypts more than 1500 rows, and concurrent
keystrokes cancel the previous in-flight search.

- New pure-JVM `services/VaultSearchPolicy.kt` owns the decision table.
- `NoteRepository.loadSearchCorpus` (`NoteRepository.kt:107-126`) loads the window
  via the new BOUNDED DAO read `NotePageDao.getAllActivePagesBounded` (`Daos.kt`,
  `LIMIT :limit`) and **always** caches it (`cachedSearchCorpus = window`) — the
  pre-fix "skip the cache over the cap" gate is deleted. Over-cap vaults are DETECTED
  (`getActivePageCountOnce` + `exceedsCorpusCap`) and surfaced via the new
  `NoteRepository.searchCorpusCapped` boolean so the UI can offer the EXPLICIT
  refine path instead of silent degradation (AGENTS.md hardware-reality rule).
- `searchPages` (`NoteRepository.kt:403-411`) filters the cached window only.
- Explicit refine: `deepSearchPages` (`NoteRepository.kt:412-434`) users the paged
  DAO read, decrypts in bounded `DEEP_SCAN_BATCH_SIZE` batches, retains only
  matches (never pins the full decrypted corpus), and is cancellable.
- `NoteflowViewModel.searchVault` + new `deepSearchVault` (`NoteflowViewModel.kt:1927-1948`)
  share ONE cancellable `Job`: every new search calls `searchVaultJob?.cancel()`
  first and a superseded search never delivers results (`coroutineContext.ensureActive()`
  guard). Concurrent full-decrypts cannot pile up.
- `HomeScreen` shows a ONE-TIME, non-alarming "Search covers the most recent pages"
  banner with a "Search all pages" action (`HomeScreen.kt:1024-1054`), gated on
  `viewModel.repository.searchCorpusCapped` + `refinedSearchDone` (reset per query
  session) → routes to `deepSearchVault`.
- Command Palette / quick-switcher (`NoteflowViewModel.buildPaletteIndex` via
  `repository.cachedCorpus()`) now indexes the same bounded cached window —
  consistent and bounded by design.

Behavior for vaults ≤ 1500 pages is unchanged (window == whole vault, cached once
per epoch per mutation). The per-keystroke cost is now always a filter over the
cached window.

## File:line evidence (commit before/after)

| Site | Before | After |
|---|---|---|
| `NoteRepository.kt` search corpus | `:58-76` gate `if (corpus.size <= searchCorpusMaxPages)` skipped cache, full `getAllActivePages()` load; `:260-268` `searchPages` per-query full decrypt | `:107-126` `loadSearchCorpus`: `getAllActivePagesBounded(SEARCH_CORPUS_CAP)` + `getActivePageCountOnce()`, `cachedSearchCorpus = window` unconditional, `searchCorpusCapped` (`:72-76`); `:403-411` `searchPages` → cached window; `:412-434` `deepSearchPages` paged |
| `Daos.kt` `NotePageDao` | `:74/83/101` `getAllActivePagesFlow`/`getPagesForSection`/`getAllActivePages` — no LIMIT on the search read | `:106-115` new `getAllActivePagesBounded(limit)` (`LIMIT :limit`), `getAllActivePagesPaged(limit, offset)` (`LIMIT :limit OFFSET :offset`), `getActivePageCountOnce()` |
| `NoteflowViewModel.kt` | `:1608-1613` `searchVault` launched a fresh coroutine per query, never cancelled | `:1927-1948` shared `searchVaultJob` (cancel-before-launch), new `deepSearchVault` shares it, `coroutineContext.ensureActive()` guard |
| `HomeScreen.kt` | `:171-176` search on every non-blank keystroke (300 ms debounce) with no capped-window awareness | `:201-208` reset `refinedSearchDone` per query session; `:1024-1054` one-time non-alarming refine banner + "Search all pages" action wired to `deepSearchVault`; `:194` state |
| `services/VaultSearchPolicy.kt` | — | NEW pure-JVM decision table: `SEARCH_CORPUS_CAP = 1500`, `DEEP_SCAN_BATCH_SIZE = 1500`, `exceedsCorpusCap`, `cachedWindowSize`, `isBlankQuery`, `pageMatches`, `refineNoticeMessage` |

## Checksums / secrets handling

- No keys, passwords, or decrypted note content are logged anywhere; this phase adds
  no new `INTERNET` usage, keeps `allowBackup=false` + `data_extraction_rules.xml`,
  `ClipboardGuard`, and FLAG_SECURE untouched. The change is purely: bounded read +
  cached window + shared cancellable job + an explicit refine path.
- No DB schema change; the three new DAO queries are read-only additions (no
  migration, no `MIGRATION_` entry, no risk of deleting user data).

## Verification

- `gradle :app:testDebugUnitTest` — **1402 tests completed, 2 failed.** The only 2
  failures are the PRE-EXISTING `B1Plat01ReleaseSigningTest` asserts:
  - `debug buildType keeps AGP auto generated debug keystore` (`B1Plat01ReleaseSigningTest.kt:100-109`)
  - `release guide forbids distributing debug-signed builds` (`B1Plat01ReleaseSigningTest.kt:148-164`)
  Both assert on `app/build.gradle.kts` / `docs/RELEASE.md`, which this phase did not
  touch — identical failures are documented as pre-existing since phase-55 in
  `docs/phase-status.md`. The new `B2Dos02VaultSearchBoundedTest` (11 tests: policy
  decision-table behaviors for cap/window/detect/blank-match + source-level wiring
  pins for the bounded DAO reads, always-cached window, shared Job, deep-scan
  paging, and the HomeScreen refine affordance) all pass.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (57/57 tasks). Debug APK
  173,756,802 bytes (173.7 MB), SHA-256 `972bf852746806b75433d5f97fdd3f69584bc2f1ae1005fd5ffb45a4aa28bad9`.
- No new dependencies, no `.github/workflows/` edits, no Gradle verification-metadata
  changes (no new artifacts resolved).

## Out of scope / inputs judged (documented, NOT fixed here)

- **Other full-vault one-shot structural readers** that call `repository.getAllActivePages()`
  (KnowledgeGraph, TagExplorer, BacklinksInspector, TagManagerDialog) remain full-set by
  design: they run once per feature open (never per keystroke) and need the whole vault to
  build the graph/tag/backlink structure. Capping them would silently damage those features;
  the finding asked for a cap "or virtualize loads" on the SEARCH path, which is delivered.
  Noted as a possible future surface, not a B2-DOS-02 regression.
- **Real incremental encrypted search index** (the finding's alternative) was NOT built:
  it is essentially FTS5-with-approval territory (AGENTS.md ROADMAP PHASE 20.5 / deferred
  sub-item 21.3 — "needs user approval (new DB schema)"). The chosen "always-cap + explicit
  refine" option needs no schema change and closes the repeated-decrypt DoS.
- **Dead `NotePageDao.searchPages`** (`Daos.kt:112-113`, `title LIKE` over ENCRYPTED title —
  unusable on ciphertext, no callers anywhere) is left untouched; the live path is
  `NoteRepository.searchPages` over the decrypted corpus.

## Tests added

`app/src/test/java/com/authorss81/noteflow/B2Dos02VaultSearchBoundedTest.kt` (11):
- decision table: cached window always ≤ `SEARCH_CORPUS_CAP`; cap boundary 1500/1501;
  blank queries never scanned; refine notice non-alarming + only for capped vaults;
  page match semantics (title/body, case-insensitive, null/blank bodies);
- source pins: corpus loader always caches the bounded window via the limited DAO read
  (+ pre-fix `searchCorpusMaxPages`/`corpus.size <= ` gate gone), deep scan pages in
  bounded batches, DAO `LIMIT :limit`/`LIMIT :limit OFFSET :offset`/count present,
  `searchVault`/`deepSearchVault` share one cancellable job with `ensureActive()` guard,
  HomeScreen one-time refine affordance wired to `deepSearchVault`.