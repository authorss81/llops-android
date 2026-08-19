package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource

/**
 * Phase 173 (feature 3): the compact per-plugin metadata line of the Plugin
 * Store rows — "what this plugin can do / how it ships" — built honestly and
 * deterministically. Pure JVM.
 *
 * Every row now states, in ONE bounded line:
 * - the capabilities the plugin DECLARES (fixed framework labels, bounded to
 *   [MAX_CAPABILITIES_IN_LINE] with a "+N more" fold) — never a claim about a
 *   plugin actually running, only what it offers;
 * - the shipping bucket ([PluginEntrySource.BUNDLED] = compiled into the base
 *   APK vs [PluginEntrySource.REMOTE] = downloadable, signature-verified);
 * - for downloadable plugins, the expected download size when known, or the
 *   honest "needs the hosted channel" note (the Phase-24 update model picks the
 *   plugin's version stream from `updateChannel`; a remote plugin with no
 *   declared model size still needs that channel to be fetched/verified).
 *
 * Edge cases pinned by [PluginStoreRowPolicyTest]: long capability lists
 * (folded), downloadable entries (size / hosted-channel note), a remote entry
 * with unknown size (hosted-channel note), and the defensive empty-capability
 * set (never a crash, never a fabricated list).
 */
object PluginStoreRowPolicy {

    /** Max capability labels folded into the metadata line before "+N more". */
    const val MAX_CAPABILITIES_IN_LINE = 3

    /**
     * The bounded, sorted list of capability labels a plugin declares, e.g.
     * `"File Transfer, OCR, +1 more"` or `"none declared"`. Order is
     * deterministic: exclusive (single-winner) capabilities first, then the
     * rest — alphabetical within each group.
     */
    fun capabilitiesLabel(capabilities: Set<PluginCapability>): String {
        if (capabilities.isEmpty()) return "none declared"
        val sorted = capabilities.distinct()
            .sortedWith(compareByDescending<PluginCapability> { it.exclusive }.thenBy { it.label })
            .map { it.label }
        val shown = sorted.take(MAX_CAPABILITIES_IN_LINE).joinToString(", ")
        return if (sorted.size > MAX_CAPABILITIES_IN_LINE) {
            "$shown, +${sorted.size - MAX_CAPABILITIES_IN_LINE} more"
        } else {
            shown
        }
    }

    /** Shipping bucket label: compile-time-in-base vs downloadable. */
    fun bucketLabel(source: PluginEntrySource): String = when (source) {
        PluginEntrySource.BUNDLED -> "Bundled (in app)"
        PluginEntrySource.REMOTE -> "Downloadable (verified)"
    }

    /**
     * Download honesty for a remote (downloadable) plugin: the expected download
     * size when declared, otherwise the "needs the hosted channel" note. Null for
     * bundled plugins (nothing is ever downloaded).
     */
    fun downloadNote(entry: PluginEntry): String? {
        if (entry.source != PluginEntrySource.REMOTE) return null
        val sizeMb = entry.installSizeBytes?.takeIf { it > 0L }?.div(1024L * 1024L)
        return if (sizeMb != null) "~$sizeMb MB download" else "needs the hosted channel"
    }

    /**
     * The one-line store metadata footer: `Serves: <caps> · <bucket>` plus, for
     * downloadable plugins, the size / hosted-channel note. Bounded by the
     * column widths above — a row never overflows the card.
     */
    fun metadataLine(entry: PluginEntry): String {
        val bucket = bucketLabel(entry.source)
        return when (val note = downloadNote(entry)) {
            null -> "Serves: ${capabilitiesLabel(entry.capabilities)} · $bucket"
            else -> "Serves: ${capabilitiesLabel(entry.capabilities)} · $bucket · $note"
        }
    }
}