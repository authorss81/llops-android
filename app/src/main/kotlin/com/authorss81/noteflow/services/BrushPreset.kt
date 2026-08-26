package com.authorss81.noteflow.services

/**
 * A ready-made named brush preset.
 *
 * Presets are NOT a new engine: they are just pre-filled parameter sets for the
 * existing [WetBrushEngine] + AGSL wet shader ([BrushStudioParams]) plus the
 * classic brush inputs (tool, size, color) and the Phase-07 pressure curve.
 * Selecting one only writes settings state — classic rendering is untouched
 * when no preset is active.
 */
data class BrushPreset(
    val id: String,
    val name: String,
    val tool: com.authorss81.noteflow.data.model.StrokeTool,
    val brushParams: BrushStudioParams,
    val size: Float,
    val colorHex: String,
    val pressureCurveKey: String,
    /**
     * Phase 197: per-brush stroke-stabilizer smoothing fraction in [0..1]
     * (mapped to EWMA window 2..12 by [StrokeSmoothingPolicy]). Fast/precise
     * brushes (calligraphy, pencil) sit LOW; broad forgiving brushes (marker,
     * watercolor) sit HIGH. Default = the no-preset baseline so imported
     * presets and pre-197 call sites behave exactly like the legacy tuning.
     */
    val smoothing: Float = StrokeSmoothingPolicy.DEFAULT_SMOOTHING
)

/**
 * Curated offline brush pack. Parameter meaning (see BrushStudioParams):
 * dilution = water ratio (wetness), charge = pigment load, pull = blend
 * strength, impasto = 3D ridge relief (hardness), paperGrain = granulation
 * texture, splatterSpread = droplet spray.
 */
object BrushPresetPack {

    private val ALL = listOf(
        BrushPreset(
            id = "pencil_graphite",
            name = "Pencil",
            tool = com.authorss81.noteflow.data.model.StrokeTool.PENCIL,
            brushParams = BrushStudioParams(
                dilution = 0.82f, charge = 0.42f, pull = 0.30f, impasto = 0.0f,
                paperGrain = 0.88f, splatterSpread = 0.10f
            ),
            size = 3.5f,
            colorHex = "#334155",
            pressureCurveKey = "linear",
            smoothing = 0.15f
        ),
        BrushPreset(
            id = "fountain_pen",
            name = "Fountain Pen",
            tool = com.authorss81.noteflow.data.model.StrokeTool.FOUNTAIN_PEN,
            brushParams = BrushStudioParams(
                dilution = 0.30f, charge = 0.92f, pull = 0.45f, impasto = 0.10f,
                paperGrain = 0.40f, splatterSpread = 0.0f
            ),
            size = 4.0f,
            colorHex = "#1B365D",
            pressureCurveKey = "heavy",
            smoothing = 0.30f
        ),
        BrushPreset(
            id = "marker",
            name = "Marker",
            tool = com.authorss81.noteflow.data.model.StrokeTool.MARKER,
            brushParams = BrushStudioParams(
                dilution = 0.18f, charge = 0.96f, pull = 0.50f, impasto = 0.0f,
                paperGrain = 0.50f, splatterSpread = 0.0f
            ),
            size = 9.0f,
            colorHex = "#9333EA",
            pressureCurveKey = "linear",
            smoothing = 0.70f
        ),
        BrushPreset(
            id = "soft_watercolor",
            name = "Soft Watercolor",
            tool = com.authorss81.noteflow.data.model.StrokeTool.WATERCOLOR,
            brushParams = BrushStudioParams(
                dilution = 0.92f, charge = 0.45f, pull = 0.62f, impasto = 0.05f,
                paperGrain = 0.92f, splatterSpread = 0.35f
            ),
            size = 14.0f,
            colorHex = "#3B82F6",
            pressureCurveKey = "light",
            smoothing = 0.90f
        ),
        BrushPreset(
            id = "dry_oil",
            name = "Dry Oil",
            tool = com.authorss81.noteflow.data.model.StrokeTool.OIL_PAINT,
            brushParams = BrushStudioParams(
                dilution = 0.14f, charge = 0.86f, pull = 0.66f, impasto = 0.72f,
                paperGrain = 0.78f, splatterSpread = 0.0f
            ),
            size = 12.0f,
            colorHex = "#B45309",
            pressureCurveKey = "heavy",
            smoothing = 0.60f
        ),
        BrushPreset(
            id = "chalk",
            name = "Chalk",
            tool = com.authorss81.noteflow.data.model.StrokeTool.CALLIGRAPHIC,
            brushParams = BrushStudioParams(
                dilution = 0.55f, charge = 0.58f, pull = 0.36f, impasto = 0.20f,
                paperGrain = 0.96f, splatterSpread = 0.20f
            ),
            size = 10.0f,
            colorHex = "#E2E8F0",
            pressureCurveKey = "light",
            // Calligraphy is a fast, deliberate stroke tool — tighter than the
            // broad marker so quick flicks stay crisp (phase-197 requirement).
            smoothing = 0.30f
        ),
        BrushPreset(
            id = "highlighter",
            name = "Highlighter",
            tool = com.authorss81.noteflow.data.model.StrokeTool.HIGHLIGHTER,
            brushParams = BrushStudioParams(
                dilution = 0.10f, charge = 1.0f, pull = 0.20f, impasto = 0.0f,
                paperGrain = 0.50f, splatterSpread = 0.0f
            ),
            size = 18.0f,
            colorHex = "#FBBF24",
            pressureCurveKey = "linear",
            smoothing = 0.45f
        ),
        BrushPreset(
            id = "eraser",
            name = "Eraser",
            tool = com.authorss81.noteflow.data.model.StrokeTool.ERASER,
            brushParams = BrushStudioParams(
                dilution = 1.0f, charge = 1.0f, pull = 0.20f, impasto = 0.0f,
                paperGrain = 0.30f, splatterSpread = 0.0f
            ),
            size = 22.0f,
            colorHex = "#475569",
            pressureCurveKey = "linear",
            // The eraser path never routes through the stabilizer; a neutral
            // value keeps every pack member valid all the same.
            smoothing = 0.50f
        ),
        // Phase 219: soft shade for portrait work — light pressure deposits
        // faint graphite that builds with repeated passes. The LIGHT curve
        // (sqrt(p)) widens low-pressure strokes so a gentle touch still covers
        // area; low charge (0.25) keeps single-pass alpha ~0.30 so 3 passes
        // reach mid-tone. SMUDGE pairs naturally for blending.
        BrushPreset(
            id = "soft_shade",
            name = "Soft Shade",
            tool = com.authorss81.noteflow.data.model.StrokeTool.PENCIL,
            brushParams = BrushStudioParams(
                dilution = 0.88f, charge = 0.25f, pull = 0.30f, impasto = 0.0f,
                paperGrain = 0.35f, splatterSpread = 0.05f
            ),
            size = 18.0f,
            colorHex = "#334155",
            pressureCurveKey = "light",
            smoothing = 0.75f
        )
    )

