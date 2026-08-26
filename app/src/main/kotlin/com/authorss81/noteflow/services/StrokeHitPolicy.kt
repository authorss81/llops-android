package com.authorss81.noteflow.services

import androidx.compose.ui.geometry.Rect
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeSelection
import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 215: pure-JVM hit policy for lasso / box-marquee stroke selection.
 *
 * Selection semantics (single decision table shared by both drag kinds):
 *  - A stroke is captured when ANY of its sample points — polyline [Stroke.points]
 *    plus the rule-stroke anchors [Stroke.start]/[Stroke.end] — lies INSIDE the
 *    region, or within a small nib-aware tolerance of its boundary. The
 *    tolerance reuses the eraser's geometry rules:
 *    `EraserGeometryPolicy.coverageRadius(TAP_TOLERANCE_PX, stroke.width)`
 *    (= TAP_TOLERANCE_PX + width/2), so fat strokes forgive slightly wider of
 *    the marquee edge than hairlines — the same "the nib has extent" reasoning
 *    the PARTIAL eraser uses.
 *  - Box-marquee additionally captures strokes whose GEOMETRY CROSSES the rect
 *    without any endpoint inside (a segment-rect intersection), so one long
 *    stroke sliced by the marquee is still selectable.
 *  - Layer scoping: locked or invisible layers are never selectable; by default
 *    only the ACTIVE layer's ink is eligible (`scopeToActiveLayer = true`).
 *  - LASER trails are ephemeral render-side highlights, not content — never
 *    selectable.
 */
object StrokeHitPolicy {

    /** Strokes commit with this layer id when no explicit layer was active. */
    const val DEFAULT_LAYER_ID = "layer_default"

    /** Padding (world px) drawn around the selection's dashed bounding box. */
    const val SELECTION_BOUNDS_PADDING_PX = 8f

    /**
     * Nib-aware capture tolerance for one stroke near a region boundary:
     * `TAP_TOLERANCE_PX + strokeWidth * 0.5` via the eraser coverage rule.
     */
    fun lassoTolerancePx(strokeWidth: Float): Float =
        EraserGeometryPolicy.coverageRadius(
            EraserGeometryPolicy.TAP_TOLERANCE_PX,
            strokeWidth.coerceAtLeast(0f)
        )

    /**
     * Whether [stroke] may take part in a selection at all: never LASER,
     * never on a locked/invisible layer, and — under the default active-layer
     * scope — only on [activeLayerId] (strokes without an explicit layer id
     * count as [DEFAULT_LAYER_ID]; a missing layer ROW is treated as unlocked
     * + visible so legacy pages stay selectable).
     */
    fun isSelectable(
        stroke: Stroke,
        layers: List<LayerEntity>,
        activeLayerId: String?,
        scopeToActiveLayer: Boolean = true
    ): Boolean {
        if (stroke.tool == StrokeTool.LASER) return false
        val effectiveLayerId = stroke.layerId ?: DEFAULT_LAYER_ID
        if (scopeToActiveLayer && effectiveLayerId != (activeLayerId ?: DEFAULT_LAYER_ID)) {
            return false
        }
        val layer = layers.firstOrNull { it.id == effectiveLayerId }
        if (layer != null) {
            if (layer.locked) return false
            if (!layer.visible) return false
        }
        return true
    }

    // ---- Box marquee -------------------------------------------------------

    /**
     * True when the stroke's geometry intersects [rect]: any sample point or
     * anchor inside, OR any of its segments (polyline runs plus the start→end
     * rule segment) crossing the rect boundary.
     */
    fun strokeIntersectsRect(stroke: Stroke, rect: Rect): Boolean {
        fun inRect(x: Float, y: Float): Boolean =
            x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

        for (p in stroke.points) {
            if (inRect(p.x, p.y)) return true
        }
        stroke.start?.let { if (inRect(it.x, it.y)) return true }
        stroke.end?.let { if (inRect(it.x, it.y)) return true }

        if (stroke.points.size >= 2) {
            var ax = stroke.points[0].x
            var ay = stroke.points[0].y
            for (i in 1 until stroke.points.size) {
                val b = stroke.points[i]
                if (segmentIntersectsRect(ax, ay, b.x, b.y, rect)) return true
                ax = b.x
                ay = b.y
            }
        }
        // Rule strokes (rect/line/arrow/ellipse snapshots) keep their geometry in
        // start/end even when points were also sampled — test that segment too.
        val s = stroke.start
        val e = stroke.end
        if (s != null && e != null && segmentIntersectsRect(s.x, s.y, e.x, e.y, rect)) {
            return true
        }
        return false
    }

