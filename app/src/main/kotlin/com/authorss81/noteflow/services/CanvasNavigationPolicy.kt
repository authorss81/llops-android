package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke

/**
 * Phase 172 — minimap quick-view navigation decision table. Pure JVM.
 *
 * Maps content/transform state to the corrected canvas transform used by the
 * minimap's zoom-to-fit and jump-home quick actions. Mirror math only:
 *  - [jumpHome] returns the transform that shows the world top-left at the
 *    viewport top-left (pan = (0,0), zoom = 100%) — the existing "Reset Zoom &
 *    Pan" / minimap 100% target, so the quick action and the old buttons agree.
 *  - [zoomToFit] centers + fits [content] inside the viewport with
 *    [FIT_PADDING_PX] breathing room, clamping scale to the same zoom window the
 *    canvas respects ([MIN_FIT_ZOOM]..[MAX_FIT_ZOOM]) and the content to the
 *    world rect (which is already bounded by `CanvasPageBudgetPolicy`'s dynamic
 *    page ceiling via `AnnotationCanvas.computeCanvasWorld`).
 *
 * Cost budgets: [computeContentBounds] reuses [MinimapGeometryPolicy]'s stride
 * sampling (≤ MAX_MINIMAP_SAMPLED_STROKES strokes and ≤ ~MAX_MINIMAP_POLYLINE_SEGMENTS
 * points sampled) above [EXACT_BOUNDS_POINT_CAP] total points, so a hugestroke
 * page can never O(n) the main thread past the minimap's own budget. Small
 * documents are computed exactly (stride 1).
 *
 * Reduce-motion is exposed as [shouldAnimate] so callers spring or snap.
 */
object CanvasNavigationPolicy {

    /** Same zoom window the in-canvas pinch/zoom buttons enforce today. */
    const val MIN_FIT_ZOOM = 0.5f

    const val MAX_FIT_ZOOM = 4.0f

    /** World-pixel breathing room around the fitted content on each side. */
    const val FIT_PADDING_PX = 48f

    /**
     * Above this many total points the [computeContentBounds] scan switches to
     * budgeted stride sampling (it cannot miss extremes the minimap itself shows,
     * and a few missing outlier pixels never matter for a fit).
     */
    const val EXACT_BOUNDS_POINT_CAP = 20_000

    data class TargetTransform(
        val scale: Float,
        val panX: Float,
        val panY: Float
    )

    /** Generic world-coordinate rectangle. */
    data class Bounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        val isEmpty: Boolean get() = minX > maxX || minY > maxY

        val width: Float get() = if (isEmpty) 0f else maxX - minX

        val height: Float get() = if (isEmpty) 0f else maxY - minY
    }

    fun emptyBounds(): Bounds = Bounds(
        Float.POSITIVE_INFINITY,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        Float.NEGATIVE_INFINITY
    )

    private fun Bounds.expand(x: Float, y: Float): Bounds = Bounds(
        if (x < minX) x else minX,
        if (y < minY) y else minY,
        if (x > maxX) x else maxX,
        if (y > maxY) y else maxY
    )

    /**
     * Animation gate: animate (spring) unless the user asked for reduce-motion,
     * in which case the caller must snap (matches [MotionPolicy.hapticsAllowed]
     * philosophy — motion is a user-level choice, never silently downgraded).
     */
    fun shouldAnimate(reduceMotion: Boolean): Boolean = !reduceMotion

    /** Jump-home: world top-left at the viewport top-left, 100% zoom. */
    fun jumpHome(): TargetTransform = TargetTransform(1f, 0f, 0f)

    /**
     * Budgeted content bounds over [strokes]. Strides match the minimap's own
     * sampler, so a pathological page costs the same as one minimap frame.
     * [offsetY] maps a stroke's page index to its world Y offset (continuous
     * mode stacks pages by `index × pageStride`; paginated mode is identity).
     */
    fun computeContentBounds(
        strokes: List<Stroke>,
        offsetY: (Int) -> Float = { 0f }
    ): Bounds {
        var bounds = emptyBounds()
        val strokeCount = strokes.size
        val strokeStep = MinimapGeometryPolicy.strokeStepFor(strokeCount)
        val totalPoints = strokes.sumOf { it.points.size }
        val budgeted = totalPoints > EXACT_BOUNDS_POINT_CAP
        val pointStep = if (budgeted) MinimapGeometryPolicy.pointStepFor(totalPoints) else 1

        var idx = 0
        while (idx < strokeCount) {
            val stroke = strokes[idx]
            val dy = offsetY(stroke.pdfPage)
            stroke.start?.let { bounds = bounds.expand(it.x, it.y + dy) }
            stroke.end?.let { bounds = bounds.expand(it.x, it.y + dy) }
            if (stroke.points.isNotEmpty()) {
                val pCount = stroke.points.size
                var p = 0
                while (p < pCount) {
                    val point = stroke.points[p]
                    bounds = bounds.expand(point.x, point.y + dy)
                    p += pointStep
                }
            }
            idx += strokeStep
        }
        return bounds
    }

    /** Intersect [content] with the bounded world rect (returns empty when fully outside). */
    fun contentWithinWorld(content: Bounds, worldW: Float, worldH: Float): Bounds {
        if (content.isEmpty || worldW <= 0f || worldH <= 0f) return content
        val minX = content.minX.coerceIn(0f, worldW)
        val minY = content.minY.coerceIn(0f, worldH)
        val maxX = content.maxX.coerceIn(0f, worldW)
        val maxY = content.maxY.coerceIn(0f, worldH)
        return Bounds(minX, minY, maxX, maxY)
    }

    /**
     * Zoom-to-fit: center [content] inside a [viewportW]×[viewportH] viewport at
     * the largest scale whose content + [FIT_PADDING_PX] still fits, clamped to
     * the canvas zoom window and the world rect. Degenerate/empty content falls
     * back to [jumpHome].
     */
    fun zoomToFit(
        content: Bounds,
        viewportW: Float,
        viewportH: Float,
        worldW: Float,
        worldH: Float
    ): TargetTransform {
        if (content.isEmpty) return jumpHome()
        val vw = viewportW.coerceAtLeast(1f)
        val vh = viewportH.coerceAtLeast(1f)
        val bounds = contentWithinWorld(content, worldW, worldH)
        // Degenerate after clamping (zero-area box — e.g. content that clamps to
        // the world corner, or a single point) has nothing to "fit"; fall back to
        // the caller's home transform instead of dividing by a zero side.
        if (bounds.isEmpty || bounds.width <= 0f || bounds.height <= 0f) return jumpHome()

        val availableW = (vw - 2 * FIT_PADDING_PX).coerceAtLeast(1f)
        val availableH = (vh - 2 * FIT_PADDING_PX).coerceAtLeast(1f)
        val scale = minOf(availableW / bounds.width, availableH / bounds.height)
            .coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)

        val centerX = (bounds.minX + bounds.maxX) / 2f
        val centerY = (bounds.minY + bounds.maxY) / 2f
        return TargetTransform(
            scale = scale,
            panX = vw / 2f - centerX * scale,
            panY = vh / 2f - centerY * scale
        )
    }
}