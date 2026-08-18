package com.authorss81.noteflow

import com.authorss81.noteflow.services.DockPosturePolicy
import com.authorss81.noteflow.services.FloatingWidgetDragPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 129: restored horizontal floating ink bar +
 * aspect-correct, draggable minimap.
 *
 * Covers the PROMPT verification areas: minimap aspect-fit math, dock posture
 * decision (portrait → horizontal), draggable anchor/position helpers (default
 * anchor vs dragged offset; settings gate), and the minimap visibility default
 * (OFF — the phase-35 default-true regression is reverted).
 */
class Phase129InkBarMinimapPolicyTest {

    // ---- Minimap aspect-fit math -------------------------------------------

    @Test
    fun `aspectFit fits a portrait page into the max box preserving aspect`() {
        // 1080 x 1528 world in a 120 x 140 max box — height-limited.
        val fit = MinimapGeometryPolicy.aspectFit(1080f, 1528f, 120f, 140f)
        assertEquals(140f, fit.height, 1e-1f)
        assertTrue("width must preserve aspect", fit.width in 95f..102f)
        val ratio = fit.width / fit.height
        assertEquals(1080f / 1528f, ratio, 2e-2f)
        assertTrue(fit.width <= 120f && fit.height <= 140f)
    }

    @Test
    fun `aspectFit fits a landscape page into the max box preserving aspect`() {
        val fit = MinimapGeometryPolicy.aspectFit(1528f, 1080f, 120f, 140f)
        assertEquals(120f, fit.width, 1e-1f)
        assertTrue("height must preserve aspect", fit.height in 82f..87f)
        assertEquals(1528f / 1080f, fit.width / fit.height, 2e-2f)
        assertTrue(fit.width <= 120f && fit.height <= 140f)
    }

    @Test
    fun `aspectFit keeps a square world square`() {
        val fit = MinimapGeometryPolicy.aspectFit(1000f, 1000f, 120f, 140f)
        // width-limited by 120 — square stays square.
        assertEquals(fit.width, fit.height, 1e-2f)
        assertTrue(fit.width <= 120f && fit.height <= 140f)
    }

    @Test
    fun `aspectFit never exceeds the max box even for extreme worlds`() {
        // Very tall seamless/infinite world.
        val fit = MinimapGeometryPolicy.aspectFit(1080f, 5000f, 120f, 140f)
        assertEquals(140f, fit.height, 1e-1f)
        assertTrue(fit.width <= 120f && fit.height <= 140f)
        // Very wide world.
        val wide = MinimapGeometryPolicy.aspectFit(5000f, 1080f, 120f, 140f)
        assertEquals(120f, wide.width, 1e-1f)
        assertTrue(wide.width <= 120f && wide.height <= 140f)
        // Degenerate tiny/zero inputs fall back rather than crash or enlarge.
        val zero = MinimapGeometryPolicy.aspectFit(0f, 0f, 120f, 140f)
        assertTrue(zero.width in 1f..120f && zero.height in 1f..140f)
    }

    @Test
    fun `aspectFit applies the min-side floor only when the upscale still fits the box`() {
        // World whose natural fit (height-limited 140) gives width ~32 < 48.
        val fit = MinimapGeometryPolicy.aspectFit(1080f, 4776f, 120f, 140f, minSide = 48f)
        // Upscaling to a 48-wide width would exceed the 140 max height, so the
        // natural aspect fit wins (tall + narrow is the honest representation).
        assertTrue("height-tight case keeps natural fit", fit.height <= 140f + 1e-2f)
        assertTrue("natural fit taller than wide", fit.height > fit.width)
    }

    @Test
    fun `aspectFit upscales to the min side when the box still fits`() {
        // World where an upscale to reach 48 on the smaller side keeps both <= max.
        val fit = MinimapGeometryPolicy.aspectFit(1000f, 2600f, 120f, 140f, minSide = 48f)
        assertTrue("either dimension meets the floor", fit.width >= 48f || fit.height >= 48f)
        assertTrue(fit.width in 1f..120f && fit.height in 1f..140f)
        assertEquals(1000f / 2600f, fit.width / fit.height, 2e-2f)
    }

