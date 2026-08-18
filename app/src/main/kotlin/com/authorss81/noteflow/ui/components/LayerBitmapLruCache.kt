package com.authorss81.noteflow.ui.components

import androidx.compose.ui.graphics.*
import com.authorss81.noteflow.services.LayerRenderBudgetPolicy
import com.authorss81.noteflow.utils.BitmapPool

/**
 * R2-b2b4-DOS-02 (phase-150): the renderer's resident layer-raster LRU.
 *
 * The pre-fix canvas used an UNBOUNDED `mutableMapOf<String, LayerBitmapCache>`
 * (`AnnotationCanvas.layerBitmapCache`): every visible layer × visible page kept
 * one full-page ARGB_8888 bitmap alive for the whole session and nothing was
 * ever evicted. `drawCompositedLayersStrokes` materialized a fresh bitmap per
 * page+layer on the FIRST draw (when the cached size mismatched) and only
 * released on key-set-wide invalidation. A page with 40 layers (crafted restore)
 * peaked at ~416 MB native per page — an OOM brick. A 2000-page document whose
 * viewport shows 16 layers × 3 pages would similarly hold 48 bitmaps
 * (~500 MB) forever.
 *
 * This holder bounds the resident native bytes to
 * [LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES] with straightforward LRU
 * semantics (Java `LinkedHashMap` access-ordering): acquiring an entry over the
 * budget evicts least-recently-USED bitmaps back to [BitmapPool] BEFORE the map
 * grows further, so acquisition is fail-closed by construction. Byte accounting
 * is O(1) (counter) and the eviction loop is bounded by the entry count (never
 * more than ~one entry per visible layer × page the user actually looks at).
 *
 * Not a synchronous-thread-safety concern: the cache is composition-scoped and
 * mutated only from the draw + invalidation effects on the same thread, exactly
 * like the pre-fix map.
 */
class LayerBitmapLruCache {
    // accessOrder = true → iteration order is most-recently-USED → least
    // recently used, so the eviction iterator naturally walks the coldest edge.
    private val map = java.util.LinkedHashMap<String, LayerBitmapCache>(0, 0.75f, true)
    private var bytes = 0L

    val size: Int get() = map.size

    /** Total resident native bytes of the cached ARGB_8888 page bitmaps. */
    val residentBytes: Long get() = bytes

    /** Reads move the entry to the warm head of the LRU. */
    fun get(key: String): LayerBitmapCache? = map[key]

    /**
     * Inserts/replaces [cache] at [key]. Any entry already at [key] is released
     * back to [BitmapPool] (its own LRU slot is overwritten), the byte counter is
     * kept exact, and if the budget is exceeded the least-recently-USED entries
     * are evicted (released to [BitmapPool]) until the budget holds.
     */
    fun put(key: String, cache: LayerBitmapCache): LayerBitmapCache {
        val existing = map[key]
        if (existing != null) {
            bytes -= LayerRenderBudgetPolicy.byteSize(existing.bitmap.width, existing.bitmap.height)
            if (existing !== cache) {
                BitmapPool.release(existing.bitmap.asAndroidBitmap())
            }
        }
        map[key] = cache
        bytes += LayerRenderBudgetPolicy.byteSize(cache.bitmap.width, cache.bitmap.height)
        if (bytes > LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES) {
            evictUntilWithinBudget()
        }
        return cache
    }

    /** Releases every cached bitmap back to [BitmapPool] (invalidation path). */
    fun clear() {
        for (entry in map.values) {
            BitmapPool.release(entry.bitmap.asAndroidBitmap())
        }
        map.clear()
        bytes = 0L
    }

    private fun evictUntilWithinBudget() {
        val iterator = map.entries.iterator()
        while (iterator.hasNext() && bytes > LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES) {
            val entry = iterator.next()
            bytes -= LayerRenderBudgetPolicy.byteSize(entry.value.bitmap.width, entry.value.bitmap.height)
            iterator.remove()
            BitmapPool.release(entry.value.bitmap.asAndroidBitmap())
        }
    }
}