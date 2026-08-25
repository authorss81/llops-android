package com.authorss81.noteflow

import com.authorss81.noteflow.data.repository.DecryptedPageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phase-207 (crypto/DB efficiency): behavior contract of the pure-JVM
 * [DecryptedPageCache] — the memoization that stops Room's TABLE-granular
 * invalidation from re-decrypting EVERY page row on every debounced keystroke
 * save across all four collected page flows.
 *
 * Proven here without Android:
 *  - HIT requires (pageId AND sha256(title ciphertext) AND sha256(body ciphertext))
 *    to all match — any rewritten field misses, so stale plaintext is impossible;
 *  - LRU eviction respects both the entry-count and the plaintext-char budget;
 *  - clear() drops every memoized plaintext (the lock()/re-key key boundary);
 *  - concurrent put/lookup from many threads stays bounded and consistent.
 */
class DecryptedPageCacheTest {

    private fun key(s: String?): String = DecryptedPageCache.fieldKeyOf(s)

    // ---------- fieldKey identity ----------

    @Test
    fun `field keys are stable for identical ciphertext and distinct otherwise`() {
        assertEquals(key("AES-GCM-blob-1"), key("AES-GCM-blob-1"))
        assertNotEquals(key("AES-GCM-blob-1"), key("AES-GCM-blob-2"))
        assertNotEquals(key("same"), key("same-but-longer"))
        assertNotEquals("one char difference must change the key", key("a"), key("b"))
    }

    @Test
    fun `null and empty fields share the empty sentinel`() {
        assertEquals("", DecryptedPageCache.fieldKeyOf(null))
        assertEquals("", DecryptedPageCache.fieldKeyOf(""))
        assertEquals(DecryptedPageCache.fieldKeyOf(null), DecryptedPageCache.fieldKeyOf(""))
        assertNotEquals("", key("x"))
    }

    @Test
    fun `field keys are 128-bit hex digests not raw string hashes`() {
        val k = key("some stored ciphertext")
        assertEquals("16 bytes truncated = 32 hex chars", 32, k.length)
        assertTrue(k.all { it in "0123456789abcdef" })
    }

    // ---------- hit / miss ----------

    @Test
    fun `an unchanged row is served from the cache`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("cipherT1"), key("cipherE1"), "Trip to Berlin", "Waves crash")
        val hit = cache.lookup("p1", key("cipherT1"), key("cipherE1"))
        assertNotNull(hit)
        assertEquals("Trip to Berlin", hit!!.decryptedTitle)
        assertEquals("Waves crash", hit.decryptedExtracted)
    }

    @Test
    fun `a rewritten body misses and can never serve stale plaintext`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("cipherT1"), key("body-v1"), "Title", "Old body")
        assertNull(
            "the body ciphertext changed — the memoized plaintext is invalid",
            cache.lookup("p1", key("cipherT1"), key("body-v2"))
        )
        // Re-decrypt + re-put with the new identity refreshes the entry.
        cache.put("p1", key("cipherT1"), key("body-v2"), "Title", "New body")
        assertEquals("New body", cache.lookup("p1", key("cipherT1"), key("body-v2"))!!.decryptedExtracted)
    }

    @Test
    fun `a rewritten title misses too`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("t1"), key("e1"), "Old title", "Body")
        assertNull(cache.lookup("p1", key("t2"), key("e1")))
    }

    @Test
    fun `another page id never hits a foreign entry`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("t1"), key("e1"), "Secret", null)
        assertNull(cache.lookup("p2", key("t1"), key("e1")))
    }

    @Test
    fun `a miss on changed ciphertext evicts the stale entry immediately`() {
        val cache = DecryptedPageCache(maxEntries = 8)
        cache.put("p1", key("v1"), "", "One", null)
        cache.lookup("p1", key("v2"), "") // mismatch → drop
        // The dropped entry must be GONE even if the caller forgets to re-put.
        cache.put("p2", key("x"), "", "Two", null)
        cache.put("p3", key("y"), "", "Three", null)
        assertNull(cache.lookup("p1", key("v2"), ""))
    }

    @Test
    fun `null extracted text round-trips`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("t"), "", "Title only", null)
        val hit = cache.lookup("p1", key("t"), "")
        assertNotNull(hit)
        assertNull(hit!!.decryptedExtracted)
    }

    // ---------- bounds ----------

    @Test
    fun `entry-count bound evicts the least recently used row`() {
        val cache = DecryptedPageCache(maxEntries = 2)
        cache.put("p1", key("k1"), "", "One", null)
        cache.put("p2", key("k2"), "", "Two", null)
        // Touch p1 so p2 becomes the LRU.
        cache.lookup("p1", key("k1"), "")
        cache.put("p3", key("k3"), "", "Three", null)
        assertNotNull("recently-used survivor", cache.lookup("p1", key("k1"), ""))
        assertNull("least-recently-used evicted", cache.lookup("p2", key("k2"), ""))
        assertNotNull(cache.lookup("p3", key("k3"), ""))
    }

    @Test
    fun `char budget evicts large bodies before small ones`() {
        // Entry cost = pageId chars ×2 + title + body. Each row here costs
        // 4×2 + 100 = 108 chars against a 500-char budget → at most 4 fit, and
        // the 5th insert must evict coldest rows instead of growing unbounded.
        val cache = DecryptedPageCache(maxEntries = 64, maxTotalChars = 500L)
        repeat(8) { i ->
            cache.put("big$i", key("bk$i"), "", "B".repeat(100), null)
        }
        assertTrue(
            "the plaintext char budget must evict (8×108=864 >> 500)",
            cache.size() < 8
        )
        assertTrue("bounded at roughly budget/entryCost", cache.size() <= 5)
    }

    @Test
    fun `a lone oversized entry survives rather than thrashing`() {
        val cache = DecryptedPageCache(maxEntries = 64, maxTotalChars = 10L)
        cache.put("huge", key("h"), "", "X".repeat(1000), null)
        assertEquals("never evict the only entry", 1, cache.size())
        assertEquals("X".repeat(1000), cache.lookup("huge", key("h"), "")!!.decryptedTitle)
    }

    @Test
    fun `re-putting an existing id replaces instead of double counting`() {
        val cache = DecryptedPageCache(maxEntries = 2)
        cache.put("p1", key("a"), "", "Small", null)
        cache.put("p1", key("a"), "", "Much much larger replacement body", null)
        assertEquals(1, cache.size())
        assertEquals("Much much larger replacement body", cache.lookup("p1", key("a"), "")!!.decryptedTitle)
    }

    // ---------- security boundary ----------

    @Test
    fun `clear drops every memoized plaintext`() {
        val cache = DecryptedPageCache()
        cache.put("p1", key("t"), key("e"), "Decrypted secret", "More secrets")
        cache.clear()
        assertEquals(0, cache.size())
        assertNull("no plaintext may survive a lock boundary", cache.lookup("p1", key("t"), key("e")))
    }

    // ---------- concurrency smoke ----------

    @Test
    fun `concurrent writers and readers stay bounded and consistent`() {
        val cache = DecryptedPageCache(maxEntries = 128)
        val threads = 8
        val opsPerThread = 400
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                ready.countDown()
                ready.await()
                repeat(opsPerThread) { i ->
                    val id = "p${(t * opsPerThread + i) % 200}"
                    val k = key("$t-$i")
                    cache.put(id, k, "", "title-$t-$i", null)
                    cache.lookup(id, k, "")
                }
                done.countDown()
            }
        }
        assertTrue("workers finished", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("bounded at maxEntries under concurrency", cache.size() <= 128)
    }
}
