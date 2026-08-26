package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 213 — behavior contract of the per-stroke drop-shadow decision table.
 *
 * Every constant the renderer consumes is pinned here so a reviewer cannot
 * drift the visual design or silently break the zero-regression guarantee
 * (null plan == byte-identical pre-213 draw path).
 */
class BrushShadowPolicyTest {

    private val anyDp = 3f // px per dp fixture

    // ---- master gate -------------------------------------------------------

    @Test
    fun `low-end devices never enable shadows`() {
        assertFalse(BrushShadowPolicy.enabled(lowEndDevice = true))
    }

    @Test
    fun `capable devices enable shadows`() {
        assertTrue(BrushShadowPolicy.enabled(lowEndDevice = false))
    }

    // ---- per-tool gate -----------------------------------------------------

    @Test
    fun `eraser laser text smudge sticker select pan eyedropper are skipped`() {
        for (tool in listOf(
            StrokeTool.ERASER, StrokeTool.LASER, StrokeTool.TEXT,
            StrokeTool.SMUDGE, StrokeTool.STICKER,
            StrokeTool.SELECT, StrokeTool.PAN, StrokeTool.EYEDROPPER
        )) {
            assertFalse("tool $tool must not cast a shadow", BrushShadowPolicy.shouldApply(tool))
        }
    }

    @Test
    fun `pigment tools apply including highlighter at reduced alpha`() {
        for (tool in listOf(
            StrokeTool.PEN, StrokeTool.FOUNTAIN_PEN, StrokeTool.PENCIL, StrokeTool.MARKER,
            StrokeTool.HIGHLIGHTER, StrokeTool.CALLIGRAPHIC, StrokeTool.NEON, StrokeTool.FINELINER,
            StrokeTool.WATERCOLOR, StrokeTool.OIL_PAINT, StrokeTool.GOUACHE, StrokeTool.RECTANGLE
        )) {
            assertTrue("tool $tool must cast a shadow", BrushShadowPolicy.shouldApply(tool))
        }
    }

    @Test
    fun `dotted strokes are skipped - dots must not gain a continuous blurred band`() {
        // Review fix (phase-213): the shipped skip set had omitted DOTTED even
        // though the REPORT claimed it was skipped — the continuous blurred
        // centerline outline under discrete dots reads as a phantom stripe.
        assertFalse(BrushShadowPolicy.shouldApply(StrokeTool.DOTTED))
        assertNull(
            BrushShadowPolicy.plan(
                StrokeTool.DOTTED, widthPx = 8f, isDarkPaper = false,
                settingEnabled = true, pxPerDp = anyDp
            )
        )
    }

    // ---- alpha -------------------------------------------------------------

    @Test
    fun `light paper shadow alpha is 0_20 dark paper 0_12`() {
        assertEquals(0.20f, BrushShadowPolicy.shadowAlpha(isDarkPaper = false), 1e-6f)
        assertEquals(0.12f, BrushShadowPolicy.shadowAlpha(isDarkPaper = true), 1e-6f)
    }

    @Test
    fun `highlighter shadow is reduced to half alpha in the plan`() {
        val light = BrushShadowPolicy.plan(StrokeTool.HIGHLIGHTER, widthPx = 10f, isDarkPaper = false, settingEnabled = true, pxPerDp = anyDp)
        assertNotNull(light)
        assertEquals(0.20f * BrushShadowPolicy.HIGHLIGHTER_ALPHA_SCALE, light!!.alpha, 1e-6f)

        val dark = BrushShadowPolicy.plan(StrokeTool.HIGHLIGHTER, widthPx = 10f, isDarkPaper = true, settingEnabled = true, pxPerDp = anyDp)
        assertEquals(0.12f * BrushShadowPolicy.HIGHLIGHTER_ALPHA_SCALE, dark!!.alpha, 1e-6f)
    }

    // ---- blur --------------------------------------------------------------

    @Test
    fun `blur radius is width times 0_6`() {
        assertEquals(10f * BrushShadowPolicy.BLUR_WIDTH_FACTOR, BrushShadowPolicy.blurRadius(10f), 1e-4f)
        assertEquals(20f * BrushShadowPolicy.BLUR_WIDTH_FACTOR, BrushShadowPolicy.blurRadius(20f), 1e-4f)
    }

    @Test
    fun `blur radius clamps to the 2 to 12 px penumbra band`() {
        assertEquals(BrushShadowPolicy.MIN_BLUR_RADIUS_PX, BrushShadowPolicy.blurRadius(0.5f), 1e-4f)
        assertEquals(BrushShadowPolicy.MAX_BLUR_RADIUS_PX, BrushShadowPolicy.blurRadius(400f), 1e-4f)
    }

    @Test
    fun `blur radius survives degenerate widths fail-safe`() {
        assertEquals(BrushShadowPolicy.MIN_BLUR_RADIUS_PX, BrushShadowPolicy.blurRadius(Float.NaN), 1e-4f)
        assertEquals(BrushShadowPolicy.MIN_BLUR_RADIUS_PX, BrushShadowPolicy.blurRadius(-5f), 1e-4f)
    }

