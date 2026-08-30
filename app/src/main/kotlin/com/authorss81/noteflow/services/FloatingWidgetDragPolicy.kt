package com.authorss81.noteflow.services

/**
 * Drag-gate and resting-position decision table for the floating ink bar and
 * the minimap (Phase 129).
 *
 * Pure JVM. Both floating widgets are draggable ONLY when the user opts in via
 * a SettingsManager boolean (both default OFF). This policy owns the three
 * opt-in gates (drag / snap-to-edge / cross-session dock persistence), the
 * "dragged offset vs default anchor" resting-position choice, and the
 * safe-area constraint used while dragging so a widget never clips outside
 * the safe insets.
 *
 * The drag offset itself is session-scoped state held by the composable; this
 * object only decides whether/how to apply it.
 */
object FloatingWidgetDragPolicy {

    const val INK_BAR_DRAGGABLE_DEFAULT = false
    const val INK_BAR_SNAP_TO_EDGE_DEFAULT = false
    const val INK_BAR_DOCK_PERSIST_DEFAULT = false
    const val MINIMAP_DRAGGABLE_DEFAULT = false

    /** Small immutable 2D holder so no Compose import leaks into the policy. */
    data class Offset(val x: Float, val y: Float) {
        companion object {
            val Zero = Offset(0f, 0f)
        }
    }

    /** Settings gate — fail closed: dragging is off unless explicitly enabled. */
    fun mayDrag(enabled: Boolean): Boolean = enabled

    /** Settings gate — the edge snap on release requires explicit opt-in. */
    fun maySnapToEdge(enabled: Boolean): Boolean = enabled

    /** Settings gate — cross-session persistence requires explicit opt-in. */
    fun mayPersistDock(enabled: Boolean): Boolean = enabled

    /** True when a previously persisted offset is present (both coords >= 0). */
    fun hasPersistedOffset(x: Float, y: Float): Boolean = x >= 0f && y >= 0f

    /**
     * A dragged offset is applied only when the gate is open AND the user has
     * actually dragged (session offset present). Without both, the widget
     * rests at its default anchor.
     */
    fun shouldApplyDraggedPosition(enabled: Boolean, hasDraggedOffset: Boolean): Boolean =
        mayDrag(enabled) && hasDraggedOffset

    /**
     * Resting position = the dragged offset when the gate allows it, otherwise
     * the widget's default anchor. [draggedX]/[draggedY] null means "never
     * dragged this session".
     */
    fun restingPosition(
        enabled: Boolean,
        draggedX: Float?,
        draggedY: Float?,
        defaultX: Float,
        defaultY: Float
    ): Offset =
        if (shouldApplyDraggedPosition(enabled, draggedX != null && draggedY != null)
            && draggedX != null && draggedY != null
        ) {
            Offset(draggedX, draggedY)
        } else {
            Offset(defaultX, defaultY)
        }

    /**
     * Clamp a free-drag top-left so the widget of [w]x[h] stays inside the
     * safe region [start..screenW-end] x [top..screenH-bottom]. Guards a
     * widget larger than the available region by anchoring it at the safe
     * start corner.
     *
     * Phase 248 (Bug 2): [topReservedPx] (default 0 = backward compatible)
     * reserves an additional band below [top] that the widget may never enter —
     * the Scaffold's top-app-bar height. The effective top clamp becomes
     * `top + topReservedPx`, so a drag to the very top stops at the bottom
     * edge of the app bar instead of at the status-bar baseline.
     */
    fun constrainWithinSafeArea(
        x: Float,
        y: Float,
        screenW: Float,
        screenH: Float,
        w: Float,
        h: Float,
        top: Float,
        bottom: Float,
        start: Float,
        end: Float,
        topReservedPx: Float = 0f
    ): Offset {
        val sw = screenW.coerceAtLeast(1f)
        val sh = screenH.coerceAtLeast(1f)
        val s = start.coerceAtLeast(0f)
        val e = end.coerceAtLeast(0f)
        val t = (top.coerceAtLeast(0f) + topReservedPx).coerceAtLeast(0f)
        val b = bottom.coerceAtLeast(0f)
        val dw = w.coerceAtLeast(0f)
        val dh = h.coerceAtLeast(0f)
        val maxX = (sw - e - dw).coerceAtLeast(s)
        val maxY = (sh - b - dh).coerceAtLeast(t)
        return Offset(x.coerceIn(s, maxX), y.coerceIn(t, maxY))
    }
}