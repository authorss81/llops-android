package com.authorss81.noteflow.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.ArrayDeque
import java.util.HashMap

/**
 * High-performance Bitmap Pool for reusing offscreen render buffers and PDF page textures.
 * Eliminates Garbage Collection (GC) pauses during rapid canvas scrolling or rendering.
 *
 * Phase-207: retention is now bounded by a GLOBAL BYTE BUDGET, not just by count.
 * The pre-fix pool capped each dimension-key at 12 bitmaps with no cross-key
 * ceiling — a single 1080×2400 ARGB_8888 key (~9.95 MB/bitmap) could legally
 * retain >100 MB, and evicted LRU layer rasters kept refilling the pool during
 * scrolling. Every release now runs through [BitmapPoolLedger] so the TOTAL
 * pooled bytes stay at or under [BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES],
 * recycling the globally-oldest rasters first (across keys). A lone oversized
 * buffer may exceed the ceiling alone rather than thrash on arrival.
 *
 * Security: pooled buffers hold rendered (decrypted) ink, so `clear()` must also
 * run at the vault LOCK boundary — NoteflowViewModel.lock() calls it alongside
 * the DEK zeroization; onTrimMemory/onLowMemory remain the memory-pressure path.
 *
 * All bookkeeping is serialized under one monitor: operations are O(queue)
 * in-memory work (no I/O), negligible against the bitmap ops they coordinate,
 * and the single lock makes the byte accounting race-free across the UI/draw
 * threads that acquire and release concurrently.
 */
object BitmapPool {

    private const val TAG = "BitmapPool"
    private const val MAX_POOL_SIZE = 12

    /** One physically pooled bitmap + its ledger record. */
    private class Pooled(
        @JvmField val bitmap: Bitmap,
        @JvmField val slot: BitmapPoolLedger.Slot
    )

    private val lock = Any()
    private val pool = HashMap<String, ArrayDeque<Pooled>>()
    private val ledger = BitmapPoolLedger()

    private fun getKey(width: Int, height: Int, config: Bitmap.Config): String {
        return "${width}x${height}_${config.name}"
    }

    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val key = getKey(width, height, config)
        pollReusable(key)?.let { return it }
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) return
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val key = getKey(bitmap.width, bitmap.height, config)
        val bytes = BitmapMemoryPolicy.bitmapBytes(bitmap.width, bitmap.height, config.name)
        synchronized(lock) {
            // Per-key COUNT cap stays (a dimension-key flood still cannot crowd
            // the structure); the global byte budget below bounds the real cost.
            if ((pool[key]?.size ?: 0) >= MAX_POOL_SIZE) {
                bitmap.recycle()
                return
            }
            // Inserting may evict the globally-oldest rasters (any key) to bring
            // the total back under BitmapMemoryPolicy.MAX_POOL_TOTAL_BYTES.
            val record = ledger.record(key, bytes)
            for (slot in record.evicted) {
                recycleLocked(slot)
            }
            pool.getOrPut(key) { ArrayDeque() }.addLast(Pooled(bitmap, record.newSlot))
        }
    }

    fun getOptionsWithInBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = config
            inMutable = true
        }
        val key = getKey(width, height, config)
        val reusable = pollReusable(key)
        if (reusable != null && !reusable.isRecycled && reusable.width == width && reusable.height == height) {
            options.inBitmap = reusable
        } else {
            // Sizing/state mismatch: hand it back so the pool keeps its budget,
            // and never risk BitmapFactory's IllegalArgumentException on inBitmap.
            reusable?.let { release(it) }
        }
        return options
    }

    fun clear() {
        synchronized(lock) {
            for (slot in ledger.clear()) {
                recycleLocked(slot)
            }
            pool.clear()
        }
        Log.d(TAG, "BitmapPool cleared")
    }

    /** Pooled-byte total right now (test/observability hook). */
    fun pooledBytes(): Long = synchronized(lock) { ledger.totalBytes }

    /** Pooled-bitmap count across all keys right now (test/observability hook). */
    fun pooledCount(): Int = synchronized(lock) { ledger.slotCount }

    /**
     * Polls a pooled bitmap for [key], keeping the ledger exact. Recycled or
     * dimension-mismatched leftovers are discarded (their accounting leaves with
     * them) and the poll continues — the pre-fix behavior for stale entries.
     */
    private fun pollReusable(key: String): Bitmap? {
        synchronized(lock) {
            while (true) {
                val candidate = pool[key]?.pollFirst() ?: return null
                ledger.withdraw(candidate.slot)
                val b = candidate.bitmap
                if (!b.isRecycled && b.isMutable) {
                    b.eraseColor(android.graphics.Color.TRANSPARENT)
                    return b
                }
            }
        }
    }

    /**
     * Recycles the physical bitmap behind a LEDGER-EVICTED slot and removes it
     * from its per-key queue. Must be called under [lock]. A slot whose physical
     * entry already left its queue (should not happen — evictions and manual
     * withdrawals both go through the ledger) is a no-op.
     */
    private fun recycleLocked(slot: BitmapPoolLedger.Slot) {
        val queue = pool[slot.key] ?: return
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val pooled = iterator.next()
            if (pooled.slot === slot) {
                iterator.remove()
                if (!pooled.bitmap.isRecycled) pooled.bitmap.recycle()
                return
            }
        }
    }
}