    @Test
    fun `aspectFit result for a portrait page stays 120 wide when a more square page fills height`() {
        val fit = MinimapGeometryPolicy.aspectFit(1200f, 1200f, 120f, 140f)
        assertEquals(120f, fit.width, 1e-2f)
        assertEquals(120f, fit.height, 1e-2f)
    }

    @Test
    fun `defaultAnchorBottomEnd mirrors the bottom-right pre-35 placement`() {
        val a = MinimapGeometryPolicy.defaultAnchorBottomEnd(1080f, 2000f, 100f, 140f, marginPx = 16f)
        assertEquals(1080f - 100f - 16f, a.x, 1e-3f)
        assertEquals(2000f - 140f - 16f, a.y, 1e-3f)
    }

    @Test
    fun `defaultAnchorBottomEnd never leaves the screen for oversized maps`() {
        val a = MinimapGeometryPolicy.defaultAnchorBottomEnd(200f, 200f, 300f, 400f, marginPx = 8f)
        assertTrue(a.x >= 0f && a.x <= 200f)
        assertTrue(a.y >= 0f && a.y <= 200f)
    }

    // ---- Minimap visibility default (OFF) ----------------------------------

    @Test
    fun `minimap visibility default is OFF`() {
        assertFalse("the phase-35 default-true regression is reverted",
            MinimapGeometryPolicy.VISIBLE_BY_DEFAULT)
        assertFalse(MinimapGeometryPolicy.shouldShow(false))
        assertTrue(MinimapGeometryPolicy.shouldShow(true))
    }

