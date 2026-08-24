package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.SymmetryCommitPolicy
import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 203: capture-time symmetry baking ([SymmetryCommitPolicy]).
 *
 * Contract under test: a stroke committed while a symmetry mode is active gains
 * an independent mirrored TWIN row at capture time; toggling the mode later must
 * never rewrite history. These tests pin the pure geometry/attribute rules of
 * the bake; the canvas wiring is pinned structurally by
 * [Phase203SymmetryCaptureBakeTest].
 */
class SymmetryCommitPolicyTest {

    private fun freehandStroke(
        id: String = "src-1",
        tool: StrokeTool = StrokeTool.PEN,
        layerId: String? = "layer_7"
    ) = Stroke(
        id = id,
        tool = tool,
        colorInt = 0xFF2244CC.toInt(),
        width = 4.5f,
        filled = false,
        text = "",
        points = listOf(
            PointF(10f, 20f, pressure = 0.25f, tilt = 0.1f, timestampMs = 111L),
            PointF(60f, 80f, pressure = 0.75f, tilt = 0.4f, timestampMs = 222L),
            PointF(120f, 30f, pressure = null, tilt = null, timestampMs = null)
        ),
        start = PointF(10f, 20f),
        end = PointF(120f, 30f),
        pdfPage = 2,
        timestampMs = 999L,
        isAdvanced = true,
        layerId = layerId,
        colorMode = StrokeColorMode.RAINBOW,
        colorSeed = 42,
        gradientToColorInt = 0xFFCC4422.toInt()
    )

    // ---- twin geometry -----------------------------------------------------

    @Test
    fun `twin points are the exact mirror of the source points`() {
        val src = freehandStroke()
        for (mode in listOf(SymmetryMode.VERTICAL, SymmetryMode.HORIZONTAL, SymmetryMode.RADIAL)) {
            val twin = SymmetryCommitPolicy.bakedTwin(src, mode, centerX = 200f, centerY = 100f)
            assertEquals("mode=$mode point count", src.points.size, twin.points.size)
            src.points.zip(twin.points).forEach { (p, m) ->
                val expected = SymmetryHelper.mirrorPoint(p.x, p.y, mode, 200f, 100f)
                assertEquals("mode=$mode x", expected.x, m.x, 0f)
                assertEquals("mode=$mode y", expected.y, m.y, 0f)
            }
            val expectedStart = SymmetryHelper.mirrorPoint(src.start!!.x, src.start!!.y, mode, 200f, 100f)
            assertEquals(expectedStart.x, twin.start!!.x, 0f)
            assertEquals(expectedStart.y, twin.start!!.y, 0f)
            val expectedEnd = SymmetryHelper.mirrorPoint(src.end!!.x, src.end!!.y, mode, 200f, 100f)
            assertEquals(expectedEnd.x, twin.end!!.x, 0f)
            assertEquals(expectedEnd.y, twin.end!!.y, 0f)
        }
    }

    @Test
    fun `vertical twin reflects x and keeps y with a concrete center`() {
        val twin = SymmetryCommitPolicy.bakedTwin(freehandStroke(), SymmetryMode.VERTICAL, 200f, 100f)
        assertEquals(390f, twin.points[0].x, 1e-4f) // 2*200 - 10
        assertEquals(20f, twin.points[0].y, 1e-4f)
        assertEquals(340f, twin.points[1].x, 1e-4f) // 2*200 - 60
        assertEquals(280f, twin.end!!.x, 1e-4f) // 2*200 - 120
    }

    @Test
    fun `per-point pressure tilt and timestamp survive the bake`() {
        val twin = SymmetryCommitPolicy.bakedTwin(freehandStroke(), SymmetryMode.HORIZONTAL, 200f, 100f)
        assertEquals(0.25f, twin.points[0].pressure!!, 0f)
        assertEquals(0.1f, twin.points[0].tilt!!, 0f)
        assertEquals(111L, twin.points[0].timestampMs!!)
        assertEquals(0.75f, twin.points[1].pressure!!, 0f)
        assertNull(twin.points[2].pressure)
        assertNull(twin.points[2].tilt)
    }

    // ---- exclusions ----------------------------------------------------------

    @Test
    fun `TEXT is excluded from baking for every active mode`() {
        for (mode in listOf(SymmetryMode.VERTICAL, SymmetryMode.HORIZONTAL, SymmetryMode.RADIAL)) {
            assertFalse(SymmetryCommitPolicy.shouldBakeMirror(mode, StrokeTool.TEXT))
        }
    }

    @Test
    fun `OFF is excluded for every tool`() {
        StrokeTool.entries.forEach { tool ->
            assertFalse("tool=$tool", SymmetryCommitPolicy.shouldBakeMirror(SymmetryMode.OFF, tool))
        }
    }

