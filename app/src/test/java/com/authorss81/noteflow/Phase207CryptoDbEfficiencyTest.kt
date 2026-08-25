package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase-207 (crypto/DB efficiency) — source-level wiring pins for three scaling
 * fixes verified against file:line before implementation:
 *
 * 1. Decrypt MEMOIZATION — Room's invalidation tracker is TABLE-granular, so
 *    every debounced keystroke save (`updatePageBody`) re-emitted all four page
 *    flows (`getPagesForSection`/`getAllActivePagesFlow`/`getRecentPages`/
 *    `getTrashedPages`) at once and each pass AES-GCM-decrypted title+body of
 *    EVERY row. Now `decryptPageIfNeeded` memoizes through `DecryptedPageCache`
 *    keyed by (pageId, ciphertext hashes).
 * 2. LAZY corpus invalidation — mutations flip a dirty flag instead of nulling
 *    the cached search window; rebuilds reuse unchanged rows by hash
 *    (`SearchCorpusReuse`). Search can never serve a stale body.
 * 3. BitmapPool byte budget + lock-path clear — retention is bounded by TOTAL
 *    bytes (`BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES`, enforced via
 *    `BitmapPoolLedger`), and `clear()` now also runs at the vault lock boundary.
 *
 * Behavior of the pure pieces is proven in DecryptedPageCacheTest /
 * SearchCorpusReuseTest / BitmapPoolLedgerTest; this class pins the Android-side
 * WIRING (no Robolectric on this project), same technique as B2Dos02VaultSearchBoundedTest.
 */
class Phase207CryptoDbEfficiencyTest {