    /**
     * Segment vs axis-aligned rect: endpoints inside, or the segment properly
     * crossing any of the four edges (orientation-based intersection tests).
     */
    fun segmentIntersectsRect(
        ax: Float, ay: Float, bx: Float, by: Float, rect: Rect
    ): Boolean {
        fun inside(x: Float, y: Float): Boolean =
            x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
        if (inside(ax, ay) || inside(bx, by)) return true

        val r0x = rect.left; val r0y = rect.top
        val r1x = rect.right; val r1y = rect.top
        val r2x = rect.right; val r2y = rect.bottom
        val r3x = rect.left; val r3y = rect.bottom
        return segmentsIntersect(ax, ay, bx, by, r0x, r0y, r1x, r1y) ||
            segmentsIntersect(ax, ay, bx, by, r1x, r1y, r2x, r2y) ||
            segmentsIntersect(ax, ay, bx, by, r2x, r2y, r3x, r3y) ||
            segmentsIntersect(ax, ay, bx, by, r3x, r3y, r0x, r0y)
    }

    private fun orientation(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ): Boolean {
        val d1 = orientation(cx, cy, dx, dy, ax, ay)
        val d2 = orientation(cx, cy, dx, dy, bx, by)
        val d3 = orientation(ax, ay, bx, by, cx, cy)
        val d4 = orientation(ax, ay, bx, by, dx, dy)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    // ---- Lasso -------------------------------------------------------------

    /**
     * True when the stroke is captured by the closed lasso polygon: any sample
     * point/anchor strictly inside (winding-number test) or within
     * [lassoTolerancePx] of the polygon boundary.
     */
    fun strokeCapturedByPolygon(stroke: Stroke, polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        val tol = lassoTolerancePx(stroke.width)

        fun captured(x: Float, y: Float): Boolean {
            if (LassoPolicy.windingContainsPoint(polygon, x, y)) return true
            return LassoPolicy.distanceToPolygonEdge(polygon, x, y) <= tol
        }

        for (p in stroke.points) {
            if (captured(p.x, p.y)) return true
        }
        stroke.start?.let { if (captured(it.x, it.y)) return true }
        stroke.end?.let { if (captured(it.x, it.y)) return true }
        return false
    }

    // ---- Full pipelines ----------------------------------------------------

    /**
     * Box-marquee selection over [allStrokes]: selectable candidates whose
     * geometry intersects the marquee rect.
     */
    fun selectFromRect(
        allStrokes: List<Stroke>,
        rect: Rect,
        layers: List<LayerEntity>,
        activeLayerId: String?
    ): StrokeSelection {
        val hits = allStrokes.filter {
            isSelectable(it, layers, activeLayerId) && strokeIntersectsRect(it, rect)
        }
        return buildSelection(hits)
    }

    /**
     * Lasso selection over [allStrokes]: selectable candidates captured by the
     * closed polygon ([LassoPolicy.classifyDrag] already decided this is a
     * genuine loop).
     */
    fun selectFromPolygon(
        allStrokes: List<Stroke>,
        polygon: List<PointF>,
        layers: List<LayerEntity>,
        activeLayerId: String?
    ): StrokeSelection {
        val hits = allStrokes.filter {
            isSelectable(it, layers, activeLayerId) && strokeCapturedByPolygon(it, polygon)
        }
        return buildSelection(hits)
    }

    /**
     * Build the resulting [StrokeSelection]: union bounds over every selected
     * stroke's points AND rule anchors, and [StrokeSelection.layerId] set only
     * when the whole selection lives on ONE layer (mixed → null).
     */
    fun buildSelection(selected: List<Stroke>): StrokeSelection {
        if (selected.isEmpty()) return StrokeSelection.EMPTY
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val ids = HashSet<String>(selected.size * 2)
        val layerIds = HashSet<String>(2)
        for (stroke in selected) {
            ids.add(stroke.id)
            layerIds.add(stroke.layerId ?: DEFAULT_LAYER_ID)
            for (p in stroke.points) {
                minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
            }
            stroke.start?.let {
                minX = minOf(minX, it.x); maxX = maxOf(maxX, it.x)
                minY = minOf(minY, it.y); maxY = maxOf(maxY, it.y)
            }
            stroke.end?.let {
                minX = minOf(minX, it.x); maxX = maxOf(maxX, it.x)
                minY = minOf(minY, it.y); maxY = maxOf(maxY, it.y)
            }
        }
        val commonLayer = if (layerIds.size == 1) layerIds.first() else null
        return StrokeSelection(
            ids = ids,
            bounds = Rect(minX, minY, maxX, maxY),
            layerId = commonLayer
        )
    }
}
