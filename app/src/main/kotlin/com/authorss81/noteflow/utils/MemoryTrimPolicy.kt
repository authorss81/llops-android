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
