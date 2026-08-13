package com.authorss81.noteflow.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

object AgslShaders {
    // Trivial pass-through shader for testing pipeline
    val PASS_THROUGH_SHADER = """
        uniform shader contents;
        half4 main(float2 coord) {
            return contents.eval(coord);
        }
    """.trimIndent()
    
    // Simple tint shader
    val TINT_SHADER = """
        uniform shader contents;
        uniform half4 tintColor;
        half4 main(float2 coord) {
            half4 c = contents.eval(coord);
            return half4(c.rgb * tintColor.rgb, c.a);
        }
    """.trimIndent()

    // AGSL Shader for Wet Color Mixing, Impasto Texturing, Cold-Press Grain & Diffuse Pigment Bleed
    val WET_MIXING_SHADER = """
        uniform shader contents;
        uniform float2 uPrevPos;
        uniform float2 uBrushPos;
        uniform float uBrushRadius;
        uniform half4 uBrushColor;
        uniform float uWetness;      // 0.0 = dry, 1.0 = wet
        uniform float uPigmentLoad;  // 0.0 = translucent, 1.0 = heavy body
        uniform float uMixStrength;  // 0.0 = no pickup, 1.0 = strong blending
        uniform float uImpasto;      // 0.0 = flat, 1.0 = thick ridges
        uniform float uPaperGrain;   // 0.0 = smooth, 1.0 = heavy cold press granulation
        uniform float uHardness;     // 0.0 = soft, 1.0 = hard edge

        float pseudoNoise(float2 p) {
            return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
        }

        float distToSegment(float2 p, float2 a, float2 b) {
            float2 pa = p - a, ba = b - a;
            float d2 = dot(ba, ba);
            float h = d2 > 0.0001 ? clamp(dot(pa, ba) / d2, 0.0, 1.0) : 0.0;
            return length(pa - ba * h);
        }

        half4 main(float2 coord) {
            float dist = distToSegment(coord, uPrevPos, uBrushPos);
            
            // REORDER: Early out before evaluating contents (GPU performance win)
            if (dist > uBrushRadius) {
                return contents.eval(coord);
            }
            
            half4 base = contents.eval(coord);
            float normDist = dist / uBrushRadius;
            
            // Hardness uniform applied to falloff transition
            float falloff = smoothstep(1.0, uHardness, normDist);
            
            // Cold press paper grain granulation modifier
            float grainNoise = pseudoNoise(coord * 0.15) * 0.6 + pseudoNoise(coord * 0.35) * 0.4;
            float granulation = mix(1.0, 0.4 + 1.2 * grainNoise, uPaperGrain);

            // Realistic color blending: avoid transparent black background darkening
            half3 mixedRgb;
            if (base.a > 0.0) {
                mixedRgb = mix(base.rgb, uBrushColor.rgb, uPigmentLoad * falloff * uMixStrength * granulation);
            } else {
                mixedRgb = uBrushColor.rgb;
            }

            // Impasto: Thick paint 3D relief with bristle lines and lighting
            if (uImpasto > 0.0) {
                // Direction vector from segment center to current coord
                float2 pa = coord - uPrevPos;
                float2 ba = uBrushPos - uPrevPos;
                float h = clamp(dot(pa, ba) / (dot(ba, ba) + 0.0001), 0.0, 1.0);
                float2 closestPoint = uPrevPos + ba * h;
                float2 toCoord = coord - closestPoint;
                float2 dir = length(toCoord) > 0.0 ? normalize(toCoord) : float2(0.0);

                // Perpendicular direction for parallel bristle lines
                float2 strokeDir = length(ba) > 0.0 ? normalize(ba) : float2(1.0, 0.0);
                float2 perpDir = float2(-strokeDir.y, strokeDir.x);
                float perpDist = dot(coord - uPrevPos, perpDir);

                // Bristle lines modulated by brush size and falloff
                float bristleFreq = 2.0 / (uBrushRadius * 0.05 + 1.0);
                float bristleRidge = sin(perpDist * bristleFreq) * 0.25 * falloff;
                
                float impastoHeight = clamp(((1.0 - normDist) * falloff) + bristleRidge, 0.0, 1.0);

                // Lighting calculation (light travels from top-left to bottom-right)
                float2 lightDir = normalize(float2(1.0, 1.0));
                float lightFacing = dot(dir, lightDir);

                float highlight = smoothstep(0.0, 1.0, lightFacing) * 0.28 * uImpasto * impastoHeight;
                float shadow = smoothstep(0.0, -1.0, lightFacing) * 0.16 * uImpasto * impastoHeight;
                mixedRgb = clamp(mixedRgb + half3(highlight) - half3(shadow), 0.0, 1.0);
            }

            // Watercolor wet-edge fringe: dark pigment accumulation at the border
            if (uWetness > 0.5) {
                float fringe = smoothstep(0.75, 0.98, normDist) * smoothstep(1.0, 0.98, normDist) * 0.35 * uWetness;
                mixedRgb = mix(mixedRgb, mixedRgb * 0.65, fringe);
            }

            // Realistic alpha blending based on wetness/viscosity
            float brushAlpha = uBrushColor.a * falloff * granulation;
            float newAlpha;
            if (uWetness > 0.5) {
                // Watercolor bleed buildup
                newAlpha = max(base.a, brushAlpha * 0.75) + base.a * brushAlpha * 0.25;
            } else {
                // Opaque layering
                newAlpha = base.a + brushAlpha * (1.0 - base.a);
            }
            newAlpha = clamp(newAlpha, 0.0, 1.0);

            return half4(mixedRgb, newAlpha);
        }
    """.trimIndent()

