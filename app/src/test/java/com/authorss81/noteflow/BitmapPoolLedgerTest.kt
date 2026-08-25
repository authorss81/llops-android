package com.authorss81.noteflow

import com.authorss81.noteflow.utils.BitmapMemoryPolicy
import com.authorss81.noteflow.utils.BitmapPoolLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-207 (crypto/DB efficiency): the pure-JVM accounting behind the
 * BitmapPool's new GLOBAL BYTE BUDGET.
 *
 * Pre-fix, BitmapPool capped retention by COUNT (12) per dimension-key with no
 * cross-key ceiling: a single 1080×2400 ARGB_8888 key (~9.95 MB/bitmap) could
 * legally retain >100 MB. These tests pin:
 *  - the byte math per Bitmap config (never an underestimate for unknowns);
 *  - eviction is GLOBAL-OLDEST-FIRST across keys under multi-key pressure;
 *  - totals stay exact through record/withdraw/clear;
 *  - a lone oversized entry survives rather than thrashing.
 */
class BitmapPoolLedgerTest {

    private val budget = BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES

    // ---------- BitmapMemoryPolicy byte math ----------

    @Test
    fun `a full-page ARGB_8888 buffer is about 10MB`() {
        // 1080 × 2400 × 4 = 10,368,000 bytes — the "~10 MB" from the finding.
        assertEquals(10_368_000L, BitmapMemoryPolicy.bitmapBytes(1080, 2400, "ARGB_8888"))
        assertEquals(
            "12 of them (the old per-key count cap) exceed 120 MB",
            10_368_000L * 12,
            BitmapMemoryPolicy.bitmapBytes(1080, 2400, "ARGB_8888") * 12
        )
    }

    @Test
    fun `bytes-per-pixel table covers every config and fails high for unknowns`() {
        assertEquals(1L, BitmapMemoryPolicy.bytesPerPixel("ALPHA_8"))
        assertEquals(2L, BitmapMemoryPolicy.bytesPerPixel("RGB_565"))
        assertEquals(2L, BitmapMemoryPolicy.bytesPerPixel("ARGB_4444"))
        assertEquals(4L, BitmapMemoryPolicy.bytesPerPixel("ARGB_8888"))
        assertEquals(4L, BitmapMemoryPolicy.bytesPerPixel("RGBA_1010102"))
        assertEquals(8L, BitmapMemoryPolicy.bytesPerPixel("RGBA_F16"))
        assertEquals("HARDWARE charged at the common rate", 4L, BitmapMemoryPolicy.bytesPerPixel("HARDWARE"))
        assertEquals("null config charged at the common rate", 4L, BitmapMemoryPolicy.bytesPerPixel(null))
        assertEquals("future configs must never be underestimated", 4L, BitmapMemoryPolicy.bytesPerPixel("SOME_NEW_CONFIG"))
    }

    @Test
    fun `degenerate dimensions cost zero bytes`() {
        assertEquals(0L, BitmapMemoryPolicy.bitmapBytes(0, 100, "ARGB_8888"))
        assertEquals(0L, BitmapMemoryPolicy.bitmapBytes(100, -1, "ARGB_8888"))
    }

    @Test
    fun `the ceiling matches the app's resident-raster philosophy`() {
        assertEquals(64L * 1024L * 1024L, budget)
    }

    // ---------- ledger mechanics ----------

    @Test
    fun `records under the budget evict nothing`() {
        val ledger = BitmapPoolLedger()
        val r = ledger.record("100x100_ARGB_8888", 40_000L)
        assertTrue(r.evicted.isEmpty())
        assertEquals(40_000L, ledger.totalBytes)
        assertEquals(1, ledger.slotCount)
    }

    @Test
    fun `multi-key pressure evicts globally oldest first not per key`() {
        val ledger = BitmapPoolLedger()
        // Simulate the finding: three dimension-keys of ~10 MB full-page buffers.
        val size = 10_368_000L
        val keys = listOf("A", "B", "C")
        val slots = ArrayList<BitmapPoolLedger.Slot>()
        val evicted = ArrayList<BitmapPoolLedger.Slot>()
        repeat(9) { i ->
            val r = ledger.record(keys[i % 3], size)
            slots += r.newSlot
            evicted += r.evicted
            assertTrue(
                "invariant: total never exceeds the ceiling by more than one lone entry",
                ledger.totalBytes <= budget || ledger.slotCount == 1
            )
        }
        // 9 × 10.37 MB ≈ 93 MB against the 64 MB ceiling → at least 3 evictions.
        assertTrue("pressure across keys must evict", evicted.size >= 3)
        assertEquals(
            "eviction order is GLOBAL age (oldest first), interleaving keys A,B,C",
            listOf("A", "B", "C"),
            evicted.take(3).map { it.key }
        )
        assertTrue(ledger.totalBytes <= budget)
        assertEquals(slots.size - evicted.size, ledger.slotCount)
    }

    @Test
    fun `the newest entry is never evicted by its own record call`() {
        val ledger = BitmapPoolLedger()
        val big = budget / 2 + 1_000_000L // two of them exceed the ceiling together
        val first = ledger.record("K", big)
        assertTrue(first.evicted.isEmpty())
        val second = ledger.record("K2", big) // total now exceeds → first must go
        assertSame(first.newSlot, second.evicted.single())
        assertTrue(second.evicted.none { it === second.newSlot })
        assertEquals(big, ledger.totalBytes)
    }

    @Test
    fun `a lone oversized entry is retained rather than thrashed`() {
        val ledger = BitmapPoolLedger()
        repeat(3) { ledger.record("K$it", 30_000_000L) } // 90 MB total
        val oversized = ledger.record("HUGE", 80_000_000L) // alone > budget
        assertTrue(oversized.evicted.isNotEmpty())
        assertTrue("the oversized entry itself is never self-evicted", oversized.evicted.all { it !== oversized.newSlot })
        assertEquals("everything older was dropped; the oversized entry survives alone", 1, ledger.slotCount)
        assertEquals(oversized.newSlot.sizeBytes(), ledger.totalBytes)
    }

    @Test
    fun `withdraw keeps totals exact and refuses double removal`() {
        val ledger = BitmapPoolLedger()
        val a = ledger.record("A", 1_000L)
        val b = ledger.record("B", 2_000L)
        assertTrue(ledger.withdraw(a.newSlot))
        assertEquals(2_000L, ledger.totalBytes)
        assertFalse("already gone — must not subtract twice", ledger.withdraw(a.newSlot))
        assertEquals(2_000L, ledger.totalBytes)
        assertTrue(ledger.withdraw(b.newSlot))
        assertEquals(0L, ledger.totalBytes)
        assertEquals(0, ledger.slotCount)
    }

    @Test
    fun `clear resets everything and reports the dropped slots`() {
        val ledger = BitmapPoolLedger()
        ledger.record("A", 5_000L)
        ledger.record("B", 7_000L)
        val dropped = ledger.clear()
        assertEquals(2, dropped.size)
        assertEquals(0L, ledger.totalBytes)
        assertEquals(0, ledger.slotCount)
        assertNull(ledger.peekOldest())
        // Post-clear records start fresh.
        val r = ledger.record("C", 1L)
        assertTrue(r.evicted.isEmpty())
        assertEquals(1L, ledger.totalBytes)
    }

    @Test
    fun `negative byte inputs are clamped not trusted`() {
        val ledger = BitmapPoolLedger()
        val r = ledger.record("X", -5L)
        assertEquals(0L, ledger.totalBytes)
        assertEquals(0L, r.newSlot.sizeBytes())
    }
}
