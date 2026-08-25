package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [WetCanvasEngine] hydration-state boundary tests — the wet-sheet
 * indicator state machine (mark / dry / reset) and its per-tool peak table
 * ([BrushStrokeMath.wetnessPeakForTool]). Pure JVM: Compose snapshot state
 * reads like plain fields here.
 */
class WetCanvasEngineTest {

    @Test
    fun `a fresh sheet is dry`() {
        val engine = WetCanvasEngine()

        assertFalse(engine.isCanvasWet)
        assertEquals(0.0f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `wet tools raise the hydration to their tool peak`() {
        val engine = WetCanvasEngine()

        engine.markPaintDeposited(StrokeTool.WATERCOLOR)

        assertTrue(engine.isCanvasWet)
        assertEquals(0.9f, engine.activeWetnessLevel, 0f)

        engine.markPaintDeposited(StrokeTool.OIL_PAINT)
        assertEquals(0.4f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `every wet-rendered tool has a positive peak and drives the sheet wet`() {
        val engine = WetCanvasEngine()

        for (tool in StrokeTool.entries.filter { BrushStrokeMath.isWetRenderedTool(it) }) {
            engine.resetCanvas()
            engine.markPaintDeposited(tool)

            assertTrue("$tool must count as wet", engine.isCanvasWet)
            assertTrue(
                "$tool peak must be in (0,1]",
                engine.activeWetnessLevel in 0f..1f && engine.activeWetnessLevel > 0f
            )
            assertEquals(
                "$tool level must equal BrushStrokeMath.wetnessPeakForTool",
                BrushStrokeMath.wetnessPeakForTool(tool),
                engine.activeWetnessLevel,
                0f
            )
        }
    }

    @Test
    fun `dry tools never wet the sheet`() {
        val engine = WetCanvasEngine()

        for (tool in listOf(StrokeTool.PEN, StrokeTool.PENCIL, StrokeTool.MARKER, StrokeTool.ERASER)) {
            engine.markPaintDeposited(tool)

            assertFalse("$tool is not a wet tool", engine.isCanvasWet)
            assertEquals(0.0f, engine.activeWetnessLevel, 0f)
        }
    }

    @Test
    fun `dry canvas sheet evaporates the indicator`() {
        val engine = WetCanvasEngine()
        engine.markPaintDeposited(StrokeTool.WATERCOLOR)

        engine.dryCanvasSheet()

        assertFalse(engine.isCanvasWet)
        assertEquals(0.0f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `reset clears all hydration state`() {
        val engine = WetCanvasEngine()
        engine.markPaintDeposited(StrokeTool.SPLATTER)

        engine.resetCanvas()

        assertFalse(engine.isCanvasWet)
        assertEquals(0.0f, engine.activeWetnessLevel, 0f)
    }

    @Test
    fun `brush studio defaults stay inside the valid parameter range`() {
        val params = BrushStudioParams()

        for (value in listOf(
            params.dilution, params.charge, params.pull,
            params.impasto, params.paperGrain, params.splatterSpread
        )) {
            assertTrue("parameter $value out of [0,1]", value in 0f..1f)
        }
    }
}
