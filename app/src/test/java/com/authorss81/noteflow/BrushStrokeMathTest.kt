package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushStrokeMath
import com.authorss81.noteflow.ui.components.AgslShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushStrokeMathTest {

    // ---- velocity -> width modulation -------------------------------------

    @Test
    fun `velocity modulation is identity when disabled`() {
        assertEquals(1f, BrushStrokeMath.velocityWidthFactor(0.05f, intensity = 0f), 1e-6f)
        assertEquals(1f, BrushStrokeMath.velocityWidthFactor(20f, intensity = 0f), 1e-6f)
    }

    @Test
    fun `velocity modulation is identity below slow threshold`() {
        // slow handwriting must reproduce the classic fixed-width stroke
        assertEquals(1f, BrushStrokeMath.velocityWidthFactor(0.3f, intensity = 1f), 1e-6f)
    }

    @Test
    fun `faster velocity produces thinner stroke`() {
        val slow = BrushStrokeMath.velocityWidthFactor(1.5f, intensity = 1f)
        val fast = BrushStrokeMath.velocityWidthFactor(5.5f, intensity = 1f)
        assertTrue("fast must be thinner: $fast >= $slow", fast < slow)
    }

    @Test
    fun `velocity multiplier stays within the bounded range`() {
        val hard = BrushStrokeMath.velocityWidthFactor(100f, intensity = 1f)
        assertTrue(hard in 0.55f..1f)
        val maxIntensity = BrushStrokeMath.velocityWidthFactor(100f, intensity = 2f)
        assertTrue(maxIntensity >= 0.55f - 1e-6f)
    }

    // ---- segment velocity -------------------------------------------------

    @Test
    fun `segment velocity uses timestamps when present`() {
        val a = PointF(0f, 0f, timestampMs = 0L)
        val b = PointF(100f, 0f, timestampMs = 50L)
        assertEquals(2f, BrushStrokeMath.segmentVelocity(a, b), 1e-4f)
    }

    @Test
    fun `segment velocity falls back to nominal step when timestamps absent`() {
        val a = PointF(0f, 0f)
        val b = PointF(96f, 28f)
        val dist = kotlin.math.sqrt(96f * 96f + 28f * 28f)
        assertEquals(dist / 16f, BrushStrokeMath.segmentVelocity(a, b), 1e-4f)
    }

    @Test
    fun `zero distance segment has zero velocity`() {
        val a = PointF(5f, 5f, timestampMs = 0L)
        val b = PointF(5f, 5f, timestampMs = 100L)
        assertEquals(0f, BrushStrokeMath.segmentVelocity(a, b), 1e-6f)
    }

    // ---- pressure -> bristle spread ----------------------------------------

    @Test
    fun `bristle spread is identity when disabled`() {
        assertEquals(1f, BrushStrokeMath.bristleSpreadFactor(0.1f, intensity = 0f), 1e-6f)
    }

    @Test
    fun `bristle spread is identity at full pressure`() {
        assertEquals(1f, BrushStrokeMath.bristleSpreadFactor(0.9f, intensity = 1f), 1e-6f)
    }

    @Test
    fun `low pressure gives wider contact patch than full pressure`() {
        val heavy = BrushStrokeMath.bristleSpreadFactor(0.9f)
        val light = BrushStrokeMath.bristleSpreadFactor(0.3f)
        assertTrue("light pressure must be wider (multiplier closer to 0.75): $light >= $heavy", light < heavy)
        assertTrue(heavy in 0.75f..1f)
        assertTrue(light in 0.75f..1f)
    }

    // ---- pigment from pressure ---------------------------------------------

    @Test
    fun `pigment rises monotonically with pressure and stays bounded`() {
        var prev = BrushStrokeMath.pigmentFromPressure(0f)
        assertTrue(prev in 0.55f..1f)
        for (i in 1..10) {
            val p = i / 10f
            val v = BrushStrokeMath.pigmentFromPressure(p)
            assertTrue("pigment $v out of range", v in 0.55f..1f)
            assertTrue("pigment must not drop", v >= prev)
            prev = v
        }
    }

    // ---- per-stroke seed ---------------------------------------------------

    @Test
    fun `seed is stable for identical ids and varies across ids`() {
        val s1 = BrushStrokeMath.strokeSeedFromId("stroke-42")
        val s2 = BrushStrokeMath.strokeSeedFromId("stroke-42")
        val s3 = BrushStrokeMath.strokeSeedFromId("stroke-43")
        assertEquals(s1, s2, 1e-6f)
        assertNotEquals(s1, s3)
        assertTrue(s1 in 0f..100f)
    }

    @Test
    fun `seed deterministic round-trip for persisted strokes`() {
        val id = "page-7|svg-9|t-aabbcc|s-123"
        assertEquals(
            BrushStrokeMath.strokeSeedFromId(id),
            BrushStrokeMath.strokeSeedFromId(id),
            1e-6f
        )
    }

    // ---- style selector ----------------------------------------------------

    @Test
    fun `every new tool maps to its own distinct style id`() {
        val ids = StrokeTool.entries
            .filter { it in listOf(
                StrokeTool.CHARCOAL, StrokeTool.OIL_PASTEL, StrokeTool.INK_WASH,
                StrokeTool.GOUACHE, StrokeTool.DRY_BRUSH, StrokeTool.PALETTE_KNIFE
            ) }
            .map { BrushStrokeMath.brushStyleIdForTool(it) }
            .toSet()
        assertEquals("six new tools must all be distinct styles", 6, ids.size)
    }

    @Test
    fun `classic tools keep their pre-phase-18 style ids`() {
        assertEquals(
            BrushStrokeMath.STYLE_WATERCOLOR,
            BrushStrokeMath.brushStyleIdForTool(StrokeTool.WATERCOLOR)
        )
        assertEquals(
            BrushStrokeMath.STYLE_OIL_PAINT,
            BrushStrokeMath.brushStyleIdForTool(StrokeTool.OIL_PAINT)
        )
        assertEquals(
            BrushStrokeMath.STYLE_SMUDGE,
            BrushStrokeMath.brushStyleIdForTool(StrokeTool.SMUDGE)
        )
        assertEquals(
            BrushStrokeMath.STYLE_SPLATTER,
            BrushStrokeMath.brushStyleIdForTool(StrokeTool.SPLATTER)
        )
        assertEquals(
            BrushStrokeMath.STYLE_DEFAULT,
            BrushStrokeMath.brushStyleIdForTool(StrokeTool.PEN)
        )
    }

    @Test
    fun `every PRESETS entry carries a matching style id and valid param range`() {
        for ((tool, preset) in AgslShaders.PRESETS) {
            assertEquals(
                "style id must round-trip through BrushStrokeMath for $tool",
                BrushStrokeMath.brushStyleIdForTool(tool),
                preset.brushStyle
            )
            for (v in listOf(preset.wetness, preset.pigmentLoad, preset.mixStrength, preset.impasto, preset.hardness)) {
                assertTrue("$tool preset param $v out of [0,1]", v in 0f..1f)
            }
        }
    }

    @Test
    fun `all six new tools have a PRESETS entry`() {
        for (tool in listOf(
            StrokeTool.CHARCOAL, StrokeTool.OIL_PASTEL, StrokeTool.INK_WASH,
            StrokeTool.GOUACHE, StrokeTool.DRY_BRUSH, StrokeTool.PALETTE_KNIFE
        )) {
            assertNotNull("missing preset for $tool", AgslShaders.PRESETS[tool])
        }
    }

    // ---- wet rendered classification ---------------------------------------

    @Test
    fun `new wet tools are classified as wet-rendered and stage a peak hydration`() {
        for (tool in listOf(StrokeTool.INK_WASH, StrokeTool.GOUACHE, StrokeTool.PALETTE_KNIFE)) {
            assertTrue("$tool must be wet-rendered", BrushStrokeMath.isWetRenderedTool(tool))
            assertTrue("$tool needs a non-zero wetness peak", BrushStrokeMath.wetnessPeakForTool(tool) > 0f)
        }
    }

    @Test
    fun `dry tools are not wet-rendered and drain hydration`() {
        for (tool in listOf(StrokeTool.CHARCOAL, StrokeTool.OIL_PASTEL, StrokeTool.DRY_BRUSH, StrokeTool.PEN)) {
            assertTrue("$tool must not be wet-rendered", !BrushStrokeMath.isWetRenderedTool(tool))
            assertEquals(0f, BrushStrokeMath.wetnessPeakForTool(tool), 1e-6f)
        }
    }

    // ---- by-name serialization round-trip ----------------------------------

    @Test
    fun `new tools survive by-name serialization`() {
        val names = listOf(
            StrokeTool.CHARCOAL, StrokeTool.OIL_PASTEL, StrokeTool.INK_WASH,
            StrokeTool.GOUACHE, StrokeTool.DRY_BRUSH, StrokeTool.PALETTE_KNIFE
        ).map { it.name } // EditorScreen persists strokes / custom presets by tool name
        val roundTripped = names.map { StrokeTool.valueOf(it) }
        assertEquals(
            listOf("CHARCOAL", "OIL_PASTEL", "INK_WASH", "GOUACHE", "DRY_BRUSH", "PALETTE_KNIFE"),
            roundTripped.map { it.name }
        )
    }
}