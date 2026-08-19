package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.PluginUpdateInfo

/**
 * Phase-157 (plugin update UX): the pure-JVM decision table that turns an
 * offered [PluginUpdateInfo] into the approval-dialog copy — and a whole
 * "Update all" batch into its sequential, per-download approval plan.
 *
 * Safety contract (R2-b2b3-LOG-03 / phase-148 precedent): `updateNotes` is
 * ATTACKER-INFLUENCEABLE — it comes from the hosted version manifest and is
 * shown verbatim in the approval dialog today. Nothing from it reaches the UI
 * raw: [notesForDisplay] sanitizes control characters, bounds the length and
 * then runs the result through [UiFailureTextPolicy.scrubForUi] (strips URL
 * userinfo/query tokens, collapses `scheme://host/path`, redacts absolute
 * filesystem paths), so a hostile note can never inject logcat-style
 * line-forgery or carry credential/path leaks into the dialog.
 *
 * Names shown in batch copy go through [nameOf] — the catalog's plugin name —
 * which is compile-time or manifest-VALIDATED (never free text), and the
 * presented batch copies are also Fed through [UiFailureTextPolicy.scrubForUi].
 *
 * The "Update all" flow stays fail-closed: this policy only orders + formats.
 * Approval is ALWAYS per download (the controller refuses any update whose
 * [PluginUpdateInfo.userApproved] flag is false), and the pinned release-table
 * gate ([CompileTimePluginPinStore]) already decided which offers exist at all.
 */
object PluginUpdatePromptPolicy {

    /** Cap on release-notes text shown in a dialog (long notes get truncated). */
    const val MAX_NOTES_CHARS = 240

    /** Cap on how many plugin names a batch summary may list before folding. */
    const val MAX_BATCH_NAMES = 3

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * The scrubbed, bounded release-notes text for the update dialog, or null
     * when the offer carries no (readable) notes. Controls collapse to a single
     * space so a multi-line / CR-LF-forged note cannot forge dialog lines, then
     * the length cap + [UiFailureTextPolicy.scrubForUi] run as the final gate.
     */
    fun notesForDisplay(notes: String?): String? {
        if (notes.isNullOrBlank()) return null
        val collapsed = WHITESPACE_REGEX.replace(notes.trim(), " ").trim()
        if (collapsed.isEmpty()) return null
        val bounded = if (collapsed.length <= MAX_NOTES_CHARS) collapsed
        else collapsed.take(MAX_NOTES_CHARS).trimEnd() + "…"
        return UiFailureTextPolicy.scrubForUi(bounded)
    }

    /** Compact version delta, e.g. "v1.2.0 → v1.3.0". */
    fun versionDeltaText(info: PluginUpdateInfo): String =
        "v${info.currentVersion} → v${info.newVersion}"

    /** One row of the "Update all" plan (formatted + scrubbed, pre-approval). */
    data class UpdateAllItem(
        val pluginId: String,
        val name: String,
        val versionDeltaText: String,
        val notes: String?
    )

    /**
     * The ordered, deduplicated "Update all" plan for [updates]. Sorted by
     * plugin id so the sequential approval flow is deterministic and a re-check
     * mid-batch cannot reorder or silently duplicate an offer. [nameOf] resolves
     * the catalog name per plugin (falls back to the plugin id).
     */
    fun updateAllPlan(
        updates: Collection<PluginUpdateInfo>,
        nameOf: (String) -> String
    ): List<UpdateAllItem> = updates
        .distinctBy { it.pluginId }
        .sortedBy { it.pluginId }
        .map { info ->
            val name = nameOf(info.pluginId).takeIf { it.isNotBlank() } ?: info.pluginId
            UpdateAllItem(
                pluginId = info.pluginId,
                name = UiFailureTextPolicy.scrubForUi(name),
                versionDeltaText = versionDeltaText(info),
                notes = notesForDisplay(info.updateNotes)
            )
        }

    /**
     * Short general-message summary after a batch check, e.g.
     * "3 update(s) ready — review and approve each one: A, B, C". The name list
     * is bounded to [MAX_BATCH_NAMES] with the count folded in. Null when there
     * is nothing to summarize (caller decides what to show instead).
     */
    fun batchSummary(
        updates: Collection<PluginUpdateInfo>,
        nameOf: (String) -> String
    ): String? {
        val plan = updateAllPlan(updates, nameOf)
        if (plan.isEmpty()) return null
        val names = plan.take(MAX_BATCH_NAMES).joinToString(", ") { it.name }
        val extra = if (plan.size > MAX_BATCH_NAMES) {
            " +${plan.size - MAX_BATCH_NAMES} more"
        } else {
            ""
        }
        val count = if (plan.size == 1) "1 update" else "${plan.size} updates"
        return "$count ready — review and approve each one: $names$extra"
    }
}