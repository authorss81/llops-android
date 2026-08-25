# Phase 207 Report — Crypto/DB Efficiency: Decrypt Memoization, Lazy Corpus Invalidation, BitmapPool Byte Budget

**Date:** 2026-08-25 · **Status:** DONE · **Verification:** `gradle assembleDebug` green (79.1 MB debug APK); `gradle testDebugUnitTest` 2859 tests / 3 failures — all 3 reproduced **identically on a clean stashed tree** (`Phase148UiFailureTextScrubTest` documented UNC-path failure + 2 `PaparazziSmokeTest` layoutlib failures that pass in isolation on both trees). No schema change, no new dependencies, `.github/workflows/` untouched.

---

## Fix 1 — Decrypt memoization vs Room's TABLE-granular invalidation fan-out

**Verified problem:** `getPagesForSection`, `getAllActivePagesFlow`, `getRecentPages`, `getTrashedPages` each ran `.map { pages.map { decryptPageIfNeeded(it) } }` with no memoization (old `NoteRepository.kt:297-315`). Room's invalidation tracker is TABLE-granular, so every debounced keystroke save (`updatePageBody`) re-emitted **all four flows simultaneously** (collected concurrently by HomeScreen + MainActivity/pagesJob), and every emission AES-GCM-decrypted title+extractedText of EVERY row.

**Implementation:**
- New pure-JVM `data/repository/DecryptedPageCache.kt`: thread-safe access-order LRU keyed by `(pageId, sha256(title ciphertext)[:128bit], sha256(extracted ciphertext)[:128bit])`. A hit requires BOTH ciphertext keys to match — any rewritten field misses and re-decrypts, so **stale plaintext can never be served**. Bounded by entry count (1024) AND a plaintext char budget (~32 MB, thrash-guarded to always keep ≥1 entry). Field keys are truncated SHA-256 hex digests, not collision-prone `hashCode`s.
- `NoteRepository.decryptPageIfNeeded` consults `displayPageCache.lookup(...)` BEFORE any AES-GCM work and writes fresh results back via `put(...)`. All four flows plus `getAllActivePages`/`getPageById`/`getPagesForSectionOnce`/`getPagesForNotebookOnce`/`deepSearchPages` benefit through the single funnel.
- **Two ISOLATED cache instances**: `displayPageCache` (misses record B1-DB-8 ledger failures) vs `corpusPageCache` (search path never records failures, phase-88 review semantics). Sharing would let a corpus-first read suppress a display-side failure report and weaken persistent-corruption escalation.
- Ledger semantics preserved: a cached marker skips only a redundant add of an already-recorded note id (the ledger is a dedup set); escalation behavior unchanged.

## Fix 2 — Search corpus invalidated EAGERLY → lazy dirty flag + hash reuse

**Verified problem:** old `NoteRepository.kt:625-627` bumped generation + nulled `cachedSearchCorpus` on EVERY body save, so the first palette/search query afterwards re-ran `decryptPageOrNullForCorpus` over up to `SEARCH_CORPUS_CAP` (1500) rows.

**Implementation:**
- `invalidateSearchCorpus()` now flips `searchCorpusDirty = true` (+ generation bump, capped-flag reset — unchanged) instead of nulling. A mutation followed by no query pays nothing.
- `loadSearchCorpus()` serves the committed window ONLY when clean (`if (!searchCorpusDirty)` double-checked under `searchCorpusLock`); a dirty flag always rebuilds. Rebuilds go through new pure-JVM `data/repository/SearchCorpusReuse.assemble(rawWindow) { decryptPageOrNullForCorpus(it) }`: per row it looks up `(id, title-hash, body-hash)` in `corpusPageCache`; hits reuse the memoized plaintext on the RAW row (fresh metadata rides on the raw entity), misses decrypt exactly that row. Undecryptable rows stay dropped and are retried next rebuild (never memoized as a permanent hole). The generation guard loop is untouched, so search still can never serve a stale snapshot mid-re-key. Existing pins kept: SQL-bounded read `getAllActivePagesBounded(SEARCH_CORPUS_CAP)` + commit `cachedSearchCorpus = window`.
- `cachedSearchCorpus = null` now exists ONLY inside the new key-epoch boundary `clearPlaintextCaches()`.

## Security boundary (plaintext-at-rest rule intact)

New private `clearPlaintextCaches()` drops `displayPageCache`, `corpusPageCache` AND the committed corpus window; it is wired into BOTH `zeroizeKey()` (lock) and the `encryptionKey` setter (unlock/re-key/restore). **No decrypted content outlives its key epoch.** Plain mutations deliberately do NOT route here (that would defeat fix 1).

## Fix 3 — BitmapPool byte budget + clear-on-lock

**Verified problem:** `utils/BitmapPool.kt` capped retention by COUNT (12) per dimension-key with no global ceiling — one 1080×2400 ARGB_8888 buffer is 10,368,000 bytes, so a single key could legally retain >123 MB, and evicted LRU layer rasters kept refilling the pool during scrolling. `clear()` fired only from `onTrimMemory(TRIM_BACKGROUND/CRITICAL)` + `onLowMemory` (MainActivity.kt:1238-1248), NEVER on lock.