    /** The full curated preset pack, in display order. */
    fun all(): List<BrushPreset> = ALL

    fun byId(id: String?): BrushPreset? = ALL.firstOrNull { it.id == id }

    /**
     * Parameter range checks. Returns a list of problems (empty = valid).
     * All BrushStudioParams live in [0..1]; size and color must be sane.
     */
    fun validateParams(brushParams: BrushStudioParams): List<String> {
        val problems = mutableListOf<String>()
        if (brushParams.dilution !in 0f..1f) problems += "dilution ${brushParams.dilution} out of [0,1]"
        if (brushParams.charge !in 0f..1f) problems += "charge ${brushParams.charge} out of [0,1]"
        if (brushParams.pull !in 0f..1f) problems += "pull ${brushParams.pull} out of [0,1]"
        if (brushParams.impasto !in 0f..1f) problems += "impasto ${brushParams.impasto} out of [0,1]"
        if (brushParams.paperGrain !in 0f..1f) problems += "paperGrain ${brushParams.paperGrain} out of [0,1]"
        if (brushParams.splatterSpread !in 0f..1f) problems += "splatterSpread ${brushParams.splatterSpread} out of [0,1]"
        return problems
    }

    /** Every preset must map to valid parameter ranges (pure Kotlin). */
    fun validateAllPresets(): List<Pair<String, String>> {
        return ALL.flatMap { preset ->
            validateParams(preset.brushParams).map { problem -> preset.id to problem }
        }
    }

    /** Every preset must resolve to a real tool and a valid stroke size. */
    fun validatePresetSizes(): List<Pair<String, String>> {
        return ALL.mapNotNull { preset ->
            if (preset.size !in 0.5f..120f) preset.id to "size ${preset.size} out of [0.5,120]" else null
        }
    }

    /** Every preset must carry an in-range stabilizer smoothing fraction (phase 197). */
    fun validatePresetSmoothing(): List<Pair<String, String>> {
        return ALL.mapNotNull { preset ->
            if (preset.smoothing.isNaN() || preset.smoothing !in 0f..1f) {
                preset.id to "smoothing ${preset.smoothing} out of [0,1]"
            } else {
                null
            }
        }
    }

    /** Returns true when the preset id is a real pack entry. */
    fun isValidId(id: String?): Boolean = byId(id) != null
}
