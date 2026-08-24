package com.authorss81.noteflow.ui.components

import android.os.Build

/**
 * Phase 201 (PERF 2.7) — single decision table for the GPU compositing tiers
 * the wet-brush pipeline relies on.
 *
 * Tiers (see `drawWetLayerPass` in AnnotationCanvas + AgslShaders.WetMixingEffect):
 *  - API 33+ ([isAgslSupported]): AGSL [android.graphics.RuntimeShader] wrapped
 *    in an [android.graphics.RenderEffect] and applied to the layer Paint; each
 *    `saveLayer` offscreen pass is hardware-accelerated and composited as a
 *    RenderNode by the GPU. This is the only tier that runs WET_MIXING_SHADER.
 *  - API 31-32: RenderEffect/RenderNode compositing exists
 *    ([renderEffectCompositingSupported]) but AGSL does not — the wet brushes
 *    render through WetBrushEngine's vector fallback on the same accelerated
 *    canvas.
 *  - API 26-30: neither capability — fully software/vector ink path.
 *
 * The threshold logic lives in pure functions taking an explicit sdkInt so the
 * tiers are unit-testable on the JVM (Build.VERSION.SDK_INT is not readable in
 * a plain unit-test environment); the live properties read the real device.
 */
object ShaderCapabilityHelper {

    /** AGSL RuntimeShader support (Android 13 / API 33). */
    val isAgslSupported: Boolean
        get() = agslSupportedFor(Build.VERSION.SDK_INT)

    /**
     * RenderEffect-on-Paint compositing support: from API 31 a RenderEffect is
     * applied to a hardware-accelerated canvas and composited via RenderNode.
     * Below 31 there is no such path at all (software fallback).
     */
    val renderEffectCompositingSupported: Boolean
        get() = renderEffectCompositingFor(Build.VERSION.SDK_INT)

    fun agslSupportedFor(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU // API 33

    fun renderEffectCompositingFor(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.S // API 31
}
