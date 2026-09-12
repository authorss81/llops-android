package com.authorss81.noteflow.utils

/**
 * Phase 269 (compat): the SINGLE truth for "may the AGSL wet-mixing shader
 * run on this device".
 *
 * Pre-269 there were two truths: `DeviceCompatibilityManager.isAgslSupported`
 * (SDK >= 33 AND tier != LOW_END) vs `ShaderCapabilityHelper.isAgslSupported`
 * (SDK >= 33 only). `AnnotationCanvas` allocated/used the RuntimeShader behind
 * the SDK-only check, so a LOW_END API-33+ device whose user re-enabled
 * `gpuWetBrushes` ran the shader — the exact OOM/jank path the tier gate
 * exists to prevent.
 *
 * Both helpers now delegate here; every canvas allocation/use site reads the
 * tier-aware [isSupported]. The SDK-only capability
 * (`ShaderCapabilityHelper.agslSupportedFor(sdkInt)`) remains as the "could
 * this OS ever run AGSL" fact for settings-visibility decisions — it is NOT
 * the render gate, and its KDoc says so.
 *
 * Pure JVM (explicit `sdkInt` + `tier` params) so the whole truth table is
 * unit-testable without Robolectric.
 */
object AgslGate {

    /** AGSL `android.graphics.RuntimeShader` arrived in Android 13. */
    const val AGSL_MIN_SDK = 33

    /**
     * Full render gate: OS capability AND capable hardware. LOW_END never
     * passes, even when the user re-enabled the `gpuWetBrushes` toggle — the
     * canvas falls back to the `WetBrushEngine` vector path and the settings
     * toggle renders disabled-with-explanation on such devices (never a
     * silently-ineffective switch).
     */
    fun isSupported(sdkInt: Int, tier: DeviceTier): Boolean =
        sdkInt >= AGSL_MIN_SDK && tier != DeviceTier.LOW_END

    /** OS-level capability only — for settings visibility, never the render gate. */
    fun sdkCapable(sdkInt: Int): Boolean = sdkInt >= AGSL_MIN_SDK
}
