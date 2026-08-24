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
    // Phase 18: uBrushStyle selects a distinct painting style (see BrushStyle constants below)
    // and uStrokeSeed gives every stroke a fresh grain/bristle phase so texture orientation
    // varies per stroke instead of being fixed per page.
    //
    // Style selector values (MUST match AgslShaders.StyleIds / ToolPreset.brushStyle):
    //   0 = generic wash     1 = WATERCOLOR   2 = OIL_PAINT   3 = SMUDGE   4 = SPLATTER
    //   5 = CHARCOAL         6 = OIL_PASTEL   7 = INK_WASH    8 = GOUACHE  9 = DRY_BRUSH
    //  10 = PALETTE_KNIFE
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
        uniform float uSeed;         // per-stroke seed: spatial dither so grain differs per stroke
        uniform float uStrokeSeed;   // secondary per-stroke seed: rotates grain/bristle phase
        uniform float uBrushStyle;   // style selector (see style constants above)
        uniform float uVibrancy;     // 0..1 render-time saturation lift on deposited pigment (0 = classic)

        float hash21(float2 p) {
            float2 q = p * 127.1 + float2(311.7, 74.7) + float2(uSeed, uSeed * 1.7);
            return fract(sin(dot(q, float2(12.9898, 78.233))) * 43758.5453);
        }

        float valueNoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 s = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(hash21(i), hash21(i + float2(1.0, 0.0)), s.x),
                mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), s.x),
                s.y
            );
        }

        float distToSegment(float2 p, float2 a, float2 b) {
            float2 pa = p - a, ba = b - a;
            float d2 = dot(ba, ba);
            float h = d2 > 0.0001 ? clamp(dot(pa, ba) / d2, 0.0, 1.0) : 0.0;
            return length(pa - ba * h);
        }

        // Phase 200 (PERF 3.2): standard piecewise sRGB transfer functions so the
        // pigment mixing below happens in LINEAR light — mirrors exactly
        // WetMixingMath.srgbToLinear / linearToSrgb / channelToMixSpace /
        // channelFromMixSpace (ColorSpaces.LinearSrgb). Mixing gamma-encoded
        // sRGB directly collapsed bright complementary washes into muddy dark
        // browns; linear-light absorbance keeps equal-energy mixes luminous.
        // Review-fix (phase-200): the knee comparison matches the Kotlin
        // reference EXACTLY — `step(c, threshold)` selects the linear segment
        // at c <= threshold, same as the Kotlin `if (c <= …)` (the previous
        // `step(threshold, c)` flipped one ulp early at exactly the knee).
        half3 srgbToLinear3(half3 c) {
            half3 lo = c / 12.92;
            half3 hi = pow((c + 0.055) / 1.055, half3(2.4));
            return mix(hi, lo, step(c, half3(0.04045)));
        }

        half3 linearToSrgb3(half3 c) {
            half3 lo = c * 12.92;
            half3 hi = pow(c, half3(1.0 / 2.4)) * 1.055 - 0.055;
            return mix(hi, lo, step(c, half3(0.0031308)));
        }

        half4 main(float2 coord) {
            float dist = distToSegment(coord, uPrevPos, uBrushPos);
            
            // REORDER: Early out before evaluating contents (GPU performance win)
            if (dist > uBrushRadius) {
                return contents.eval(coord);
            }
            
            half4 base = contents.eval(coord);
            float normDist = dist / uBrushRadius;

            // Phase 19: saturate the deposited pigment toward fully saturated so
            // OIL_PAINT/WATERCOLOR (and every style) keep their pigment character
            // while gaining vibrancy. Hue and max channel (value) are preserved,
            // so the result is always in gamut and uVibrancy == 0 is bit-identical
            // to the pre-phase-19 shader for the same inputs.
            half3 vibBrushColor = uBrushColor.rgb;
            if (uVibrancy > 0.0) {
                float maxc = max(vibBrushColor.r, max(vibBrushColor.g, vibBrushColor.b));
                float minc = min(vibBrushColor.r, min(vibBrushColor.g, vibBrushColor.b));
                float cd = maxc - minc;
                float s = maxc > 0.0 ? cd / maxc : 0.0;
                float s2 = s + (1.0 - s) * uVibrancy;
                if (s2 > s && cd > 0.0001) {
                    // c' = v - (v - c) * (v - (v - s2*v))/cd = v - (v-c)*(s2/cd)*v
                    vibBrushColor = clamp(maxc - (maxc - vibBrushColor) * ((maxc - (maxc * (1.0 - s2))) / cd), 0.0, 1.0);
                }
            }
            
            // Hardness uniform applied to falloff transition. Phase 27: the band is
            // guaranteed at least ~1.5px wide in PIXEL space (capped at half the
            // radius) so hard brushes (high uHardness) never alias into a sub-pixel
            // ring at small widths. `min(uHardness, …)` keeps the soft-brush look
            // unchanged (wide band) while forcing a real penumbra for hard brushes.
            // Mirrors BrushColorModeMath.edgeFeather — the two MUST stay in sync.
            float minFeatherPx = 1.5;
            float bandWidth = min(minFeatherPx, uBrushRadius * 0.5);
            float bandStart = min(uHardness, 1.0 - bandWidth / uBrushRadius);
            float falloff = smoothstep(1.0, bandStart, normDist);
            
            // Cold press paper grain granulation modifier (stable value noise, seeded per stroke
            // via uSeed in hash21 plus a per-stroke field offset from uStrokeSeed so the grain
            // orientation/phase rotates for every new stroke instead of staying page-fixed).
            float2 grainO = coord * 0.15 + float2(uStrokeSeed * 37.0, uStrokeSeed * 71.0);
            float grainNoise = valueNoise(grainO) * 0.6 + valueNoise(coord * 0.35 + float2(uStrokeSeed * 19.0, uStrokeSeed * 43.0)) * 0.4;
            float granulation = mix(1.0, 0.4 + 1.2 * grainNoise, uPaperGrain);

            // Stroke direction + perpendicular bands (used by dry-brush gaps, charcoal streaks,
            // pastel wax, palette-knife smear and the impasto relief).
            float2 ba = uBrushPos - uPrevPos;
            float2 strokeDir = length(ba) > 0.0 ? normalize(ba) : float2(1.0, 0.0);
            float2 perpDir = float2(-strokeDir.y, strokeDir.x);
            float perpDist = dot(coord - uPrevPos, perpDir);

            // --- Style-specific coverage & alpha modifiers -------------------------------
            float styleCoverage = 1.0;
            float styleAlpha = 1.0;

            if (uBrushStyle >= 5.0) {
                float2 spos = coord * 0.24;
                float along = dot(spos, strokeDir);
                float perp = dot(spos, perpDir);
                // Phase-rotated hail/shower streaks so each stroke deposits differently.
                float alongPhase = along + uStrokeSeed * 3.0;
                if (uBrushStyle == 5.0) {
                    // CHARCOAL: soft-edged powdery streaks, pigment only on grain peaks.
                    float streak = valueNoise(float2(alongPhase * 0.42, perp * 1.7)) * 0.6 + valueNoise(float2(alongPhase * 1.1, perp * 3.2)) * 0.4;
                    float peakGrain = smoothstep(0.50, 1.0, grainNoise);
                    float notches = smoothstep(0.42, 1.0, valueNoise(float2(alongPhase * 2.2, perp * 6.0)));
                    styleCoverage = clamp(0.26 + 0.95 * peakGrain * streak * notches, 0.0, 1.0);
                    styleAlpha = 0.92;
                } else if (uBrushStyle == 6.0) {
                    // OIL_PASTEL: waxy, opaque body with visible wax-streak noise.
                    float wax = valueNoise(float2(perp * 4.0, alongPhase * 1.3)) * 0.40 + 0.60;
                    styleCoverage = clamp(0.82 + wax * 0.18, 0.0, 1.0);
                    styleAlpha = 1.0;
                } else if (uBrushStyle == 7.0) {
                    // INK_WASH: concentrated wet ink — full body, strong dark wet edge handled below.
                    styleCoverage = 1.0;
                    styleAlpha = 0.98;
                } else if (uBrushStyle == 9.0) {
                    // DRY_BRUSH: only bristle tips touch — narrow perpendicular gaps.
                    float bristle = fract(perpDist / max(uBrushRadius * 0.62, 0.5) + uStrokeSeed * 4.0);
                    float gap = abs(bristle - 0.5);
                    float bristleCoverage = 1.0 - smoothstep(0.045, 0.24, gap);
                    styleCoverage = clamp(0.10 + bristleCoverage * 0.9, 0.0, 1.0);
                    styleAlpha = 0.85;
                } else if (uBrushStyle == 10.0) {
                    // PALETTE_KNIFE: directional smear streaks that drag existing paint.
                    float smear = valueNoise(float2(perp * 3.2, alongPhase * 0.7)) * 0.55 + 0.45;
                    styleCoverage = clamp(0.55 + smear * 0.45, 0.0, 1.0);
                    styleAlpha = 0.96;
                }
            }

            // Pigment-space (subtractive) mixing: absorbances multiply
            // (1-(1-base)(1-brush)) per channel instead of a linear-RGB lerp,
            // which keeps overlapping complementary colors clean instead of muddy.
            // Phase 200: the absorbance product is evaluated in LINEAR LIGHT — the
            // sRGB-encoded inputs are linearized first and the mixed result is
            // re-encoded back to sRGB for write-back. This is the physically
            // correct Beer–Lambert model and keeps equal-energy mixes luminous.
            // Mirrors WetMixingMath.pigmentMix / pigmentMixRgb(…, LinearSrgb).
            float pigmentFactor;
            if (uBrushStyle == 8.0) {
                // GOUACHE: near-100% pigment coverage, matte flat coat.
                pigmentFactor = clamp(falloff * (0.9 + 0.1 * uPigmentLoad) * granulation, 0.0, 1.0);
            } else {
                pigmentFactor = clamp(uPigmentLoad * falloff * uMixStrength * granulation * styleCoverage, 0.0, 1.0);
            }
            half3 linBase = srgbToLinear3(base.rgb);
            half3 linBrush = srgbToLinear3(vibBrushColor);
            half3 mixedLin;
            if (base.a > 0.0) {
                mixedLin = linBase + (1.0 - (1.0 - linBase) * (1.0 - linBrush) - linBase) * pigmentFactor;
            } else {
                mixedLin = linBrush;
            }
            // Review-fix (phase-200): pixels where NOTHING mixes stay BIT-EXACT
            // with the pre-200 write-back instead of paying an fp16 EOTF round
            // trip: a zero-pigment deposit over existing paint passes base.rgb
            // straight through, and an empty canvas passes the vibrancy-adjusted
            // brush color straight through. Only real mixes are re-encoded.
            half3 mixedRgb;
            if (base.a > 0.0 && pigmentFactor > 0.0) {
                mixedRgb = linearToSrgb3(mixedLin);
            } else if (base.a > 0.0) {
                mixedRgb = base.rgb;
            } else {
                mixedRgb = vibBrushColor;
            }

            // Impasto: Thick paint 3D relief with bristle lines and lighting
            if (uImpasto > 0.0) {
                // Direction vector from segment center to current coord
                float2 pa = coord - uPrevPos;
                float h = clamp(dot(pa, ba) / (dot(ba, ba) + 0.0001), 0.0, 1.0);
                float2 closestPoint = uPrevPos + ba * h;
                float2 toCoord = coord - closestPoint;
                float2 dir = length(toCoord) > 0.0 ? normalize(toCoord) : float2(0.0);

                // Bristle lines modulated by brush size and falloff (phase-rotated per stroke)
                float bristleFreq = 2.0 / (uBrushRadius * 0.05 + 1.0);
                float bristleRidge = sin(perpDist * bristleFreq + uStrokeSeed * 6.0) * 0.25 * falloff;
                
                float impastoHeight = clamp(((1.0 - normDist) * falloff) + bristleRidge, 0.0, 1.0);

                // Palette knife rides flat: cap impasto relief tight to its low preset.
                if (uBrushStyle == 10.0) {
                    impastoHeight *= 0.35 + 0.65 * smoothstep(0.6, 1.0, normDist);
                }

                // Lighting calculation (light travels from top-left to bottom-right)
                float2 lightDir = normalize(float2(1.0, 1.0));
                float lightFacing = dot(dir, lightDir);

                float highlight = smoothstep(0.0, 1.0, lightFacing) * 0.28 * uImpasto * impastoHeight;
                float shadow = smoothstep(0.0, -1.0, lightFacing) * 0.16 * uImpasto * impastoHeight;
                mixedRgb = clamp(mixedRgb + half3(highlight) - half3(shadow), 0.0, 1.0);
            }

            // Watercolor wet-edge fringe: dark pigment accumulation at the border, plus a
            // secondary bloom ring for a tighter, more realistic damp crust (Phase 18).
            if (uWetness > 0.5) {
                float wetEdge;
                if (uBrushStyle == 7.0) {
                    // INK_WASH: concentrated, high-contrast wet edge — strong dark rim + pooling.
                    wetEdge = smoothstep(0.70, 0.97, normDist) * smoothstep(1.0, 0.90, normDist) * 0.55 * uWetness;
                } else {
                    wetEdge = smoothstep(0.75, 0.98, normDist) * smoothstep(1.0, 0.98, normDist) * 0.35 * uWetness;
                }
                // Secondary bloom ring: a fainter, slightly wider band feeding the wet crust.
                float bloom = smoothstep(0.50, 0.72, normDist) * smoothstep(1.0, 0.82, normDist) * 0.20 * uWetness;
                wetEdge = wetEdge + bloom;
                mixedRgb = mix(mixedRgb, mixedRgb * 0.60, wetEdge);
            }

            // Realistic alpha blending: source-over accumulation for wet AND dry.
            // Overlapping washes darken monotonically (wet-on-wet watercolor)
            // instead of the old max() shortcut which blocked overlap buildup.
            // Mirrors WetMixingMath.sourceOverAlpha.
            float brushAlpha;
            if (uBrushStyle == 8.0) {
                // GOUACHE: matte, near-opaque flat coat.
                brushAlpha = falloff * (0.88 + 0.12 * uPigmentLoad);
            } else {
                brushAlpha = uBrushColor.a * falloff * granulation * styleAlpha * styleCoverage;
            }
            brushAlpha = clamp(brushAlpha, 0.0, 1.0);
            float newAlpha = base.a + brushAlpha * (1.0 - base.a);
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
        val hardness: Float,
        val brushStyle: Int = 0
    )

    /**
     * Style selector values used by the shader's uBrushStyle uniform — MUST stay
     * in sync with the constants documented in [WET_MIXING_SHADER] and with
     * [com.authorss81.noteflow.services.BrushStrokeMath.brushStyleIdForTool].
     */
    object StyleIds {
        const val DEFAULT = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_DEFAULT
        const val WATERCOLOR = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_WATERCOLOR
        const val OIL_PAINT = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_OIL_PAINT
        const val SMUDGE = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_SMUDGE
        const val SPLATTER = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_SPLATTER
        const val CHARCOAL = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_CHARCOAL
        const val OIL_PASTEL = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_OIL_PASTEL
        const val INK_WASH = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_INK_WASH
        const val GOUACHE = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_GOUACHE
        const val DRY_BRUSH = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_DRY_BRUSH
        const val PALETTE_KNIFE = com.authorss81.noteflow.services.BrushStrokeMath.STYLE_PALETTE_KNIFE
    }

    val PRESETS = mapOf(
        com.authorss81.noteflow.data.model.StrokeTool.OIL_PAINT to ToolPreset(
            wetness = 0.15f,
            pigmentLoad = 0.95f,
            mixStrength = 0.85f,
            impasto = 0.9f,
            hardness = 0.8f,
            brushStyle = StyleIds.OIL_PAINT
        ),
        com.authorss81.noteflow.data.model.StrokeTool.WATERCOLOR to ToolPreset(
            wetness = 0.9f,
            pigmentLoad = 0.35f,
            mixStrength = 0.6f,
            impasto = 0.0f,
            hardness = 0.25f,
            brushStyle = StyleIds.WATERCOLOR
        ),
        com.authorss81.noteflow.data.model.StrokeTool.SMUDGE to ToolPreset(
            wetness = 0.4f,
            pigmentLoad = 0.0f,
            mixStrength = 0.85f,
            impasto = 0.1f,
            hardness = 0.5f,
            brushStyle = StyleIds.SMUDGE
        ),
        com.authorss81.noteflow.data.model.StrokeTool.SPLATTER to ToolPreset(
            wetness = 0.7f,
            pigmentLoad = 0.9f,
            mixStrength = 0.3f,
            impasto = 0.2f,
            hardness = 0.7f,
            brushStyle = StyleIds.SPLATTER
        ),
        // Phase 18: NEW brush styles — each is a genuinely distinct render.
        com.authorss81.noteflow.data.model.StrokeTool.CHARCOAL to ToolPreset(
            wetness = 0.0f,
            pigmentLoad = 0.55f,
            mixStrength = 0.7f,
            impasto = 0.0f,
            hardness = 0.3f,
            brushStyle = StyleIds.CHARCOAL
        ),
        com.authorss81.noteflow.data.model.StrokeTool.OIL_PASTEL to ToolPreset(
            wetness = 0.05f,
            pigmentLoad = 0.95f,
            mixStrength = 0.5f,
            impasto = 0.3f,
            hardness = 0.85f,
            brushStyle = StyleIds.OIL_PASTEL
        ),
        com.authorss81.noteflow.data.model.StrokeTool.INK_WASH to ToolPreset(
            wetness = 0.85f,
            pigmentLoad = 0.9f,
            mixStrength = 0.45f,
            impasto = 0.0f,
            hardness = 0.3f,
            brushStyle = StyleIds.INK_WASH
        ),
        com.authorss81.noteflow.data.model.StrokeTool.GOUACHE to ToolPreset(
            wetness = 0.1f,
            pigmentLoad = 0.98f,
            mixStrength = 0.6f,
            impasto = 0.05f,
            hardness = 0.9f,
            brushStyle = StyleIds.GOUACHE
        ),
        com.authorss81.noteflow.data.model.StrokeTool.DRY_BRUSH to ToolPreset(
            wetness = 0.0f,
            pigmentLoad = 0.5f,
            mixStrength = 0.6f,
            impasto = 0.0f,
            hardness = 0.4f,
            brushStyle = StyleIds.DRY_BRUSH
        ),
        com.authorss81.noteflow.data.model.StrokeTool.PALETTE_KNIFE to ToolPreset(
            wetness = 0.2f,
            pigmentLoad = 0.7f,
            mixStrength = 0.95f,
            impasto = 0.15f,
            hardness = 0.65f,
            brushStyle = StyleIds.PALETTE_KNIFE
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
     * can be updated per frame without per-frame shader/effect allocation, plus the
     * reusable [renderNode] that CARRIES the effect (phase-201/PERF 2.7):
     * `android.graphics.Paint` has NO setRenderEffect API — the only public
     * carriers are View/RenderNode (`RenderNode.setRenderEffect`, API 31+), so the
     * wet pass records its strokes into this node and composites it onto the
     * hardware canvas. Requires API 33+ (AGSL) — guard instantiation with
     * [ShaderCapabilityHelper.isAgslSupported].
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class WetMixingEffect {
        private val runtimeShader = android.graphics.RuntimeShader(WET_MIXING_SHADER)
        val effect: RenderEffect =
            android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "contents").asComposeRenderEffect()
        val androidEffect: android.graphics.RenderEffect =
            android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "contents")

        /** GPU carrier for the wet pass (reused every frame; never re-allocated). */
        val renderNode: android.graphics.RenderNode = android.graphics.RenderNode("inkflow-wet-mix")

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
            paperGrain: Float = 0.5f,
            seed: Float = 0f,
            strokeSeed: Float = 0f,
            brushStyle: Int = StyleIds.DEFAULT,
            vibrancy: Float = 0f
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
            runtimeShader.setFloatUniform("uSeed", seed)
            runtimeShader.setFloatUniform("uStrokeSeed", strokeSeed)
            runtimeShader.setFloatUniform("uBrushStyle", brushStyle.toFloat())
            runtimeShader.setFloatUniform("uVibrancy", vibrancy.coerceIn(0f, 1f))
        }
    }
}
