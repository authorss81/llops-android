package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import com.authorss81.noteflow.services.StrokeSimplifyPolicy
import com.authorss81.noteflow.ui.components.ShaderCapabilityHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 201 (PERF 1.3 + 1.4 + 2.7): pure decision tables for the stroke-input
 * pipeline — the SMOOTH pressure gamma, the per-brush RDP epsilon, and the
 * GPU compositing tiers — plus source pins for their wiring.
 */
class Phase201StrokeInputPipelineTest {

    // ---------- StrokeSimplifyPolicy: per-brush epsilon table ----------

    @Test
    fun `non-hairline strokes keep the legacy 1_3 px epsilon`() {
        for (tool in StrokeTool.entries) {
            assertEquals(
                "$tool @12px must stay legacy",
                StrokeSimplifyPolicy.DEFAULT_EPSILON_PX,
                StrokeSimplifyPolicy.epsilonFor(tool, 12f),
                0f
            )
            assertEquals(StrokeSimplifyPolicy.DEFAULT_EPSILON_PX, StrokeSimplifyPolicy.epsilonFor(tool, 3.01f), 0f)
        }
    }

    @Test
    fun `fine-tip tools at narrow widths land in the hairline band`() {
        val fineTools = listOf(StrokeTool.PEN, StrokeTool.FOUNTAIN_PEN, StrokeTool.PENCIL, StrokeTool.FINELINER)
        for (tool in fineTools) {
            assertTrue("$tool must be hairline-class at 2px", StrokeSimplifyPolicy.isHairlineBrush(tool, 2f))
            val eps = StrokeSimplifyPolicy.epsilonFor(tool, 2f)
            assertTrue(
                "$tool @2px epsilon $eps outside the hairline band",
                eps >= StrokeSimplifyPolicy.HAIRLINE_MIN_EPSILON_PX &&
                    eps <= StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX
            )
        }
    }

    @Test
    fun `hairline band endpoints are exact goldens`() {
        // Finest nib -> tightest epsilon; at the width cutoff -> looser bound.
        assertEquals(
            StrokeSimplifyPolicy.HAIRLINE_MIN_EPSILON_PX,
            StrokeSimplifyPolicy.epsilonFor(StrokeTool.PEN, 1.0f),
            0f
        )
        assertEquals(
            StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX,
            StrokeSimplifyPolicy.epsilonFor(StrokeTool.PEN, 3.0f),
            0f
        )
    }

    @Test
    fun `epsilon interpolates monotonically with hairline width`() {
        var prev = StrokeSimplifyPolicy.epsilonFor(StrokeTool.FINELINER, 0f)
        var w = 0.25f
        while (w <= 3.0f) {
            val cur = StrokeSimplifyPolicy.epsilonFor(StrokeTool.FINELINER, w)
            assertTrue("epsilon must not decrease as width grows (w=$w)", cur >= prev - 1e-6f)
            prev = cur
            w += 0.25f
        }
        // Degenerate widths fail safe INSIDE the band, never above it.
        val degenerate = StrokeSimplifyPolicy.epsilonFor(StrokeTool.PEN, Float.NEGATIVE_INFINITY)
        assertTrue(degenerate <= StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX)
    }

    @Test
    fun `wide fine-tip strokes are NOT hairline`() {
        assertFalse(StrokeSimplifyPolicy.isHairlineBrush(StrokeTool.PEN, 5f))
        assertFalse(StrokeSimplifyPolicy.isHairlineBrush(StrokeTool.FINELINER, 36f))
    }

    // ---------- ShaderCapabilityHelper: GPU compositing tiers ----------

    @Test
    fun `agsl tier is exactly API 33+`() {
        for (sdk in 26..32) {
            assertFalse("API $sdk must not run AGSL", ShaderCapabilityHelper.agslSupportedFor(sdk))
        }
        for (sdk in 33..36) {
            assertTrue("API $sdk must run AGSL", ShaderCapabilityHelper.agslSupportedFor(sdk))
        }
    }

    @Test
    fun `renderEffect compositing tier is exactly API 31+ with the AGSL-only caveat below 33`() {
        for (sdk in 26..30) {
            assertFalse("API $sdk has no RenderEffect compositing", ShaderCapabilityHelper.renderEffectCompositingFor(sdk))
        }
        for (sdk in 31..36) {
            assertTrue("API $sdk composites RenderEffects via RenderNode", ShaderCapabilityHelper.renderEffectCompositingFor(sdk))
        }
        // The wet-mix shader itself still requires AGSL: on 31-32 the canvas
        // falls back to WetBrushEngine's vector path even though RenderEffect exists.
        for (sdk in 31..32) {
            assertFalse(ShaderCapabilityHelper.agslSupportedFor(sdk))
        }
    }

    @Test
    fun `WetMixingEffect owns a reusable RenderNode carrier`() {
        val source = readSource("ui/components/AgslShaders.kt")
        assertTrue(
            "the wet effect must own its RenderNode (no per-frame allocation)",
            source.contains("""android.graphics.RenderNode("inkflow-wet-mix")""")
        )
        assertTrue(
            "the carrier doc must record WHY Paint cannot carry the effect",
            source.contains("NO setRenderEffect")
        )
    }

    // ---------- wiring pins ----------

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile && java.io.File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    @Test
    fun `pressure remap stays capture-time only and SMOOTH ships a persisted setting key`() {
        val helperSource = readSource("services/PressureCurveHelper.kt")
        assertTrue(helperSource.contains("""SMOOTH("Smooth", "smooth""""))
        // Capture-time semantics doc must survive: no render-time re-remap.
        assertTrue(helperSource.contains("applied ONCE"))

        val settingsSource = readSource("services/SettingsManager.kt")
        assertTrue(settingsSource.contains("pressure_curve_key"))
    }

    @Test
    fun `canvas commit path uses the policy and the live preview never simplifies`() {
        val canvasSource = readSource("ui/components/AnnotationCanvas.kt")
        assertTrue(canvasSource.contains("StrokeSimplifyPolicy.epsilonFor(tool, width)"))
        assertEquals(
            "exactly one simplify call site",
            1,
            Regex("""RamerDouglasPeucker\.simplify\(""").findAll(canvasSource).count()
        )
    }

    @Test
    fun `pressure curve chips row is horizontally scrollable for compact screens`() {
        val editorSource = readSource("ui/screens/EditorScreen.kt")
        // The chip row lives AFTER the "Pressure Curve" sheet header; from there
        // to the curve iteration the Row must carry a horizontalScroll so four
        // chips never clip at 360dp (phase-166 discipline).
        val region = editorSource.substringAfter("\"Pressure Curve\"")
        assertTrue(region.contains("PressureCurve.entries.forEach"))
        assertTrue(
            "the curve chip row must scroll instead of clipping at 360dp",
            region.substringBefore("PressureCurve.entries.forEach").contains(".horizontalScroll(rememberScrollState())")
        )
    }
}
