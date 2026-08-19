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
 * This holder bounds the resident native bytes with a two-tier guarantee
 * (phase-150 review fix 1):
 *  - the ACTIVE page's (the page being drawn, i.e. the [put] key just touched)
 *    own layer stack is NEVER evicted — its resident size is already bounded by
 *    LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT × one page bitmap, and
 *    evicting it mid-draw would make a legitimate 16-layer note re-rasterize
 *    itself every frame (the benchmark regression this review fix removes);
 *  - every OTHER least-recently-used page's rasters ARE evicted back to
 *    [BitmapPool] until the resident total fits
 *    LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES, so a multi-page viewport
 *    still holds a bounded working set.
 *
 * The keep/evict DECISION is the policy's pure
 * [LayerRenderBudgetPolicy.resolveProtectedEviction] (unit-testable without
 * Android); this class only executes it against the `LinkedHashMap` and releases
 * the evicted bitmaps. Unless the protected page alone is over the byte budget
 * (only possible when a full 16-layer stack exceeds it, which the layer cap
 * deliberately permits), `resolveProtectedEviction` settles at or under the
 * budget by construction.
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
     * kept exact, and if the budget is exceeded the decision is delegated to
     * [LayerRenderBudgetPolicy.resolveProtectedEviction]: least-recently-USED
     * entries of OTHER pages are released to [BitmapPool] until the budget
     * holds, while [key]'s own page's stack is never released mid-draw.
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
            evictUntilWithinBudget(key)
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

    private fun evictUntilWithinBudget(activeKey: String) {
        val coldToWarmKeys = map.keys.toList()
        val kept = LayerRenderBudgetPolicy.resolveProtectedEviction(
            coldToWarmKeys = coldToWarmKeys,
            coldToWarmBytes = coldToWarmKeys.map {
                LayerRenderBudgetPolicy.byteSize(map[it]!!.bitmap.width, map[it]!!.bitmap.height)
            },
            protectedPage = LayerRenderBudgetPolicy.pageKeyOf(activeKey),
            residentBytes = bytes
        )
        val keptSet = kept.toHashSet()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in keptSet) continue
            bytes -= LayerRenderBudgetPolicy.byteSize(entry.value.bitmap.width, entry.value.bitmap.height)
            iterator.remove()
            BitmapPool.release(entry.value.bitmap.asAndroidBitmap())
        }
    }
}