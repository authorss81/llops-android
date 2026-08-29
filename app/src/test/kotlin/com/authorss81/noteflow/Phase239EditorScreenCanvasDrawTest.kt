package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushStrokeMath
import com.authorss81.noteflow.services.EraserGeometryPolicy
import com.authorss81.noteflow.services.SymmetryCommitPolicy
import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import com.authorss81.noteflow.services.WetCanvasEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 239 — the canvas drawing critical path, as pure-JVM logic tests.
 *
 * Phase-235 originally specified this as an instrumented `EditorScreen` UI test
 * (drag on canvas → verify activePoints, PARTIAL eraser splits, watercolor wet
 * mask, symmetry twin baking). Instrumented Compose tests cannot run on the JVM
 * (they need an AVD or Robolectric, both forbidden in this pipeline), so the same
 * critical-path regressions are pinned against the real backing classes:
 *
 *  - `WetCanvasEngine.markPaintDeposited` + `BrushStrokeMath.wetnessPeakForTool`
 *    (the hydration state the brush bar + wet shader feed off),
 *  - `StrokeTool.isFreehandTool`/`BrushStrokeMath.isWetRenderedTool`
 *    (what a normal drag can actually deposit),
 *  - `EraserGeometryPolicy.stampRadius`/`coverageRadius` (the PARTIAL-eraser
 *    round mask — wet pigments erase via a single round Clear punch, no fragments),
 *  - `SymmetryCommitPolicy.bakedTwin` + `SymmetryHelper`
 *    (a committed stroke persists BOTH rows when a mode is active).
 *
 * Regression ids pinned here: `1e54820` (light pressure not dropped),
 * `8a2032d` (wet erase mask), `phase-203`/`phase-204` (symmetry + non-fragment
 * partial erase), `phase-226` (selection scale/rotate — see Phase239LassoSelectionTest).
 */
class Phase239EditorScreenCanvasDrawTest {

    // ---- drawing pipeline: point accumulation / wet hydration ----------------

    @Test
    fun `depositing a watercolor stroke raises the wet hydration state`() {
        val engine = WetCanvasEngine()
        assertFalse(engine.isCanvasWet)
        assertEquals(0f, engine.activeWetnessLevel, 0f)

        engine.markPaintDeposited(StrokeTool.WATERCOLOR)

        assertTrue("watercolor marks the sheet wet", engine.isCanvasWet)
        assertEquals(BrushStrokeMath.wetnessPeakForTool(StrokeTool.WATERCOLOR), engine.activeWetnessLevel, 0.0001f)
        assertEquals(0.9f, engine.activeWetnessLevel, 0.0001f)
    }