    @Test
    fun `every non-text tool bakes while a mode is active`() {
        val drawing = StrokeTool.entries.filter { it != StrokeTool.TEXT }
        for (mode in listOf(SymmetryMode.VERTICAL, SymmetryMode.HORIZONTAL, SymmetryMode.RADIAL)) {
            drawing.forEach { tool ->
                assertTrue("mode=$mode tool=$tool", SymmetryCommitPolicy.shouldBakeMirror(mode, tool))
            }
        }
    }

    // ---- center freezing -------------------------------------------------------

    @Test
    fun `the twin uses the FROZEN capture-time center not a recomputed one`() {
        val src = freehandStroke()
        // Two different frozen centers must produce DIFFERENT twins...
        val twinPage0 = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.HORIZONTAL, 300f, 764f)
        val twinPage3 = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.HORIZONTAL, 300f, 2356f)
        assertNotEquals(twinPage0.points[1].y, twinPage3.points[1].y, 0f)
        // ...and each must equal the manual mirror about ITS OWN frozen value.
        val manual = SymmetryHelper.mirrorPoint(src.points[1].x, src.points[1].y, SymmetryMode.HORIZONTAL, 300f, 2356f)
        assertEquals(manual.x, twinPage3.points[1].x, 0f)
        assertEquals(manual.y, twinPage3.points[1].y, 0f)
    }

    @Test
    fun `page1 world-center horizontal twin stays inside its own page slab`() {
        // Phase-202 finding, now applied to baking: the frozen center must be in
        // WORLD space so the twin lands back on the same page.
        val pageHeight = 1528f
        val pageTop = 1592f // page 1 top (stride 1528+64)
        val worldCenterY = pageTop + pageHeight / 2f
        val src = freehandStroke().copy(points = listOf(PointF(100f, pageTop + 100f)))
        val twin = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.HORIZONTAL, 300f, worldCenterY)
        val localTwinY = twin.points.single().y - pageTop
        assertTrue(
            "twin must land INSIDE the page slab, was $localTwinY",
            localTwinY in 0f..pageHeight
        )
        assertEquals(pageHeight - 100f, localTwinY, 1e-3f)
    }

    // ---- identity + attribute round-trip ----------------------------------------

    @Test
    fun `twin gets its own fresh id distinct from the source`() {
        val src = freehandStroke(id = "original")
        val t1 = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.VERTICAL, 200f, 100f)
        val t2 = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.VERTICAL, 200f, 100f)
        assertNotEquals("original", t1.id)
        assertNotEquals(src.id, t2.id)
        assertNotEquals("two bakes must never collide", t1.id, t2.id)
    }

    @Test
    fun `visual attributes round-trip unchanged onto the twin`() {
        val src = freehandStroke(layerId = "layer_9")
        val twin = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.RADIAL, 200f, 100f)
        assertEquals(src.tool, twin.tool)
        assertEquals(src.colorInt, twin.colorInt)
        assertEquals(src.width, twin.width, 0f)
        assertEquals(src.filled, twin.filled)
        assertEquals(src.text, twin.text)
        assertEquals(src.pdfPage, twin.pdfPage)
        assertEquals(src.timestampMs, twin.timestampMs)
        assertEquals(src.isAdvanced, twin.isAdvanced)
        assertEquals(src.layerId, twin.layerId)
        assertEquals(src.colorMode, twin.colorMode)
        assertEquals(src.colorSeed, twin.colorSeed)
        assertEquals(src.gradientToColorInt, twin.gradientToColorInt)
        // Same layer means the twin renders in the SAME layer pass as the source.
        assertEquals("layer_9", twin.layerId)
    }

    @Test
    fun `double bake returns the source geometry - mirror involution`() {
        val src = freehandStroke()
        val once = SymmetryCommitPolicy.bakedTwin(src, SymmetryMode.VERTICAL, 200f, 100f)
        val twice = SymmetryCommitPolicy.bakedTwin(once, SymmetryMode.VERTICAL, 200f, 100f)
        src.points.zip(twice.points).forEach { (p, q) ->
            assertEquals(p.x, q.x, 1e-4f)
            assertEquals(p.y, q.y, 1e-4f)
        }
    }

    @Test
    fun `shape strokes with start end only bake their anchors`() {
        val shape = Stroke(
            id = "rect-1",
            tool = StrokeTool.RECTANGLE,
            width = 3f,
            points = emptyList(),
            start = PointF(50f, 50f),
            end = PointF(150f, 90f),
            pdfPage = 0,
            layerId = "layer_default"
        )
        assertTrue(SymmetryCommitPolicy.shouldBakeMirror(SymmetryMode.VERTICAL, StrokeTool.RECTANGLE))
        val twin = SymmetryCommitPolicy.bakedTwin(shape, SymmetryMode.VERTICAL, 100f, 70f)
        assertTrue(twin.points.isEmpty())
        assertEquals(150f, twin.start!!.x, 0f) // 2*100 - 50
        assertEquals(50f, twin.end!!.x, 0f) // 2*100 - 150
    }
}
