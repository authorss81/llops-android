package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 198 (PERF 2.1 + 2.5): live-stroke recomposition isolation + viewport
 * culling window.
 *
 * Layout Inspector cannot run on this CI runner (no device/emulator), so the
 * invalidation surface is pinned STRUCTURALLY, at source level:
 *
 *  1. The live ink draws in a separate [LiveStrokePreview] canvas node whose
 *     composition body reads NO per-sample state — volatile state reaches it
 *     through provider lambdas read only in draw scope.
 *  2. The main canvas pass builds a live preview ONLY for wet tools (the AGSL
 *     shader must see committed strokes + preview in one saveLayer); every
 *     other tool's pen sample can no longer invalidate the whole pass.
 *  3. The eraser aim cursor moved into the same isolated node.
 *  4. The layer-raster cache invalidates INCREMENTALLY (per-entry content
 *     hash gates) — the blanket clear-on-commit LaunchedEffect is gone.
 *  5. The paginated loop iterates the closed-form O(visiblePages) window from
 *     ViewportPageWindowPolicy instead of skip-`continue`ing every page.
 */
class Phase198LiveStrokeIsolationTest {

    private fun source(rel: String): String = File(repoRoot(), rel).readText()

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun canvasSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")

    // ---- 1. Isolated overlay node ------------------------------------------------

    @Test
    fun `live ink renders in an isolated LiveStrokePreview node`() {
        val src = canvasSource()
        assertTrue(src.contains("@Composable\nprivate fun LiveStrokePreview("))
        assertTrue(src.contains("testTag(\"live_stroke_preview\")"))
        assertTrue(src.contains("if (liveOverlayVisible) {"))
        // The overlay is stacked with the SAME world transform as the main pass.
        assertTrue(src.contains("transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)"))
    }

    @Test
    fun `overlay gate is derived state that flips only at stroke boundaries`() {
        val src = canvasSource()
        assertTrue(src.contains("val liveOverlayVisible by remember(currentTool) {"))
        assertTrue(src.contains("derivedStateOf {"))
        assertTrue(
            src.contains(
                "activePoints.isNotEmpty() ||\n" +
                    "                        (activeStart != null && activeEnd != null) ||"
            )
        )
    }

    @Test
    fun `volatile per-sample state reaches the overlay through providers not params`() {
        val src = canvasSource()
        val sigStart = src.indexOf("private fun LiveStrokePreview(")
        val drawStart = src.indexOf("Canvas(modifier = modifier)", sigStart)
        assertTrue(sigStart >= 0 && drawStart > sigStart)
        val signature = src.substring(sigStart, drawStart)
        assertTrue(signature.contains("activeStartProvider: () -> PointF?"))
        assertTrue(signature.contains("activeEndProvider: () -> PointF?"))
        assertTrue(signature.contains("eraserCursorProvider: () -> Offset?"))
        assertFalse("value params would recompose the overlay per sample", signature.contains("activeStart: PointF?"))
        assertFalse(signature.contains("eraserCursorCanvas: Offset?"))
    }

    @Test
    fun `overlay reads the live state only inside its draw scope`() {
        val src = canvasSource()
        val sigStart = src.indexOf("private fun LiveStrokePreview(")
        val body = src.substring(sigStart)
        assertTrue(body.contains("val hasLiveInk = activePoints.isNotEmpty() || (activeStartProvider() != null && activeEndProvider() != null)"))
        assertTrue(body.contains("start = activeStartProvider(),"))
        assertTrue(body.contains("end = activeEndProvider(),"))
        assertTrue(body.contains("val cursorPos = eraserCursorProvider()"))
    }

    // ---- 2. Main pass is preview-free except the documented wet exception ---------

    @Test
    fun `main canvas builds exactly two preview strokes - wet-only plus overlay`() {
        val src = canvasSource()
        // Exactly two `id = "preview"` stroke constructions remain: the wet-only
        // one in the main pass and the overlay's own. (The pre-198 file built
        // three — one per page branch.)
        assertEquals(2, Regex("id = \"preview\"").findAll(src).count())
    }

