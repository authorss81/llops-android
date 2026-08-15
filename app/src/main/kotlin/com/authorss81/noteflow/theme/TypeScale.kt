package com.authorss81.noteflow.theme

/**
 * Phase 34: the Material 3 type scale expressed as plain data so the scale
 * roles are (a) consistent, (b) on the 4 dp baseline grid, and (c) pure-JVM
 * testable without Compose textures hitting resources.
 *
 * A [TypeScaleRole] groups the Material 3 styles by intent:
 * - DISPLAY   — large page-opening statements (editorial serif when the theme
 *               asks for it).
 * - HEADLINE  — strong section openers.
 * - TITLE     — bars, cards, list-item titles (UI chrome).
 * - BODY      — reading text (switches to the serif in Markdown reading mode).
 * - LABEL     — buttons, chips, captions, metadata.
 *
 * Font sizes and line-heights follow the Material spec; every line-height is on
 * the 4 dp baseline grid (lineHeightSp % 4f == 0f) so rows align across roles.
 */
enum class TypeScaleRole { DISPLAY, HEADLINE, TITLE, BODY, LABEL }

data class TypeScaleSpec(
    val styleName: String,
    val role: TypeScaleRole,
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val fontWeight: Int,
    val letterSpacingSp: Float = 0f
) {
    val isOnBaselineGrid: Boolean
        get() = lineHeightSp % 4f == 0f && fontSizeSp > 0f
}

object TypeScale {

    /** Material 3 type-style members in each role, in descending size. */
    private val roleMembers: Map<TypeScaleRole, List<String>> = mapOf(
        TypeScaleRole.DISPLAY to listOf("displayLarge", "displayMedium", "displaySmall"),
        TypeScaleRole.HEADLINE to listOf("headlineLarge", "headlineMedium", "headlineSmall"),
        TypeScaleRole.TITLE to listOf("titleLarge", "titleMedium", "titleSmall"),
        TypeScaleRole.BODY to listOf("bodyLarge", "bodyMedium", "bodySmall"),
        TypeScaleRole.LABEL to listOf("labelLarge", "labelMedium", "labelSmall")
    )

    /** Every Material 3 type style, with intent and Material-spec metrics. */
    val scales: List<TypeScaleSpec> = listOf(
        TypeScaleSpec("displayLarge", TypeScaleRole.DISPLAY, 57f, 64f, 400, letterSpacingSp = -0.25f),
        TypeScaleSpec("displayMedium", TypeScaleRole.DISPLAY, 45f, 52f, 400),
        TypeScaleSpec("displaySmall", TypeScaleRole.DISPLAY, 36f, 44f, 400),
        TypeScaleSpec("headlineLarge", TypeScaleRole.HEADLINE, 32f, 40f, 400),
        TypeScaleSpec("headlineMedium", TypeScaleRole.HEADLINE, 28f, 36f, 400),
        TypeScaleSpec("headlineSmall", TypeScaleRole.HEADLINE, 24f, 32f, 400),
        TypeScaleSpec("titleLarge", TypeScaleRole.TITLE, 22f, 28f, 500),
        TypeScaleSpec("titleMedium", TypeScaleRole.TITLE, 16f, 24f, 500),
        TypeScaleSpec("titleSmall", TypeScaleRole.TITLE, 14f, 20f, 500),
        TypeScaleSpec("bodyLarge", TypeScaleRole.BODY, 16f, 24f, 400),
        TypeScaleSpec("bodyMedium", TypeScaleRole.BODY, 14f, 20f, 400),
        TypeScaleSpec("bodySmall", TypeScaleRole.BODY, 12f, 16f, 400),
        TypeScaleSpec("labelLarge", TypeScaleRole.LABEL, 14f, 20f, 500),
        TypeScaleSpec("labelMedium", TypeScaleRole.LABEL, 12f, 16f, 500),
        TypeScaleSpec("labelSmall", TypeScaleRole.LABEL, 11f, 16f, 500)
    )

    private val byName: Map<String, TypeScaleSpec> = scales.associateBy { it.styleName }

    /** The spec for a Material 3 style name, or null if not part of the scale. */
    fun specFor(styleName: String): TypeScaleSpec? = byName[styleName]

    /** The intent role of a Material 3 style name, or null if unknown. */
    fun roleFor(styleName: String): TypeScaleRole? = byName[styleName]?.role

    /** Every style name that belongs to [role], in descending size order. */
    fun styleNamesFor(role: TypeScaleRole): List<String> = roleMembers[role].orEmpty()

    /** True when the scale covers every Material 3 role style. */
    fun isComplete(): Boolean =
        roleMembers.values.flatten().all { it in byName } && byName.size == 15
}