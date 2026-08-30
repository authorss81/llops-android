package com.authorss81.noteflow

import com.authorss81.noteflow.services.DockPosturePolicy
import com.authorss81.noteflow.services.FloatingWidgetDragPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for Phase 248 (AUDIT_2026-08-30): the minimap must anchor to
 * the canvas PANE it lives in, not the device screen, and the floating ink
 * bar must clamp its drag + resting anchors below the Scaffold topBar — not
 * just the status-bar inset.
 *
 * The anchor/clamp policy matchers are direct unit checks; the wiring in
 * AnnotationCanvas.kt / EditorScreen.kt is pinned by source assertions so a
 * future edit can't silently re-bind the minimap to device dims or drop the
 * topBar reservation.
 */
class Phase248MinimapPaneSizeTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/$relative"),
            java.io.File("src/main/kotlin/$relative")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("main source found: $relative", file != null)
        return file!!.readText()
    }

    // ---- Minimap anchors to the PANE, not the full window -------------------

    @Test
    fun `minimap default anchor uses the canvas pane size not the device screen`() {
        // A 600-wide pane inside a 1200-wide window: the old device-dims math
        // anchored the 120-wide map at x=1064 (past the visible pane); the pane
        // math keeps it inside the 600px canvas.
        val pane = MinimapGeometryPolicy.defaultAnchorBottomEnd(600f, 800f, 120f, 140f, marginPx = 16f)
        assertEquals(600f - 120f - 16f, pane.x, 1e-3f)
        assertEquals(800f - 140f - 16f, pane.y, 1e-3f)
        assertEquals(464f, pane.x, 1e-3f)
        assertEquals(644f, pane.y, 1e-3f)

        val windowWide = MinimapGeometryPolicy.defaultAnchorBottomEnd(1200f, 800f, 120f, 140f, marginPx = 16f)
        assertEquals(1200f - 120f - 16f, windowWide.x, 1e-3f)
        assertTrue(
            "pane anchor must NOT equal the window-wide anchor",
            pane.x != windowWide.x
        )
    }

    @Test
    fun `minimap anchor with pane smaller than the map stays inside the pane`() {
        val a = MinimapGeometryPolicy.defaultAnchorBottomEnd(200f, 200f, 300f, 400f, marginPx = 8f)
        assertTrue(a.x >= 0f && a.x <= 200f)
        assertTrue(a.y >= 0f && a.y <= 200f)
    }

    // ---- Floating ink bar: topBar reservation in the drag clamp -------------

    @Test
    fun `constrainWithinSafeArea reserves the topBar band above the insets`() {
        // top inset 0, topBar content height 56: a drag toward y=10 stops at y=56.
        val clampedUp = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 10f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 0f, bottom = 24f, start = 8f, end = 8f,
            topReservedPx = 56f
        )
        assertEquals(56f, clampedUp.y, 1e-3f)

        val below = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 80f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 0f, bottom = 24f, start = 8f, end = 8f,
            topReservedPx = 56f
        )
        assertEquals(80f, below.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea composes the status-bar inset with the topBar reserve`() {
        // top = status bar 48, reserve = topBar content 56 → effective 104.
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 10f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 48f, bottom = 24f, start = 8f, end = 8f,
            topReservedPx = 56f
        )
        assertEquals(48f + 56f, c.y, 1e-3f)
        assertEquals(104f, c.y, 1e-3f)

        val mid = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 200f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 48f, bottom = 24f, start = 8f, end = 8f,
            topReservedPx = 56f
        )
        assertEquals(200f, mid.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea without a reservation keeps the Phase129 behaviour`() {
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = -50f, y = 5000f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 48f, bottom = 24f, start = 8f, end = 8f
        )
        assertEquals(8f, c.x, 1e-3f)
        assertEquals(2000f - 56f - 24f, c.y, 1e-3f)

        val id = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 100f, y = 200f, screenW = 1000f, screenH = 2000f,
            w = 200f, h = 56f, top = 0f, bottom = 0f, start = 0f, end = 0f
        )
        assertEquals(100f, id.x, 1e-3f)
        assertEquals(200f, id.y, 1e-3f)
    }

    @Test
    fun `constrainWithinSafeArea tolerates a widget larger than the pane with a reserve`() {
        val c = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            x = 10f, y = 10f, screenW = 100f, screenH = 100f,
            w = 300f, h = 200f, top = 8f, bottom = 8f, start = 8f, end = 8f,
            topReservedPx = 56f
        )
        assertEquals(8f, c.x, 1e-3f)
        assertEquals("reserve anchors the oversized widget at the reserved safe top",
            8f + 56f, c.y, 1e-3f)
    }

    // ---- Resting anchors respect the reserved topBar band -------------------

    @Test
    fun `default anchors keep the Phase129 results when no reservation is passed`() {
        val (x, y) = DockPosturePolicy.horizontalDefaultAnchor(1080f, 2000f, 400f, 56f, bottomMarginPx = 20f)
        assertEquals((1080f - 400f) / 2f, x, 1e-3f)
        assertEquals(2000f - 56f - 20f, y, 1e-3f)

        val (x2, y2) = DockPosturePolicy.verticalDefaultAnchor(2000f, 1080f, 56f, 300f, endMarginPx = 20f)
        assertEquals(2000f - 56f - 20f, x2, 1e-3f)
        assertEquals((1080f - 300f) / 2f, y2, 1e-3f)
    }

    @Test
    fun `default anchors clamp at the reserved line on short windows`() {
        // Horizontal pill: bottom fit lands above the reserve → clamps to it.
        val (x, y) = DockPosturePolicy.horizontalDefaultAnchor(100f, 100f, 200f, 60f, 20f, 56f)
        assertTrue(x >= 0f && y >= 0f)
        assertEquals(56f, y, 1e-3f)

        // Vertical column: the vertical centre 0 clamps to the reserve.
        val (x2, y2) = DockPosturePolicy.verticalDefaultAnchor(2000f, 300f, 56f, 300f, 20f, 56f)
        assertEquals(2000f - 56f - 20f, x2, 1e-3f)
        assertEquals(56f, y2, 1e-3f)

        // Bottom-centre pill is unaffected by the top reserve (harmless pass).
        val (x3, y3) = DockPosturePolicy.horizontalDefaultAnchor(1080f, 2000f, 400f, 56f, 20f, 56f)
        assertEquals((1080f - 400f) / 2f, x3, 1e-3f)
        assertEquals(2000f - 56f - 20f, y3, 1e-3f)
    }

    @Test
    fun `phase-129 margin constants are preserved`() {
        assertEquals(20f, DockPosturePolicy.DEFAULT_BOTTOM_MARGIN_PX, 1e-6f)
        assertEquals(20f, DockPosturePolicy.DEFAULT_END_MARGIN_PX, 1e-6f)
    }

    // ---- Source pins: the minimap is pane-bound, the dock is topBar-aware ----

    @Test
    fun `AnnotationCanvas minimap block binds to paneW and paneH only`() {
        val canvas = mainSource("com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        assertTrue("minimap block present", canvas.contains("if (showMinimap) {"))
        val minimap = canvas.substring(canvas.indexOf("if (showMinimap) {"))

        assertFalse(
            "device screenWidthDp must not appear anywhere",
            canvas.contains("LocalConfiguration.current.screenWidthDp")
        )
        assertFalse(
            "device screenHeightDp must not appear anywhere",
            canvas.contains("LocalConfiguration.current.screenHeightDp")
        )
        assertTrue(
            "pane dims are the canvas-box dimensions",
            minimap.contains("val paneW = canvasBoxW") && minimap.contains("val paneH = canvasBoxH")
        )
        assertTrue(
            "the minimap drag pointerInput keys are the pane dims (not screenW/H)",
            minimap.contains(".pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)")
        )
        assertFalse(
            "the minimap drag keys must never reference the old device dims",
            minimap.contains("pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, screenW, screenH)")
        )
        assertTrue(
            "the minimap drag clamp uses the pane dims",
            minimap.contains("constrainWithinSafeArea") &&
                minimap.contains("dragBase.y + change.position.y - dragStart.y") &&
                minimap.contains("paneW, paneH, minimapWidthPx, minimapHeightPx")
        )
    }

    @Test
    fun `EditorScreen docks the floating bar below the measured topBar`() {
        val editor = mainSource("com/authorss81/noteflow/ui/screens/EditorScreen.kt")

        assertTrue(
            "the Scaffold topBar is measured into topBarHeightPx",
            editor.contains("var topBarHeightPx by remember { mutableFloatStateOf(0f) }")
        )
        assertTrue(
            "the topBar Surface reports its size",
            editor.contains(".onSizeChanged { topBarHeightPx = it.height.toFloat() }")
        )
        assertTrue(
            "the dock receives the topBar height",
            editor.contains("topBarHeightPx = topBarHeightPx")
        )
        assertTrue(
            "the reserved band is the topBar content height (measured minus inset)",
            editor.contains("val topReservedPx = (topBarHeightPx - topInsetPx).coerceAtLeast(0f)")
        )
        assertTrue(
            "the drag clamp passes the reservation by name",
            editor.contains("constrainWithinSafeArea") && editor.contains("topReservedPx = topReservedPx")
        )
    }
}