**Implementation:**
- New pure-JVM `utils/BitmapMemoryPolicy.kt`: `MAX_POOL_TOTAL_BYTES = 64 MB` (matches the app's existing resident-raster philosophy, `LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES`) + `bytesPerPixel` table (unknown/null/HARDWARE charged at ARGB_8888 rate — never an underestimate) + `bitmapBytes`.
- New pure-JVM `utils/BitmapPoolLedger.kt`: oldest→newest slot queue with global byte accounting; `record(key, bytes)` returns the caller's new slot + any GLOBALLY-OLDEST slots evicted (across keys) to fit the ceiling; a lone oversized entry survives rather than thrashing; `withdraw` keeps totals exact for consumer-side removals; `clear()` reports dropped slots.
- `BitmapPool` rewritten around the ledger (single monitor; public API `acquire/release/getOptionsWithInBitmap/clear` unchanged — LayerBitmapLruCache/EditorScreen call sites untouched). Per-key COUNT cap stays as a secondary bound. Observability hooks `pooledBytes()`/`pooledCount()` added.
- **Lock hook:** `NoteflowViewModel.lock()` calls `BitmapPool.clear()` INSIDE the `hasMasterPassword` gate immediately after `repository.zeroizeKey()` — pooled rasters hold rendered (decrypted) ink and previously survived a lock. Passwordless vaults keep the session-preserving no-op posture (phase-181).

## Estimated crypto passes saved per autosave — 500-row vault

Model: 500 active rows, every row carrying encrypted title + encrypted body; one debounced autosave (`updatePageBody`) + one subsequent palette/search query.

| Path | Pre-fix | Post-fix |
|---|---|---|
| 4 page-flow emissions × 500 rows × 2 fields | 4,000 GCM passes | 4 × 2 = **8** (only the edited row misses; rest are SHA-256 checks ≈ µs + cache hits) |
| Search-corpus rebuild after the eager null | 1,000 GCM passes | **2** (edited row only) — or **0** if no query ever arrives before the next mutation (lazy flag) |
| **Total per autosave(+query)** | **≈ 5,000** | **≈ 10** |

**≈ 4,990 AES-GCM field decryptions saved per keystroke autosave (~99.8%).** On a capped 1500-row vault the delta grows to ~12,000 → ~10 per event. Honest caveats: the FIRST pass after each unlock pays one full populate (~1,000 passes, same as one pre-fix rebuild); rows evicted beyond the LRU bounds re-pay on their next read (a slowdown, never a correctness issue); `deepSearchPages` keeps its deliberate per-batch decrypts (explicit user-approved refine path).

## Tests

| Test class | Count | What it proves |
|---|---|---|
| `DecryptedPageCacheTest` | 13 | hit requires id+both hashes; rewritten title/body miss; stale entry dropped on mismatched lookup; LRU entry-count + char-budget eviction; lone oversized survivor; replace-not-double-count; `clear()` empties (lock boundary); 8-thread concurrency smoke stays bounded |
| `SearchCorpusReuseTest` | 7 | first build decrypts all; single-row edit re-decrypts ONLY that row; fresh metadata rides on the raw row; undecryptable rows dropped+retried (never cached forever) and recover after unlock; order preserved; empty window free; title-hash-only change invalidates |
| `BitmapPoolLedgerTest` | 9 | 1080×2400×4 = 10,368,000 bytes; bytesPerPixel table fails HIGH for unknown configs; multi-key pressure evicts GLOBAL-oldest-first interleaving A,B,C; newest entry never self-evicted; lone oversized retained; withdraw refuses double-subtraction; clear resets; negatives clamped |
| `Phase207CryptoDbEfficiencyTest` | 9 | source pins: 4 flows route through `decryptPageIfNeeded`; lookup-before-AES + put-back wiring; two isolated cache instances; dirty-flag without eager null; `cachedSearchCorpus = null` appears ONLY in `clearPlaintextCaches()`; boundary wired into zeroizeKey + setter; fast-path/reuse/commit tokens; ledger-charged releases; `BitmapPool.clear()` inside the has-master-password gate after `zeroizeKey()` |

**38 new tests, all green.** Existing pins verified compatible: `B2Dos02VaultSearchBoundedTest` (`cachedSearchCorpus = window`, bounded DAO read), `B1Db08DecryptFailureTest` region pins (single-line cache-hit return adds no brace before the pinned tokens), `B1Db08`/`B2Ui2` lock-ordering pins (zeroize → reset ordering intact), `Phase150CanvasRenderBudgetTest` (`BitmapPool.release(` call sites intact).

## Files

- NEW `app/src/main/kotlin/com/authorss81/noteflow/data/repository/DecryptedPageCache.kt`
- NEW `app/src/main/kotlin/com/authorss81/noteflow/data/repository/SearchCorpusReuse.kt`
- NEW `app/src/main/kotlin/com/authorss81/noteflow/utils/BitmapMemoryPolicy.kt`
- NEW `app/src/main/kotlin/com/authorss81/noteflow/utils/BitmapPoolLedger.kt`
- MOD `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`
- MOD `app/src/main/kotlin/com/authorss81/noteflow/utils/BitmapPool.kt`
- MOD `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt` (lock() hook only)
- NEW 4 test classes under `app/src/test/java/com/authorss81/noteflow/`
