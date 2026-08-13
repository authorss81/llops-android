package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Palette
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
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var params by remember { mutableStateOf(engine.brushParams) }

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
                    .padding(vertical = 4.dp)
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
                    onValueChange = { params = params.copy(dilution = it) },
                    valueRange = 0f..1f
                )

                // Slider 2: Charge (Paint Load)
                Text("Charge (Initial Paint Load): ${(params.charge * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.charge,
                    onValueChange = { params = params.copy(charge = it) },
                    valueRange = 0f..1f
                )

                // Slider 3: Pull & Blend Strength
                Text("Pull & Blend Strength: ${(params.pull * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.pull,
                    onValueChange = { params = params.copy(pull = it) },
                    valueRange = 0f..1f
                )

                // Slider 4: Impasto 3D Ridge Relief
                Text("Impasto 3D Ridge Relief: ${(params.impasto * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.impasto,
                    onValueChange = { params = params.copy(impasto = it) },
                    valueRange = 0f..1f
                )

                // Slider 5: Cold Press Paper Grain
                Text("Cold Press Paper Grain: ${(params.paperGrain * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = params.paperGrain,
                    onValueChange = { params = params.copy(paperGrain = it) },
                    valueRange = 0f..1f
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    engine.brushParams = params
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
