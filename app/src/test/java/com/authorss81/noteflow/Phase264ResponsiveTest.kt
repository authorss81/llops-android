package com.authorss81.noteflow

import com.authorss81.noteflow.services.InkBarDrawingPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-264 (responsive residuals): menus + map scale + drag keys + yield.
 *
 * Pure-JVM policy checks plus source pins so the four fixes can't regress:
 *  1. overflow-menu caps never read LocalConfiguration (stale through freeform
 *     drag); they measure the live window / pane box.
 *  2. every minimap site uses the SINGLE minOf map scale (tap/drag/draw agree).
 *  3. drag-gesture pointerInput keys are minimal; measured state rides
 *     rememberUpdatedState (no mid-drag restart, no 100ms-stale zoom).
 *  4. the ink-bar yield fires whenever shouldYield says so (dragged-to-top +
 *     PEN included), on the usable height, for both postures, re-checked after
 *     snap; the minimap offset survives rotation.
 */
class Phase264ResponsiveTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/$relative"),
            java.io.File("src/main/kotlin/$relative")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("main source found: $relative", file != null)
        return file!!.readText()
    }

    // ---- 1. overflow-menu cap: no LocalConfiguration ------------------------

    @Test
    fun `overflow menu cap never reads LocalConfiguration`() {
        val src = mainSource("com/authorss81/noteflow/ui/components/OverflowMenuSupport.kt")
        assertFalse(
            "menu cap must not read LocalConfiguration (stale through freeform drag)",
            src.contains("LocalConfiguration.current")
        )
        assertTrue(
            "window cap measures the live window (layout listener, not config pulse)",
            src.contains("OnGlobalLayoutListener") && src.contains("rememberLiveWindowSizeDp")
        )
        assertTrue(
            "height cap rides the live window height",
            src.contains("overflowMenuScrollModifier") && src.contains("maxMenuHeightDp(windowHeightDp)")
        )
        assertTrue(
            "width cap rides the live window width",
            src.contains("overflowMenuWidthModifier") && src.contains("maxMenuWidthDp(windowWidthDp)")
        )
    }

    // ---- 2. single mapScale formula + round-trip ----------------------------

    @Test
    fun `mapScale is minOf both axes for a tall infinite canvas`() {
        // Tall world (1528x4000) in a fitted map box: width-only scale
        // over-shoots the height axis; the single minOf keeps both inside.
        val worldW = 1528f
        val worldH = 4000f
        val boxW = 100f
        val boxH = 120f
        val scale = MinimapGeometryPolicy.mapScale(boxW, boxH, worldW, worldH)
        assertEquals(minOf(boxW / worldW, boxH / worldH), scale, 1e-6f)
        val widthOnly = boxW / worldW
        assertTrue(
            "width-only scale must differ from the unified scale on a tall world",
            kotlin.math.abs(widthOnly - scale) > 1e-6f
        )
        // Height fits exactly; width-only would overflow it.
        assertEquals(boxH, worldH * scale, 1e-3f)
        assertTrue(worldW * widthOnly <= boxW + 1e-3f)
        assertTrue(
            "width-only scale overflows the box height",
            worldH * widthOnly > boxH
        )
    }

    @Test
    fun `map tap round-trips through the single scale`() {
        val worldW = 1528f
        val worldH = 4000f
        val boxW = 100f
        val boxH = 120f
        val scale = MinimapGeometryPolicy.mapScale(boxW, boxH, worldW, worldH)
        // Inside the fitted map ((worldW*scale)=45.84 wide, full 120 tall).
        val world = MinimapGeometryPolicy.mapToWorld(40f, 60f, scale, worldW, worldH)
        val back = MinimapGeometryPolicy.worldToMap(world.x, world.y, scale)
        assertEquals(40f, back.x, 1e-3f)
        assertEquals(60f, back.y, 1e-3f)
        // Clamp: touches past the edge pin to the world bounds.
        val clamped = MinimapGeometryPolicy.mapToWorld(5000f, -10f, scale, worldW, worldH)
        assertEquals(worldW, clamped.x, 1e-3f)
        assertEquals(0f, clamped.y, 1e-3f)
    }

    @Test
    fun `AnnotationCanvas uses the single mapScale policy at every site`() {
        val canvas = mainSource("com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        val uses = canvas.split("MinimapGeometryPolicy.mapScale(").size - 1
        assertTrue("tap/drag handler + thumbnail draw use the policy (got $uses)", uses >= 2)
        assertFalse(
            "no bare width-only map scale may survive",
            canvas.contains("val mapScale = size.width / spW")
        )
    }

    // ---- 3. stable gesture keys ---------------------------------------------

    @Test
    fun `minimap drag keys are the gate plus pane dims only`() {
        val canvas = mainSource("com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        val minimap = canvas.substring(canvas.indexOf("if (showMinimap) {"))
        assertTrue(
            "outer drag keys hold the gate + pane dims",
            minimap.contains(".pointerInput(minimapDraggable, paneW, paneH)")
        )
        assertTrue(
            "measured drag state rides rememberUpdatedState",
            minimap.contains("minimapGeomState") && minimap.contains("rememberUpdatedState")
        )
    }

    @Test
    fun `minimap tap and drag share one gesture with structural keys`() {
        val canvas = mainSource("com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        val minimap = canvas.substring(canvas.indexOf("if (showMinimap) {"))
        assertTrue(
            "tap+drag are unified via awaitEachGesture",
            minimap.contains("awaitEachGesture")
        )
        assertTrue(
            "map gesture keys are structural (mode/count/paging) only",
            minimap.contains(".pointerInput(isContinuousMode, dynamicPageCount, divideIntoPages)")
        )
        assertFalse(
            "the stale layoutZoomScale key must be gone from the map gesture",
            minimap.contains("divideIntoPages, layoutZoomScale, paneW")
        )
        assertTrue(
            "live zoom/pan ride rememberUpdatedState",
            minimap.contains("mapZoomPanState") && minimap.contains("mapWorldState")
        )
    }

    @Test
    fun `dock drag keys hold the gate only`() {
        val editor = mainSource("com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        assertTrue(
            "dock drag keys hold the draggable gate only",
            editor.contains(".pointerInput(draggable)")
        )
        assertFalse(
            "measured dock dims must not be gesture keys",
            editor.contains(".pointerInput(draggable, screenW, screenH, dockW, dockH)")
        )
        assertTrue(
            "dock geometry rides rememberUpdatedState",
            editor.contains("dockGeomState") && editor.contains("DockDragGeom")
        )
    }

    // ---- 4. yield: dragged-to-top + PEN, usable height, both postures -------

    @Test
    fun `yield fires when dragged-to-top with a drawing tool active`() {
        // Usable height (window minus system bars); PEN dragged to y=0 yields.
        val usable = 2000f - 48f - 24f
        assertTrue(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 0f,
                availableHeight = usable
            )
        )
        // Threshold is the usable midpoint, not the full window.
        assertTrue(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = usable / 2f - 1f,
                availableHeight = usable
            )
        )
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = usable / 2f,
                availableHeight = usable
            )
        )
        // Navigation tools never yield, wherever the bar sits.
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = false,
                barTopY = 0f,
                availableHeight = usable
            )
        )
    }

    @Test
    fun `EditorScreen yields on usable height for both postures with a snap re-check`() {
        val editor = mainSource("com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        assertTrue(
            "threshold is the usable height (window minus system bars)",
            editor.contains("val usableHeightPx = (screenH - topInsetPx - bottomInsetPx)")
        )
        assertTrue(
            "resting yield reads the usable height",
            editor.contains("availableHeight = usableHeightPx")
        )
        val yieldCall = editor.indexOf("InkBarDrawingPolicy.shouldYieldDrawingArea(")
        assertTrue("yield call present", yieldCall >= 0)
        val guardWindow = editor.substring(kotlin.math.max(0, yieldCall - 400), yieldCall)
        assertFalse(
            "yield must not be gated on the horizontal posture",
            guardWindow.contains("horizontalPosture &&")
        )
        assertTrue(
            "the snap target is re-checked through the yield",
            editor.contains("applyYieldIfNeeded")
        )
    }

    @Test
    fun `minimap drag offset survives rotation and no bottomReserve magic exists`() {
        val canvas = mainSource("com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        assertTrue(
            "drag offset is rememberSaveable",
            canvas.contains("minimapDragOffsetSaver") &&
                canvas.contains("rememberSaveable(stateSaver = minimapDragOffsetSaver)")
        )
        assertFalse(
            "no bottomReservePx magic may exist",
            canvas.contains("bottomReservePx")
        )
    }
}
