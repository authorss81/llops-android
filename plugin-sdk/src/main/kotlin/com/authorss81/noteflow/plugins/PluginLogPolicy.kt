package com.authorss81.noteflow.plugins

/**
 * B2-LOG-04 (phase-93): the decision table for what may reach the plugin logcat
 * sink — the "log only fixed tokens" rule (same family as the app's
 * `services.FailureLogPolicy` for import/export) applied to the plugin pipeline.
 *
 * Failure messages in the download/install/update path are attacker-influenceable:
 * a hostile manifest leg (or a re-pointed catalog entry) can embed a `downloadUrl`,
 * a plugin id, or arbitrary text inside a failure `reason`. Whether that text is
 * truncated with `substringBefore('.')` or echoed verbatim, routing it into the log
 * line both (1) leaks attacker-chosen data into logcat (the B2-LOG-04 exfil shape:
 * a URL can carry vault/note identifiers) and (2) lets a CR/LF inside the field
 * FORGE a new logcat line.
 *
 * The rule is therefore enforced in two mechanical, unit-tested places:
 *
 *  - **Parse-time rejection.** `PluginEntry.validationErrors()` and
 *    `HostedPluginVersion.validationErrors()` call [lineBreakError] for `id` /
 *    `name` / `downloadUrl` fields that carry CR/LF ([hasLineBreak]), so a value
 *    that could forge a logcat line never even enters the pipeline (manifests,
 *    persisted catalog blobs and update offers are refused whole).
 *  - **Sink-side hygiene.** `AndroidPluginLogger` routes every line through
 *    [safeLine] so CR/LF and URL-shaped tokens can never reach logcat even if a
 *    caller slips (defense in depth).
 *
 * Pure JVM decision table — `indexOf('/')`/`replace`/`Regex` only, no platform
 * calls; every rule is pinned by `B2Log04PluginLogScrubbingTest`.
 */
object PluginLogPolicy {

    /** True when [value] carries a CR or LF — a logcat line-forgery vehicle. */
    fun hasLineBreak(value: String): Boolean =
        value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0

    /** Fixed user-facing error for a CR/LF-carrying [fieldName]. Never embeds the
     *  offending value (B2-LOG-04: echoes only fixed text, not the hostile field). */
    fun lineBreakError(fieldName: String): String =
        "$fieldName must not contain line breaks"

    /** Remove CR/LF from [value]. The line-forgery vehicle is stripped, but
     *  URL-shaped tokens must ALSO be handled by [safeLine]. */
    fun stripLineBreaks(value: String): String =
        value.replace("\r", "").replace("\n", "")

    /**
     * The ONE sanitizer for a whole logcat line. Guarantees:
     *  1. CR/LF are removed (`\r`, `\n`, or any mixed sequence) so a hostile
     *     field never forges a new logcat line.
     *  2. URL-shaped tokens (`http://…`, `https://…`, run to the next space) are
     *     replaced with `<url>` so a hostile `downloadUrl` — or a URL an
     *     attacker embedded in a failure message — is never echoed into logcat.
     */
    fun safeLine(text: String): String =
        stripLineBreaks(text).replace(URL_TOKEN, "<url>")

    private val URL_TOKEN: Regex = Regex("""https?://\S+""")
}