package com.authorss81.noteflow.services

/**
 * B2-DOS-06 (phase-82): the PSD export layer-budget decision table. Pure JVM so
 * the cap arithmetic and the non-alarming notice wording are unit-testable
 * without Android (no Bitmap / DataOutputStream under test here).
 *
 * The vulnerability (see docs/security-report.md B2-DOS-06): layered PSD export
 * materialized one full-page 1080x1528 ARGB_8888 Bitmap PER layer (~6.6 MB each)
 * plus per-layer uncompressed channel buffers (~6.6 MB each) all held in heap at
 * once, with an UNBOUNDED layer count — a 25-layer note peaked near ~350 MB and
 * OOM'd 1-2 GB devices on every export (the Layers panel adds layers freely and
 * a restored vault can carry arbitrary `layers` rows).
 *
 * This policy is the single source of the export layer budget:
 *  1. [MAX_EXPORT_LAYER_COUNT] — how many DATA layers (the layers the user sees
 *     in the Layers panel) a PSD export may contain. The background sheet is
 *     not a user-managed layer and is always included.
 *  2. [capLayerCount] / [omittedLayerCount] / [isLayerCountCapped] — the
 *     capping arithmetic used by `ImportExportService.exportPageToPsd` so it
 *     never CREATES the extra per-layer bitmaps in the first place (the primary
 *     memory bound).
 *  3. [noticeMessage] — the one-time non-alarming wording shown when layers were
 *     omitted (AGENTS.md hardware reality: no silent degradation).
 */
object PsdExportPolicy {

    /** Maximum number of drawing layers a PSD export may include. */
    const val MAX_EXPORT_LAYER_COUNT = 16

    /** How many channels each PSD layer record declares (A, R, G, B). */
    const val CHANNELS_PER_LAYER = 4

    /** Clamp a raw layer count to the export budget (never negative). */
    fun capLayerCount(layerCount: Int): Int =
        layerCount.coerceAtLeast(0).coerceAtMost(MAX_EXPORT_LAYER_COUNT)

    /** True when [layerCount] exceeds the budget and some layers would be dropped. */
    fun isLayerCountCapped(layerCount: Int): Boolean =
        layerCount > MAX_EXPORT_LAYER_COUNT

    /** How many layers a cap drops for a vault of [layerCount] layers (0 if none). */
    fun omittedLayerCount(layerCount: Int): Int =
        if (layerCount > MAX_EXPORT_LAYER_COUNT) layerCount - MAX_EXPORT_LAYER_COUNT else 0

    /**
     * Non-alarming, one-time notice shown after a PSD export. When layers were
     * omitted the message states how many were exported / dropped so the user
     * can re-layer the note if the cap bit them; when nothing was omitted it
     * says so plainly (never an error tone).
     */
    fun noticeMessage(exportedLayerCount: Int, omittedLayerCount: Int): String {
        val exported = exportedLayerCount.coerceAtLeast(0)
        val omitted = omittedLayerCount.coerceAtLeast(0)
        return if (omitted > 0) {
            val total = exported + omitted
            "PSD export included the bottom $exported of $total layers — " +
                "$omitted layer${if (omitted == 1) "" else "s"} omitted (max $MAX_EXPORT_LAYER_COUNT)."
        } else {
            "PSD export included all $exported layers."
        }
    }
}
