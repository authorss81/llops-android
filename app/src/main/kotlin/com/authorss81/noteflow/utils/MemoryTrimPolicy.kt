package com.authorss81.noteflow.utils

/**
 * Phase 269 (compat): single threshold for memory-pressure cache clearing.
 *
 * Pre-269 `MainActivity.onTrimMemory` cleared the 64 MB `BitmapPool` only at
 * `TRIM_MEMORY_BACKGROUND` (40) / `RUNNING_CRITICAL` (15), so a
 * `RUNNING_LOW` (10) / `RUNNING_MODERATE` (5) squeeze OOMed while the pool
 * still held tens of MB of pooled rasters. The pool (plus the small
 * `PaperGrainTileCache`) now clears at every level >= [CLEAR_AT_LEVEL].
 *
 * Phase-269 review-fix note: `RUNNING_MODERATE` (5) is DELIBERATELY excluded.
 * Level 5 is a light hint ("things are fine, start tidying"), not pressure —
 * dropping tens of MB of warm rasters there would churn every minor squeeze
 * for no OOM gain. The first level that signals real pressure is
 * `RUNNING_LOW` (10), and everything at or above it clears.
 *
 * Framework values mirrored as literals (kept in sync with
 * `android.content.ComponentCallbacks2`):
 * RUNNING_MODERATE = 5, RUNNING_LOW = 10, RUNNING_CRITICAL = 15,
 * UI_HIDDEN = 20, BACKGROUND = 40, MODERATE = 60, COMPLETE = 80.
 *
 * Pure JVM so the threshold is source-pinned by `Phase269CompatTest`.
 */
object MemoryTrimPolicy {

    /** Lowest trim level that drops cached bitmaps (RUNNING_LOW). */
    const val CLEAR_AT_LEVEL = 10

    /** True when [level] must release `BitmapPool` + grain tiles. */
    fun shouldClearCaches(level: Int): Boolean = level >= CLEAR_AT_LEVEL
}
