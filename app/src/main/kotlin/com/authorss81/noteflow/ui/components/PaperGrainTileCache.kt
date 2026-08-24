package com.authorss81.noteflow.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.authorss81.noteflow.services.PaperGrainPolicy

/**
 * Phase 200 (PERF 3.3): process-wide cache of the tileable paper-grain noise
 * tiles + their REPEAT `BitmapShader` brushes.
 *
 * "Drawn once, near-zero cost": each tile is generated at most once per
 * process per paper family (light/dark), held in a small access-order LRU
 * bounded by [PaperGrainPolicy.MAX_CACHED_TILES] (worst case
 * [PaperGrainPolicy.maxResidentBytes]), and the matching
 * [android.graphics.BitmapShader] (TileMode REPEAT × REPEAT) is created once
 * alongside it — so the canvas draw path only ever does a single textured
 * round-rect per page card and allocates nothing.
 *
 * Deliberately NOT `LayerBitmapLruCache`/`LayerRenderBudgetPolicy`: that LRU's
 * eviction protector parses layer keys (`<page>_<layer>_…`) to keep the active
 * page resident, which is meaningless for the two grain families; this holder
 * is a tiny standalone LRU instead. Eviction drops references WITHOUT
 * `Bitmap.recycle()` — a previously handed-out brush may still be referenced
 * by a recorded display list, and recycling under it would crash the frame;
 * GC reclaims the pixels once no brush references remain.
 *
 * Mutated only from composition (the `remember { }` blocks in
 * `AnnotationCanvas`), like the other composition-scoped caches.
 */
object PaperGrainTileCache {

    private val tiles = java.util.LinkedHashMap<String, ImageBitmap>(0, 0.75f, true)
    private val brushes = java.util.LinkedHashMap<String, ShaderBrush>(0, 0.75f, true)

    /** Cached (or freshly generated) tile for a paper family. */
    fun tileFor(isDarkPaper: Boolean): ImageBitmap {
        val key = PaperGrainPolicy.cacheKey(isDarkPaper)
        tiles[key]?.let { return it }
        val generated = generateTile(isDarkPaper)
        tiles[key] = generated
        evictBeyondBudget()
        return generated
    }

    /**
     * Cached REPEAT-tiled shader brush for a paper family. Returns null when
     * the policy disables the grain (low-end devices) so callers never pay the
     * generation cost for a texture they will not draw.
     */
    fun brushFor(isDarkPaper: Boolean, enabled: Boolean): ShaderBrush? {
        if (!enabled) return null
        val key = PaperGrainPolicy.cacheKey(isDarkPaper)
        brushes[key]?.let { return it }
        val shader = BitmapShader(
            tileFor(isDarkPaper).asAndroidBitmap(),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT
        )
        val brush = ShaderBrush(shader)
        brushes[key] = brush
        evictBeyondBudget()
        return brush
    }

    /**
     * Test hook: drops every cached tile + brush.
     *
     * Review-fix (phase-200) honesty note: this is deliberately NOT wired to
     * `onTrimMemory` — the LRU is hard-capped at [PaperGrainPolicy.MAX_CACHED_TILES]
     * tiles (≈576 KB worst case), so a trim callback would have nothing to add.
     * Wire it only if `TILE_SIZE_PX` or `MAX_CACHED_TILES` ever grow meaningfully.
     */
    fun clear() {
        tiles.clear()
        brushes.clear()
    }

    fun cachedTileCount(): Int = tiles.size

    private fun evictBeyondBudget() {
        while (tiles.size > PaperGrainPolicy.MAX_CACHED_TILES) {
            val coldest = tiles.keys.firstOrNull() ?: break
            tiles.remove(coldest)
            brushes.remove(coldest)
        }
        while (brushes.size > PaperGrainPolicy.MAX_CACHED_TILES) {
            brushes.remove(brushes.keys.first())
        }
    }

    private fun generateTile(isDarkPaper: Boolean): ImageBitmap {
        val size = PaperGrainPolicy.TILE_SIZE_PX
        val argb = PaperGrainPolicy.speckleRgb(isDarkPaper) or 0xFF000000.toInt()
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val a = PaperGrainPolicy.pixelAlphaAt(
                    PaperGrainPolicy.noiseAt(x, y, isDarkPaper),
                    isDarkPaper
                )
                pixels[i++] = if (a <= 0f) 0 else (argb and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap.asImageBitmap()
    }
}
