package com.authorss81.noteflow.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.authorss81.noteflow.data.model.StrokeTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

data class BrushStudioParams(
    val dilution: Float = 0.6f,       // Water ratio vs pigment (0.0 = heavy paint, 1.0 = thin water wash)
    val charge: Float = 0.8f,         // Initial paint load on brush
    val pull: Float = 0.7f,           // Smudge & blend pull strength
    val impasto: Float = 0.4f,        // 3D oil paint ridge height
    val paperGrain: Float = 0.5f,     // Cold press paper valley granulation
    val splatterSpread: Float = 0.3f  // Droplet spray spread
)

class WetCanvasEngine(
    val gridWidth: Int = 128,
    val gridHeight: Int = 128
) {
    // Wetness map: 0.0f = completely dry, 1.0f = fully saturated water
    val wetnessGrid = FloatArray(gridWidth * gridHeight)
    
    // Pigment density map: 0.0f = clean, 1.0f = dense pigment
    val pigmentGrid = FloatArray(gridWidth * gridHeight)

    // Red, Green, Blue pigment channels for realistic physical color mixing
    val redGrid = FloatArray(gridWidth * gridHeight)
    val greenGrid = FloatArray(gridWidth * gridHeight)
    val blueGrid = FloatArray(gridWidth * gridHeight)

    var brushParams by mutableStateOf(BrushStudioParams())
    var isCanvasWet by mutableStateOf(false)
    var activeWetnessLevel by mutableFloatStateOf(0.0f)

    private var diffusionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Start active background simulation for water diffusion and paint drying.
     */
    fun startSimulation() {
        if (diffusionJob?.isActive == true) return
        diffusionJob = scope.launch {
            while (isActive) {
                delay(120) // ~8 fps simulation tick for water diffusion
                if (isCanvasWet) {
                    stepDiffusionAndDrying()
                }
            }
        }
    }

    fun stopSimulation() {
        diffusionJob?.cancel()
        diffusionJob = null
    }

    /**
     * Deposit paint/water onto canvas from stroke movements
     */
    fun depositStrokePoint(
        point: Offset,
        canvasWidth: Float,
        canvasHeight: Float,
        brushRadius: Float,
        color: Color,
        tool: StrokeTool
    ) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return

        val normX = (point.x / canvasWidth).coerceIn(0f, 1f)
        val normY = (point.y / canvasHeight).coerceIn(0f, 1f)

        val gx = (normX * (gridWidth - 1)).toInt()
        val gy = (normY * (gridHeight - 1)).toInt()

        val radGrid = ((brushRadius / canvasWidth) * gridWidth).coerceAtLeast(1f)

        val rInt = (radGrid.toInt() + 2)
        val minX = (gx - rInt).coerceAtLeast(0)
        val maxX = (gx + rInt).coerceAtMost(gridWidth - 1)
        val minY = (gy - rInt).coerceAtLeast(0)
        val maxY = (gy + rInt).coerceAtMost(gridHeight - 1)

        val baseWater = when (tool) {
            StrokeTool.WATERCOLOR -> 0.9f * (0.5f + brushParams.dilution)
            StrokeTool.OIL_PAINT -> 0.25f
            StrokeTool.SMUDGE -> 0.4f
            StrokeTool.SPLATTER -> 0.7f
            else -> 0.0f
        }

        val basePigment = when (tool) {
            StrokeTool.WATERCOLOR -> 0.5f * (1.1f - brushParams.dilution)
            StrokeTool.OIL_PAINT -> 0.85f * brushParams.charge
            StrokeTool.SMUDGE -> 0.0f // Smudge moves existing pigment
            StrokeTool.SPLATTER -> 0.9f
            else -> 0.0f
        }

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - gx
                val dy = y - gy
                val distSq = (dx * dx + dy * dy).toFloat()
                if (distSq <= radGrid * radGrid) {
                    val falloff = (1f - distSq / (radGrid * radGrid)).coerceIn(0f, 1f)
                    val idx = y * gridWidth + x

                    // Add water saturation
                    wetnessGrid[idx] = (wetnessGrid[idx] + baseWater * falloff).coerceIn(0f, 1.0f)

                    if (tool == StrokeTool.SMUDGE) {
                        // Smudge redistributes pigment outward
                        val shiftX = (gx + (dx * 1.2f)).toInt().coerceIn(0, gridWidth - 1)
                        val shiftY = (gy + (dy * 1.2f)).toInt().coerceIn(0, gridHeight - 1)
                        val shiftIdx = shiftY * gridWidth + shiftX
                        val transferred = pigmentGrid[idx] * 0.3f * brushParams.pull * falloff

                        pigmentGrid[idx] -= transferred
                        pigmentGrid[shiftIdx] = (pigmentGrid[shiftIdx] + transferred).coerceIn(0f, 1f)
                        redGrid[shiftIdx] = (redGrid[shiftIdx] + redGrid[idx] * transferred).coerceIn(0f, 1f)
                        greenGrid[shiftIdx] = (greenGrid[shiftIdx] + greenGrid[idx] * transferred).coerceIn(0f, 1f)
                        blueGrid[shiftIdx] = (blueGrid[shiftIdx] + blueGrid[idx] * transferred).coerceIn(0f, 1f)
                    } else {
                        // Deposit pigment
                        pigmentGrid[idx] = (pigmentGrid[idx] + basePigment * falloff).coerceIn(0f, 1.0f)
                        redGrid[idx] = (redGrid[idx] * (1f - falloff) + color.red * falloff).coerceIn(0f, 1f)
                        greenGrid[idx] = (greenGrid[idx] * (1f - falloff) + color.green * falloff).coerceIn(0f, 1f)
                        blueGrid[idx] = (blueGrid[idx] * (1f - falloff) + color.blue * falloff).coerceIn(0f, 1f)
                    }
                }
            }
        }

        if (tool == StrokeTool.SPLATTER) {
            // Generate extra random droplets
            val numDroplets = (10 * brushParams.splatterSpread).toInt()
            for (i in 0 until numDroplets) {
                val rx = (gx + Random.nextInt(-rInt * 3, rInt * 3)).coerceIn(0, gridWidth - 1)
                val ry = (gy + Random.nextInt(-rInt * 3, rInt * 3)).coerceIn(0, gridHeight - 1)
                val idx = ry * gridWidth + rx
                wetnessGrid[idx] = 0.8f
                pigmentGrid[idx] = 0.9f
                redGrid[idx] = color.red
                greenGrid[idx] = color.green
                blueGrid[idx] = color.blue
            }
        }

        updateWetnessState()
    }

    /**
     * Single step of water diffusion and natural paint drying
     */
    private fun stepDiffusionAndDrying() {
        var totalWet = 0.0f

        val nextWetness = wetnessGrid.clone()
        val nextPigment = pigmentGrid.clone()

        for (y in 1 until gridHeight - 1) {
            for (x in 1 until gridWidth - 1) {
                val idx = y * gridWidth + x
                val wet = wetnessGrid[idx]

                if (wet > 0.05f) {
                    // Diffuse pigment to wet neighbor cells (Wet-on-Wet Bloom)
                    val neighbors = arrayOf(
                        idx - 1, idx + 1, idx - gridWidth, idx + gridWidth
                    )

                    val currPig = pigmentGrid[idx]
                    for (nIdx in neighbors) {
                        val neighborWet = wetnessGrid[nIdx]
                        if (neighborWet > 0.1f && currPig > 0.05f) {
                            val diffAmount = 0.08f * wet * brushParams.dilution
                            nextPigment[nIdx] = (nextPigment[nIdx] + diffAmount * currPig).coerceIn(0f, 1f)
                            nextPigment[idx] = (nextPigment[idx] - diffAmount * currPig).coerceAtLeast(0f)
                        }
                    }

                    // Natural water evaporation (drying)
                    val dryingRate = 0.04f
                    nextWetness[idx] = (wet - dryingRate).coerceAtLeast(0.0f)
                }

                totalWet += nextWetness[idx]
            }
        }

        System.arraycopy(nextWetness, 0, wetnessGrid, 0, wetnessGrid.size)
        System.arraycopy(nextPigment, 0, pigmentGrid, 0, pigmentGrid.size)

        val avgWetness = totalWet / (gridWidth * gridHeight)
        activeWetnessLevel = avgWetness
        isCanvasWet = avgWetness > 0.001f
    }

    /**
     * "Dry Sheet" Button Action: Locks all paint on canvas by immediately evaporating all water.
     */
    fun dryCanvasSheet() {
        wetnessGrid.fill(0.0f)
        isCanvasWet = false
        activeWetnessLevel = 0.0f
    }

    /**
     * Clear all wetness and pigment maps
     */
    fun resetCanvas() {
        wetnessGrid.fill(0.0f)
        pigmentGrid.fill(0.0f)
        redGrid.fill(0.0f)
        greenGrid.fill(0.0f)
        blueGrid.fill(0.0f)
        isCanvasWet = false
        activeWetnessLevel = 0.0f
    }

    private fun updateWetnessState() {
        var sum = 0f
        for (w in wetnessGrid) sum += w
        val avg = sum / wetnessGrid.size
        activeWetnessLevel = avg
        isCanvasWet = avg > 0.001f
    }
}
