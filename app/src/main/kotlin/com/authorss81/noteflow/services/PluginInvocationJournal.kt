package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 173 (feature 2): the bounded, persisted, scrubbed invocation journal
 * behind `Settings → Plugins`.
 *
 * The plugin settings dialog already shows each plugin's last-invocation
 * summary; this journal extends that to an honest **last-N invocations** log
 * (timestamp, capability, outcome, reason) so real users can diagnose silent
 * failures. Design rules (all pure JVM / unit-testable):
 *
 * - **Bounded** — at most [MAX_JOURNAL_ENTRIES] entries survive per plugin;
 *   [record] trims the OLDEST first (a fixpoint: a plugin can never grow an
 *   unbounded blob, no matter how frantic its callers).
 * - **Persisted** — the wire string round-trips through [Store]; the Settings
 *   implementation lives in `SettingsManager` (no schema change, and the store
 *   `Delete` wipes it together with every other `plugin_*` key).
 * - **Scrubbed** — [sanitizeDetail] passes every detail (failure reason /
 *   exception name) through [UiFailureTextPolicy.scrubForUi] and strips the
 *   journal's own separators, so a hostile plugin's availability-gate reason can
 *   never forge extra journal lines (the phase-148 precedent) or leak a path.
 *   Payload content is never written by construction — the manager records fixed
 *   labels + exception-class names only.
 *
 * Wire format (one line per entry): `epochMillis\u0001capabilityKey\u0001ok|fail\u0001detail`,
 * lines joined by `\n`. The separators never appear in any legitimate field
 * (capability keys are framework constants; detail is separator-stripped).
 */
object PluginInvocationJournal {

    /** Maximum journal lines kept per plugin (trimming drops the oldest). */
    const val MAX_JOURNAL_ENTRIES = 20

    /** Cap on a single detail field after scrubbing (a fixed, small bound). */
    const val MAX_DETAIL_CHARS = 120

    private const val FIELD_SEPARATOR = "\u0001"
    private const val ENTRY_SEPARATOR = "\n"

    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** One recorded invocation. [detail] is the scrubbed, bounded reason/summary. */
    data class Entry(
        val atMillis: Long,
        val capabilityKey: String,
        val ok: Boolean,
        val detail: String?
    )

    /** Outcome marker used in the wire format of an [Entry]. */
    internal fun outcomeToken(ok: Boolean): String = if (ok) "ok" else "fail"

    /**
     * Scrub + bound a detail string before it may be stored or rendered. Strips
     * paths/URL-credentials via [UiFailureTextPolicy.scrubForUi], removes any
     * journal separator characters (no line/field forgery), and caps the length.
     */
    fun sanitizeDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val scrubbed = UiFailureTextPolicy.scrubForUi(raw)
            .replace(ENTRY_SEPARATOR, " ")
            .replace(FIELD_SEPARATOR, " ")
            .trim()
            .take(MAX_DETAIL_CHARS)
            .trimEnd()
        return scrubbed.takeIf { it.isNotEmpty() }
    }

    /**
     * Append [entry] to [wire] (or start a fresh journal) and trim to the newest
     * [MAX_JOURNAL_ENTRIES]. Returns a stable wire string (never null/empty-free:
     * a single entry always yields a non-blank wire). Malformed prior lines are
     * dropped rather than propagated.
     */
    fun record(wire: String?, entry: Entry): String {
        val detail = sanitizeDetail(entry.detail)
        // Review-fix (phase-173): the capability key is ALSO sanitized on write
        // (separator-stripped + bounded + non-blank) so the wire-fidelity rule —
        // the separators never appear in any field — holds even if a caller
        // passes a non-framework key, not just because the manager happens to
        // use framework constants.
        val capabilityKey = entry.capabilityKey
            .replace(FIELD_SEPARATOR, " ")
            .replace(ENTRY_SEPARATOR, " ")
            .trim()
            .take(64)
            .ifBlank { "unknown" }
        val line = buildString {
            append(entry.atMillis)
            append(FIELD_SEPARATOR)
            append(capabilityKey)
            append(FIELD_SEPARATOR)
            append(outcomeToken(entry.ok))
            append(FIELD_SEPARATOR)
            append(detail.orEmpty())
        }
        val lines = rawLines(wire).toMutableList()
        lines.add(line)
        while (lines.size > MAX_JOURNAL_ENTRIES) lines.removeAt(0)
        return lines.joinToString(ENTRY_SEPARATOR)
    }

    /** Parse a journal wire back into its entries, oldest first. Malformed lines
     *  are skipped (a journal with a torn tail still renders its valid prefix). */
    fun parse(wire: String?): List<Entry> =
        rawLines(wire).mapNotNull { parseLine(it) }

    /** The newest [MAX_JOURNAL_ENTRIES] entries, newest first (journal view order). */
    fun newestFirst(wire: String?): List<Entry> =
        parse(wire).takeLast(MAX_JOURNAL_ENTRIES).reversed()

    /**
     * One human-readable journal line: `MM-dd HH:mm:ss  ·  Capability  ·  OK`
     * or `…  ·  Failed — <scrubbed reason>`. Bounded: the detail is already
     * capped so every line fits a mobile row.
     */
    fun renderLine(entry: Entry): String {
        val capability = PluginCapability.byKey(entry.capabilityKey)?.label ?: entry.capabilityKey
        val stamp = Instant.ofEpochMilli(entry.atMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        return if (entry.ok) {
            "$stamp  ·  $capability  ·  OK"
        } else {
            val detail = sanitizeDetail(entry.detail)
            "$stamp  ·  $capability  ·  Failed" + (detail?.let { " — $it" } ?: "")
        }
    }

    /** Ready-to-render bound journal lines, newest first. Always bounded. */
    fun journalLines(wire: String?): List<String> = newestFirst(wire).map { renderLine(it) }

    // ---- persistence abstraction -------------------------------------------

    /**
     * Storage for one raw journal wire per plugin. The production store is
     * SettingsManager-backed (`plugin_invocation_journal_<id>`, wiped by store
     * delete); tests use an in-memory map. [read] returns null for a fresh
     * plugin (no journal yet).
     */
    interface Store {
        fun read(pluginId: String): String?
        fun write(pluginId: String, wire: String?)
    }

    /** Default: journaling off (every existing PluginManager caller keeps working). */
    object NoOpStore : Store {
        override fun read(pluginId: String): String? = null
        override fun write(pluginId: String, wire: String?) {}
    }

    // ---- wire helpers -------------------------------------------------------

    private fun rawLines(wire: String?): List<String> =
        wire.orEmpty().split(ENTRY_SEPARATOR).filter { it.isNotBlank() }

    private fun parseLine(raw: String): Entry? {
        val fields = raw.split(FIELD_SEPARATOR)
        if (fields.size < 3) return null
        val atMillis = fields[0].toLongOrNull() ?: return null
        val ok = when (fields[2]) {
            "ok" -> true
            "fail" -> false
            else -> return null
        }
        val detail = fields.getOrNull(3).takeUnless { it.isNullOrEmpty() }?.let { sanitizeDetail(it) }
        val capabilityKey = fields[1].ifBlank { "unknown" }.take(64)
        return Entry(atMillis, capabilityKey, ok, detail)
    }
}