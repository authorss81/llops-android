package com.authorss81.noteflow.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushShadowPolicy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 213 — pooled vector drop-shadow underlay for [drawSingleStroke].
 *
 * Draws ONE offset, blurred outline Path for a stroke BEFORE its main ink pass,
 * giving every tool a consistent "lifted off the paper" cue. Design contracts:
 *
 *  - ZERO allocation on the hot path: one reusable [Path] + one reusable [Paint]
 *    live for the process; the [BlurMaskFilter] is re-created only when the
 *    quantized radius actually changes (never inside any per-segment loop).
 *  - The shadow color follows the PAPER, not the ink: near-black shadow on
 *    light stock, subtle white lift on dark stock (a dark shadow disappears on
 *    dark paper; the phase-213 prompt's theme-awareness requirement).
 *  - Geometry mirrors the main branches exactly: midpoint-quadratic smoothing
 *    for freehand tools, identical vertex math for LINE/RECTANGLE/ELLIPSE/
 *    TRIANGLE/STAR/PENTAGON/HEXAGON/ARROW — so the shadow sits under the ink,
 *    never peeking out at corners.
 *  - Thread contract: canvas drawing in Compose happens on the UI thread (the
 *    layer rasters rasterize through CanvasDrawScope on the same thread), so
 *    the single shared Paint/Path pair is safe. Nothing here is reachable from
 *    background export threads (ImportExportService keeps its own renderer).
 */
internal object StrokeShadowRenderer {

    private const val ARROW_HEAD_SIZE_PX = 24f
    private val ARROW_HEAD_ANGLE = Math.toRadians(30.0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // Blur filters are native wrappers; keep ONE alive per distinct quantized
    // radius instead of allocating per stroke draw (live preview redraws every
    // frame while a stroke is in progress).
    private var cachedBlurRadius = -1f

    /**
     * Draw the shadow for [stroke] onto [canvas] using [plan]. The caller has
     * already decided eligibility via [BrushShadowPolicy.plan]; this is pure
     * drawing. [offsetY] is the page offset the main pass also applies.
     */
    fun drawStrokeShadow(
        canvas: Canvas,
        stroke: Stroke,
        offsetY: Float,
        plan: BrushShadowPolicy.ShadowPlan,
        isDarkPaper: Boolean
    ) {
        if (!buildGeometry(path, stroke)) return

        val argb = if (isDarkPaper) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        if (paint.color != argb) paint.color = argb
        paint.strokeWidth = stroke.width
        paint.alpha = (plan.alpha * 255f).coerceIn(0f, 255f).toInt()

        // Quantize to 0.5 px so slow width changes don't thrash the filter cache.
        val quantized = (plan.blurRadiusPx * 2f).toInt() / 2f
        if (quantized != cachedBlurRadius || paint.maskFilter == null) {
            paint.maskFilter = BlurMaskFilter(quantized.coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            cachedBlurRadius = quantized
        }

        val saveCount = canvas.save()
        try {
            canvas.translate(plan.offsetX, offsetY + plan.offsetY)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** Build the stroke's outline geometry into [path]; false when empty. */
    private fun buildGeometry(path: Path, stroke: Stroke): Boolean {
        path.reset()
        val start = stroke.start
        val end = stroke.end
        return when {
            stroke.tool.isShapeTool && start != null && end != null -> {
                buildShapeOutline(path, stroke.tool, start, end)
                true
            }
            stroke.points.isNotEmpty() -> {
                buildFreehandOutline(path, stroke)
                true
            }
            start != null && end != null -> {
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
                true
            }
            else -> false
        }
    }

    /** Shape outlines — vertex math copied verbatim from the drawSingleStroke branches. */
    private fun buildShapeOutline(path: Path, tool: StrokeTool, start: PointF, end: PointF) {
        when (tool) {
            StrokeTool.LINE -> {
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
            }
            StrokeTool.RECTANGLE -> {
                val left = minOf(start.x, end.x)
                val top = minOf(start.y, end.y)
                path.addRect(left, top, left + abs(end.x - start.x), top + abs(end.y - start.y), Path.Direction.CW)
            }
            StrokeTool.ELLIPSE -> {
                val left = minOf(start.x, end.x)
                val top = minOf(start.y, end.y)
                path.addOval(left, top, left + abs(end.x - start.x), top + abs(end.y - start.y), Path.Direction.CW)
            }
            StrokeTool.TRIANGLE -> {
                path.moveTo((start.x + end.x) / 2f, minOf(start.y, end.y))
                path.lineTo(minOf(start.x, end.x), maxOf(start.y, end.y))
                path.lineTo(maxOf(start.x, end.x), maxOf(start.y, end.y))
                path.close()
            }
            StrokeTool.STAR -> polygon(
                path,
                cx = (start.x + end.x) / 2f,
                cy = (start.y + end.y) / 2f,
                rx = abs(end.x - start.x) / 2f,
                ry = abs(end.y - start.y) / 2f,
                vertices = 10,
                startAngleDeg = -90f,
                alternateRadius = true
            )
            StrokeTool.PENTAGON -> regularPolygon(path, start, end, vertices = 5, startAngleDeg = -90.0)
            StrokeTool.HEXAGON -> regularPolygon(path, start, end, vertices = 6, startAngleDeg = -90.0)
            StrokeTool.ARROW -> {
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
                val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
                val x1 = end.x - ARROW_HEAD_SIZE_PX * cos(angle - ARROW_HEAD_ANGLE).toFloat()
                val y1 = end.y - ARROW_HEAD_SIZE_PX * sin(angle - ARROW_HEAD_ANGLE).toFloat()
                val x2 = end.x - ARROW_HEAD_SIZE_PX * cos(angle + ARROW_HEAD_ANGLE).toFloat()
                val y2 = end.y - ARROW_HEAD_SIZE_PX * sin(angle + ARROW_HEAD_ANGLE).toFloat()
                path.moveTo(end.x, end.y)
                path.lineTo(x1, y1)
                path.moveTo(end.x, end.y)
                path.lineTo(x2, y2)
            }
            else -> {
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
            }
        }
    }

    private fun regularPolygon(path: Path, start: PointF, end: PointF, vertices: Int, startAngleDeg: Double) {
        polygon(
            path,
            cx = (start.x + end.x) / 2f,
            cy = (start.y + end.y) / 2f,
            rx = abs(end.x - start.x) / 2f,
            ry = abs(end.y - start.y) / 2f,
            vertices = vertices,
            startAngleDeg = Math.toDegrees(startAngleDeg).toFloat(),
            alternateRadius = false
        )
    }

    private fun polygon(
        path: Path,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        vertices: Int,
        startAngleDeg: Float,
        alternateRadius: Boolean
    ) {
        val stepAngleDeg = 360f / vertices
        var first = true
        for (i in 0 until vertices) {
            val angleRad = Math.toRadians(i * stepAngleDeg.toDouble() + startAngleDeg.toDouble())
            val r = if (alternateRadius && i % 2 == 1) 0.4f else 1f
            val x = cx + rx * r * cos(angleRad).toFloat()
            val y = cy + ry * r * sin(angleRad).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
    }

    /**
     * Freehand outline: the SAME midpoint-quadratic smoothing the PEN branch
     * uses (silky centerline); a single sample becomes a filled dot so taps
     * still cast a round shadow.
     */
    private fun buildFreehandOutline(path: Path, stroke: Stroke) {
        val pts = stroke.points
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL_AND_STROKE
            path.addCircle(pts[0].x, pts[0].y, stroke.width / 2f, Path.Direction.CW)
            return
        }
        paint.style = Paint.Style.STROKE
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
            return
        }
        val firstMidX = (pts[0].x + pts[1].x) / 2f
        val firstMidY = (pts[0].y + pts[1].y) / 2f
        path.lineTo(firstMidX, firstMidY)
        for (i in 1 until pts.size - 1) {
            val p1 = pts[i]
            val p2 = pts[i + 1]
            path.quadTo(p1.x, p1.y, (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        path.lineTo(pts.last().x, pts.last().y)
    }
}
