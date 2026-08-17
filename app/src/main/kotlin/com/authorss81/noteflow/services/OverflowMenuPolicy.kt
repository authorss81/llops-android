package com.authorss81.noteflow.services

/**
 * Phase-120 (UI/UX): overflow-menu sizing decision table — pure JVM.
 *
 * Every three-dot (⋮) overflow [androidx.compose.material3.DropdownMenu] in the
 * app is capped to a fraction of the CURRENT screen height so the menu can never
 * be taller than the on-screen space (small screens, landscape, large fonts),
 * which is exactly the failure mode where the bottom entries became unreachable
 * before this phase. On roomy portrait screens the derived cap is larger than
 * Material 3's fixed 288.dp internal cap, so the effective cap stays 288.dp and
 * the menu renders exactly as before — the change only bites where it must.
 *
 * All functions are plain math on dp values; the composable wiring in
 * `ui/components/OverflowMenuSupport.kt` is the only Android-facing caller.
 */
object OverflowMenuPolicy {

    /** The most of the on-screen height an overflow menu may ever claim. */
    const val MAX_MENU_FRACTION_OF_SCREEN = 0.60f

    /**
     * Absolute ceiling in dp. A tablet portrait must not turn an overflow menu
     * into a near-full-screen sheet; roomy screens keep Material 3's default.
     */
    const val ABSOLUTE_MAX_MENU_HEIGHT_DP = 560f

    /** Floor in dp — always keep roughly two standard rows reachable. */
    const val MIN_MENU_HEIGHT_DP = 120f

    /** Material 3 standard single-line menu row height (dp) for count math. */
    const val DEFAULT_MENU_ROW_HEIGHT_DP = 48f

    /** Default Material 3 internal dropdown cap (dp), kept as the upper bound on big screens. */
    const val MATERIAL_DROPDOWN_MAX_HEIGHT_DP = 288f

    /**
     * Max menu height in dp for the given screen height (dp).
     * Degenerate (non-positive) inputs fall back to the floor.
     */
    fun maxMenuHeightDp(screenHeightDp: Int): Float {
        if (screenHeightDp <= 0) return MIN_MENU_HEIGHT_DP
        val fromFraction = screenHeightDp * MAX_MENU_FRACTION_OF_SCREEN
        return fromFraction.coerceIn(MIN_MENU_HEIGHT_DP, ABSOLUTE_MAX_MENU_HEIGHT_DP)
    }

    /** True when a menu's intrinsic content height exceeds the allowed cap. */
    fun contentOverflows(contentHeightDp: Float, maxHeightDp: Float): Boolean =
        contentHeightDp > maxHeightDp

    /** Estimated content height (dp) of `itemCount` uniform rows. */
    fun estimatedContentHeightDp(
        itemCount: Int,
        rowHeightDp: Float = DEFAULT_MENU_ROW_HEIGHT_DP
    ): Float = (itemCount.coerceAtLeast(0) * rowHeightDp).coerceAtLeast(0f)

    /**
     * How many uniform rows are visible inside a capped menu height.
     * At least 1 and never more than [itemCount]; a non-positive
     * [rowHeightDp] (unknown row geometry) reports the full count.
     */
    fun visibleItemCount(
        itemCount: Int,
        maxHeightDp: Float,
        rowHeightDp: Float = DEFAULT_MENU_ROW_HEIGHT_DP
    ): Int {
        if (itemCount <= 0) return 0
        if (rowHeightDp <= 0f) return itemCount
        val visible = (maxHeightDp / rowHeightDp).toInt().coerceAtLeast(1)
        return visible.coerceAtMost(itemCount)
    }
}
