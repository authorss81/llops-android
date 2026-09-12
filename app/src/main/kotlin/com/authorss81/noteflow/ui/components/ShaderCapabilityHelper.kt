package com.authorss81.noteflow.ui.components

import android.os.Build
import com.authorss81.noteflow.utils.AgslGate
import com.authorss81.noteflow.utils.DeviceTier

/**
 * Phase 201 (PERF 2.7) — single decision table for the GPU compositing tier
 * the wet-brush pipeline relies on.
 *
 * Tiers (see `drawWetLayerPass` in AnnotationCanvas + AgslShaders.WetMixingEffect):
 *  - API 33+ ([isAgslSupported]): AGSL [android.graphics.RuntimeShader] wrapped
 *    in an [android.graphics.RenderEffect], applied to a reusable RenderNode
 *    (`RenderNode.setRenderEffect`) and composited by the hardware canvas via
 *    `Canvas.drawRenderNode`. This is the only tier that runs WET_MIXING_SHADER.
 *  - API 31-32: RenderEffect/RenderNode compositing exists but AGSL does not —
 *    the wet brushes render through WetBrushEngine's vector fallback on the
 *    same accelerated canvas. (The pure 31+ tier function that used to live
 *    here was removed in the phase-201 review: nothing consumed it.)
 *  - API 26-30: neither capability — fully software/vector ink path.
 *
 * The threshold logic lives in a pure function taking an explicit sdkInt so the
 * tier is unit-testable on the JVM (Build.VERSION.SDK_INT is not readable in a
 * plain unit-test environment); the live property reads the real device.
 */
object ShaderCapabilityHelper {

    /**
     * Phase 269: OS-level AGSL capability ONLY (Android 13 / API 33) — for
     * settings-visibility decisions, NEVER the render gate. The render gate is
     * the tier-aware [agslSupportedFor]/[AgslGate.isSupported]: on LOW_END
     * hardware this property is true while the shader must stay off.
     */
    val isAgslSupported: Boolean
        get() = agslSupportedFor(Build.VERSION.SDK_INT)

    fun agslSupportedFor(sdkInt: Int): Boolean =
        AgslGate.sdkCapable(sdkInt)

    /**
     * Phase 269: the full single-truth gate (SDK >= 33 AND tier != LOW_END) —
     * the same [AgslGate.isSupported] `DeviceCompatibilityManager` and every
     * `AnnotationCanvas` allocation/use site read.
     */
    fun agslSupportedFor(sdkInt: Int, tier: DeviceTier): Boolean =
        AgslGate.isSupported(sdkInt, tier)
}
