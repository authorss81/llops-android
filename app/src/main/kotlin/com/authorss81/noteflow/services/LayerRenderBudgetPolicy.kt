package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.LayerEntity

/**
 * R2-b2b4-DOS-02 (phase-150): the LIVE canvas layer + resident layer-bitmap
 * budget decision table. Pure JVM so every gate is unit-testable without
 * Android (no Bitmap / ImageBitmap under test here).
 *
 * The vulnerability (see docs/security-report-round2.md R2-b2b4-DOS-02):
 * `EditorScreen.onAddLayer` had no maximum, `LayerDao.getLayersForPage`
 * returned EVERY `layers` row, and the renderer kept ONE full-page ARGB_8888
 * bitmap per visible layer resident for the whole session
 * (`AnnotationCanvas` `layerBitmapCache`, never evicted). A crafted backup
 * spreading strokes across 40 layers on a 1080x2400 page materialized ~416 MB
 * native (multiple pages × more), OOMing on open. The ONLY cap in the stack
 * was `PsdExportPolicy` on the EXPORT path (16 layers).
 *
 * This policy is the single source of the LIVE budget:
 *  1. [MAX_LIVE_LAYER_COUNT] — the on-screen layer cap, deliberately the SAME
 *     16 as the export path ([PsdExportPolicy.MAX_EXPORT_LAYER_COUNT]) so the
 *     editor shows exactly what a PSD export can carry. Enforced at the add /
 *     duplicate handlers (fail-closed snackbar, never a silent limit), at the
 *     DAO load (a bounded top-by-`zOrder` query keeps only the visually
 *     front-most = highest-`zOrder` layers; strokes whose layer was dropped
 *     fall back onto the first retained layer, bytes never lost) and on the
 *     RESTORE path (a raw sanitizer caps a crafted backup's `layers` table per
 *     page BEFORE re-key / transplant).
 *  2. [MAX_RESIDENT_BITMAP_BYTES] — the resident layer-bitmap map byte budget.
 *     The renderer caches one full-page bitmap per visible layer+page; an LRU
 *     holder ([com.authorss81.noteflow.ui.components.LayerBitmapLruCache])
 *     evicts least-recently-used bitmaps back to [com.authorss81.noteflow.utils.BitmapPool]
 *     so total resident raster memory stays bounded regardless of page count.
 *     [byteSize] / [wouldExceedResidentBudget] / [overageBytes] are the byte
 *     accounting. The budget is a CROSS-PAGE bound: [resolveProtectedEviction]
 *     never evicts the ACTIVE page's own layer stack (bounded by the layer cap)
 *     so a legit 16-layer note is not re-rasterized every frame — see
 *     [MAX_RESIDENT_BITMAP_BYTES] for the two-tier guarantee (phase-150 review fix 1).
 *  3. The capacity arithmetic + BOTH non-alarming notices ([layerLimitNotice],
 *    [layersCappedNotice]) — AGENTS.md hardware-reality rule: never silent
 *    degradation, always a one-time non-alarming message.
 *
 * The exported SQL literals ([BOUNDED_TOP_LAYERS_ROOM_SQL] /
 * [KEEP_HIGHEST_Z_LAYERS_RAW_SQL]) are the SINGLE ordering source wired into
 * the Room `@Query` AND the raw restore sanitizer — both keep the TOP
 * `MAX_LIVE_LAYER_COUNT` layers per page by `zOrder DESC` with a `rowid`
 * tie-break so a crafted equal-`zOrder` backup prunes deterministically.
 * This is NOT a security feature the user must opt out of: it is a fail-closed
 * memory bound held at the same number the export already promises, so lifting
 * it would reintroduce the DoS vector the prompt asked us to close.
 */
object LayerRenderBudgetPolicy {

    /**
     * Maximum number of drawing layers a live page may hold (matches the PSD
     * export cap [PsdExportPolicy.MAX_EXPORT_LAYER_COUNT]).
     */
    const val MAX_LIVE_LAYER_COUNT = 16

