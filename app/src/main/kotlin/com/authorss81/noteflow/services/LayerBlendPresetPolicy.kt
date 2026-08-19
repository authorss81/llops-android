package com.authorss81.noteflow.services

/**
 * Phase 172 — layer blend-mode quick-preset decision table. Pure JVM.
 *
 * The layer panel already persists `LayerEntity.opacity`/`blendMode` through
 * the existing layer-update path (no schema change). This policy is the single
 * owner of the compact preset set surfaced as labelled chips in the layer row:
 * a curated subset of the 12 modes the canvas renderer accepts
 * (`AnnotationCanvas.layerBlendApiQ` on API 29+, `layerBlendPorterDuff` below),
 * chosen to match the wet-brush/AGSL chart's meaningful tones.
 *
 * [isSupportedByRenderer] mirrors the renderer's accepted set so the presets
 * (and any code that validates a blend string) share one source of truth and a
 * test can pin "every preset must render".
 */
object LayerBlendPresetPolicy {

    data class BlendPreset(
        val key: String,
        val label: String,
        val description: String
    )

    /** Every blend key the canvas renderer maps (API-qualified map + PorterDuff fallback). */
    val RENDERER_SUPPORTED_MODES: List<String> = listOf(
        "NORMAL", "MULTIPLY", "SCREEN", "OVERLAY",
        "DARKEN", "LIGHTEN", "COLOR_DODGE", "COLOR_BURN",
        "HARD_LIGHT", "SOFT_LIGHT", "DIFFERENCE", "EXCLUSION"
    )

    /** Compact quick-preset set shown as chips in the layer row. */
    val PRESETS: List<BlendPreset> = listOf(
        BlendPreset("NORMAL", "Normal", "Paint as-is over the layers below"),
        BlendPreset("MULTIPLY", "Multiply", "Darkens by multiplying the layers below"),
        BlendPreset("SCREEN", "Screen", "Lightens by inverting and multiplying"),
        BlendPreset("OVERLAY", "Overlay", "Combine multiply + screen by luminance"),
        BlendPreset("SOFT_LIGHT", "Soft Light", "Soft tonal contrast on the layers below")
    )

    fun presets(): List<BlendPreset> = PRESETS

    /** Case-insensitive membership check against the renderer's accepted set. */
    fun isSupportedByRenderer(key: String): Boolean =
        RENDERER_SUPPORTED_MODES.any { it.equals(key, ignoreCase = true) }

    /** The preset matching [key], or null when it is not one of the presets. */
    fun presetFor(key: String): BlendPreset? =
        PRESETS.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** User-facing label for a stored layer blend key (falls back to the key itself). */
    fun displayLabel(key: String): String = presetFor(key)?.label ?: key
}