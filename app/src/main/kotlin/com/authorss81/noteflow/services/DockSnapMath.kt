package com.authorss81.noteflow.services

/**
 * Edge-snap math for the floating scribe tool dock (Phase 35).
 *
 * Pure JVM: given the dock's centre on screen, decide which screen edge it
 * snaps to (left / right / bottom) and where exactly it rests once docked,
 * keeping the whole dock inside the screen. The UI then animates to that
 * anchor with a spring (or a snap when reduce-motion is on).
 *
 * Coordinates are in screen pixels; the dock is assumed to be roughly pill
 * shaped with an axis-aligned bounding box of [dockSize].
 */
object DockSnapMath {

    enum class DockEdge { START, END, BOTTOM }

    data class DockAnchor(
        val edge: DockEdge,
        /** Content-top-left in screen pixels once docked. */
        val x: Float,
        val y: Float,
        /** 0..1 progress along the edge (top->bottom for START/END, left->right for BOTTOM). */
        val fraction: Float
    )

    /** Nearest-edge classification for a dock centre inside [screenSize]. */
    fun nearestEdge(centreX: Float, centreY: Float, screenW: Float, screenH: Float): DockEdge {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val toLeft = centreX.coerceAtLeast(0f)
        val toRight = (sw - centreX).coerceAtLeast(0f)
        val toBottom = (sh - centreY).coerceAtLeast(0f)
        // Ties prefer START (left) so the classification is deterministic.
        if (toLeft <= toRight && toLeft <= toBottom) return DockEdge.START
        if (toRight <= toBottom) return DockEdge.END
        return DockEdge.BOTTOM
    }

    /**
     * Decide the resting anchor for the dock whose *centre* is currently at
     * [centre] within [screenSize]. [marginPx] is the minimum gap to the edge,
     * [dockSize] the dock's bounding box. Returns the anchor with content
     * top-left [x], [y] and the along-edge [fraction].
     */
    fun snap(
        centre: Offset, // androidx-free: use a tiny holder
        screenW: Float,
        screenH: Float,
        marginPx: Float,
        dockW: Float,
        dockH: Float
    ): DockAnchor {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val m = marginPx.coerceAtLeast(0f)
        val dw = dockW.coerceAtLeast(1f)
        val dh = dockH.coerceAtLeast(1f)
        val edge = nearestEdge(centre.x, centre.y, sw, sh)
        return when (edge) {
            DockEdge.START -> {
                val maxY = (sh - dh - m).coerceAtLeast(m)
                val yTop = centre.y.coerceIn(m, maxY)
                DockAnchor(edge, m, yTop, ((yTop - m) / (maxY - m).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            DockEdge.END -> {
                val maxY = (sh - dh - m).coerceAtLeast(m)
                val yTop = centre.y.coerceIn(m, maxY)
                DockAnchor(edge, (sw - dw - m).coerceAtLeast(m), yTop, ((yTop - m) / (maxY - m).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            DockEdge.BOTTOM -> {
                val maxX = (sw - dw - m).coerceAtLeast(m)
                val xLeft = centre.x.coerceIn(m, maxX)
                DockAnchor(edge, xLeft, (sh - dh - m).coerceAtLeast(m), ((xLeft - m) / (maxX - m).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
        }
    }

    /** Keep a free-dragged dock fully inside the screen whilst it is being moved. */
    fun constrainInside(x: Float, y: Float, screenW: Float, screenH: Float, dockW: Float, dockH: Float): Offset {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val dw = dockW.coerceAtLeast(0f)
        val dh = dockH.coerceAtLeast(0f)
        return Offset(
            x = x.coerceIn(0f, (sw - dw).coerceAtLeast(0f)),
            y = y.coerceIn(0f, (sh - dh).coerceAtLeast(0f))
        )
    }

    /** Freedom-friendly tiny 2D holder so the math needs no Compose import. */
    data class Offset(val x: Float, val y: Float) {
        companion object { val Zero = Offset(0f, 0f) }
    }
}