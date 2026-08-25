package com.authorss81.noteflow.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.authorss81.noteflow.R

/**
 * Phase 34: bundled typefaces, licensed under the SIL Open Font License 1.1
 * (full texts: docs/fonts/plus-jakarta-sans-OFL.txt, docs/fonts/lora-OFL.txt).
 *
 * - **Plus Jakarta Sans** (a clean modern geometric sans) for ALL system UI
 *   chrome and metadata.
 * - **Lora** (an editorial serif) for Markdown long-form reading mode and the
 *   editorial display/headline roles in light themes.
 *
 * Both are variable-weight TTFs (~600 KB total on disk). Compose drives their
 * weight axis explicitly via [FontVariation], so a bold heading renders a real
 * bold instance instead of a synthetic weight; the persistence ships the raw
 * variable files so glyph narrowness/spacing stays true to the design.
 */
@OptIn(ExperimentalTextApi::class)
object AppFonts {

    /** Actually-coupled weights used by the type scale. */
    val SupportedWeights: List<FontWeight> = listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold
    )

    private fun variationSettings(weight: FontWeight): FontVariation.Settings =
        FontVariation.Settings(
            FontVariation.weight(weight.weight)
        )

    /** Plus Jakarta Sans — geometric sans. Bounded to the 400–700 instance. */
    val Sans: FontFamily = FontFamily(
        SupportedWeights.map { weight ->
            Font(
                resId = R.font.plus_jakarta_sans,
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = variationSettings(weight)
            )
        }
    )

    /** Lora — editorial serif, roman. */
    val Serif: FontFamily = FontFamily(
        SupportedWeights.map { weight ->
            Font(
                resId = R.font.lora,
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = variationSettings(weight)
            )
        }
    )

    // Phase 211: `SerifItalic` + res/font/lora_italic.ttf (221 KB) were DELETED.
    // The family was never referenced outside this object (grep-verified), so
    // the italic face shipped in every APK for nothing. If an italic serif is
    // ever needed again, prefer synthetic slanting (FontStyle.Italic on the
    // lora variable font) or re-add the asset deliberately.
}