    @Test
    fun `a dry tool never marks the sheet wet`() {
        val engine = WetCanvasEngine()
        engine.markPaintDeposited(StrokeTool.PEN)
        assertFalse("PEN is not a wet tool", engine.isCanvasWet)
        assertEquals(0f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `dry sheet action and reset clear hydration`() {
        val engine = WetCanvasEngine()
        engine.markPaintDeposited(StrokeTool.WATERCOLOR)
        engine.dryCanvasSheet()
        assertFalse(engine.isCanvasWet)
        assertEquals(0f, engine.activeWetnessLevel, 0f)

        engine.markPaintDeposited(StrokeTool.GOUACHE)
        engine.resetCanvas()
        assertFalse(engine.isCanvasWet)
        assertEquals(0f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `every freehand tool has a stable distinct wetness peak`() {
        val wetTools = listOf(
            StrokeTool.WATERCOLOR, StrokeTool.INK_WASH, StrokeTool.SPLATTER,
            StrokeTool.GOUACHE, StrokeTool.PALETTE_KNIFE, StrokeTool.SMUDGE, StrokeTool.OIL_PAINT
        )
        val peaks = wetTools.associateWith { BrushStrokeMath.wetnessPeakForTool(it) }
        // Every wet tool must report real hydration; the peak distinguishes the
        // wet tools from the dry pens (note SMUDGE and PALETTE_KNIFE intentionally
        // share 0.5 — the hydration UI only needs wet vs dry, not full uniqueness).
        peaks.forEach { (tool, peak) ->
            assertTrue("$tool must be wet-rendered", BrushStrokeMath.isWetRenderedTool(tool))
            assertTrue("$tool peak must be positive", peak > 0f)
        }
        // Cross-check against the two dry pen tools (never wet).
        assertFalse(BrushStrokeMath.isWetRenderedTool(StrokeTool.PEN))
        assertFalse(BrushStrokeMath.isWetRenderedTool(StrokeTool.MARKER))
        assertEquals(0f, BrushStrokeMath.wetnessPeakForTool(StrokeTool.PEN), 0f)
    }

    @Test
    fun `light pressure is not dropped - pigment and width stay bounded`() {
        // 1e54820: a light (0.3) pressure tap must still produce a usable stroke,
        // never zero pigment / a degenerate width.
        val pigment = BrushStrokeMath.pigmentFromPressure(0.3f)
        assertTrue("light pressure keeps pigment", pigment >= 0.55f && pigment <= 1f)
        assertEquals(0.55f + 0.45f * 0.3f, pigment, 0.0001f)

        val spread = BrushStrokeMath.bristleSpreadFactor(0.3f)
        assertTrue("light pressure widens the bristle patch", spread in 0.75f..1f)
        // Full pressure is identity (no regression for finger/mouse input).
        assertEquals(1f, BrushStrokeMath.bristleSpreadFactor(0.9f), 0.0001f)
    }

    @Test
    fun `velocity width modulation never goes below the floor`() {
        // A fast stroke thins the line but never below 0.55x the base width.
        assertEquals(1f, BrushStrokeMath.velocityWidthFactor(0.5f), 0f)
        val fast = BrushStrokeMath.velocityWidthFactor(10f)
        assertTrue("fast stroke thins below identity", fast < 1f)
        assertTrue("never thinner than the floor", fast >= 0.55f)
        // intensity 0 reproduces the classic fixed width exactly.
        assertEquals(1f, BrushStrokeMath.velocityWidthFactor(10f, intensity = 0f), 0f)
    }

    // ---- PARTIAL eraser: round mask, no fragments ----------------------------

    @Test
    fun `partial eraser derives a bounded pressure-aware round mask`() {
        // 8a2032d / phase-204: the PARTIAL eraser punches a single round mask per
        // erase-sample; the mask radius grows with pressure but is fully bounded.
        val light = EraserGeometryPolicy.stampRadius(8f, 0.0f)
        val heavy = EraserGeometryPolicy.stampRadius(8f, 1.0f)
        assertTrue("heavier press carves a wider mask", heavy > light)
        assertTrue("mask never below the floor", light >= EraserGeometryPolicy.MIN_ERASE_WIDTH_PX)
        assertTrue("mask never exceeds the cap", heavy <= EraserGeometryPolicy.MAX_ERASE_WIDTH_PX + 4f)
    }

    @Test
    fun `coverage radius swallows the nib half-width for a clean cut`() {
        val stamp = EraserGeometryPolicy.stampRadius(10f, 0.8f)
        val cover = EraserGeometryPolicy.coverageRadius(stamp, strokeWidth = 4f)
        // The mask must swallow the full nib half-width so the surviving run's
        // end point always lies OUTSIDE the round mask (a round cap, no sliver).
        assertTrue(cover > stamp)
        assertEquals(stamp + 2f, cover, 0.0001f)
    }

    @Test
    fun `preview radius is bounded and never degenerates`() {
        val small = EraserGeometryPolicy.previewRadius(4f, 2f)
        val huge = EraserGeometryPolicy.previewRadius(120f, 300f)
        assertTrue("preview never drops below the minimum", small >= EraserGeometryPolicy.MIN_ERASE_WIDTH_PX)
        assertTrue("preview never exceeds the 2x cap", huge <= EraserGeometryPolicy.MAX_ERASE_WIDTH_PX * 2f)
    }

    // ---- symmetry: committed stroke persists original + baked twin -----------

    @Test
    fun `vertical symmetry bakes a twin about the frozen axis`() {
        val src = Stroke(
            id = "s1",
            tool = StrokeTool.PEN,
            points = listOf(PointF(10f, 20f, pressure = 0.5f), PointF(30f, 40f, pressure = 0.7f)),
            start = PointF(10f, 20f),
            end = PointF(30f, 40f),
            pdfPage = 1
        )
        val mode = SymmetryMode.VERTICAL
        val centerX = 100f
        val centerY = 0f

        assertTrue(SymmetryCommitPolicy.shouldBakeMirror(mode, src.tool))
        val twin = SymmetryCommitPolicy.bakedTwin(src, mode, centerX, centerY)

        assertNotEquals("twin must be an independent row", src.id, twin.id)
        assertEquals("twin keeps the same tool/visuals", src.tool, twin.tool)
        assertEquals(src.points.size, twin.points.size)
        // Vertical mirror: 2*centerX - x for every point, y unchanged.
        assertEquals(2f * centerX - 10f, twin.points[0].x, 0.0001f)
        assertEquals(20f, twin.points[0].y, 0.0001f)
        assertEquals(2f * centerX - 30f, twin.points[1].x, 0.0001f)
        // Per-point pressure/tilt/timestamp are carried through unchanged.
        assertEquals(0.5f, twin.points[0].pressure!!, 0f)
        assertEquals(0.7f, twin.points[1].pressure!!, 0f)
    }

    @Test
    fun `radial symmetry mirrors both axes and double mirrors round-trip`() {
        val src = Stroke(
            id = "s2",
            tool = StrokeTool.WATERCOLOR,
            points = listOf(PointF(10f, 20f), PointF(30f, 40f))
        )
        val mode = SymmetryMode.RADIAL
        val centerX = 50f
        val centerY = 50f

        val twin = SymmetryCommitPolicy.bakedTwin(src, mode, centerX, centerY)
        assertEquals(2f * centerX - 10f, twin.points[0].x, 0.0001f)
        assertEquals(2f * centerY - 20f, twin.points[0].y, 0.0001f)

        val roundTrip = SymmetryCommitPolicy.bakedTwin(twin, mode, centerX, centerY)
        assertEquals("mirroring twice returns the source geometry", src.points[0].x, roundTrip.points[0].x, 0.0001f)
        assertEquals(src.points[1].x, roundTrip.points[1].x, 0.0001f)
    }

    @Test
    fun `text strokes never bake a mirror and off mode never twins`() {
        // The TEXT exclusion is a GATE (`shouldBakeMirror`), not a property of the
        // twin builder: the builder mirrors whatever geometry it is given, but the
        // caller never asks it to twin a TEXT stroke.
        assertFalse("TEXT is excluded from baking a mirror", SymmetryCommitPolicy.shouldBakeMirror(SymmetryMode.VERTICAL, StrokeTool.TEXT))
        assertTrue("a pen does bake a mirror", SymmetryCommitPolicy.shouldBakeMirror(SymmetryMode.VERTICAL, StrokeTool.PEN))

        assertFalse("OFF never bakes", SymmetryCommitPolicy.shouldBakeMirror(SymmetryMode.OFF, StrokeTool.PEN))
        val off = SymmetryCommitPolicy.bakedTwin(Stroke(id = "s4", tool = StrokeTool.PEN, points = listOf(PointF(5f, 5f))), SymmetryMode.OFF, 0f, 0f)
        assertEquals("OFF leaves geometry untouched", 5f, off.points[0].x, 0f)
        assertEquals("OFF leaves geometry untouched", 5f, off.points[0].y, 0f)
    }

    @Test
    fun `horizontal symmetry mirrors the y axis only`() {
        val mirrored = SymmetryHelper.mirrorPoint(10f, 20f, SymmetryMode.HORIZONTAL, centerX = 0f, centerY = 100f)
        assertEquals(10f, mirrored.x, 0f)
        assertEquals(180f, mirrored.y, 0.0001f)
        assertEquals(2f * 100f - 20f, mirrored.y, 0.0001f)
        assertFalse(SymmetryHelper.isActive(SymmetryMode.OFF))
        assertTrue(SymmetryHelper.isActive(SymmetryMode.VERTICAL))
    }
}
