package com.authorss81.noteflow

import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymmetryHelperTest {

    private fun mirror(x: Float, y: Float, mode: SymmetryMode, cx: Float = 200f, cy: Float = 100f) =
        SymmetryHelper.mirrorPoint(x, y, mode, cx, cy)

    @Test
    fun `OFF is identity`() {
        val m = mirror(37f, 91f, SymmetryMode.OFF)
        assertEquals(37f, m.x, 1e-5f)
        assertEquals(91f, m.y, 1e-5f)
        assertFalse(SymmetryHelper.isActive(SymmetryMode.OFF))
    }

    @Test
    fun `vertical mirror reflects x and keeps y`() {
        val m = mirror(50f, 80f, SymmetryMode.VERTICAL)
        // symmetric about centerX=200: x' = 2*200 - 50 = 350
        assertEquals(350f, m.x, 1e-5f)
        assertEquals(80f, m.y, 1e-5f)
        assertTrue(SymmetryHelper.isActive(SymmetryMode.VERTICAL))
    }

    @Test
    fun `horizontal mirror reflects y and keeps x`() {
        val m = mirror(50f, 30f, SymmetryMode.HORIZONTAL)
        // symmetric about centerY=100: y' = 2*100 - 30 = 170
        assertEquals(50f, m.x, 1e-5f)
        assertEquals(170f, m.y, 1e-5f)
    }

    @Test
    fun `radial mirror reflects both axes`() {
        val m = mirror(50f, 30f, SymmetryMode.RADIAL)
        assertEquals(350f, m.x, 1e-5f)
        assertEquals(170f, m.y, 1e-5f)
    }

    @Test
    fun `mirror twice returns the original for every mode`() {
        SymmetryMode.entries.forEach { mode ->
            val first = mirror(73f, 41f, mode)
            val second = mirror(first.x, first.y, mode)
            assertEquals("${mode} double-mirror x", 73f, second.x, 1e-4f)
            assertEquals("${mode} double-mirror y", 41f, second.y, 1e-4f)
        }
    }

    @Test
    fun `points exactly on the mirror axis stay put`() {
        val onAxisV = mirror(200f, 77f, SymmetryMode.VERTICAL)
        assertEquals(200f, onAxisV.x, 1e-5f)
        assertEquals(77f, onAxisV.y, 1e-5f)

        val onAxisH = mirror(66f, 100f, SymmetryMode.HORIZONTAL)
        assertEquals(66f, onAxisH.x, 1e-5f)
        assertEquals(100f, onAxisH.y, 1e-5f)

        val onCenter = mirror(200f, 100f, SymmetryMode.RADIAL)
        assertEquals(200f, onCenter.x, 1e-5f)
        assertEquals(100f, onCenter.y, 1e-5f)
    }

    @Test
    fun `mirrorPoints preserves length and maps every point`() {
        val pts = listOf(1f to 2f, 3f to 4f, 5f to 6f)
        val out = SymmetryHelper.mirrorPoints(pts, SymmetryMode.VERTICAL, 200f, 100f)
        assertEquals(pts.size, out.size)
        assertEquals(399f to 2f, out[0])
        assertEquals(397f to 4f, out[1])
        assertEquals(395f to 6f, out[2])
    }

    @Test
    fun `setting key round-trips and falls back to OFF`() {
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey("off"))
        assertEquals(SymmetryMode.VERTICAL, SymmetryMode.fromSettingKey("vertical"))
        assertEquals(SymmetryMode.HORIZONTAL, SymmetryMode.fromSettingKey("horizontal"))
        assertEquals(SymmetryMode.RADIAL, SymmetryMode.fromSettingKey("radial"))
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey("nonsense"))
        assertEquals(SymmetryMode.OFF, SymmetryMode.fromSettingKey(null))
    }

    // -----------------------------------------------------------------------
    // Phase 202 + Phase 203 — WORLD-space mirror contract.
    //
    // AnnotationCanvas stores stroke points in WORLD coordinates. The
    // phase-203 CAPTURE-TIME twin bake mirrors the raw world points about a
    // WORLD axis centre frozen from symmetryCenterFor(...) at drag end (the
    // same centre the live preview showed). The old view-time render pass this
    // contract guarded is GONE for committed strokes — but the underlying math
    // invariant still protects every page >= 1:
    //   bakedLocal(world y) = mirrorPoint(x, worldY, mode, cx, cyWorld).y - top
    //   identity: 2*(C - top) - (y - top)  ==  2*C - y - top
    // A LOCAL centre argument would land the baked twin off-page on later pages.
    // -----------------------------------------------------------------------

    private val PAGE_HEIGHT = 1528f
    private val PAGE_GAP = 64f
    private val stride = PAGE_HEIGHT + PAGE_GAP
    private val page1Top = stride // page index 1
    private val worldCentreY = page1Top + PAGE_HEIGHT / 2f // 2356
    private val localCentreY = PAGE_HEIGHT / 2f // 764 — the PRE-FIX (wrong) argument

    /** Exactly the capture-time twin bake AnnotationCanvas performs at drag end. */
    private fun renderedMirrorLocalY(
        worldY: Float,
        centreYArgument: Float,
        mode: SymmetryMode = SymmetryMode.HORIZONTAL
    ): Float {
        val worldX = 540f
        val centreX = 540f
        val mirrored = SymmetryHelper.mirrorPoint(worldX, worldY, mode, centreX, centreYArgument)
        // drawSingleStroke's translation of the mirrored copy by -pageTopY:
        return mirrored.y - page1Top
    }

    @Test
    fun `page1 horizontal mirror stays on its own page when the centre is WORLD`() {
        val worldPointY = page1Top + 100f
        val rendered = renderedMirrorLocalY(worldPointY, worldCentreY)
        // Translate-then-mirror about the local centre is the ground truth.
        val expected = 2f * (worldCentreY - page1Top) - (worldPointY - page1Top)
        assertEquals(expected, rendered, 1e-3f)
        assertTrue(
            "the mirrored copy must land INSIDE the page bitmap",
            rendered in 0f..PAGE_HEIGHT
        )
    }

    @Test
    fun `pre-fix local-centre argument threw the page1 mirror off-page`() {
        val worldPointY = page1Top + 100f
        val rendered = renderedMirrorLocalY(worldPointY, localCentreY)
        val expected = 2f * (worldCentreY - page1Top) - (worldPointY - page1Top)
        assertTrue(
            "local centre + world points must violate the translate/mirror identity",
            kotlin.math.abs(rendered - expected) > 1f
        )
        assertTrue(
            "pre-fix behaviour: the mirrored copy fell OUTSIDE the page bitmap",
            rendered < 0f || rendered > PAGE_HEIGHT
        )
    }

    @Test
    fun `page1 radial mirror obeys the same world-centre contract`() {
        val worldPointY = page1Top + 200f
        val renderedWithWorld = renderedMirrorLocalY(worldPointY, worldCentreY, SymmetryMode.RADIAL)
        val expected = 2f * (worldCentreY - page1Top) - (worldPointY - page1Top)
        assertEquals(expected, renderedWithWorld, 1e-3f)
        assertTrue(renderedWithWorld in 0f..PAGE_HEIGHT)

        val renderedWithLocal = renderedMirrorLocalY(worldPointY, localCentreY, SymmetryMode.RADIAL)
        assertTrue(
            "radial with the local centre must break the identity too",
            kotlin.math.abs(renderedWithLocal - expected) > 1f
        )
    }

    @Test
    fun `vertical mode was never affected because x does not shift between world and local`() {
        val worldX = 300f
        val viaWorldCentre = SymmetryHelper.mirrorPoint(worldX, 0f, SymmetryMode.VERTICAL, 540f, worldCentreY)
        val viaLocalCentre = SymmetryHelper.mirrorPoint(worldX, 0f, SymmetryMode.VERTICAL, 540f, localCentreY)
        assertEquals(viaWorldCentre.x, viaLocalCentre.x, 0f)
        assertEquals(780f, viaWorldCentre.x, 1e-4f)
    }

    @Test
    fun `mirror-then-translate equals translate-then-mirror for every page and y-mirroring mode`() {
        // The invariant that makes BOTH render paths (cached local bitmap vs
        // direct world-space draw) agree — a regression here re-breaks pages>0.
        for (page in 0..3) {
            val top = page * stride
            val centreWorld = top + PAGE_HEIGHT / 2f
            for (mode in listOf(SymmetryMode.HORIZONTAL, SymmetryMode.RADIAL)) {
                val worldY = top + 37f * (page + 1)
                val mirroredWorld = SymmetryHelper.mirrorPoint(540f, worldY, mode, 540f, centreWorld).y
                val thenTranslate = mirroredWorld - top
                val localY = worldY - top
                val thenMirror = SymmetryHelper.mirrorPoint(540f, localY, mode, 540f, PAGE_HEIGHT / 2f).y
                assertEquals("page=$page mode=$mode", thenMirror, thenTranslate, 1e-3f)
            }
        }
    }
}
