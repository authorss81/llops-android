package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginCapability

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
 * The host-side implementation of the capability facade (Phase 23).
 *
 * A [PluginContext] NEVER touches the database, keystore, [EncryptionService]
 * or decrypted content itself — it only decides GRANT vs DENY and delegates the
 * actual operation to this host, which lives in app code. The host is the only
 * place that can reach the note editor / network / model-download flow, and the
 * plugin never holds a reference to it.
 */
interface FacadeHost {
    fun insertText(text: String): FacadeResult<Unit>
    fun showResult(title: String, body: String): FacadeResult<Unit>
    fun httpGet(url: String): FacadeResult<String>
    fun readSelection(): FacadeResult<String>
    fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit>
}

/** A single facade call, used as the whitelist unit (see [FacadeWhitelist]). */
enum class FacadeCall { INSERT_TEXT, SHOW_RESULT, HTTP_GET_HTTPS, READ_SELECTION, REQUEST_MODEL_DOWNLOAD }

/**
 * The capability whitelist matrix (Phase 23 — fills in the Phase-22 deny-by-
 * default skeleton; see `docs/plugin-architecture.md` § Capability whitelist).
 *
 * A plugin is granted EXACTLY the calls its capability needs — never "because it
 * is trusted". The rule is a pure function of the plugin's declared
 * [PluginCapability] set, so it is unit-testable.
 */
object FacadeWhitelist {

    /** capability → the facade calls it is granted. */
    private val grants: Map<PluginCapability, Set<FacadeCall>> = mapOf(
        PluginCapability.TextTransform to setOf(
            FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION
        ),
        PluginCapability.OCR to setOf(
            FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.HTTP_GET_HTTPS,
            FacadeCall.READ_SELECTION, FacadeCall.REQUEST_MODEL_DOWNLOAD
        ),
        PluginCapability.WebSearch to setOf(
            FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.HTTP_GET_HTTPS,
            FacadeCall.READ_SELECTION
        ),
        PluginCapability.WebCapture to setOf(
            FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.HTTP_GET_HTTPS,
            FacadeCall.READ_SELECTION
        ),
        PluginCapability.Assistant to setOf(
            FacadeCall.INSERT_TEXT, FacadeCall.SHOW_RESULT, FacadeCall.HTTP_GET_HTTPS,
            FacadeCall.READ_SELECTION, FacadeCall.REQUEST_MODEL_DOWNLOAD
        ),
        PluginCapability.Export to setOf(
            FacadeCall.SHOW_RESULT, FacadeCall.READ_SELECTION
        )
    )

    /**
     * The union of grants for [capabilities]. Unknown/unlisted capabilities
     * contribute nothing — deny-by-default for anything not on the matrix.
     */
    fun grantedFor(capabilities: Set<PluginCapability>): Set<FacadeCall> =
        capabilities.fold(emptySet()) { acc, capability ->
            acc + (grants[capability].orEmpty())
        }
}

/**
 * Capability-aware [PluginContext] (Phase 23).
 *
 * Grants exactly the facade calls the plugin's capability whitelist allows and
 * delegates the granted operation to [host]. Everything else resolves to
 * [FacadeResult.Denied] with an honest reason — deny-by-default is preserved
 * for any capability/call not on the matrix.
 *
 * TLS rule: `httpGet` is only ever granted as the HTTPS variant. A plugin whose
 * whitelist includes [FacadeCall.HTTP_GET_HTTPS] may still not request a
 * cleartext URL — `httpsOnly == false` is refused (never a downgrade).
 */
class CapabilityAwarePluginContext(
    override val pluginId: String,
    private val capabilities: Set<PluginCapability>,
    private val host: FacadeHost
) : PluginContext {

    private val granted: Set<FacadeCall> = FacadeWhitelist.grantedFor(capabilities)

    override fun insertText(text: String): FacadeResult<Unit> =
        if (FacadeCall.INSERT_TEXT in granted) host.insertText(text) else deny("insertText")

    override fun showResult(title: String, body: String): FacadeResult<Unit> =
        if (FacadeCall.SHOW_RESULT in granted) host.showResult(title, body) else deny("showResult")

    override fun httpGet(url: String, httpsOnly: Boolean): FacadeResult<String> = when {
        FacadeCall.HTTP_GET_HTTPS !in granted -> deny("httpGet")
        !httpsOnly -> FacadeResult.Denied(
            "httpGet refused: plugin '$pluginId' is only granted TLS (https) requests — never a cleartext downgrade."
        )
        else -> host.httpGet(url)
    }

    override fun readSelection(): FacadeResult<String> =
        if (FacadeCall.READ_SELECTION in granted) host.readSelection() else deny("readSelection")

    override fun requestModelDownload(sizeBytes: Long): FacadeResult<Unit> =
        if (FacadeCall.REQUEST_MODEL_DOWNLOAD in granted) {
            host.requestModelDownload(sizeBytes)
        } else {
            deny("requestModelDownload")
        }

    private fun deny(call: String): FacadeResult<Nothing> = FacadeResult.Denied(
        "capability-facade call '$call' is not granted to plugin '$pluginId' (its capability whitelist does not include it)."
    )
}

/**
 * Creates the [PluginContext] handed to a plugin at load time. [DEFAULT] keeps
 * the Phase-22 deny-by-default behaviour (used by the standalone stub runtime);
 * [capabilityAware] builds the Phase-23 whitelist-granting facade over [host].
 */
fun interface PluginContextFactory {
    fun contextFor(entry: PluginEntry): PluginContext

    companion object {
        /** Everything denied — the honest default until a factory is wired. */
        val DEFAULT: PluginContextFactory = PluginContextFactory { entry ->
            DefaultPluginContext(entry.id)
        }

        /** Capability-whitelist facade over [host] (Phase 23 runtime). */
        fun capabilityAware(host: FacadeHost): PluginContextFactory =
            PluginContextFactory { entry ->
                CapabilityAwarePluginContext(entry.id, entry.capabilities, host)
            }
    }
}
