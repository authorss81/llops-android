package com.authorss81.noteflow.services

/**
 * Floating ink-bar drawing-yield decision (Phase 244, Bug 2).
 *
 * When the floating ink bar is positioned in the TOP half of the canvas and the
 * user switches to a drawing tool (freehand ink or a shape), the bar overlaps
 * exactly the strip of canvas the user is trying to draw on — its buttons /
 * drag gesture swallow the touch, so a stroke can't begin there. The fix is to
 * yield that strip back to the canvas: while a drawing tool is active AND the
 * bar would rest in the top half, the bar rests at its default bottom anchor
 * instead. Switching back to a navigation tool (pan / select) returns the bar
 * to the user's own dragged position.
 *
 * Pure JVM so the decision is unit-testable without a UI.
 */
object InkBarDrawingPolicy {

    /** A freehand ink or geometry tool draws strokes on the canvas. */
    fun isDrawingTool(drawingToolActive: Boolean): Boolean = drawingToolActive

    /**
     * Whether the bar should yield the drawing area to the canvas.
     *
     * True only when a drawing tool is active AND the bar's resting top edge is
     * in the top half of the available height ([barTopY] < [availableHeight]/2).
     * A bottom-resting bar is already out of the way of top drawing, so it is
     * never nudged.
     */
    fun shouldYieldDrawingArea(
        drawingToolActive: Boolean,
        barTopY: Float,
        availableHeight: Float
    ): Boolean {
        if (!isDrawingTool(drawingToolActive)) return false
        // No real canvas to yield on a degenerate/zero-height region.
        if (availableHeight <= 0f) return false
        val h = availableHeight.coerceAtLeast(1f)
        val topY = barTopY.coerceAtLeast(0f)
        return topY < h / 2f
    }
}
