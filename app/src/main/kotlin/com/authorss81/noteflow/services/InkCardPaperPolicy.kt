package com.authorss81.noteflow.services

/**
 * Phase 187 — gallery ink-card paper-texture policy.
 *
 * Pure-JVM decision table + geometry for the notebook-paper look of ink-note
 * gallery cards that have no extracted OCR text (so there is nothing honest to
 * show as a text preview). All colour ALPHAS, the honest label, the dot-grid
 * pattern constants and the BOUNDED grid geometry live here so the draw path is
 * testable without Compose and a reviewer can't reintroduce inline literals
 * (or an unbounded draw loop) in `GalleryView`.
 *
 * Draw-budget discipline (AGENTS.md low-end rule + phase-188 risk #1): the grid
 * is a dot-grid drawn with `DrawScope` primitives — bounded at
 * [MAX_GRID_COLUMNS] × [MAX_GRID_ROWS] ≤ 96 dots total, NO list/object
 * allocation per frame, NO `pointsJson` rasterization (the card texture is
 * derived purely from card size + constants, never from stroke geometry).
 *
 * The label is deliberately HONEST: "Handwritten note" — it never claims OCR
 * text exists when it doesn't.
 */
object InkCardPaperPolicy {

    /** Honest label for a card that has real ink strokes but no OCR text. */
    const val HANDWRITTEN_LABEL = "Handwritten note"

    /** Paper-fill alpha painted over the card's `surfaceVariant` container. */
    const val PAPER_BACKGROUND_ALPHA = 0.7f

    /** Alpha applied to `scheme.outlineVariant` for the dot-grid ink. */
    const val GRID_ALPHA = 0.3f

    /** Dot-grid spacing in dp (scaled to px via `LocalDensity` once, off-draw). */
    const val GRID_SPACING_DP = 22f

    /** Dot radius in dp (scaled to px once, off-draw). */
    const val DOT_RADIUS_DP = 1.5f

    /** Hard caps keeping the draw loop tiny even on large/tablet cards. */
    const val MAX_GRID_COLUMNS = 12
    const val MAX_GRID_ROWS = 8

    /** Worst-case dot budget for a full card = 12 × 8 = 96 draws. */
    const val MAX_DOT_COUNT = MAX_GRID_COLUMNS * MAX_GRID_ROWS

    private val NON_INK_FILE_TYPES = setOf("pdf", "image", "text")

    /** A page whose body is handwritten canvas strokes (no import file type). */
    fun isInkCanvasPage(sourceFileType: String?): Boolean =
        sourceFileType == null || sourceFileType !in NON_INK_FILE_TYPES

    /**
     * Bounded column count of the dot grid for a card of `sizePx` across at
     * `spacingPx` pitch. Never exceeds [MAX_GRID_COLUMNS]; never below 1;
     * fail-safe for non-positive/NaN inputs.
     */
    fun gridColumns(sizePx: Float, spacingPx: Float): Int {
        if (sizePx <= 0f || sizePx.isNaN()) return 1
        val pitch = if (spacingPx <= 0f || spacingPx.isNaN()) 1f else spacingPx
        return ((sizePx / pitch).toInt() + 1).coerceIn(1, MAX_GRID_COLUMNS)
    }

    /** Bounded row count, mirroring [gridColumns]. */
    fun gridRows(sizePx: Float, spacingPx: Float): Int {
        if (sizePx <= 0f || sizePx.isNaN()) return 1
        val pitch = if (spacingPx <= 0f || spacingPx.isNaN()) 1f else spacingPx
        return ((sizePx / pitch).toInt() + 1).coerceIn(1, MAX_GRID_ROWS)
    }

    /** Total dots for a card of the given size. Upper-bounded by the caps. */
    fun totalDots(widthPx: Float, heightPx: Float, spacingPx: Float): Int =
        gridColumns(widthPx, spacingPx) * gridRows(heightPx, spacingPx)
}