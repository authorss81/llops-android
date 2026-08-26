package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeSelection
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 216: pure-JVM decision table for lasso-selection clipboard, duplicate,
 * delete, hit-testing, and translate operations on selected [Stroke]s.
 *
 * All functions are allocation-free per query, unit-testable, and operate on
 * plain floats so they run on the JVM without any Android framework dependency.
 * The Compose layer feeds these functions the selected strokes' geometry and
 * the pointer/touch data; the result is a new stroke list or a verification
 * that the write-path encryption gate ([VaultWriteGate]) is still enforced.
 */
object StrokeSelectionActionPolicy {

    /** Clipboard mime type for serialized ink strokes. */
    const val CLIPBOARD_MIME = "inkflow-strokes"

    /** Horizontal/vertical offset (world px) for duplicate placement. */
    const val DUPLICATE_OFFSET_PX = 12f

    /** Hit-test tolerance (world px) for line/arrow proximity. */
    const val LINE_HIT_TOLERANCE_PX = 12f

    /** Hit-test tolerance (world px) for ellipse boundary proximity. */
    const val ELLIPSE_HIT_TOLERANCE_PX = 8f

    /** Hit-test margin factor for ellipse containment test (1 + margin). */
    const val ELLIPSE_MARGIN_FACTOR = 1.08f

    /** Factor of width used to compute padding for line hit tolerance. */
    const val LINE_WIDTH_FACTOR = 0.5f

    // ---- Clipboard ----------------------------------------------------------

    /**
     * Serialize a list of strokes to a JSON string suitable for the system
     * clipboard. Reuses [EncryptionService.serializeStrokes] which Gson-encodes
     * the full stroke data (geometry + tool + color + metadata).
     *
     * The clipboard is a SHARED, NON-ENCRYPTED surface — [ClipboardGuard]
     * scrubs it on lock and [ClipboardScrubPolicy] enforces the time window.
     */
    fun serializeForClipboard(strokes: List<Stroke>): String {
        return EncryptionService.serializeStrokes(strokes)
    }

    /**
     * Deserialize strokes from the clipboard payload. Returns an empty list
     * on malformed input (fail-closed — never crash on hostile clipboard).
     */
    fun deserializeFromClipboard(json: String): List<Stroke> {
        return EncryptionService.deserializeStrokes(json)
    }

    // ---- Duplicate ----------------------------------------------------------

    /**
     * Duplicate selected strokes with fresh UUIDs and a fixed offset so the
     * copies are visually distinct from the originals. The offset is divided by
     * [zoomScale] so the visual displacement is constant regardless of zoom.
     *
     * @param selectedStrokes strokes to duplicate
     * @param zoomScale current canvas zoom (1 = no zoom)
     * @return new strokes with fresh ids, offset positions, and the same
     *         layer assignment; empty when input is empty.
     */
    fun duplicateStrokes(
        selectedStrokes: List<Stroke>,
        zoomScale: Float
    ): List<Stroke> {
        if (selectedStrokes.isEmpty()) return emptyList()
        val safeZoom = if (zoomScale <= 0f) 1f else zoomScale
        val dx = DUPLICATE_OFFSET_PX / safeZoom
        val dy = DUPLICATE_OFFSET_PX / safeZoom
        return selectedStrokes.map { stroke ->
            val newPoints = stroke.points.map { p ->
                p.copy(x = p.x + dx, y = p.y + dy)
            }
            val newStart = stroke.start?.let { s ->
                s.copy(x = s.x + dx, y = s.y + dy)
            }
            val newEnd = stroke.end?.let { e ->
                e.copy(x = e.x + dx, y = e.y + dy)
            }
            stroke.copy(
                id = java.util.UUID.randomUUID().toString(),
                points = newPoints,
                start = newStart,
                end = newEnd
            )
        }
    }

    // ---- Delete -------------------------------------------------------------

    /**
     * Remove selected strokes from the full page stroke list.
     *
     * @param allStrokes the current page's complete stroke list
     * @param selectedIds ids of strokes to remove
     * @return filtered list with selected strokes removed
     */
    fun deleteSelected(
        allStrokes: List<Stroke>,
        selectedIds: Set<String>
    ): List<Stroke> {
        if (selectedIds.isEmpty()) return allStrokes
        return allStrokes.filterNot { it.id in selectedIds }
    }

