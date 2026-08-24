# Phase 207: Crypto/DB Efficiency — Decrypt Memoization, Lazy Corpus Invalidation, BitmapPool Byte Budget [PERF]

**Goal:** Three scaling bottlenecks that grow with vault size, each CPU/RAM waste verified with file:line.

1. **Table-level Room invalidation fan-out re-decrypts EVERY row on EVERY save.** Four flows — `getPagesForSection`, `getAllActivePagesFlow`, `getRecentPages`, `getTrashedPages` (`NoteRepository.kt:297-315`) — each `.map { pages.map { decryptPageIfNeeded(it) } }` with NO memoization (`decryptPageIfNeeded` `:1683-1691`). Room's invalidation tracker is TABLE-granular, so every debounced keystroke save (`updatePageBody` `NoteRepository.kt:614-628`) simultaneously re-emits all four flows, collected concurrently by HomeScreen (`HomeScreen.kt:85-90`) + MainActivity (`MainActivity.kt:301-305`), each pass AES-GCM-decrypting title+extractedText of EVERY row.
   **Fix:** memoize decrypted fields keyed by `(pageId, ciphertextHash)` inside the map step (pure-JVM `DecryptedPageCache` with size bound + tests), or collapse the four trackers into one shared decrypted source (`shareIn`) flowed downstream. Preserve lock()-time cache eviction (no plaintext retention across lock).

2. **Search corpus invalidated EAGERLY on every body save.** `NoteRepository.kt:625-627` bumps generation + nulls cache per save → first palette/search query afterwards re-runs `decryptPageOrNullForCorpus` over up to `SEARCH_CORPUS_CAP` rows (`loadSearchCorpus` `:237-261`).
   **Fix:** dirty-flag instead of eager null-out; on rebuild reuse still-valid entries by ciphertext hash (only changed rows re-decrypt). Keep the existing generation guard semantics for correctness (search must never serve stale bodies).

3. **BitmapPool caps by COUNT (12) per dimension-key with no global byte budget.** `utils/BitmapPool.kt:16-18,40-44` — ~10 MB per 1080×2400 ARGB_8888 bitmap ⇒ >100 MB retained PER KEY possible; evicted LRU rasters refill the pool; `clear()` fires only on TRIM_BACKGROUND/CRITICAL + onLowMemory (`MainActivity.kt:1223-1233`) — NOT on lock().
   **Fix:** total-bytes ceiling enforced in `release()` (recycle oldest beyond budget, pure-JVM accounting class + tests); hook `BitmapPool.clear()` into the `lock()` path alongside the existing layer-cache clear (`AnnotationCanvas.kt:821-827` pattern).

## DoD
`gradle assembleDebug` green; `testDebugUnitTest` green with new pure-JVM tests (cache hit/miss/evict by hash, dirty-flag rebuild reuse, pool byte-budget invariant incl. multi-key pressure). Plaintext-at-rest rule intact: caches must be empty at `.done`. `workspace/phase-207/REPORT.md` with estimated crypto passes saved per autosave on a 500-row vault. No schema change, no workflow edits.
