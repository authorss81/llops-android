package com.authorss81.noteflow.utils

import java.util.ArrayDeque

/**
 * Phase-207: pure-JVM byte accounting for [BitmapPool] — the global ceiling the
 * per-key COUNT cap could never provide.
 *
 * The ledger mirrors the pool's physical retention as an oldest→newest queue of
 * [Slot]s (one per pooled bitmap, tagged with its dimension [Slot.key] and byte
 * size) and answers exactly two questions:
 *  - after a bitmap ENTERS the pool, which OLDEST slots (any key — global age
 *    order, not per-key order) must leave so
 *    [BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES] holds again;
 *  - how to keep the running total exact when a slot leaves for another reason
 *    (acquired by a consumer, discarded on a size mismatch).
 *
 * A lone oversized entry is deliberately allowed to exceed the ceiling rather
 * than be evicted immediately: it is usually about to be acquired again, and
 * recycling it on arrival would turn the pool into churn. Every OTHER entry is
 * dropped until only that one remains.
 *
 * NOT thread-safe by design — [BitmapPool] serializes all calls under its own
 * lock. Pure JVM (no Bitmap references) so the multi-key pressure invariants are
 * unit-testable without Android.
 */
class BitmapPoolLedger {

    /** One pooled entry's accounting record. Identity = reference equality. */
    class Slot internal constructor(internal val key: String, internal val bytes: Long) {
        internal fun sizeBytes(): Long = bytes
    }

    /** Outcome of [record]: the caller's NEWEST slot plus any globally-oldest slots it displaced. */
    class RecordResult internal constructor(
        val newSlot: Slot,
        val evicted: List<Slot>
    )

    private val oldestFirst = ArrayDeque<Slot>()
    var totalBytes: Long = 0L
        private set

    /** How many slots the ledger currently tracks (= physically pooled bitmaps). */
    val slotCount: Int
        get() = oldestFirst.size

    /**
     * Records a NEWEST slot for a bitmap entering the pool, then drops OLDEST
     * slots (global age order across keys) while the total exceeds
     * [BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES] and more than one slot remains.
     * @return the caller's [RecordResult.newSlot] (attach it to the physical
     * entry) and the [RecordResult.evicted] slots, coldest first, to recycle.
     */
    fun record(key: String, bytes: Long): RecordResult {
        val slot = Slot(key, if (bytes < 0L) 0L else bytes)
        oldestFirst.addLast(slot)
        totalBytes += slot.bytes
        var evicted: MutableList<Slot>? = null
        while (oldestFirst.size > 1 && totalBytes > maxTotalBytes()) {
            val head = oldestFirst.removeFirst()
            totalBytes -= head.bytes
            if (evicted == null) evicted = ArrayList(2)
            evicted += head
        }
        return RecordResult(slot, evicted ?: emptyList())
    }

    /**
     * Removes [slot] when it left the pool WITHOUT being ledger-evicted
     * (consumer acquired it / it was recycled as invalid). Keeps totals exact;
     * @return false if the slot was not tracked (already evicted) — callers must
     * not subtract twice.
     */
    fun withdraw(slot: Slot): Boolean {
        // Slot has identity equality; removeFirstOccurrence walks coldest→warmest.
        if (!oldestFirst.removeFirstOccurrence(slot)) return false
        totalBytes -= slot.bytes
        return true
    }

    /** Coldest tracked slot (next to go if the budget is busted), or null. */
    fun peekOldest(): Slot? = oldestFirst.peekFirst()

    /** Drops all tracking; returns the slots (coldest first) for the caller to release. */
    fun clear(): List<Slot> {
        val slots = ArrayList<Slot>(oldestFirst)
        oldestFirst.clear()
        totalBytes = 0L
        return slots
    }

    private fun maxTotalBytes(): Long = BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES
}
