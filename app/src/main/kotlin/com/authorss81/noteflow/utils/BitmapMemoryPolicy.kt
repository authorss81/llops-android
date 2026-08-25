package com.authorss81.noteflow.utils

/**
 * Phase-207 (crypto/DB efficiency): pure-JVM decision table for the pooled
 * raster byte budget.
 *
 * The scaling problem: [BitmapPool] capped its retention by COUNT (12) per
 * dimension-key with NO global byte ceiling. One 1080×2400 ARGB_8888 bitmap is
 * ~9.95 MB, so a single key could legally retain >100 MB — and evicted LRU
 * layer rasters keep refilling the pool during canvas scrolling. The old
 * `clear()` fired only from `onTrimMemory`/`onLowMemory`, never at the vault
 * lock boundary.
 *
 * This policy owns the numbers; [BitmapPoolLedger] owns the mechanics and
 * [BitmapPool] executes them. Pure JVM so every gate is unit-testable without
 * Android (no Bitmap under test here).
 */
object BitmapMemoryPolicy {

    /**
     * Total native bytes the pool may retain ACROSS ALL dimension-keys. Sized to
     * match the app's existing resident-raster budget philosophy
     * ([com.authorss81.noteflow.services.LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES]
     * = 64 MB): ~6 full-page 1080×2400 ARGB_8888 buffers, comfortably inside a
     * 1–2 GB device budget, versus the >100 MB PER KEY the count-only cap
     * allowed before.
     */
    const val MAX_POOL_TOTAL_BYTES = 64L * 1024L * 1024L

    /**
     * Approximate bytes per pixel for a Bitmap config name (the string form of
     * [android.graphics.Bitmap.Config]). Unknown/null/HARDWARE configs are
     * charged the ARGB_8888 rate — never an underestimate.
     */
    fun bytesPerPixel(configName: String?): Long = when (configName) {
        "ALPHA_8" -> 1L
        "RGB_565", "ARGB_4444" -> 2L
        "RGBA_1010102" -> 4L
        "RGBA_F16" -> 8L
        else -> 4L // ARGB_8888, HARDWARE, null, anything newer
    }

    /** Native byte size of a [width]×[height] bitmap in [configName] (0 for non-positive dims). */
    fun bitmapBytes(width: Int, height: Int, configName: String?): Long {
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong() * bytesPerPixel(configName)
    }
}
