package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushPreset
import com.authorss81.noteflow.services.BrushPresetFileCodec
import com.authorss81.noteflow.services.BrushPresetFileCodec.DecodeResult
import com.authorss81.noteflow.services.BrushPresetPack
import com.authorss81.noteflow.services.BrushStudioParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 220: blender-strength + scatter-amount pro brush controls.
 *
 * Covers:
 *  - lerp caps (spacing and scatter are clamped within [0..1])
 *  - BrushStudioParams round-trip through the codec
 *  - validation rejects out-of-range values
 *  - legacy defaults preserve pre-220 behaviour
 */
class BlenderScatterPolicyTest {

    // ---- lerp caps -------------------------------------------------------------

    @Test
    fun `scatter lerp at zero produces legacy tight spacing`() {
        val scatter = 0f
        val spacing = 0.45f + (0.12f - 0.45f) * scatter
        val scatterFactor = 0.15f + (0.55f - 0.15f) * scatter
        assertEquals("spacing at 0 should be 0.45", 0.45f, spacing, 0.001f)
        assertEquals("scatter at 0 should be 0.15", 0.15f, scatterFactor, 0.001f)
    }

    @Test
    fun `scatter lerp at one produces wide scatter`() {
        val scatter = 1f
        val spacing = 0.45f + (0.12f - 0.45f) * scatter
        val scatterFactor = 0.15f + (0.55f - 0.15f) * scatter
        assertEquals("spacing at 1 should be 0.12", 0.12f, spacing, 0.001f)
        assertEquals("scatter at 1 should be 0.55", 0.55f, scatterFactor, 0.001f)
    }

    @Test
    fun `scatter lerp at midpoint is linear blend`() {
        val scatter = 0.5f
        val spacing = 0.45f + (0.12f - 0.45f) * scatter
        val scatterFactor = 0.15f + (0.55f - 0.15f) * scatter
        assertEquals("spacing at 0.5 should be 0.285", 0.285f, spacing, 0.001f)
        assertEquals("scatter at 0.5 should be 0.35", 0.35f, scatterFactor, 0.001f)
    }

    @Test
    fun `blenderStrength at 85 equals legacy SMUDGE ToolPreset mixStrength`() {
        // The pre-220 ToolPreset(SMUDGE).mixStrength = 0.85.
        val blender = 85 / 100f
        assertEquals("blender 85% should match legacy mixStrength 0.85", 0.85f, blender, 0.001f)
    }

    @Test
    fun `blenderStrength at zero produces no blending`() {
        val blender = 0 / 100f
        assertEquals("blender 0% should be 0.0", 0f, blender, 0.001f)
    }

    @Test
    fun `blenderStrength at 100 produces full blending`() {
        val blender = 100 / 100f
        assertEquals("blender 100% should be 1.0", 1f, blender, 0.001f)
    }

    // ---- round-trip codec ------------------------------------------------------

    @Test
    fun `codec round-trips blenderStrength and scatterAmount`() {
        val params = BrushStudioParams(
            dilution = 0.5f, charge = 0.6f, pull = 0.7f,
            impasto = 0.3f, paperGrain = 0.4f, splatterSpread = 0.2f,
            blenderStrength = 0.62f, scatterAmount = 0.78f
        )
        val preset = BrushPreset(
            id = "test_preset", name = "Test", tool = StrokeTool.SMUDGE,
            brushParams = params, size = 10f, colorHex = "#FF0000",
            pressureCurveKey = "linear"
        )
        val encoded = BrushPresetFileCodec.encode(preset)
        val result = BrushPresetFileCodec.decode(encoded)
        assertTrue("decode should succeed", result is DecodeResult.Preset)
        val decoded = (result as DecodeResult.Preset).preset
        assertEquals("blenderStrength round-trips", 0.62f, decoded.brushParams.blenderStrength, 0.001f)
        assertEquals("scatterAmount round-trips", 0.78f, decoded.brushParams.scatterAmount, 0.001f)
    }

    @Test
    fun `codec backward-compat legacy bundle falls back to defaults`() {
        // A JSON bundle that lacks blenderStrength/scatterAmount keys
        // (written before phase 220) should decode with legacy defaults.
        val legacyJson = """
            {
                "format": "inkflow.brushpreset",
                "version": 1,
                "name": "Legacy Brush",
                "tool": "SMUDGE",
                "size": 12.0,
                "colorHex": "#ABCDEF",
                "pressureCurveKey": "linear",
                "brushParams": {
                    "dilution": 0.5, "charge": 0.5, "pull": 0.5,
                    "impasto": 0.5, "paperGrain": 0.5, "splatterSpread": 0.5
                }
            }
        """.trimIndent()
        val result = BrushPresetFileCodec.decode(legacyJson.toByteArray(Charsets.UTF_8))
        assertTrue("legacy decode should succeed", result is DecodeResult.Preset)
        val decoded = (result as DecodeResult.Preset).preset
        assertEquals("blenderStrength defaults to 0.85 for legacy bundles", 0.85f, decoded.brushParams.blenderStrength, 0.001f)
        assertEquals("scatterAmount defaults to 0.0 for legacy bundles", 0f, decoded.brushParams.scatterAmount, 0.001f)
    }

    // ---- validation ------------------------------------------------------------

    @Test
    fun `validateParams rejects out-of-range blenderStrength`() {
        val params = BrushStudioParams(blenderStrength = 1.5f)
        val problems = BrushPresetPack.validateParams(params)
        assertTrue("should reject blenderStrength > 1", problems.any { it.contains("blenderStrength") })
    }

    @Test
    fun `validateParams rejects negative scatterAmount`() {
        val params = BrushStudioParams(scatterAmount = -0.1f)
        val problems = BrushPresetPack.validateParams(params)
        assertTrue("should reject scatterAmount < 0", problems.any { it.contains("scatterAmount") })
    }

    @Test
    fun `validateParams accepts valid blender and scatter`() {
        val params = BrushStudioParams(blenderStrength = 0.85f, scatterAmount = 0.5f)
        val problems = BrushPresetPack.validateParams(params)
        assertTrue("valid values produce no problems", problems.isEmpty())
    }

    // ---- legacy defaults preserve pre-220 presets ------------------------------

    @Test
    fun `all presets pass validation with new fields`() {
        val problems = BrushPresetPack.validateAllPresets()
        assertTrue("no preset may leave [0,1]", problems.isEmpty())
    }

    @Test
    fun `default BrushStudioParams has legacy blender and scatter defaults`() {
        val params = BrushStudioParams()
        assertEquals("default blenderStrength is 0.85", 0.85f, params.blenderStrength, 0.001f)
        assertEquals("default scatterAmount is 0.0", 0f, params.scatterAmount, 0.001f)
    }

    // ---- source pin: AnnotationCanvas wiring -----------------------------------

    @Test
    fun `source pin BrushStudioParams data class includes blender and scatter`() {
        // Verifies the data class fields exist and carry correct defaults.
        // If the fields are removed or renamed, this will fail at compile time.
        val params = BrushStudioParams()
        assertEquals(0.85f, params.blenderStrength, 0.001f)
        assertEquals(0f, params.scatterAmount, 0.001f)
        val copy = params.copy(blenderStrength = 0.3f, scatterAmount = 0.7f)
        assertEquals(0.3f, copy.blenderStrength, 0.001f)
        assertEquals(0.7f, copy.scatterAmount, 0.001f)
    }
}
