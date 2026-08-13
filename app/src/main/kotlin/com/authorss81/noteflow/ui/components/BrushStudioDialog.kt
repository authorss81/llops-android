package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.BrushStudioParams
import com.authorss81.noteflow.services.WetCanvasEngine

@Composable
fun BrushStudioDialog(
    engine: WetCanvasEngine,
    onDismiss: () -> Unit,
    velocityModulated: Boolean = false,
    velocityIntensity: Float = 1f,
    onVelocityModulatedChange: (Boolean) -> Unit = {},
    onVelocityIntensityChange: (Float) -> Unit = {},
    nibAngleDeg: Float = 45f,
    onNibAngleChange: (Float) -> Unit = {},
    chiselNibAngleDeg: Float = 30f,
    onChiselNibAngleChange: (Float) -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    var params by remember { mutableStateOf(engine.brushParams) }
    var velocityOn by remember { mutableStateOf(velocityModulated) }
    var velocityAmt by remember { mutableFloatStateOf(velocityIntensity) }
    var nibAngle by remember { mutableFloatStateOf(nibAngleDeg) }
    var chiselAngle by remember { mutableFloatStateOf(chiselNibAngleDeg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, contentDescription = null, tint = scheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Brush Studio & Paint Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
                    .heightIn(max = 640.dp)
            ) {
                Text(
                    text = "Adjust real physical paint behaviors:",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SuggestionChip(
                        onClick = {
                            params = BrushStudioParams(dilution = 0.85f, charge = 0.5f, pull = 0.8f, impasto = 0.0f, paperGrain = 0.7f)
                        },
                        label = { Text("Water Wash", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = {
                            params = BrushStudioParams(dilution = 0.2f, charge = 0.95f, pull = 0.5f, impasto = 0.8f, paperGrain = 0.3f)
                        },
                        label = { Text("Oil Impasto", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = {
                            params = BrushStudioParams(dilution = 0.5f, charge = 0.7f, pull = 0.95f, impasto = 0.2f, paperGrain = 0.5f)
                        },
                        label = { Text("Smudge/Blend", fontSize = 11.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Slider 1: Dilution (Water Ratio)
                Text("Dilution (Water Ratio): ${(params.dilution * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.dilution,
                    onValueChange = { v -> params = params.copy(dilution = v) },
                    valueRange = 0f..1f
                )

                // Slider 2: Charge (Paint Load)
                Text("Charge (Initial Paint Load): ${(params.charge * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.charge,
                    onValueChange = { v -> params = params.copy(charge = v) },
                    valueRange = 0f..1f
                )

                // Slider 3: Pull & Blend Strength
                Text("Pull & Blend Strength: ${(params.pull * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.pull,
                    onValueChange = { v -> params = params.copy(pull = v) },
                    valueRange = 0f..1f
                )

                // Slider 4: Impasto 3D Ridge Relief
                Text("Impasto 3D Ridge Relief: ${(params.impasto * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.impasto,
                    onValueChange = { v -> params = params.copy(impasto = v) },
                    valueRange = 0f..1f
                )

                // Slider 5: Cold Press Paper Grain
                Text("Cold Press Paper Grain: ${(params.paperGrain * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.paperGrain,
                    onValueChange = { v -> params = params.copy(paperGrain = v) },
                    valueRange = 0f..1f
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Phase 18: Velocity-based width modulation (PEN / FOUNTAIN_PEN / FINELINER / CALLIGRAPHIC).
                Text(
                    text = "Phase 18 brush physics",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Velocity → Width (fast = thin)", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = velocityOn,
                        onCheckedChange = {
                            velocityOn = it
                            onVelocityModulatedChange(it)
                        }
                    )
                }
                if (velocityOn) {
                    Text("Velocity Strength: ${(velocityAmt * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = velocityAmt,
                        onValueChange = {
                            velocityAmt = it
                            onVelocityIntensityChange(it)
                        },
                        valueRange = 0.1f..1f
                    )
                }

                // Phase 18: calligraphic & chisel nib angle control.
                Text("Calligraphic Nib Angle: ${nibAngle.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = nibAngle,
                    onValueChange = {
                        nibAngle = it
                        onNibAngleChange(it)
                    },
                    valueRange = -45f..90f
                )

                Text("Chisel Marker Angle: ${chiselAngle.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = chiselAngle,
                    onValueChange = {
                        chiselAngle = it
                        onChiselNibAngleChange(it)
                    },
                    valueRange = -45f..90f
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Dry Canvas Action Button
                Surface(
                    color = if (engine.isCanvasWet) scheme.primaryContainer else scheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { engine.dryCanvasSheet() }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = scheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (engine.isCanvasWet) "Dry Canvas Sheet (Lock Active Water)" else "Canvas Sheet is Dry",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Phase 18 notes: CHARCOAL, OIL_PASTEL, INK_WASH, GOUACHE, DRY_BRUSH and PALETTE_KNIFE are new distinct brushes in the tool picker. Velocity & nib settings persist across restarts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    engine.brushParams = params
                    onVelocityModulatedChange(velocityOn)
                    onVelocityIntensityChange(velocityAmt)
                    onNibAngleChange(nibAngle)
                    onChiselNibAngleChange(chiselAngle)
                    onDismiss()
                }
            ) {
                Text("Apply Brush Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}