    // ---- Shape-aware hit testing --------------------------------------------

    /**
     * Whether a world-coordinate hit point lands on (or near) [stroke].
     * Delegates to the shape-specific detector based on [Stroke.tool].
     *
     * Axis-aligned v1: rotated-shape support needs a Stroke.rotationDegrees
     * field + approved migration per AGENTS.md major-arch-change rule.
     */
    fun hitTestStroke(
        hitX: Float,
        hitY: Float,
        stroke: Stroke,
        zoomScale: Float = 1f
    ): Boolean {
        val tolerance = LINE_HIT_TOLERANCE_PX / max(0.01f, zoomScale)
        return when (stroke.tool) {
            StrokeTool.RECTANGLE -> hitTestRectangle(hitX, hitY, stroke, tolerance)
            StrokeTool.ELLIPSE -> hitTestEllipse(hitX, hitY, stroke)
            StrokeTool.LINE, StrokeTool.ARROW -> hitTestLine(hitX, hitY, stroke, tolerance)
            else -> hitTestFreehand(hitX, hitY, stroke, tolerance)
        }
    }

    /**
     * Axis-aligned rectangle containment: the hit point is inside the bounding
     * box defined by [Stroke.start] (top-left) and [Stroke.end] (bottom-right),
     * or within [tolerance] of any of the four edges.
     */
    internal fun hitTestRectangle(
        hitX: Float,
        hitY: Float,
        stroke: Stroke,
        tolerance: Float
    ): Boolean {
        val start = stroke.start ?: return false
        val end = stroke.end ?: return false
        val left = min(start.x, end.x)
        val right = max(start.x, end.x)
        val top = min(start.y, end.y)
        val bottom = max(start.y, end.y)

        // Inside the rect (with tolerance inflation)
        if (hitX >= left - tolerance && hitX <= right + tolerance &&
            hitY >= top - tolerance && hitY <= bottom + tolerance
        ) {
            return true
        }

        // Near any of the four edges
        val dLeft = abs(hitX - left)
        val dRight = abs(hitX - right)
        val dTop = abs(hitY - top)
        val dBottom = abs(hitY - bottom)
        val onVerticalEdge = (hitY in top - tolerance..bottom + tolerance) &&
            (dLeft <= tolerance || dRight <= tolerance)
        val onHorizontalEdge = (hitX in left - tolerance..right + tolerance) &&
            (dTop <= tolerance || dBottom <= tolerance)
        return onVerticalEdge || onHorizontalEdge
    }

    /**
     * Ellipse containment: ((x-cx)/rx)² + ((y-cy)/ry)² <= (1 + margin)².
     * The margin provides a small hit-slop around the boundary.
     */
    internal fun hitTestEllipse(
        hitX: Float,
        hitY: Float,
        stroke: Stroke
    ): Boolean {
        val start = stroke.start ?: return false
        val end = stroke.end ?: return false
        val left = min(start.x, end.x)
        val right = max(start.x, end.x)
        val top = min(start.y, end.y)
        val bottom = max(start.y, end.y)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = max(1f, (right - left) / 2f)
        val ry = max(1f, (bottom - top) / 2f)
        val dx = (hitX - cx) / rx
        val dy = (hitY - cy) / ry
        return dx * dx + dy * dy <= ELLIPSE_MARGIN_FACTOR * ELLIPSE_MARGIN_FACTOR
    }

    /**
     * Line/arrow proximity: the perpendicular distance from [hitX, hitY] to
     * the start→end segment, plus half the stroke width, must be within
     * [tolerance].
     */
    internal fun hitTestLine(
        hitX: Float,
        hitY: Float,
        stroke: Stroke,
        tolerance: Float
    ): Boolean {
        val start = stroke.start ?: return false
        val end = stroke.end ?: return false
        val dist = LassoPolicy.distanceToSegment(hitX, hitY, start.x, start.y, end.x, end.y)
        val effectiveTolerance = tolerance + stroke.width * LINE_WIDTH_FACTOR
        return dist <= effectiveTolerance
    }

