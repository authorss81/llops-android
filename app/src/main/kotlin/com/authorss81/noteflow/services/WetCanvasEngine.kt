package com.authorss81.noteflow.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.authorss81.noteflow.data.model.StrokeTool

data class BrushStudioParams(
    val dilution: Float = 0.6f,       // Water ratio vs pigment (0.0 = heavy paint, 1.0 = thin water wash)
    val charge: Float = 0.8f,         // Initial paint load on brush
    val pull: Float = 0.7f,           // Smudge & blend pull strength
    val impasto: Float = 0.4f,        // 3D oil paint ridge height
    val paperGrain: Float = 0.5f,     // Cold press paper valley granulation
    val splatterSpread: Float = 0.3f, // Droplet spray spread
    // Phase 220: pro brush controls.
    // blenderStrength: 0..1, maps to the AGSL shader's uMixStrength when
    // SMUDGE tool is active. 0.85 = the pre-220 ToolPreset(SMUDGE).mixStrength.
    // Non-SMUDGE tools ignore this field (the shader uses ToolPreset.mixStrength).
    val blenderStrength: Float = 0.85f,
    // scatterAmount: 0..1, modulates bitmap-stamp spacing + scatter for
    // texture-engine tools (SPLATTER/SMUDGE family). 0 = legacy tight
    // spacing, 1 = wide scatter.
    val scatterAmount: Float = 0f
)

/**
 * Tracks the wet-sheet hydration state used by the wet-brush UI and feeds the
 * paper-grain parameter to the AGSL wet shader via [brushParams.paperGrain].
 *
 * The old 128x128 wetness/pigment grid simulation was removed: it was written
 * every stroke (depositStrokePoint) and diffused on a background ~8fps thread,
 * but NOTHING ever read it back for rendering, so it was pure silent CPU burn.
 * The AGSL wet-mixing shader reads no grid uniform — only brushParams.paperGrain.
 * See phase-04 graphics audit, item 4.
 */
class WetCanvasEngine {
    var brushParams by mutableStateOf(BrushStudioParams())
    var isCanvasWet by mutableStateOf(false)
    var activeWetnessLevel by mutableFloatStateOf(0.0f)

    /**
     * Marks the sheet as having active water/paint while a wet stroke is being
     * deposited. No grid, no background loop: the shader does the mixing.
     */
    fun markPaintDeposited(tool: StrokeTool) {
        val peak = BrushStrokeMath.wetnessPeakForTool(tool)
        if (peak <= 0f) return
        activeWetnessLevel = peak
        isCanvasWet = true
    }

    /**
     * "Dry Sheet" action: locks the hydration indicator by evaporating water.
     */
    fun dryCanvasSheet() {
        isCanvasWet = false
        activeWetnessLevel = 0.0f
    }

    /**
     * Clear all hydration state.
     */
    fun resetCanvas() {
        isCanvasWet = false
        activeWetnessLevel = 0.0f
    }
}