    @Test
    fun `SettingsManager minimap default reads OFF`() {
        // Gradle unit tests run with the module dir as cwd; resolve the source
        // file relative to whichever is present.
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt"),
            java.io.File("src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")
        )
        val src = candidates.firstOrNull { it.exists() }
        assertTrue("SettingsManager.kt source found", src != null && src!!.exists())
        val text = src!!.readText()
        assertTrue(
            "one minimap_hud_enabled accessor present",
            Regex("\"minimap_hud_enabled\"").containsMatchIn(text)
        )
        assertTrue(
            "minimap getter default reads the OFF policy constant (no literal true)",
            text.contains("\"minimap_hud_enabled\", MinimapGeometryPolicy.VISIBLE_BY_DEFAULT)")
        )
        assertFalse(
            "the literal default-true regression must be gone",
            text.contains("\"minimap_hud_enabled\", true)")
        )
    }

    // ---- Dock posture decision (portrait → horizontal) ---------------------

    @Test
    fun `portrait maps to the horizontal pill`() {
        assertEquals(DockPosturePolicy.InkBarPosture.HORIZONTAL, DockPosturePolicy.postureFor(isLandscape = false))
        assertTrue(DockPosturePolicy.isHorizontal(isLandscape = false))
    }

    @Test
    fun `landscape maps to the vertical side column`() {
        assertEquals(DockPosturePolicy.InkBarPosture.VERTICAL, DockPosturePolicy.postureFor(isLandscape = true))
        assertFalse(DockPosturePolicy.isHorizontal(isLandscape = true))
    }

    @Test
    fun `portrait default anchor is bottom-centre 20dp above the bottom`() {
        val (x, y) = DockPosturePolicy.horizontalDefaultAnchor(1080f, 2000f, 400f, 56f, bottomMarginPx = 20f)
        assertEquals((1080f - 400f) / 2f, x, 1e-3f)
        assertEquals(2000f - 56f - 20f, y, 1e-3f)
    }

    @Test
    fun `landscape default anchor is end-centre 20dp from the end edge`() {
        val (x, y) = DockPosturePolicy.verticalDefaultAnchor(2000f, 1080f, 56f, 300f, endMarginPx = 20f)
        assertEquals(2000f - 56f - 20f, x, 1e-3f)
        assertEquals((1080f - 300f) / 2f, y, 1e-3f)
    }

    @Test
    fun `default anchors stay on screen on degenerate small screens`() {
        val (x, y) = DockPosturePolicy.horizontalDefaultAnchor(100f, 100f, 200f, 60f, 20f)
        assertTrue(x >= 0f && y >= 0f)
        val (x2, y2) = DockPosturePolicy.verticalDefaultAnchor(100f, 100f, 200f, 60f, 20f)
        assertTrue(x2 >= 0f && y2 >= 0f)
    }

    // ---- Draggable anchor/position helpers (settings gate) -----------------

    @Test
    fun `draggable settings default OFF for both widgets`() {
        assertFalse(FloatingWidgetDragPolicy.INK_BAR_DRAGGABLE_DEFAULT)
        assertFalse(FloatingWidgetDragPolicy.MINIMAP_DRAGGABLE_DEFAULT)
        assertFalse(FloatingWidgetDragPolicy.INK_BAR_SNAP_TO_EDGE_DEFAULT)
        assertFalse(FloatingWidgetDragPolicy.INK_BAR_DOCK_PERSIST_DEFAULT)
    }

    @Test
    fun `mayDrag fails closed and obeys the gate`() {
        assertFalse(FloatingWidgetDragPolicy.mayDrag(false))
        assertTrue(FloatingWidgetDragPolicy.mayDrag(true))
        assertFalse(FloatingWidgetDragPolicy.maySnapToEdge(false))
        assertTrue(FloatingWidgetDragPolicy.maySnapToEdge(true))
        assertFalse(FloatingWidgetDragPolicy.mayPersistDock(false))
        assertTrue(FloatingWidgetDragPolicy.mayPersistDock(true))
    }

    @Test
    fun `dragged offset applies only when enabled AND dragged`() {
        assertFalse(FloatingWidgetDragPolicy.shouldApplyDraggedPosition(enabled = false, hasDraggedOffset = true))
        assertFalse(FloatingWidgetDragPolicy.shouldApplyDraggedPosition(enabled = false, hasDraggedOffset = false))
        assertFalse(FloatingWidgetDragPolicy.shouldApplyDraggedPosition(enabled = true, hasDraggedOffset = false))
        assertTrue(FloatingWidgetDragPolicy.shouldApplyDraggedPosition(enabled = true, hasDraggedOffset = true))
    }

    @Test
    fun `restingPosition is the dragged offset only when the gate passes`() {
        val def = FloatingWidgetDragPolicy.Offset(100f, 900f)
        val dragged = FloatingWidgetDragPolicy.Offset(40f, 200f)

        val off = FloatingWidgetDragPolicy.restingPosition(false, dragged.x, dragged.y, def.x, def.y)
        assertEquals(def.x, off.x, 1e-3f)
        assertEquals(def.y, off.y, 1e-3f)

        val on = FloatingWidgetDragPolicy.restingPosition(true, dragged.x, dragged.y, def.x, def.y)
        assertEquals(dragged.x, on.x, 1e-3f)
        assertEquals(dragged.y, on.y, 1e-3f)

        val neverDragged = FloatingWidgetDragPolicy.restingPosition(true, null, null, def.x, def.y)
        assertEquals(def.x, neverDragged.x, 1e-3f)
        assertEquals(def.y, neverDragged.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea keeps the widget inside the safe region`() {
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = -50f, y = 5000f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 48f, bottom = 24f, start = 8f, end = 8f
        )
        assertEquals(8f, c.x, 1e-3f)
        assertEquals(2000f - 56f - 24f, c.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea is identity for in-bounds positions`() {
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 200f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 0f, bottom = 0f, start = 0f, end = 0f
        )
        assertEquals(100f, c.x, 1e-3f)
        assertEquals(200f, c.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea tolerates a widget larger than the screen`() {
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 10f, y = 10f, screenW = 100f, screenH = 100f,
            w = 300f, h = 200f, top = 8f, bottom = 8f, start = 8f, end = 8f
        )
        assertEquals(8f, c.x, 1e-3f)
        assertEquals(8f, c.y, 1e-3f)
    }
}