    /**
     * Freehand stroke proximity: the hit point is within [tolerance] of ANY
     * segment in the stroke's polyline.
     */
    internal fun hitTestFreehand(
        hitX: Float,
        hitY: Float,
        stroke: Stroke,
        tolerance: Float
    ): Boolean {
        val pts = stroke.points
        if (pts.isEmpty()) {
            val anchor = stroke.start ?: return false
            val d = sqrt((hitX - anchor.x) * (hitX - anchor.x) + (hitY - anchor.y) * (hitY - anchor.y))
            return d <= tolerance + stroke.width * LINE_WIDTH_FACTOR
        }
        for (i in 0 until pts.size) {
            // Test distance to the point itself
            val d = sqrt((hitX - pts[i].x) * (hitX - pts[i].x) + (hitY - pts[i].y) * (hitY - pts[i].y))
            if (d <= tolerance + stroke.width * LINE_WIDTH_FACTOR) return true
            // Test distance to the segment to the next point
            if (i < pts.size - 1) {
                val segDist = LassoPolicy.distanceToSegment(
                    hitX, hitY, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y
                )
                if (segDist <= tolerance + stroke.width * LINE_WIDTH_FACTOR) return true
            }
        }
        return false
    }

    // ---- Translate ----------------------------------------------------------

    /**
     * Translate (move) selected strokes by a world-coordinate delta. Updates
     * [Stroke.points], [Stroke.start], [Stroke.end], and recomputes
     * [Stroke.pdfPage] from the new Y position via [getPageFromCanvasY].
     *
     * @param strokes all strokes on the page (selected + unselected)
     * @param selectedIds ids of the strokes to translate
     * @param dx world-coordinate X delta
     * @param dy world-coordinate Y delta
     * @param pageStride vertical stride between pages (world px)
     * @param pageHeight height of one page (world px)
     * @return new full stroke list with translated selected strokes and
     *         recomputed pdfPage values
     */
    fun translateSelected(
        strokes: List<Stroke>,
        selectedIds: Set<String>,
        dx: Float,
        dy: Float,
        pageStride: Float,
        pageHeight: Float
    ): List<Stroke> {
        if (selectedIds.isEmpty()) return strokes
        return strokes.map { stroke ->
            if (stroke.id !in selectedIds) return@map stroke
            val newPoints = stroke.points.map { p ->
                p.copy(x = p.x + dx, y = p.y + dy)
            }
            val newStart = stroke.start?.let { s ->
                s.copy(x = s.x + dx, y = s.y + dy)
            }
            val newEnd = stroke.end?.let { e ->
                e.copy(x = e.x + dx, y = e.y + dy)
            }
            // Recompute pdfPage from the new Y position
            val newY = newPoints.firstOrNull()?.y ?: newStart?.y ?: stroke.pdfPage * pageStride
            val newPage = getPageFromCanvasY(newY, pageStride)
            stroke.copy(
                points = newPoints,
                start = newStart,
                end = newEnd,
                pdfPage = newPage
            )
        }
    }

    /**
     * Derive a page index from a world Y coordinate. Matches the canvas
     * convention: page 0 starts at Y=0, each page occupies [pageStride] px.
     */
    internal fun getPageFromCanvasY(canvasY: Float, pageStride: Float): Int {
        if (pageStride <= 0f) return 0
        return (canvasY / pageStride).toInt().coerceAtLeast(0)
    }

    // ---- Selection bounds recomputation -------------------------------------

    /**
     * Recompute the selection bounding box after a translation, using the same
     * logic as [StrokeHitPolicy.buildSelection] but without allocating a full
     * [StrokeSelection].
     */
    fun recomputeBounds(
        strokes: List<Stroke>,
        selectedIds: Set<String>
    ): androidx.compose.ui.geometry.Rect {
        if (selectedIds.isEmpty()) return androidx.compose.ui.geometry.Rect.Zero
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (stroke in strokes) {
            if (stroke.id !in selectedIds) continue
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
        if (minX > maxX || minY > maxY) return androidx.compose.ui.geometry.Rect.Zero
        return androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
    }
}