    private fun readSource(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    private fun repo() = readSource("data/repository/NoteRepository.kt")
    private fun viewModel() = readSource("ui/viewmodel/NoteflowViewModel.kt")
    private fun bitmapPool() = readSource("utils/BitmapPool.kt")

    // ---------- fix 1: display decrypt memoization ----------

    @Test
    fun `all four page flows route through the single memoized decrypt`() {
        val src = repo()
        assertEquals(
            "each of the four table-granular flows must still decrypt via decryptPageIfNeeded",
            4,
            Regex(Regex.escape("pages.map { page -> decryptPageIfNeeded(page) }")).findAll(src).count()
        )
    }

    @Test
    fun `decryptPageIfNeeded memoizes by page id plus both ciphertext hashes`() {
        val region = repo().substringAfter("private fun decryptPageIfNeeded").substringBefore("private fun decryptStoredGeometryOrBlank")
        assertTrue(
            "a hit must short-circuit BEFORE any AES-GCM work",
            region.contains("displayPageCache.lookup(page.id, titleKey, extractedKey)")
        )
        assertTrue(region.contains("if (memoized != null) return page.copy(title = memoized.decryptedTitle, extractedText = memoized.decryptedExtracted)")
        )
        assertTrue(
            "fresh decryptions must be written back to the memoization",
            region.contains("displayPageCache.put(page.id, titleKey, extractedKey, decryptedTitle, decryptedExtracted)")
        )
    }

    @Test
    fun `display and corpus caches are isolated instances`() {
        val src = repo()
        assertTrue(src.contains("private val displayPageCache = DecryptedPageCache()"))
        assertTrue(src.contains("private val corpusPageCache = DecryptedPageCache()"))
        assertTrue(src.contains("private val corpusReuse = SearchCorpusReuse(corpusPageCache)"))
    }

    // ---------- fix 2: lazy corpus invalidation ----------

    @Test
    fun `mutations mark the corpus dirty instead of nulling it`() {
        val region = repo().substringAfter("private fun invalidateSearchCorpus()").substringBefore("private fun clearPlaintextCaches")
        assertTrue(
            "every mutation must flip the lazy flag",
            region.contains("searchCorpusDirty = true")
        )
        assertFalse(
            "the eager null-out that forced a full-window re-decrypt after every save must be gone",
            region.contains("cachedSearchCorpus = null")
        )
    }

    @Test
    fun `the committed window is nulled only at the key epoch boundary`() {
        assertEquals(
            "cachedSearchCorpus = null must appear ONLY inside clearPlaintextCaches (lock/re-key)",
            1,
            Regex(Regex.escape("cachedSearchCorpus = null")).findAll(repo()).count()
        )
        val boundary = repo().substringAfter("private fun clearPlaintextCaches()").substringBefore("\n    }")
        assertTrue(boundary.contains("displayPageCache.clear()"))
        assertTrue(boundary.contains("corpusPageCache.clear()"))
        assertTrue(boundary.contains("cachedSearchCorpus = null"))
    }

    @Test
    fun `the key epoch boundary is wired into zeroizeKey and the key setter`() {
        val src = repo()
        val header = src.substringBefore("private fun requireEncryptionKey")
        val setterBlock = header.substringAfter("set(value) {").substringBefore("fun zeroizeKey")
        assertTrue("key replacement must drop every plaintext cache", setterBlock.contains("clearPlaintextCaches()"))
        val zeroizeBlock = header.substringAfter("fun zeroizeKey() {").substringBefore("/**")
        assertTrue("lock-time zeroization must drop every plaintext cache", zeroizeBlock.contains("clearPlaintextCaches()"))
    }

    @Test
    fun `corpus fast path serves only clean windows and rebuilds reuse by hash`() {
        val region = repo().substringAfter("private suspend fun loadSearchCorpus").substringBefore("val notebooks:")
        assertTrue(
            "the stale window must never be served while dirty",
            region.contains("if (!searchCorpusDirty)")
        )
        assertTrue(
            "rebuilds must go through the hash-reuse assembler",
            region.contains("corpusReuse.assemble(")
        )
        assertTrue(
            "the bounded SQL window read stays (B2-DOS-02)",
            region.contains("getAllActivePagesBounded(VaultSearchPolicy.SEARCH_CORPUS_CAP)")
        )
        assertTrue(
            "a successful rebuild commits the window and clears the flag atomically",
            region.contains("cachedSearchCorpus = window") && region.contains("searchCorpusDirty = false")
        )
    }

    // ---------- fix 3: bitmap pool byte budget + lock hook ----------

    @Test
    fun `pool releases are charged against the global ledger ceiling`() {
        val src = bitmapPool()
        assertTrue(src.contains("private val ledger = BitmapPoolLedger()"))
        assertTrue(
            "release must run the budgeted insert (evicting globally-oldest beyond it)",
            src.contains("ledger.record(key, bytes)") &&
                src.contains("BitmapMemoryPolicy.bitmapBytes(bitmap.width, bitmap.height, config.name)")
        )
        assertTrue(
            "acquire must keep the ledger exact when draining a queue",
            src.contains("ledger.withdraw(candidate.slot)")
        )
        assertTrue(
            "clear must recycle every tracked slot",
            src.contains("for (slot in ledger.clear())")
        )
    }

    @Test
    fun `the lock path recycles pooled rasters alongside the DEK zeroization`() {
        val lockBody = viewModel().substringAfter("fun lock()").substringBefore("override fun onCleared")
        val gateIdx = lockBody.indexOf("if (settings.hasMasterPassword) {")
        assertTrue("lock teardown stays gated on the master password", gateIdx >= 0)
        val zeroizeAt = lockBody.indexOf("repository.zeroizeKey()")
        val poolClearAt = lockBody.indexOf("com.authorss81.noteflow.utils.BitmapPool.clear()")
        assertTrue(
            "BitmapPool.clear() must be INSIDE the has-master-password gate (pooled rasters hold rendered ink)",
            poolClearAt > gateIdx
        )
        assertTrue(
            "the pool clear sits at the same session teardown as the DEK zeroization",
            zeroizeAt in 0 until poolClearAt
        )
    }
}
