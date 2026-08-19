package com.authorss81.noteflow

import com.authorss81.noteflow.services.LayerBlendPresetPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 172 — layer blend-mode quick presets.
 * Verifies the compact preset set (all five MUST be renderer-supported so a
 * preset chip never pushes an unwired blend string), the renderer-mode gate and
 * the display labels.
 */
class LayerBlendPresetPolicyTest {

    @Test
    fun `preset set is exactly the compact five`() {
        val keys = LayerBlendPresetPolicy.presets().map { it.key }
        assertEquals(
            listOf("NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "SOFT_LIGHT"),
            keys
        )
    }

    @Test
    fun `every preset is supported by the renderer`() {
        val rendererSupported = LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES.map { it.uppercase() }.toSet()
        LayerBlendPresetPolicy.presets().forEach { preset ->
            assertTrue(
                "preset ${preset.key} must be renderer-supported (a chip must never set an unwired blend)",
                preset.key.uppercase() in rendererSupported
            )
        }
    }

    @Test
    fun `every preset has a human label`() {
        LayerBlendPresetPolicy.presets().forEach { preset ->
            assertTrue(preset.label.isNotBlank())
            assertTrue(preset.description.isNotBlank())
        }
    }

    @Test
    fun `isSupportedByRenderer is case-insensitive and succeeds for the full dropdown set`() {
        assertTrue(LayerBlendPresetPolicy.isSupportedByRenderer("normal"))
        assertTrue(LayerBlendPresetPolicy.isSupportedByRenderer("NORMAL"))
        assertTrue(LayerBlendPresetPolicy.isSupportedByRenderer("multiply"))
        assertTrue(LayerBlendPresetPolicy.isSupportedByRenderer("COLOR_DODGE"))
        assertFalse(LayerBlendPresetPolicy.isSupportedByRenderer("BOGUS"))
        assertFalse(LayerBlendPresetPolicy.isSupportedByRenderer(""))
    }

    @Test
    fun `presetFor resolves case-insensitively`() {
        assertEquals("Multiply", LayerBlendPresetPolicy.presetFor("multiply")?.label)
        assertNull(LayerBlendPresetPolicy.presetFor("DIFFERENCE")) // full set, not a preset
    }

    @Test
    fun `displayLabel maps a stored key to its label, falling back to the key`() {
        assertEquals("Soft Light", LayerBlendPresetPolicy.displayLabel("SOFT_LIGHT"))
        // Case-insensitive resolve to the preset label even for lowercase stored keys.
        assertEquals("Screen", LayerBlendPresetPolicy.displayLabel("screen"))
        // Non-preset (full-set) keys fall back to the key itself.
        assertEquals("EXCLUSION", LayerBlendPresetPolicy.displayLabel("EXCLUSION"))
        assertEquals("DARKEN", LayerBlendPresetPolicy.displayLabel("DARKEN"))
    }

    @Test
    fun `renderer supported list has exactly the twelve known modes`() {
        assertEquals(
            listOf(
                "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY",
                "DARKEN", "LIGHTEN", "COLOR_DODGE", "COLOR_BURN",
                "HARD_LIGHT", "SOFT_LIGHT", "DIFFERENCE", "EXCLUSION"
            ),
            LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES
        )
    }
}