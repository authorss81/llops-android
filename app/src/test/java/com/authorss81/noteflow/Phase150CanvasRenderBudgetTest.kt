package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.services.CanvasPageBudgetPolicy
import com.authorss81.noteflow.services.LayerRenderBudgetPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b4-DOS-02 / R2-b2b4-DOS-03 / R2-b2b5-FEA-04 (phase-150) — canvas memory
 * and per-frame render budgets.
 *
 * Findings (docs/security-report-round2.md):
 *  - R2-b2b4-DOS-02 (MEDIUM): the LIVE layer count was UNBOUNDED. The editor's
 *    add/duplicate handlers had no cap, `LayerDao.getLayersForPage` returned
 *    EVERY `layers` row, and the renderer kept one full-page ARGB_8888 bitmap
 *    per visible layer in an unbounded map for the whole session — a crafted
 *    backup spreading strokes across 40 layers on a 1080x2400 page peaked at
 *    ~416 MB native per page → OOM on open.
 *  - R2-b2b4-DOS-03 (LOW): the minimap HUD re-walked EVERY stroke/point on the
 *    main thread at a FIXED 1-4 stride and issued one `drawLine` per retained
 *    pair — ~50k draw commands per pan/zoom frame at the phase-50 geometry cap.
 *  - R2-b2b5-FEA-04 (LOW): `dynamicPageCount` derived from the raw end-of-stroke
 *    Y with NO upper clamp — one crafted stroke point `"y":1e9` (short JSON,
 *    passes every length gate) produced ~628,141 pages, and the renderer looped
 *    `0 until renderPageCount` on EVERY frame.
 *
 * Fix shape (all provable on the pure JVM — no Room/SQLCipher/Bitmap):
 *  - [LayerRenderBudgetPolicy] owns the LIVE layer cap (16, deliberately the
 *    same as the PSD export cap), the resident-bitmap byte budget (64 MB) and
 *    byte accounting, the top-`zOrder`-by-rowid ordering SQL (the SINGLE literal
 *    wired into the Room DAO's bounded read AND the raw restore sanitizer) and
 *    both non-alarming notices.
 *  - `LayerDao.getTopLayersForPageBounded` + `NoteRepository.getLayersForPage`
 *    materialize the TOP-16 read; the VM raises the one-time notice when the raw
 *    count exceeds it; `EditorScreen` fails closed at the add/duplicate handlers.
 *  - the restore sanitizer strips a crafted `layers` table under the candidate
 *    key before re-key; the export trims the STAGED snapshot, never the vault.
 *  - [CanvasPageBudgetPolicy] clamps the world Y to a sizable ceiling and bounds
 *    the derived page count; the minimap walks budgeted strides
 *    ([MinimapGeometryPolicy]) so the pass is `<= MAX_MINIMAP_POLYLINE_SEGMENTS +
 *    MAX_MINIMAP_SAMPLED_STROKES` `drawLine`s regardless of geometry size.
 *
 * The Android binding is pinned at source level below.
 */
class Phase150CanvasRenderBudgetTest {

    private fun layer(ordinal: Int, zOrder: Int) = LayerEntity(
        id = "layer_$ordinal",
        pageId = "page-A",
        name = "Layer $ordinal",
        zOrder = zOrder,
        opacity = 1.0f,
        blendMode = "NORMAL",
        visible = true,
        locked = false
    )

    private fun rowsOfZ(vararg z: Int): List<Pair<Int, Int>> =
        z.mapIndexed { rowid, zOrder -> rowid to zOrder }

    // ---------------------------------------------------------------------
    // R2-b2b4-DOS-02: layer-count cap + byte budget
    // ---------------------------------------------------------------------

    @Test
    fun `the layer budget owns the exact numbers the fix wires`() {
        assertEquals(
            "the LIVE cap matches the export cap exactly",
            16,
            LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT
        )
        assertEquals("resident raster budget is 64 MB", 64L * 1024L * 1024L, LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES)
        assertEquals("cached rasters are ARGB_8888", 4, LayerRenderBudgetPolicy.BYTES_PER_PIXEL)
        assertTrue("one 1080x2400 page bitmap is ~10.4 MB", LayerRenderBudgetPolicy.byteSize(1080, 2400) == 10_368_000L)
        assertEquals("non-positive dims cost nothing", 0L, LayerRenderBudgetPolicy.byteSize(0, 2400))
        assertEquals("non-positive dims cost nothing", 0L, LayerRenderBudgetPolicy.byteSize(-1, -1))
    }

    @Test
    fun `the gate math caps adds at 16 and allows everything below`() {
        assertEquals("16 rows IS at the cap", 16, LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT)
        for (n in 0 until LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT) {
            assertFalse("$n layers below the cap add fine", LayerRenderBudgetPolicy.layerLimitReached(n))
            assertTrue("$n layers below the cap may yet add", LayerRenderBudgetPolicy.mayAddLayer(n))
        }
        assertTrue("16 layers blocks another add", LayerRenderBudgetPolicy.layerLimitReached(16))
        assertFalse("16 layers cannot add", LayerRenderBudgetPolicy.mayAddLayer(16))
    }

    @Test
    fun `over-cap counts expose exactly how many layers fold`() {
        assertTrue(LayerRenderBudgetPolicy.isLayerCountCapped(40))
        assertTrue(LayerRenderBudgetPolicy.isLayerCountCapped(17))
        assertFalse(LayerRenderBudgetPolicy.isLayerCountCapped(16))
        assertFalse(LayerRenderBudgetPolicy.isLayerCountCapped(0))
        assertEquals("a crafted 40-layer page folds 24", 24, LayerRenderBudgetPolicy.omittedLayerCount(40))
        assertEquals("at the cap nothing folds", 0, LayerRenderBudgetPolicy.omittedLayerCount(16))
        assertEquals("under the cap nothing folds", 0, LayerRenderBudgetPolicy.omittedLayerCount(3))
    }

    @Test
    fun `the cap keeps the top-zOrder layers with a deterministic rowid tie-break`() {
        val layers = (0 until 40).map { i -> layer(i, zOrder = i) }
        val capped = LayerRenderBudgetPolicy.capToLiveLimit(layers)

        assertEquals("keeps exactly the cap", LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT, capped.size)
        // Highest zOrder rows survive — the visually FRONT-MOST layers hold the
        // newest strokes; bottom (zOrder 0..23) fold.
        assertEquals((24..39).map { "layer_$it" }, capped.map { it.id })
        assertEquals("output is ascending zOrder for the editor path", 24, capped.first().zOrder)
        assertEquals("output max is the top layer", 39, capped.last().zOrder)
        assertFalse(capped.any { it.zOrder < 24 })
    }

    @Test
    fun `equal zOrder rows prune by rowid and under-cap pages are untouched`() {
        // All 40 layers share zOrder 0 — the keep-set must be the LAST-INSERTED 16
        // (highest rowid), exactly like `ORDER BY zOrder DESC, rowid DESC LIMIT 16`.
        val tie = (0 until 40).map { i -> layer(i, zOrder = 0) }
        val kept = LayerRenderBudgetPolicy.capToLiveLimit(tie)
        assertEquals(LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT, kept.size)
        // Ties break on list order descending (the rowid DESC of the SQL): the
        // LAST-INSERTED 16 survive, still ordered highest-rowid first (Kotlin's
        // stable sort keeps tie order).
        assertEquals((39 downTo 24).map { "layer_$it" }, kept.map { it.id })

        val small = (0 until 5).map { i -> layer(i, zOrder = i * 2) }
        assertEquals("a 5-layer page is untouched", small.map { it.id }, LayerRenderBudgetPolicy.capToLiveLimit(small).map { it.id })
        assertEquals(0, LayerRenderBudgetPolicy.omittedLayerCount(5))
    }

    @Test
    fun `the bounded DAO SQL and the restore SQL share the cap ordering`() {
        val dao = LayerRenderBudgetPolicy.BOUNDED_TOP_LAYERS_ROOM_SQL
        assertTrue("the DAO read keeps the top N", dao.contains("LIMIT :limit"))
        assertTrue("top by zOrder, ties by rowid — the SAME ordering the pure model proves", dao.contains("ORDER BY zOrder DESC, rowid DESC"))
        assertTrue("scoped to the page", dao.contains("pageId = :pageId"))
        assertFalse("the LIMIT is bound, never interpolated", dao.contains("LIMIT 16"))

        val raw = LayerRenderBudgetPolicy.KEEP_HIGHEST_Z_LAYERS_RAW_SQL
        assertTrue("restore is a DELETE, not a SELECT", raw.startsWith("DELETE FROM layers WHERE pageId = ?"))
        assertTrue("the keep-set is the top window by the same ordering", raw.contains("ORDER BY zOrder DESC, rowid DESC LIMIT ?"))
        assertTrue("everything below folds", raw.contains("id NOT IN"))
        assertTrue("the keep-set is scoped to the same page", raw.contains("SELECT id FROM layers WHERE pageId = ?"))
        assertFalse("the keep count is a bound parameter", raw.contains("LIMIT 16"))
    }

    @Test
    fun `the fake DAO bounded read returns the same top-16 the policy models`() {
        // Simulates `getTopLayersForPageBounded(pageId, MAX_LIVE_LAYER_COUNT)`:
        // ORDER BY zOrder DESC, rowid DESC LIMIT 16, then the repo's ascending
        // re-sort for the editor.
        val rows = rowsOfZ(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39)
        val topBySql = rows.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            .take(LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT)
            .sortedBy { it.second }
        assertEquals(16, topBySql.size)
        assertEquals("the front-most zOrder 24..39 survive in ascending order",
            (24L..39L).map { it.toInt() }, topBySql.map { it.second })
        assertEquals("the repo's ascending re-sort matches the editor path",
            (24..39).toList(), topBySql.map { it.second })
        assertFalse(topBySql.any { it.second < 24 })
    }

    @Test
    fun `the resident byte budget evicts the coldest entries to hold 64 MB`() {
        // Pure-JVM mirror of LayerBitmapLruCache.put()'s accounting (the class
        // itself is Android-bound via ImageBitmap/BitmapPool).
        val pageBytes = LayerRenderBudgetPolicy.byteSize(1080, 2400) // 10_368_000
        val capacity = (LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES / pageBytes).toInt()

        // Simulate drawing 12 pages one after another (never re-visited): the
        // pre-fix map held all 12 (~124 MB); the budgeted LRU keeps the newest.
        var bytes = 0L
        val warmestFirst = mutableListOf<String>() // most recently used at the tail
        for (i in 0 until 12) {
            warmestFirst.add("p$i")
            bytes += pageBytes
            while (bytes > LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES) {
                val evicted = warmestFirst.removeAt(0) // coldest edge
                bytes -= pageBytes
                assertTrue("the coldest bitmap is released back to the pool", evicted != "p11")
            }
        }
        assertTrue("resident raster stays within the byte budget", bytes <= LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES)
        assertEquals("~6 pages survive at 1080x2400", capacity, warmestFirst.size)
        assertEquals("the most recently drawn pages are retained", (6..11).map { "p$it" }, warmestFirst)
    }

    // ---- Phase-150 review fix 1: protected-page eviction --------------------

    @Test
    fun `the protected-page eviction keeps a drawn page's whole layer stack`() {
        val pageBytes = LayerRenderBudgetPolicy.byteSize(1080, 2400)
        var bytes = 0L
        var lru = listOf<Pair<String, Long>>() // cold -> warm, the LRU's map order
        for (i in 0 until LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT) {
            val key = "pageA_layer_${i}_OFF_v0.0" // mirrors the real AnnotationCanvas cache key
            lru = lru + (key to pageBytes)
            bytes += pageBytes
            val keep = LayerRenderBudgetPolicy.resolveProtectedEviction(
                lru.map { it.first },
                lru.map { it.second },
                protectedPage = LayerRenderBudgetPolicy.pageKeyOf(key),
                residentBytes = bytes
            )
            val removed = lru.map { it.first } - keep
            lru = lru.filter { it.first in keep }
            bytes -= removed.size * pageBytes
        }
        // Review fix: evicting the ACTIVE page's own stack mid-draw was turning a
        // legit 16-layer note into per-frame re-rasterization. All 16 survive one
        // draw pass even though 16 x 10_368_000 = 166 MB > the 64 MB budget.
        assertEquals("the full top-16 stack of the active page survives one pass", LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT, lru.size)
        assertEquals("resident holds all 16 (bounded by the layer cap, not per-frame churn)", 16L * pageBytes, bytes)
        assertEquals("the page token parses out of the cache key", "pageA", LayerRenderBudgetPolicy.pageKeyOf(lru.first().first))
    }

    @Test
    fun `cross-page evictions still hold the 64 MB byte budget`() {
        val pageBytes = LayerRenderBudgetPolicy.byteSize(1080, 2400)
        val capacity = (LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES / pageBytes).toInt()
        var bytes = 0L
        var lru = listOf<Pair<String, Long>>()
        for (i in 0 until 12) {
            val key = "p$i"
            lru = lru + (key to pageBytes)
            bytes += pageBytes
            val keep = LayerRenderBudgetPolicy.resolveProtectedEviction(
                lru.map { it.first },
                lru.map { it.second },
                protectedPage = LayerRenderBudgetPolicy.pageKeyOf(key),
                residentBytes = bytes
            )
            val removed = lru.map { it.first } - keep
            lru = lru.filter { it.first in keep }
            bytes -= removed.size * pageBytes
        }
        assertTrue("resident raster stays within the byte budget across pages", bytes <= LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES)
        assertEquals("only the recently drawn pages survive", capacity, lru.size)
        assertEquals("the recently drawn pages survive in order", (6..11).map { "p$it" }, lru.map { it.first })
    }

    @Test
    fun `the LRU executes the policy's protected eviction decision and releases to the pool`() {
        val lru = sourceFile("ui/components/LayerBitmapLruCache.kt")
        assertTrue("the keep decision is the policy's protected resolver", lru.contains("LayerRenderBudgetPolicy.resolveProtectedEviction("))
        assertTrue("the active page token comes from the policy", lru.contains("LayerRenderBudgetPolicy.pageKeyOf(activeKey)"))
        assertTrue("evicted bitmaps go back to the pool", lru.contains("BitmapPool.release("))
        assertTrue("the read still moves the entry to the warm head", lru.contains("fun get(key: String): LayerBitmapCache? = map[key]"))
    }

    @Test
    fun `byte-size accounting reports overages and the budget reads sane at the edges`() {
        val pageBytes = LayerRenderBudgetPolicy.byteSize(1080, 2400) // 10_368_000
        // Two pages (20.7 MB) and one page both sit well under the 64 MB budget.
        assertEquals(0L, LayerRenderBudgetPolicy.overageBytes(pageBytes, pageBytes))
        assertFalse(LayerRenderBudgetPolicy.wouldExceedResidentBudget(pageBytes, pageBytes))
        assertFalse(LayerRenderBudgetPolicy.wouldExceedResidentBudget(pageBytes * 5, pageBytes)) // 62.2 MB < 64 MB
        // Six pages (~62.2 MB) plus one more (~72.6 MB) clearly overflows.
        assertTrue(LayerRenderBudgetPolicy.wouldExceedResidentBudget(pageBytes * 6, pageBytes))
        // Starting exactly AT the budget, the next acquire busts it by one page.
        assertEquals(pageBytes, LayerRenderBudgetPolicy.overageBytes(
            LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES,
            pageBytes
        ))
        assertEquals("an empty cache never overflows", 0L, LayerRenderBudgetPolicy.overageBytes(0L, pageBytes))
    }

    @Test
    fun `the notices are non-alarming and state the honest facts`() {
        assertTrue(LayerRenderBudgetPolicy.layerLimitNotice().contains("16"))
        assertTrue(LayerRenderBudgetPolicy.layerLimitNotice().contains("PSD export"))
        val folded = LayerRenderBudgetPolicy.layersCappedNotice(16, 24)
        assertTrue("mentions the count folded", folded.contains("24"))
        assertTrue("mentions the cap", folded.contains("16"))
        assertFalse("no-alarm tone", folded.contains("fatal") || folded.contains("error"))
        val fine = LayerRenderBudgetPolicy.layersCappedNotice(16, 0)
        assertTrue(fine.contains("16"))
    }

    // ---------------------------------------------------------------------
    // R2-b2b5-FEA-04: world-height ceiling + page-count clamp
    // ---------------------------------------------------------------------

    @Test
    fun `a crafted 1e9 stroke Y is clamped to the world ceiling`() {
        val pageStride = 1528f + 64f // pageHeightPx + pageGapPx default-ish
        val rawMaxY = 1_000_000_000f
        // Pre-fix derivation -> ~628,141 pages.
        val preFixPages = (rawMaxY / pageStride).toInt() + 1
        assertTrue("pre-fix derived an absurd page count", preFixPages > 600_000)

        val clamped = CanvasPageBudgetPolicy.clampMaxStrokeY(rawMaxY, pageStride)
        assertEquals("clamped to exactly the ceiling", CanvasPageBudgetPolicy.maxStrokeYCeiling(pageStride), clamped)
        val derived = CanvasPageBudgetPolicy.calculatedPagesFor(clamped, pageStride)
        assertTrue("the raw derivation at the ceiling overflows by one (2001)", derived > CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES)
        assertEquals(
            "the render count is the clamped value",
            CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES.toLong(),
            CanvasPageBudgetPolicy.clampCalculatedPages(derived).toLong()
        )
        assertEquals(
            "the render loop gets exactly the ceiling after the clamp",
            CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES.toLong(),
            CanvasPageBudgetPolicy.clampCalculatedPages(CanvasPageBudgetPolicy.calculatedPagesFor(clamped, pageStride)).toLong()
        )
    }

    @Test
    fun `non-finite and extreme stroke Ys collapse safely`() {
        assertEquals(0f, CanvasPageBudgetPolicy.clampMaxStrokeY(Float.NaN, 1592f))
        assertEquals(0f, CanvasPageBudgetPolicy.clampMaxStrokeY(Float.POSITIVE_INFINITY, 1592f))
        assertEquals(
            CanvasPageBudgetPolicy.maxStrokeYCeiling(1592f),
            CanvasPageBudgetPolicy.clampMaxStrokeY(1e18f, 1592f)
        )
        assertEquals("a sub-stride Y still derives one page", 1, CanvasPageBudgetPolicy.calculatedPagesFor(0.5f, 1592f))
        assertEquals("a zero stride cannot divide (fails to one page)", 1, CanvasPageBudgetPolicy.calculatedPagesFor(9_999f, 0f))
        assertEquals("a non-finite max derives one page", 1, CanvasPageBudgetPolicy.calculatedPagesFor(Float.NaN, 1592f))
        assertEquals(
            "at the ceiling the clamped render count is the ceiling",
            CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES.toLong(),
            CanvasPageBudgetPolicy.clampCalculatedPages(
                CanvasPageBudgetPolicy.calculatedPagesFor(CanvasPageBudgetPolicy.maxStrokeYCeiling(1592f), 1592f)
            ).toLong()
        )
    }

    @Test
    fun `ordinary documents are untouched by the ceiling`() {
        val pageStride = 1592f
        val maxY = 40_000f // ~25 pages of real writing
        val clamped = CanvasPageBudgetPolicy.clampMaxStrokeY(maxY, pageStride)
        assertEquals("clamping never folds a sane document", maxY, clamped)
        val pages = CanvasPageBudgetPolicy.calculatedPagesFor(clamped, pageStride)
        assertTrue("the sane document derives a sane count", pages in 1 until CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES)
    }

    // ---------------------------------------------------------------------
    // R2-b2b4-DOS-03: minimap per-frame work budget
    // ---------------------------------------------------------------------

    @Test
    fun `the minimap pass is bounded regardless of the geometry cap`() {
        // The phase-50 geometry cap is ~200k points; the pre-fix stride honored it
        // with ~50k drawLine calls (>> a frame budget). Now it settles far under.
        val budgeted = MinimapGeometryPolicy.maxLineDraws(strokeCount = 4_000, totalPoints = 200_000)
        assertTrue(
            "the budgeted pass is bounded by the two minimap budgets",
            budgeted <= MinimapGeometryPolicy.MAX_MINIMAP_SAMPLED_STROKES + MinimapGeometryPolicy.MAX_MINIMAP_POLYLINE_SEGMENTS
        )
        // Worst case: 120 sampled strokes + <=400 poly-line segments.
        assertEquals(MinimapGeometryPolicy.MAX_MINIMAP_SAMPLED_STROKES + MinimapGeometryPolicy.MAX_MINIMAP_POLYLINE_SEGMENTS, 520)
        assertTrue("200k points drew ~50k lines pre-fix and ~400 after", budgeted <= 520)
    }

    @Test
    fun `the stride math samples at most the stroke cap and stays correct under it`() {
        assertEquals(1, MinimapGeometryPolicy.strokeStepFor(10))
        assertEquals(5, MinimapGeometryPolicy.strokeStepFor(500))
        assertEquals(34, MinimapGeometryPolicy.strokeStepFor(4000))
        assertEquals("90 strokes stay under the sampling cap", 90, MinimapGeometryPolicy.sampledStrokeCount(90))
        assertEquals("4k strokes sample at most 120", 120, MinimapGeometryPolicy.sampledStrokeCount(4_000))
        assertEquals("empty canvas draws nothing pathological", 0, MinimapGeometryPolicy.sampledStrokeCount(0))
    }

    @Test
    fun `small documents stepped at one are unchanged`() {
        assertEquals(1, MinimapGeometryPolicy.pointStepFor(50))
        assertEquals("every point of a 50-point page is retained (stride 1)", 50, (50 - 1) / 1 + 1)
        assertEquals(1, MinimapGeometryPolicy.strokeStepFor(120))
        // 50 total points -> step 1 => the polyline pass = 49 segments, all inside budget.
        assertTrue(49 <= MinimapGeometryPolicy.MAX_MINIMAP_POLYLINE_SEGMENTS)
    }

    // ---------------------------------------------------------------------
    // source pins: the Android wiring realizes the pure policies
    // ---------------------------------------------------------------------

    @Test
    fun `the DAO binds the policy literal into the bounded top-layer read`() {
        val daos = sourceFile("data/db/Daos.kt")
        assertTrue(
            "the bounded read is the policy's single ordering literal",
            daos.contains("@Query(LayerRenderBudgetPolicy.BOUNDED_TOP_LAYERS_ROOM_SQL)")
        )
        assertTrue("the raw count method exists for the notice", daos.contains("suspend fun countLayersForPage(pageId: String): Int"))
        assertTrue("the DAO imports the policy", daos.contains("import com.authorss81.noteflow.services.LayerRenderBudgetPolicy"))
    }

    @Test
    fun `the repository load caps the layers and survives a 40-row crafted page`() {
        val repo = sourceFile("data/repository/NoteRepository.kt")
        val region = repo.substringAfter("suspend fun getLayersForPage").substringBefore("suspend fun getLayerCountForPage")
        assertTrue("the live read is the bounded top-layer query", region.contains("getTopLayersForPageBounded("))
        assertTrue("the bounded read passes the policy cap", region.contains("LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT"))
        assertTrue("the read re-sorts ascending for the editor", region.contains(".sortedBy { it.zOrder }"))
        assertTrue("a genuinely empty page still creates the default layer", region.contains("LayerEntity(") && region.contains("insertLayer(defaultLayer)"))
        assertTrue("the count accessor exists", repo.contains("suspend fun getLayerCountForPage(pageId: String): Int"))
        // Phase-150 review fix 2: capToLiveLimit is LIVE over the bounded read,
        // not a test-only mirror — a 40-row crafted page still prunes the same way.
        assertTrue("the pure policy model runs live over the bounded read", region.contains("capToLiveLimit(it)"))
    }

    @Test
    fun `the legacy unbounded DAO read is never used by the repository`() {
        val repo = sourceFile("data/repository/NoteRepository.kt")
        val loader = repo.substringAfter("suspend fun getLayersForPage").substringBefore("suspend fun getLayerCountForPage")
        assertTrue("the repo never calls the legacy unbounded DAO method", !loader.contains("getLayersForPage(pageId)"))
        assertTrue("the repo calls only the bounded top-layer read", loader.contains("getTopLayersForPageBounded("))
        val daos = sourceFile("data/db/Daos.kt")
        assertTrue("the legacy unbounded read stays for API compat but has no live caller", daos.contains("suspend fun getLayersForPage(pageId: String): List<LayerEntity>"))
    }

    @Test
    fun `the editor gates add and duplicate at the cap and fails closed`() {
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val add = editor.substringAfter("fun onAddLayer()").substringAfter("R2-b2b4-DOS-02").substringBefore("fun onUpdateLayer")
        assertTrue("add checks the cap before anything else", add.contains("LayerRenderBudgetPolicy.layerLimitReached(layers.size)"))
        assertTrue("add shows the non-alarming notice and returns", add.contains("viewModel.showSnackbar(LayerRenderBudgetPolicy.layerLimitNotice(), isLong = true)"))
        assertTrue("add fails closed (returns) instead of silently adding", add.contains("return"))

        val dup = editor.substringAfter("fun onDuplicateLayer").substringAfter("R2-b2b4-DOS-02").substringBefore("fun onMergeDown")
        assertTrue("duplicate checks the same cap", dup.contains("LayerRenderBudgetPolicy.layerLimitReached(layers.size)"))
        assertTrue("duplicate shows the same non-alarming notice", dup.contains("LayerRenderBudgetPolicy.layerLimitNotice()"))
    }

    @Test
    fun `the ViewModel raises the one-time layers-capped notice on load and clears at lock`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val loader = vm.substringAfter("suspend fun loadEditorCanvasPage").substringBefore("suspend fun loadAllActivePages")
        assertTrue("the loader compares the raw count against the retained list", loader.contains("repository.getLayerCountForPage(pageId)"))
        assertTrue("the notice runs through the one-time gate", loader.contains("maybeNotifyLayersCapped("))
        assertTrue("the folder count comes from the policy", loader.contains("LayerRenderBudgetPolicy.omittedLayerCount") || loader.contains("LayerRenderBudgetPolicy.omittedLayerCount("))
        assertTrue("the one-time set exists and is keyed per page", vm.contains("layerCappedNotifiedPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()"))
        assertTrue("the lock path clears the session gate", vm.contains("layerCappedNotifiedPages.clear()"))
        // Phase-150 review fix 6: the RAW count is read BEFORE the bounded load
        // (so a genuinely empty page's post-insert default layer can't skew it).
        val countIdx = loader.indexOf("repository.getLayerCountForPage(pageId)")
        val loadIdx = loader.indexOf("repository.getLayersForPage(pageId)")
        assertTrue("raw count is read before the bounded layer load", countIdx != -1 && loadIdx != -1 && countIdx < loadIdx)
    }

    @Test
    fun `the renderer raster cache is the bounded LRU and clamps the page count`() {
        val canvas = sourceFile("ui/components/AnnotationCanvas.kt")
        assertTrue("the resident rasters live in the bounded LRU", canvas.contains("com.authorss81.noteflow.ui.components.LayerBitmapLruCache()"))
        assertTrue("the draw entry uses get/put with the LRU", canvas.contains("layerBitmapCache.get(cacheKey)"))
        assertTrue("put goes through the eviction budget", canvas.contains("layerBitmapCache.put(cacheKey, cache)"))
        assertTrue("the world-Y clamp is wired before the page math", canvas.contains("CanvasPageBudgetPolicy.clampMaxStrokeY(maxStrokeY, pageStride)"))
        assertTrue("the derived count is clamped", canvas.contains("CanvasPageBudgetPolicy.clampCalculatedPages("))
    }

    @Test
    fun `the one-time page-count-capped notice is wired through canvas to the editor snackbar`() {
        // Phase-150 review fix 4: the clamp is never SILENT — a note whose own
        // strokes stretch past the world ceiling raises a one-time non-alarming
        // notice, distinct from deep panning (visibleBottomY).
        val canvas = sourceFile("ui/components/AnnotationCanvas.kt")
        assertTrue("the canvas exposes the capped callback (default no-op)", canvas.contains("onDynamicPageCountCapped: (() -> Unit)? = null"))
        assertTrue("the canvas derives the own-content-beyond flag separately", canvas.contains("ownContentBeyondCeiling"))
        assertTrue("the one-time notify flag is remembered", canvas.contains("pageCountCappedNotified"))
        assertTrue("the fold is only flagged for OWN content, not pan depth", canvas.contains("maxStrokeY > com.authorss81.noteflow.services.CanvasPageBudgetPolicy.maxStrokeYCeiling(pageStride)"))

        val editor = sourceFile("ui/screens/EditorScreen.kt")
        assertTrue("the editor wires the notice through the snackbar", editor.contains("CanvasPageBudgetPolicy.pageCountCappedNotice()"))
        assertTrue("the editor passes the callback to the canvas", editor.contains("onDynamicPageCountCapped = {"))

        val policy = sourceFile("services/CanvasPageBudgetPolicy.kt")
        assertTrue("the notice text lives in the policy", policy.contains("fun pageCountCappedNotice()"))
        assertTrue("the notice states the ceiling instead of alarming", policy.contains("at most \$MAX_DYNAMIC_PAGES pages"))
    }

    @Test
    fun `the per-page stroke walk is grouped once and the minimap pass is budgeted`() {
        val canvas = sourceFile("ui/components/AnnotationCanvas.kt")
        val pageDiv = canvas.substringAfter("val strokesByPage = activeStrokeList.groupBy { it.pdfPage }").substringBefore("drawCompositedLayersStrokes")
        assertTrue("a hoisted groupBy replaces the per-page filter", pageDiv.contains("strokesByPage[pageIdx] ?: emptyList()"))

        val minimapRegion = canvas.substringAfter("val strokeStep = MinimapGeometryPolicy.strokeStepFor(strokeCount)")
            .substringBefore("val viewWOnCanvas")
        assertTrue("the minimap iterates by the budgeted stroke stride", minimapRegion.contains("step strokeStep"))
        assertTrue("the minimap derives a global point stride from the total", minimapRegion.contains("MinimapGeometryPolicy.pointStepFor(totalPoints)"))
        assertTrue("the minimap iterates by the budgeted point stride", minimapRegion.contains("step pointStep"))
        assertTrue("the total point count is summed once for the stride", canvas.contains("activeStrokeList.sumOf { it.points.size }"))
        assertFalse("the fixed 1/2/4 point stride is gone", minimapRegion.contains("val pStep = if (pCount > 100) 4"))
        // Phase-150 review fix 5: a short stroke whose own point count is
        // overshot by the GLOBAL stride gets a single fallback line instead of
        // vanishing from the thumbnail.
        assertTrue("short strokes get a start-to-end fallback line", minimapRegion.contains("if (!drew)"))
    }

    @Test
    fun `the restore sanitizer strips crafted layers under the candidate key and the export trims the staged snapshot`() {
        val service = sourceFile("services/ImportExportService.kt")

        val validate = service.substringAfter("private fun validateAndPrepareRestoredDb").substringBefore("private fun rekeyVoiceNoteBlobs")
        assertTrue("restore runs the layer sanitizer under the candidate key", validate.contains("sanitizeRestoredLayerCounts(db)"))

        val sanitizer = service.substringAfter("private fun pruneLayerPagesToLiveCap").substringBefore("private fun sanitizeRestoredSourceFilePaths")
        assertTrue("the sanitizer core iterates every distinct page", sanitizer.contains("SELECT DISTINCT pageId FROM layers"))
        assertTrue("the sanitizer shares the policy's keep-SQL", sanitizer.contains("LayerRenderBudgetPolicy.KEEP_HIGHEST_Z_LAYERS_RAW_SQL"))
        assertTrue("the keep count is the policy cap", sanitizer.contains("LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT"))

        val wrapper = service.substringAfter("private fun sanitizeRestoredLayerCounts").substringBefore("private fun pruneStagedSnapshotLayers")
        assertTrue("a missing table is tolerated like the other sanitizers", wrapper.contains("shouldPropagateRestoreStripFailure"))
        assertTrue("the restore sanitizer delegates to the shared prune core", wrapper.contains("pruneLayerPagesToLiveCap(db)"))

        val staged = service.substringAfter("private fun pruneStagedSnapshotLayers").substringBefore("private fun sanitizeRestoredSourceFilePaths")
        assertTrue("the staged prune opens only the snapshot copy", staged.contains("stagedDb, passphrase"))
        assertTrue("the staged prune is keyed by the in-memory DEK", staged.contains("VaultKeyHolder.dek"))
        assertTrue("the staged prune never touches the live repository", !staged.contains("repository."))
        // Phase-150 review fix 3: a crafted/pre-schema archive with no `layers`
        // table must not abort the whole backup — same tolerance as the restore
        // sanitizer (real failures still abort).
        assertTrue("the staged prune tolerates a missing layers table", staged.contains("shouldPropagateRestoreStripFailure"))
    }

    // ---------- helpers ----------

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}