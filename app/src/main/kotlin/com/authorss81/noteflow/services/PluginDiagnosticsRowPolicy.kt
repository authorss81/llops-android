package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginInvocationRecord
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginStateInfo

/**
 * Phase-157 (plugin diagnostics): the pure-JVM row builder behind
 * `Settings → Plugins`. Closes the "which plugin failed why" opacity with one
 * compact, honest footer per plugin:
 *
 * - **Served capabilities** — the fixed [PluginCapability.label]s the plugin
 *   declares (capability labels are framework constants, so this text is always
 *   safe by construction).
 * - **Opt-in state** — on/off (mirrors the toggle).
 * - **Lifecycle state** — the same fixed labels as the store/settings UI,
 *   centralized here so one tested table drives both.
 * - **Failure reason** — `PluginStateInfo.reason` (which for a hostile plugin's
 *   `availability()` gate can carry arbitrary text) and the last-invocation
 *   summary are the phase-148 risk surface: every one of them passes through
 *   [UiFailureTextPolicy.scrubForUi] before it may reach the row, so raw paths /
 *   URL credential tokens can never be echoed (R2-b2b3-LOG-03 precedent).
 *
 * Pure JVM; all output strings are bounded and fixed-labelled.
 */
object PluginDiagnosticsRowPolicy {

    /** Max capability labels shown before the "+N more" fold. */
    const val MAX_CAPABILITIES_SHOWN = 4

    private val lifecycleLabels: Map<PluginLifecycleState, String> = mapOf(
        PluginLifecycleState.REGISTERED to "Available — off",
        PluginLifecycleState.ENABLED to "Enabled — verifying",
        PluginLifecycleState.AVAILABLE to "Active",
        PluginLifecycleState.UNAVAILABLE to "Unavailable",
        PluginLifecycleState.DISABLED to "Disabled",
        PluginLifecycleState.REJECTED to "Rejected"
    )

    /** The capability labels this plugin serves, bounded, e.g. "OCR · Text Tools". */
    fun servedCapabilitiesLabel(capabilities: Set<PluginCapability>): String {
        if (capabilities.isEmpty()) return "No capabilities declared"
        val sorted = capabilities.map { it.label }.distinct().sorted()
        val shown = sorted.take(MAX_CAPABILITIES_SHOWN).joinToString(" · ")
        return if (sorted.size > MAX_CAPABILITIES_SHOWN) {
            "$shown · +${sorted.size - MAX_CAPABILITIES_SHOWN} more"
        } else {
            shown
        }
    }

    /** Compact opt-in label matching the toggle. */
    fun optInLabel(enabled: Boolean): String = if (enabled) "Opt-in: on" else "Opt-in: off"

    /** Fixed lifecycle label for [state] (null → "Unknown"). */
    fun lifecycleLabel(state: PluginLifecycleState?): String =
        state?.let { lifecycleLabels[it] } ?: "Unknown"

    /**
     * Scrub-before-surfacing wrapper for a single failure text, or null when
     * blank. Returns exactly the scrubbed text (no prefix) so the caller can
     * style it — the risk gate is the [UiFailureTextPolicy.scrubForUi] pass.
     */
    fun scrub(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val scrubbed = UiFailureTextPolicy.scrubForUi(text).trim()
        return scrubbed.takeIf { it.isNotEmpty() }
    }

    /** "Reason: …" line from [PluginStateInfo.reason], scrubbed, or null. */
    fun reasonLine(state: PluginStateInfo?): String? =
        scrub(state?.reason)?.let { "Reason: $it" }

    /**
     * Last-invocation line from the diagnostics record. A failure summary is
     * scrubbed (the manager already records fixed labels + exception class
     * names, but defense-in-depth stays: never a raw path). Null when the plugin
     * was never invoked.
     */
    fun lastInvocationLine(last: PluginInvocationRecord?): String? = when {
        last == null -> null
        last.ok -> "Last check: OK"
        else -> scrub(last.summary)?.let { "Last check: failed — $it" } ?: "Last check: failed"
    }

    /**
     * The compact diagnostic footer for a Settings→Plugins row. Composed of the
     * serve/opt-in/lifecycle parts joined with " · ", followed (when present)
     * by the scrubbed reason line. Bounded and single-purpose — callers that
     * want finer-grained lines should use [reasonLine]/[lastInvocationLine].
     */
    fun footer(
        capabilities: Set<PluginCapability>,
        enabled: Boolean,
        state: PluginLifecycleState?
    ): String {
        val served = servedCapabilitiesLabel(capabilities)
        val optIn = optInLabel(enabled)
        val lifecycle = lifecycleLabel(state)
        return "$served · $optIn · $lifecycle"
    }
}