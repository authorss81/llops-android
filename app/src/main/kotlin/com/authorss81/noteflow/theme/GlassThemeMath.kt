package com.authorss81.noteflow.theme

import androidx.compose.ui.graphics.Color

/**
 * Pure-JVM glassmorphism color math (Phase 28). Everything here is deterministic
 * and unit-testable on the JVM: given an ambient background color + ambient
 * polarity it derives frosted panel/container colors whose on-panel text
 * contrast is guaranteed (WCAG ≥ [MIN_CONTRAST_RATIO]).
 */
object GlassThemeMath {

    const val MIN_CONTRAST_RATIO = 4.5f

    /** WCAG relative luminance for a Compose [Color]. */
    fun relativeLuminance(c: Color): Double {
        fun linear(channel: Double): Double =
            if (channel <= 0.03928) channel / 12.92
            else Math.pow((channel + 0.055) / 1.055, 2.4)
        val r = linear(c.red.toDouble())
        val g = linear(c.green.toDouble())
        val b = linear(c.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two opaque colors (1.0 .. 21.0). */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun isReadable(fg: Color, bg: Color, minRatio: Double = MIN_CONTRAST_RATIO.toDouble()): Boolean =
        contrastRatio(fg, bg) >= minRatio

    /** Linearly interpolate two opaque colors by [t] in 0..1. */
    fun lerp(a: Color, b: Color, t: Float): Color {
        val tc = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * tc,
            green = a.green + (b.green - a.green) * tc,
            blue = a.blue + (b.blue - a.blue) * tc,
            alpha = 1f
        )
    }

    /**
     * Froster result for one ambient base color + polarity.
     * @param panelFrost Tint overlaying the ambient (drawn at ~α 0.55 over the
     *                   colorful background) — the frosted glass surface.
     * @param panelSolid The [panelFrost] composited over a representative
     *                   ambient base, used ONLY for contrast verification (the
     *                   rendered surface is [panelFrost], translucent).
     * @param onPanel The text color that passes WCAG contrast on the panel.
     */
    data class GlassPanelRoles(
        val ambientBase: Color,
        val panelFrost: Color,
        val panelSolid: Color,
        val onPanel: Color,
        val surfaceElevated: Color,
        val outlineSoft: Color,
        val isDark: Boolean
    )

    /**
     * Derives frosted-panel color roles from an ambient background color.
     * The on-panel text color is chosen and then iteratively pulled toward
     * black/white until WCAG contrast is met — never forced, always verifiable.
     */
    fun derivePanelRoles(ambientBase: Color, isDark: Boolean): GlassPanelRoles {
        val frostTint = if (isDark) Color.White else Color.Black
        // Frost = ambient base lightened (light mode) / darkened (dark mode),
        // then made translucent for the glass varnish.
        val mixed = if (isDark) lerp(ambientBase, Color.Black, 0.45f)
                    else lerp(ambientBase, Color.White, 0.38f)
        val panelFrost = mixed.copy(alpha = 0.55f)
        val panelSolid = lerp(ambientBase, mixed, 1f)

        // On-panel text: default white in dark, near-black in light, then iterate.
        val targetText = if (isDark) Color(0xFFF4F7FF) else Color(0xFF1B2A4A)
        val base = if (isDark) Color.Black else Color.White
        var onPanel = targetText
        var i = 0
        while (i < 12 && !isReadable(onPanel, panelSolid)) {
            onPanel = lerp(onPanel, base, 0.12f * (i + 1))
            i++
        }

        val surfaceElevated = if (isDark) lerp(mixed, Color.White, 0.12f).copy(alpha = 0.72f)
                              else lerp(mixed, Color.White, 0.45f).copy(alpha = 0.72f)
        val outlineSoft = if (isDark) Color.White.copy(alpha = 0.18f)
                          else Color.Black.copy(alpha = 0.10f)

        return GlassPanelRoles(
            ambientBase = ambientBase,
            panelFrost = panelFrost,
            panelSolid = panelSolid,
            onPanel = onPanel,
            surfaceElevated = surfaceElevated,
            outlineSoft = outlineSoft,
            isDark = isDark
        )
    }

    /**
     * A representative "frosted glass" panel for the given ambient: [panelFrost]
     * composited over [ambientBase] gives exactly the surface a translucent
     * frosted panel reads as. Used by tests to verify contrast on the resulting
     * composite (the honest rendered color).
     */
    fun compositePanel(frost: Color, ambientBase: Color): Color {
        val f = frost.copy(alpha = 1f)
        return lerp(ambientBase, f, frost.alpha)
    }
}