    // ---- offset ------------------------------------------------------------

    @Test
    fun `offset follows the 0_35 x 0_40 width factors inside the clamp band`() {
        val off = BrushShadowPolicy.offset(widthPx = 10f, pxPerDp = anyDp)
        assertEquals(10f * BrushShadowPolicy.OFFSET_X_WIDTH_FACTOR, off.x, 1e-4f)
        assertEquals(10f * BrushShadowPolicy.OFFSET_Y_WIDTH_FACTOR, off.y, 1e-4f)
    }

    @Test
    fun `offset clamps to 1 to 6 dp converted through density`() {
        val hairline = BrushShadowPolicy.offset(widthPx = 0.2f, pxPerDp = 2f)
        assertEquals(1f * 2f, hairline.x, 1e-4f) // MIN_OFFSET_DP
        assertEquals(1f * 2f, hairline.y, 1e-4f)

        val marker = BrushShadowPolicy.offset(widthPx = 2000f, pxPerDp = 3f)
        assertEquals(6f * 3f, marker.x, 1e-4f) // MAX_OFFSET_DP
        assertEquals(6f * 3f, marker.y, 1e-4f)
    }

    @Test
    fun `non-positive or non-finite density fails safe to 1`() {
        val off = BrushShadowPolicy.offset(widthPx = 0.5f, pxPerDp = 0f)
        assertEquals(1f, off.x, 1e-4f)
        val nan = BrushShadowPolicy.offset(widthPx = 0.5f, pxPerDp = Float.NaN)
        assertEquals(1f, nan.x, 1e-4f)
    }

    // ---- plan ----------------------------------------------------------------

    @Test
    fun `plan is null when the setting is off - backward compatible zero contribution`() {
        assertNull(BrushShadowPolicy.plan(StrokeTool.PEN, widthPx = 8f, isDarkPaper = false, settingEnabled = false, pxPerDp = anyDp))
    }

    @Test
    fun `tier-aware plan is null on low-end devices even when enabled`() {
        assertNull(
            BrushShadowPolicy.plan(
                StrokeTool.PEN, widthPx = 8f, isDarkPaper = false,
                settingEnabled = true, lowEndDevice = true, pxPerDp = anyDp
            )
        )
        assertNotNull(
            BrushShadowPolicy.plan(
                StrokeTool.PEN, widthPx = 8f, isDarkPaper = false,
                settingEnabled = true, lowEndDevice = false, pxPerDp = anyDp
            )
        )
    }

    @Test
    fun `plan is null for skipped tools even when enabled`() {
        for (tool in listOf(StrokeTool.ERASER, StrokeTool.LASER, StrokeTool.TEXT)) {
            assertNull(
                "tool $tool must contribute nothing",
                BrushShadowPolicy.plan(tool, widthPx = 8f, isDarkPaper = false, settingEnabled = true, pxPerDp = anyDp)
            )
        }
    }

    @Test
    fun `plan is null for degenerate widths`() {
        assertNull(BrushShadowPolicy.plan(StrokeTool.PEN, widthPx = 0f, isDarkPaper = false, settingEnabled = true, pxPerDp = anyDp))
        assertNull(BrushShadowPolicy.plan(StrokeTool.PEN, widthPx = Float.NaN, isDarkPaper = false, settingEnabled = true, pxPerDp = anyDp))
    }

    @Test
    fun `plan carries coherent offset blur and alpha for a normal pen stroke`() {
        val plan = BrushShadowPolicy.plan(StrokeTool.PEN, widthPx = 10f, isDarkPaper = false, settingEnabled = true, pxPerDp = anyDp)
        assertNotNull(plan)
        assertEquals(3.5f, plan!!.offsetX, 1e-4f)
        assertEquals(4.0f, plan.offsetY, 1e-4f)
        assertEquals(6f, plan.blurRadiusPx, 1e-4f)
        assertEquals(0.20f, plan.alpha, 1e-4f)
    }

    // ---- GPU carrier tier table ---------------------------------------------

    @Test
    fun `gpu carrier tier table qualifies api 31 plus non low end only`() {
        assertTrue(BrushShadowPolicy.gpuCarrierPreferred(sdkInt = 31, lowEndDevice = false))
        assertTrue(BrushShadowPolicy.gpuCarrierPreferred(sdkInt = 35, lowEndDevice = false))
        assertFalse(BrushShadowPolicy.gpuCarrierPreferred(sdkInt = 30, lowEndDevice = false))
        assertFalse(BrushShadowPolicy.gpuCarrierPreferred(sdkInt = 26, lowEndDevice = false))
        assertFalse("low-end devices stay on the vector path", BrushShadowPolicy.gpuCarrierPreferred(sdkInt = 35, lowEndDevice = true))
    }
}
