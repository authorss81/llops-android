package com.authorss81.noteflow.plugins.runtime

/**
 * Result of a capability-facade call. The facade NEVER throws at plugin code
 * and NEVER leaks internals — every call resolves to a typed outcome:
 *
 * - [Granted] — the call was permitted (and, for [PluginContext.readSelection]
 *   etc., carried back the value).
 * - [Denied] — the plugin asked for something its capability whitelist does not
 *   allow. Deny-by-default: every call is denied until the runtime grants it.
 * - [Failed] — the call was permitted but could not be completed; [message] is
 *   user-facing.
 */
sealed class FacadeResult<out T> {
    data class Granted<T>(val value: T) : FacadeResult<T>()
    data class Denied(val reason: String) : FacadeResult<Nothing>()
    data class Failed(val message: String) : FacadeResult<Nothing>()
}

/**
 * THE capability facade — the ONLY surface downloadable plugin code may call
 * (Phase 22, see `docs/plugin-architecture.md` § Capability facade contract).
 *
 * A loaded plugin receives a [PluginContext] scoped to its own [PluginEntry]
 * and may ONLY interact with the app through these narrow calls. It NEVER
 * receives direct `Context`, DB, `NoteRepository`, AndroidKeyStore,
 * `EncryptionService` or decrypted-content handles — those stay in the host.
 *
 * The default skeleton ([DefaultPluginContext]) denies EVERYTHING; Phase 23
 * wires a privilege-aware implementation that grants calls per the capability
 * whitelist matrix, and Phase 25/26 plugins use the same facade.
 */
interface PluginContext {
    /** The id of the plugin this context belongs to (its only identity). */
    val pluginId: String

    /** Insert [text] into the currently open note at the cursor. */
    fun insertText(text: String): FacadeResult<Unit>

    /** Show a typed result (title + body) to the user in the app's UI. */
    fun showResult(title: String, body: String): FacadeResult<Unit>

    /**
     * Perform an HTTP(S) GET and return the body. [httpsOnly] == true forces
     * TLS; a non-HTTPS URL is refused (never silently downgraded).
     */
    fun httpGet(url: String, httpsOnly: Boolean): FacadeResult<String>

    /** Read the current text selection in the open note. */
    fun readSelection(): FacadeResult<String>

    /**
     * Ask the host to download a model/asset of [sizeBytes] into app-private
     * files, with the user's explicit consent (the host shows the dialog).
     */
    fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit>
}

/**
 * Deny-by-default [PluginContext] (Phase 22 skeleton).
 *
 * Every call returns [FacadeResult.Denied] with an honest reason: no
 * downloadable plugin is permitted anything until a later phase wires the
 * capability whitelist. This is a real, correct boundary — NOT a fake "works"
 * implementation. Until Phase 23 grants calls, downloadable plugin code can
 * build UI only through the app's own routes.
 */
class DefaultPluginContext(
    override val pluginId: String
) : PluginContext {

    override fun insertText(text: String): FacadeResult<Unit> = deny("insertText")

    override fun showResult(title: String, body: String): FacadeResult<Unit> = deny("showResult")

    override fun httpGet(url: String, httpsOnly: Boolean): FacadeResult<String> = deny("httpGet")

    override fun readSelection(): FacadeResult<String> = deny("readSelection")

    override fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit> =
        deny("requestModelDownload")

    private fun deny(call: String): FacadeResult<Nothing> = FacadeResult.Denied(
        "capability-facade call '$call' is not granted to plugin '$pluginId' " +
            "(deny-by-default — the whitelist is wired in Phase 23)."
    )
}

/**
 * Creates the [PluginContext] handed to a plugin at load time. Phase 22 ships
 * the deny-by-default factory; Phase 23 swaps in a capability-aware one without
 * changing the rest of the runtime.
 */
fun interface PluginContextFactory {
    fun contextFor(entry: PluginEntry): PluginContext

    companion object {
        /** The Phase-22 default: everything denied. */
        val DEFAULT: PluginContextFactory = PluginContextFactory { entry ->
            DefaultPluginContext(entry.id)
        }
    }
}