    // Preset values for tools
    data class ToolPreset(
        val wetness: Float,
        val pigmentLoad: Float,
        val mixStrength: Float,
        val impasto: Float,
        val hardness: Float
    )

    val PRESETS = mapOf(
        com.authorss81.noteflow.data.model.StrokeTool.OIL_PAINT to ToolPreset(
            wetness = 0.15f,
            pigmentLoad = 0.95f,
            mixStrength = 0.85f,
            impasto = 0.9f,
            hardness = 0.8f
        ),
        com.authorss81.noteflow.data.model.StrokeTool.WATERCOLOR to ToolPreset(
            wetness = 0.9f,
            pigmentLoad = 0.35f,
            mixStrength = 0.6f,
            impasto = 0.0f,
            hardness = 0.25f
        ),
        com.authorss81.noteflow.data.model.StrokeTool.SMUDGE to ToolPreset(
            wetness = 0.4f,
            pigmentLoad = 0.0f,
            mixStrength = 0.85f,
            impasto = 0.1f,
            hardness = 0.5f
        ),
        com.authorss81.noteflow.data.model.StrokeTool.SPLATTER to ToolPreset(
            wetness = 0.7f,
            pigmentLoad = 0.9f,
            mixStrength = 0.3f,
            impasto = 0.2f,
            hardness = 0.7f
        )
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createPassThroughEffect(): RenderEffect {
        val runtimeShader = android.graphics.RuntimeShader(PASS_THROUGH_SHADER)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(
            runtimeShader,
            "contents"
        ).asComposeRenderEffect()
    }

    /**
     * Reusable wet-mixing effect: owns one RuntimeShader + RenderEffect so uniforms
     * can be updated per frame without per-frame shader/effect allocation.
     * Requires API 33+ (AGSL) — guard instantiation with [ShaderCapabilityHelper.isAgslSupported].
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class WetMixingEffect {
        private val runtimeShader = android.graphics.RuntimeShader(WET_MIXING_SHADER)
        val effect: RenderEffect =
            android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "contents").asComposeRenderEffect()
        val androidEffect: android.graphics.RenderEffect =
            android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "contents")

        fun update(
            prevX: Float,
            prevY: Float,
            brushX: Float,
            brushY: Float,
            radius: Float,
            color: androidx.compose.ui.graphics.Color,
            wetness: Float,
            pigmentLoad: Float,
            mixStrength: Float,
            impasto: Float,
            hardness: Float,
            paperGrain: Float = 0.5f
        ) {
            runtimeShader.setFloatUniform("uPrevPos", prevX, prevY)
            runtimeShader.setFloatUniform("uBrushPos", brushX, brushY)
            runtimeShader.setFloatUniform("uBrushRadius", radius.coerceAtLeast(1f))
            runtimeShader.setFloatUniform("uBrushColor", color.red, color.green, color.blue, color.alpha)
            runtimeShader.setFloatUniform("uWetness", wetness)
            runtimeShader.setFloatUniform("uPigmentLoad", pigmentLoad)
            runtimeShader.setFloatUniform("uMixStrength", mixStrength)
            runtimeShader.setFloatUniform("uImpasto", impasto)
            runtimeShader.setFloatUniform("uPaperGrain", paperGrain)
            runtimeShader.setFloatUniform("uHardness", hardness)
        }
    }
}
