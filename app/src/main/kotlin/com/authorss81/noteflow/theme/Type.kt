package com.authorss81.noteflow.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Phase 34: the app-wide type scale.
 *
 * Every Material 3 style is derived from [TypeScale] (metrics live in the
 * pure data, on the 4 dp baseline grid) and paired with a font family:
 * - DISPLAY/HEADLINE get the editorial [AppFonts.Serif] when the theme asks for
 *   it (light/sepia) and the geometric [AppFonts.Sans] otherwise.
 * - TITLE/BODY/LABEL (all UI chrome + metadata) always use [AppFonts.Sans].
 *
 * Markdown long-form reading mode opts into [AppFonts.Serif] on the BODY roles
 * via `serifBodyStyle` (MarkdownPreviewScreen) — the UI default stays sans.
 */

private fun buildAppTypography(displaySerif: Boolean): Typography {
    val displayFamily = if (displaySerif) AppFonts.Serif else AppFonts.Sans
    val displayWeight = if (displaySerif) FontWeight.SemiBold else FontWeight.Normal

    fun textStyle(spec: TypeScaleSpec, family: FontFamily): TextStyle = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(spec.fontWeight),
        fontSize = spec.fontSizeSp.sp,
        lineHeight = spec.lineHeightSp.sp,
        letterSpacing = spec.letterSpacingSp.sp
    )

    val styles: Map<String, TextStyle> = TypeScale.scales.associate { spec ->
        val family = when (spec.role) {
            TypeScaleRole.DISPLAY -> displayFamily
            TypeScaleRole.HEADLINE -> displayFamily
            else -> AppFonts.Sans
        }
        val weightAdjusted = when (spec.role) {
            TypeScaleRole.DISPLAY -> spec.copy(fontWeight = displayWeight.weight)
            TypeScaleRole.BODY -> spec.copy(fontWeight = 400)
            else -> spec
        }
        spec.styleName to textStyle(weightAdjusted, family)
    }

    fun t(name: String): TextStyle = styles.getValue(name)

    return Typography(
        displayLarge = t("displayLarge"),
        displayMedium = t("displayMedium"),
        displaySmall = t("displaySmall"),
        headlineLarge = t("headlineLarge"),
        headlineMedium = t("headlineMedium"),
        headlineSmall = t("headlineSmall"),
        titleLarge = t("titleLarge"),
        titleMedium = t("titleMedium"),
        titleSmall = t("titleSmall"),
        bodyLarge = t("bodyLarge"),
        bodyMedium = t("bodyMedium"),
        bodySmall = t("bodySmall"),
        labelLarge = t("labelLarge"),
        labelMedium = t("labelMedium"),
        labelSmall = t("labelSmall")
    )
}

/** Default typography (sans grouped; display sans too). */
val Typography: Typography = buildAppTypography(displaySerif = false)

/** A BODY-role style rendered in the editorial serif for long-form reading. */
fun serifBodyStyle(
    base: TextStyle,
    serif: Boolean
): TextStyle =
    if (serif) base.copy(fontFamily = AppFonts.Serif) else base

fun typographyFor(mode: AppThemeMode, systemDark: Boolean): Typography {
    val isDark = when (mode) {
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> false
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.DYNAMIC -> systemDark
        AppThemeMode.GLASS -> systemDark
    }

    val useSerif = when (mode) {
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> true
        AppThemeMode.DARK, AppThemeMode.AMOLED -> false
        AppThemeMode.SYSTEM, AppThemeMode.DYNAMIC, AppThemeMode.GLASS -> !isDark
    }

    return buildAppTypography(displaySerif = useSerif)
}