    /**
     * Total native bytes the resident layer-bitmap LRU holds ACROSS pages.
     * ~6 full-page 1080x2400 ARGB_8888 bitmaps (10.4 MB each) — far below the
     * ~416 MB the uncapped pre-fix state could reach with 40 layers, and
     * comfortably inside a 1-2 GB device's budget.
     *
     * The byte budget is a CROSS-PAGE bound (phase-150 review fix 1): the LRU
     * never evicts the ACTIVE page's own layer stack (its resident size is
     * already bounded by the [MAX_LIVE_LAYER_COUNT] cap + one page-bitmap
     * each), because evicting it mid-draw would turn a legitimate 16-layer note
     * into per-frame re-rasterization churn. A page being drawn therefore holds
     * at most `MAX_LIVE_LAYER_COUNT` bitmaps (≈166 MB at 1080x2400 — the same
     * worst case the pre-fix canvas held for a legit 16-layer note), and every
     * OTHER least-recently-used page's bitmaps are evicted back to the pool to
     * hold this budget. Widget order of the two invariants:
     * 1. single page ≤ MAX_LIVE_LAYER_COUNT bitmaps (never churns its own draw);
     * 2. everything else ≤ MAX_RESIDENT_BITMAP_BYTES.
     */
    const val MAX_RESIDENT_BITMAP_BYTES = 64L * 1024L * 1024L

    /** Bytes per pixel of the cached layer render bitmaps (ARGB_8888). */
    const val BYTES_PER_PIXEL = 4

    /**
     * The Room DAO's bounded top-layer read (see [LayerDao.getTopLayersForPageBounded]).
     * Single literal shared with the pure-JVM [capToLiveLimit] semantics and
     * contrasted against the raw restore sanitizer's statement.
     */
    const val BOUNDED_TOP_LAYERS_ROOM_SQL =
        "SELECT * FROM layers WHERE pageId = :pageId ORDER BY zOrder DESC, rowid DESC LIMIT :limit"

    /**
     * The restore-time sanitizer statement: delete every layer row of a page
     * that is NOT one of the top [MAX_LIVE_LAYER_COUNT] by `zOrder` (ties by
     * rowid). One statement per page, `LIMIT 16` subquery so the keep-set is
     * always the front-most layers; strokes referencing a dropped layer fall
     * back onto the first retained layer in the renderer.
     */
    const val KEEP_HIGHEST_Z_LAYERS_RAW_SQL =
        "DELETE FROM layers WHERE pageId = ? AND id NOT IN " +
            "(SELECT id FROM layers WHERE pageId = ? ORDER BY zOrder DESC, rowid DESC LIMIT ?)"

    /** Native byte size of an ARGB_8888 [width]x[height] page bitmap (0 for non-positive dims). */
    fun byteSize(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong() * BYTES_PER_PIXEL
    }

    /** True when a page is already AT the live layer cap (no more may be added). */
    fun layerLimitReached(layerCount: Int): Boolean = layerCount >= MAX_LIVE_LAYER_COUNT

    /** True when adding one more layer keeps the page within the live cap. */
    fun mayAddLayer(layerCount: Int): Boolean = layerCount < MAX_LIVE_LAYER_COUNT

    /** True when a raw count exceeds the live cap (some layers must be omitted). */
    fun isLayerCountCapped(layerCount: Int): Boolean = layerCount > MAX_LIVE_LAYER_COUNT

    /** How many layers a live cap drops for a vault of [layerCount] layers (0 if none). */
    fun omittedLayerCount(layerCount: Int): Int =
        (layerCount - MAX_LIVE_LAYER_COUNT).coerceAtLeast(0)

    /** True when adding a [newEntryBytes]-byte bitmap would bust the resident budget. */
    fun wouldExceedResidentBudget(currentBytes: Long, newEntryBytes: Long): Boolean =
        currentBytes + newEntryBytes > MAX_RESIDENT_BITMAP_BYTES

    /** By how many bytes the resident map busts the budget including the new entry (0 if fine). */
    fun overageBytes(currentBytes: Long, newEntryBytes: Long): Long =
        (currentBytes + newEntryBytes - MAX_RESIDENT_BITMAP_BYTES).coerceAtLeast(0L)

    /**
     * The page token embedded in a layer-raster cache key
     * (`"${pageIdx}_${layer.id}_${symmetryMode}_v${vibrancyBoost}"`,
     * AnnotationCanvas cache keys). The LRU's active-page protection keys off
     * this token.
     */
    fun pageKeyOf(cacheKey: String): String = cacheKey.substringBefore('_')

