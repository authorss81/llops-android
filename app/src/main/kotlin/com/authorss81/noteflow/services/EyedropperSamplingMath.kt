package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-JVM math for the eyedropper sampling path (Phase 27). All functions are
 * deterministic and unit-testable: screen<->canvas coordinate mapping, page
 * bitmap pixel lookup, stroke hit distance and a source-over compositing step
 * that reproduces how the renderer stacks a stroke color over the page base.
 */
object EyedropperSamplingMath {

    /**
     * Maps a screen-space pointer position back to canvas coordinates for the
     * given pan offset and zoom. The canvas is scaled around the top-left origin
     * (TransformOrigin(0,0)), so the inverse is simply the affine divide.
     * IMPORTANT: this must stay the inverse of the render transform
     * (graphicsLayer { scaleX/scaleY = zoom, translationX/Y = pan }).
     */
    fun screenToCanvas(screenX: Float, screenY: Float, panX: Float, panY: Float, zoom: Float): Pair<Float, Float> {
        val z = if (zoom == 0f) 1f else zoom
        return ((screenX - panX) / z) to ((screenY - panY) / z)
    }

    /**
     * Maps a canvas-space page coordinate to a pixel in the page source bitmap.
     * The page bitmap is always stretched/letterboxed to [pageWidthPx] wide, so a
     * single uniform scale maps both axes.
     */
    fun canvasToPagePixel(
        canvasX: Float,
        canvasY: Float,
        pageTopY: Float,
        pageWidthPx: Float,
        pageHeightPx: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Pair<Int, Int>? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val localY = canvasY - pageTopY
        if (localY < 0f || localY > pageHeightPx) return null
        val scale = pageWidthPx / bitmapWidth.toFloat()
        val bx = (canvasX / scale).toInt().coerceIn(0, bitmapWidth - 1)
        val by = (localY / scale).toInt().coerceIn(0, bitmapHeight - 1)
        return bx to by
    }

    /**
     * Phase 225: maps a canvas-space tap to a pixel inside the per-page
     * reference-image bitmap, or returns null when the tap falls OUTSIDE the
     * reference bounds (or the bitmap is invalid).
     *
     * The reference underlay is drawn at world rect (refX, pageTopY + refY) with
     * the size (refWidth, refHeight); the source bitmap is scaled to fit that
     * rect (aspect preserved by [ReferenceImagePolicy.fitForPage]). Per-axis
     * scales are used so a tiny rounding difference between the two axes can
     * never push the pixel out of bounds.
     */
    fun referencePixel(
        canvasX: Float,
        canvasY: Float,
        pageTopY: Float,
        refX: Float,
        refY: Float,
        refWidth: Float,
        refHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Pair<Int, Int>? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (refWidth <= 0f || refHeight <= 0f) return null
        val localX = canvasX - refX
        val localY = canvasY - (pageTopY + refY)
        if (localX < 0f || localY < 0f || localX > refWidth || localY > refHeight) return null
        val px = (localX / refWidth * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val py = (localY / refHeight * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        return px to py
    }

    /**
     * Phase 225: 1:1 extraction rect (enlarged by [margin]) around the target
     * pixel, clamped to the bitmap bounds. Used to decode ONLY the sampled region
     * of the reference file instead of the whole photo. Returns null when the
     * pixel is out of range or the bitmap is invalid.
     */
    fun referenceSamplingRect(
        px: Int,
        py: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        margin: Int = 1
    ): Rect? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        if (px < 0 || py < 0 || px >= bitmapWidth || py >= bitmapHeight) return null
        val left = (px - margin).coerceAtLeast(0)
        val top = (py - margin).coerceAtLeast(0)
        val right = (px + margin + 1).coerceAtMost(bitmapWidth)
        val bottom = (py + margin + 1).coerceAtMost(bitmapHeight)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    /** Phase 225: pure-JVM integer rect mirroring android.graphics.Rect for the sampling region. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Phase 225: the eyedropper resolution PRIORITY — the per-page reference
     * underlay wins over the layer raster when a tap lands inside it, so palette
     * building from a photo samples the photo (not the dimmed overlay frame or
     * the ink on top). Pure lookup so the ordering is unit-testable.
     */
    fun resolveSampleSource(referenceHit: Boolean): Source =
        if (referenceHit) Source.REFERENCE else Source.LAYER

    /** Phase 225: the two pixel sources an eyedropper tap can resolve to. */
    enum class Source { REFERENCE, LAYER }

    /**
     * Minimum distance from [p] to the polyline of [points]. Used for tight
     * stroke hit-testing (eyedropper) where the rendering margin must not make a
     * tap land on a stroke that is visually far away.
     */
    fun distanceToPolyline(points: List<PointF>, px: Float, py: Float): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) {
            val dx = points[0].x - px
            val dy = points[0].y - py
            return sqrt(dx * dx + dy * dy)
        }
        var best = Float.MAX_VALUE
        for (i in 1 until points.size) {
            best = minOf(best, distanceToSegment(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, px, py))
        }
        return best
    }

    fun distanceToSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-6f) {
            val ex = px - ax
            val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val ex = px - cx
        val ey = py - cy
        return sqrt(ex * ex + ey * ey)
    }

    /**
     * Index of the polyline vertex nearest to [px,py]. Used by the eyedropper to
     * pick the progress value (arc-length normalized) at which the stroke's color
     * should be derived when the tap lands between vertices.
     */
    fun nearestIndex(points: List<PointF>, px: Float, py: Float): Int {
        if (points.isEmpty()) return 0
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in points.indices) {
            val dx = points[i].x - px
            val dy = points[i].y - py
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * Source-over composite of a "painted" color over a base pixel
     * (0xAARRGGBB ints). Mirrors the renderer's SRC_OVER accumulation:
     *   outA = overA + baseA * (1 - overA)
     *   outC = (overC*overA + baseC*baseA*(1-overA)) / outA
     */
    fun composite(baseArgb: Int, overArgb: Int): Int {
        val baseA = alpha(baseArgb) / 255f
        val overA = alpha(overArgb) / 255f
        val outA = overA + baseA * (1f - overA)
        if (outA <= 1e-5f) return 0x00000000

        fun channel(base: Int, over: Int): Int {
            val c = (over * overA + base * baseA * (1f - overA)) / outA
            return c.roundToInt().coerceIn(0, 255)
        }
        val a = (outA * 255f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or
            (channel(red(baseArgb), red(overArgb)) shl 16) or
            (channel(green(baseArgb), green(overArgb)) shl 8) or
            channel(blue(baseArgb), blue(overArgb))
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    private fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    private fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    /**
     * Effective render alpha used by the common solid/line tools. This mirrors the
     * alphas drawSingleStroke applies so the sampling composite lands close to the
     * on-screen pixel (textured brushes are approximated by their dominant alpha).
     */
    fun approximateStrokeAlpha(toolName: String?, alpha: Float): Float {
        val base = alpha.coerceIn(0f, 1f)
        return when (toolName) {
            "HIGHLIGHTER" -> base * 0.35f
            "MARKER" -> base * 0.42f
            "PENCIL" -> base * 0.82f
            "AIRBRUSH" -> base * 0.35f
            "SPLATTER", "SMUDGE" -> base * 0.65f
            else -> base
        }
    }
}