    @Test
    fun `wet exception is gated on the wet-tool classifier`() {
        val src = canvasSource()
        val gateIdx = src.indexOf("val liveWetPreviewStroke = if (")
        assertTrue(gateIdx >= 0)
        val gate = src.substring(gateIdx, gateIdx + 400)
        assertTrue(gate.contains("BrushStrokeMath.isWetRenderedTool(currentTool)"))
        assertTrue(gate.contains("activePoints.isNotEmpty()"))
    }

    @Test
    fun `all three page branches pass the wet-only preview`() {
        val src = canvasSource()
        assertTrue(src.contains("previewStroke = liveWetPreviewStroke,")) // single-page + seamless
        assertTrue(src.contains("val pageLiveWetPreview = if (activeTargetPage == pageIdx) liveWetPreviewStroke else null"))
        assertTrue(src.contains("previewStroke = pageLiveWetPreview,"))
    }

    @Test
    fun `multi-color uniform no longer reads live points for dry tools`() {
        val src = canvasSource()
        val idx = src.indexOf("val wetEffectColor = if (")
        assertTrue(idx >= 0)
        val line = src.substring(idx, src.indexOf('\n', idx))
        assertTrue(line.contains("liveWetPreviewStroke != null"))
    }

    // ---- 3. Eraser cursor relocation ----------------------------------------------

    @Test
    fun `eraser aim cursor moved into the isolated node`() {
        val src = canvasSource()
        val overlayTag = src.indexOf("testTag(\"live_stroke_preview\")")
        val cursorDraw = src.indexOf("EraserGeometryPolicy.previewRadius(currentWidth, currentWidth)")
        assertTrue(overlayTag >= 0 && cursorDraw > overlayTag)
        // The STROKE-erase highlight loop moved with it. Phase 203: plain
        // per-stroke hit-test — mirrored twins are real rows now, so the old
        // mirror-the-query-point expression is gone.
        val highlight = src.indexOf("val hits = strokeContainsPoint(stroke, cursorPos)")
        assertTrue(highlight > overlayTag)
        assertFalse(
            "the view-time erase-through-mirror special-case must not resurrect",
            src.contains("Offset(mirror.x, mirror.y)")
        )
    }

    // ---- 4. Incremental layer-cache invalidation ----------------------------------

    @Test
    fun `blanket clear-on-commit effect is gone - rasters invalidate via content hash`() {
        val src = canvasSource()
        assertFalse(
            "the pre-198 effect wiped every page x layer raster on each stroke commit",
            src.contains("LaunchedEffect(strokes, layers, vibrancyBoost)")
        )
        // Unmount hygiene stays.
        assertTrue(src.contains("DisposableEffect(Unit)"))
        assertTrue(src.contains("layerBitmapCache.clear()"))
        // The lazy per-key content gates are the invalidation mechanism now.
        assertTrue(src.contains("cache.hash != strokesHash || cache.hash == 0"))
    }

    @Test
    fun `structural data classes back the hash-gate contract`() {
        val models = source("app/src/main/kotlin/com/authorss81/noteflow/data/model/StrokeModels.kt")
        assertTrue(models.contains("data class Stroke("))
        assertTrue(models.contains("data class PointF("))
    }

    // ---- 5. O(visiblePages) culling window -----------------------------------------

    @Test
    fun `paginated loop iterates the closed-form visible window only`() {
        val src = canvasSource()
        assertTrue(src.contains("com.authorss81.noteflow.services.ViewportPageWindowPolicy.visiblePageRange("))
        assertTrue(src.contains("for (pageIdx in visiblePageWindow) {"))
        assertFalse(
            "the iterate-and-skip loop must be gone",
            src.contains("if (pageBottomY < visibleTop || pageTopY > visibleBottom) continue")
        )
        // Horizontal early-out hoisted out of the per-page loop.
        assertTrue(src.contains("val horizontallyOffscreen = canvasW <= 0f"))
    }
}