    /**
     * The survivor set of the LRU's protected-page eviction decision (phase-150
     * review fix 1): walk the resident entries in LRU order (coldest first) and
     * release any whose [pageKeyOf] page differs from [protectedPage] until the
     * resident byte total ([residentBytes]) fits [MAX_RESIDENT_BITMAP_BYTES].
     * Entries OF the protected page survive unconditionally — their resident
     * size is already bounded by [MAX_LIVE_LAYER_COUNT] × one page bitmap, and
     * evicting them mid-draw would make a legitimate 16-layer page re-rasterize
     * itself every frame. Once the budget holds, warmer entries are retained too,
     * so a multi-page viewport still holds only the recently-visited pages.
     * Pure decision, shared with [com.authorss81.noteflow.ui.components.LayerBitmapLruCache].
     */
    fun resolveProtectedEviction(
        coldToWarmKeys: List<String>,
        coldToWarmBytes: List<Long>,
        protectedPage: String,
        residentBytes: Long
    ): List<String> {
        var bytes = residentBytes
        var budgetHolds = bytes <= MAX_RESIDENT_BITMAP_BYTES
        val survivors = ArrayList<String>(coldToWarmKeys.size)
        for (i in coldToWarmKeys.indices) {
            val key = coldToWarmKeys[i]
            val keep = budgetHolds || pageKeyOf(key) == protectedPage
            if (keep) {
                survivors += key
            } else {
                bytes -= coldToWarmBytes[i]
                if (bytes <= MAX_RESIDENT_BITMAP_BYTES) budgetHolds = true
            }
        }
        return survivors
    }

    /**
     * The live-cap semantics definition (pure JVM mirror of the SQL in
     * [BOUNDED_TOP_LAYERS_ROOM_SQL] / [KEEP_HIGHEST_Z_LAYERS_RAW_SQL]): keep the
     * TOP [MAX_LIVE_LAYER_COUNT] layers by `zOrder` (ties broken by list order =
     * the SQL's `rowid`), then return them in ascending `zOrder` so downstream
     * consumers (the renderer's layer sort + the editor's active-layer pick)
     * see the same ordering a pre-fix ASC full read produced. Never mutates the
     * input.
     */
    fun capToLiveLimit(layers: List<LayerEntity>): List<LayerEntity> {
        if (layers.size <= MAX_LIVE_LAYER_COUNT) return layers
        return layers
            .withIndex()
            // zOrder DESC; ties break on list order (the index) — the SQL mirror
            // of `ORDER BY zOrder DESC, rowid DESC`, because the DAO's read order
            // IS rowid order and a crafted equal-zOrder backup must prune by the
            // same deterministic rule everywhere.
            .sortedWith(compareByDescending<IndexedValue<LayerEntity>> { it.value.zOrder }.thenByDescending { it.index })
            .take(MAX_LIVE_LAYER_COUNT)
            .map { it.value }
            // Back to ascending zOrder so downstream consumers (renderer layer
            // sort + the editor's active-layer pick) see the same ordering a
            // pre-fix ASC full read produced.
            .sortedBy { it.zOrder }
    }

    /**
     * Non-alarming failure notice shown when a user tries to add/duplicate a
     * layer past the live cap. Informational tone; states the number is the
     * same as the export budget so there is no surprise later.
     */
    fun layerLimitNotice(): String =
        "You've reached the maximum of $MAX_LIVE_LAYER_COUNT layers per note " +
            "(the same cap PSD export uses). Merge or delete a layer to add more."

    /**
     * One-time non-alarming notice shown when a page opened with more layers
     * than [MAX_LIVE_LAYER_COUNT] (a crafted restore or a pre-fix vault). The
     * bottom [dropped] layers stay in the vault but are NOT shown; their ink is
     * folded into the retained stack while reading. States the honest facts
     * (kept, dropped, cap) without an alarm tone, and notes that saving the page
     * makes the fold permanent (the save path persists only the retained layers).
     */
    fun layersCappedNotice(kept: Int, dropped: Int): String {
        val k = kept.coerceAtLeast(0)
        val d = dropped.coerceAtLeast(0)
        return if (d > 0) {
            "This page holds more layers than the canvas can show (max $MAX_LIVE_LAYER_COUNT). " +
                "The lowest $d layer${if (d == 1) "" else "s"} fold into the retained stack and " +
                "are permanently folded when you save this page."
        } else {
            "Page opened with its $k layers."
        }
    }
}