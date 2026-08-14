package com.authorss81.noteflow.plugins

import android.content.Context
import android.os.Build
import com.authorss81.noteflow.plugins.assistant.OnDeviceAssistantPlugin
import com.authorss81.noteflow.plugins.clipshare.ClipToInkFlowPlugin
import com.authorss81.noteflow.plugins.dictation.OnDeviceDictationPlugin
import com.authorss81.noteflow.plugins.export.ExportEnginePlugin
import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionEngine
import com.authorss81.noteflow.plugins.ocr.OnDeviceOcrPlugin
import com.authorss81.noteflow.plugins.readaloud.OnDeviceReadAloudPlugin
import com.authorss81.noteflow.plugins.screenshot.ScreenshotNotePluginImpl
import com.authorss81.noteflow.plugins.store.PluginInstallStore
import com.authorss81.noteflow.plugins.texttools.TextToolsEngine
import com.authorss81.noteflow.plugins.translation.OnDeviceTranslationPlugin
import com.authorss81.noteflow.plugins.webcapture.WebCaptureEngine
import com.authorss81.noteflow.plugins.websearch.DuckDuckGoWebSearchPlugin

/**
 * Compile-time registry of installed plugins.
 *
 * Plugins are discovered by simple list registration — the registry never loads
 * code at runtime, so the set of plugins is fixed at build time (honest, safe,
 * and trivially unit-testable). A new plugin is added by implementing
 * [NoteflowPlugin], adding it to [defaultPlugins], and (for a brand-new
 * capability) defining its serving interface. Nothing else in the app needs to
 * change.
 *
 * ## What the registry does (Phase 11 hardening)
 *
 * - **Manifest validation** — every manifest is validated at construction
 *   (blank fields, unparseable/invalid version, minSupportedApi above the
 *   device, no capabilities, self-dependency, duplicate ids). Invalid plugins
 *   are REJECTED with a reason — never a crash.
 * - **Derived states, recomputed on every change** — [resolve] derives each
 *   plugin's [PluginLifecycleState] fresh: opt-in, device availability,
 *   dependency resolution and capability-conflict arbitration are all folded in.
 *   A revoked permission or lost dependency immediately flips the state, so no
 *   stale state is ever served.
 * - **Dependency resolution** — plugins may depend on other plugins
 *   ([PluginManifest.dependencies]) and on capabilities
 *   ([PluginManifest.requiresCapabilities]). [resolveEnableOrder] computes a
 *   valid topological enable order; [setEnabled] refuses to enable a plugin
 *   whose requirements are unmet.
 * - **Conflict arbitration** — when two enabled plugins claim the same
 *   exclusive capability, a deterministic winner is chosen (higher version;
 *   tie → earlier registration) and the loser is reported as disabled with a
 *   reason.
 * - **Contained lifecycle** — [onEnable]/[onDisable]/[onConfigChanged] run
 *   guarded: a throwing hook is logged (never raw content) and contained.
 *
 * @param enableStore persistence for per-plugin opt-in.
 * @param settingsStore persistence for per-plugin (namespaced) settings.
 * @param installStore persistence for the plugin store's install state (Phase 21).
 *   A plugin whose install state is false is "not downloaded": it is excluded
 *   from [allPlugins], enabling and capability routing until it is re-installed
 *   via [installPlugin]. Defaults to a store where EVERY plugin is installed
 *   (backward compatible — existing tests/callers keep every plugin active).
 * @param plugins the installed plugins (defaults to the built-in set).
 * @param optionalPluginFactories factory list for OPTIONAL bundled store plugins
 *   (Phase 21). A factory is compiled-in but NOT installed by default; it is
 *   listed in the store as "Not downloaded" until the user installs it. A plugin
 *   the user already installed (persisted via [installStore]) is re-materialized
 *   at construction so it survives process restarts. Factory instances are the
 *   canonical definitions (no duplicate catalog entries).
 * @param currentApiLevel device API level used for manifest validation
 *   (injected for JVM testability; production passes Build.VERSION.SDK_INT).
 * @param logger lifecycle/failure logging (NoOp by default for JVM tests).
 */
class PluginRegistry(
    private val enableStore: PluginEnableStore,
    private val settingsStore: PluginSettingsStore = InMemoryPluginSettingsStore(),
    private val plugins: List<NoteflowPlugin> = defaultPlugins(),
    private val installStore: PluginInstallStore = AllInstalledInstallStore,
    private val optionalPluginFactories: List<() -> NoteflowPlugin> = emptyList(),
    private val currentApiLevel: Int = Build.VERSION.SDK_INT,
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    private val basePlugins = plugins

    /** Optional bundled definitions (store-only): compiled in, NOT active by default. */
    private val optionalPlugins: List<NoteflowPlugin> = optionalPluginFactories.map { it() }

    /** Plugins added at runtime via [installPlugin] (compiled, but not default). */
    private val extraPlugins = mutableListOf<NoteflowPlugin>()

    /** Every compiled plugin the app ships with, installed or not (Phase 21).
     *  Includes the OPTIONAL definitions (so the store can list them as
     *  "Not downloaded") and any runtime-installed extras. Never lists an id
     *  twice. */
    val compiledPlugins: List<NoteflowPlugin>
        get() = (basePlugins + optionalPlugins + extraPlugins).distinctBy { it.id }

    /** Plugins currently installed ("downloaded") — the active registry set.
     *  Optional definitions re-materialized on process restart (see [init]) are
     *  active exactly when their persisted install state says so. */
    private fun activePlugins(): List<NoteflowPlugin> =
        compiledPlugins.filter { installStore.isInstalled(it.id) }

    private fun pluginById(id: String): NoteflowPlugin? =
        activePlugins().firstOrNull { it.id == id }

    /** Fresh registration order over the CURRENT compiled set, so runtime
     *  installs (Phase 21 store) participate in conflict arbitration instead of
     *  crashing with a missing key. */
    private fun registrationOrder(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        compiledPlugins.forEachIndexed { index, p ->
            if (p.id !in out) out[p.id] = index
        }
        return out
    }

    /** Plugin ids that failed manifest validation or are duplicate ids. */
    private val rejectedIds: Set<String>
    private val validationErrors: Map<String, List<String>>

    private val enabledNotified = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val availabilityCache = java.util.concurrent.ConcurrentHashMap<String, PluginAvailability>()
    private val arbitrationDisabledNotified = mutableSetOf<String>()

    init {
        // Phase 21: re-materialize optional definitions the user already
        // installed in a previous session (install state persists in the store
        // across restarts) — otherwise the plugin would be "installed" in name
        // but absent from the active registry.
        optionalPlugins.filter { installStore.isInstalled(it.id) }.forEach { extraPlugins.add(it) }

        val occurrences = mutableMapOf<String, MutableList<Int>>()
        (basePlugins + optionalPlugins).forEachIndexed { i, p ->
            occurrences.getOrPut(p.id) { mutableListOf() }.add(i)
        }
        val rejected = mutableSetOf<String>()
        val errors = mutableMapOf<String, List<String>>()
        (basePlugins + optionalPlugins).forEachIndexed { i, p ->
            val firstIndex = occurrences.getValue(p.id).first()
            if (firstIndex != i) {
                rejected.add(p.id)
                errors[p.id] = listOf("duplicate plugin id '${p.id}' (registered more than once)")
            } else {
                val validation = PluginManifestValidator.validate(p.manifest, currentApiLevel)
                if (validation is ManifestValidation.Invalid) {
                    rejected.add(p.id)
                    errors[p.id] = validation.errors
                }
            }
        }
        rejectedIds = rejected
        validationErrors = errors
    }

    // ---- lifecycle hooks ---------------------------------------------------

    /**
     * Run at process start (from the ViewModel) so plugins that are already
     * enabled in the persisted store still get their [NoteflowPlugin.onEnable]
     * hook — otherwise a plugin enabled in a previous session would never be
     * initialized in this one. Idempotent per process, order-respecting and
     * conflict-aware: hooks fire in dependency order, and a plugin that the
     * deterministic arbitration just disabled (e.g. two rivals stored enabled
     * from a prior session) is NOT initialized — it would never serve and its
     * onEnable would have no matching onDisable. Contained: a throwing hook is
     * logged.
     */
    @Synchronized
    fun onProcessStart(context: Context?) {
        val states = resolve(context)
        when (val resolution = resolveEnableOrder()) {
            is PluginOrderResolution.Success -> resolution.order.forEach { id ->
                val plugin = pluginById(id) ?: return@forEach
                if (!enableStore.isEnabled(id)) return@forEach
                val state = states[id]?.state
                val canInit = state == PluginLifecycleState.AVAILABLE ||
                    state == PluginLifecycleState.ENABLED ||
                    state == PluginLifecycleState.UNAVAILABLE
                if (canInit && enabledNotified.add(id)) {
                    guardedOnEnable(plugin, context)
                }
            }
            is PluginOrderResolution.Cyclic -> logger.error(
                "process-start", "registry",
                "dependency cycle prevents enabling: ${resolution.pluginIds}"
            )
        }
    }

    /**
     * Recompute device availability for every plugin. Called whenever a real
     * context (or a change that could affect availability) is available, so the
     * derived states never go stale. A throwing [NoteflowPlugin.availability] is
     * contained and recorded as unavailable.
     */
    @Synchronized
    fun refreshAvailability(context: Context?) {
        availabilityCache.clear()
        activePlugins().forEach { p ->
            availabilityCache[p.id] = containedAvailability(p, context)
        }
    }

    /**
     * Set a plugin's opt-in state. Enabling is refused with a clear reason when
     * the plugin's requirements are unmet (invalid manifest, missing/not-enabled
     * dependency, unavailable required capability, or it would lose a
     * deterministic capability conflict). Disabling always succeeds and fires the
     * plugin's [NoteflowPlugin.onDisable]; a plugin disabled by conflict
     * arbitration also gets a (once-per-process) onDisable so its resources are
     * released. Returns a typed [PluginEnableResult] instead of throwing.
     */
    @Synchronized
    fun setEnabled(pluginId: String, enabled: Boolean, context: Context? = null): PluginEnableResult {
        val plugin = pluginById(pluginId)
        if (enabled) {
            val refusal = refusalReasonForEnable(pluginId, context)
            if (refusal != null) {
                return PluginEnableResult.Refused(pluginId, refusal)
            }
            enableStore.setEnabled(pluginId, true)
            if (enabledNotified.add(pluginId) && plugin != null) {
                guardedOnEnable(plugin, context)
            }
            logger.lifecycle("enabled", pluginId, plugin?.name ?: pluginId)
            refreshAvailability(context)
            disableNewlyArbitratedLosers(context)
            return PluginEnableResult.Changed(pluginId, nowEnabled = true)
        }
        enableStore.setEnabled(pluginId, false)
        plugin?.let {
            guardedOnDisable(it, context)
            logger.lifecycle("disabled", pluginId, it.name)
        }
        // A later re-enable in the same process must fire onEnable again (the
        // plugin was torn down by the onDisable above).
        enabledNotified.remove(pluginId)
        refreshAvailability(context)
        // Arbitration may now pick a different winner — release previously
        // disabled losers so their derived state can recover to AVAILABLE.
        releaseArbitratedLosers(context)
        return PluginEnableResult.Changed(pluginId, nowEnabled = false)
    }

    /** Notify a plugin that one of its `plugins.<id>.<key>` settings changed. */
    fun notifyConfigChanged(pluginId: String, context: Context? = null) {
        val plugin = pluginById(pluginId) ?: return
        try {
            plugin.onConfigChanged(context, pluginSettingsFor(plugin))
            logger.lifecycle("config-changed", pluginId, plugin.name)
        } catch (e: Throwable) {
            logger.error(pluginId, plugin.name, "onConfigChanged threw ${e::class.java.simpleName}")
        }
    }

    // ---- store lifecycle (Phase 21) -----------------------------------------

    /**
     * Re-register a DOWNLOADABLE (remote) plugin at process start (Phase 23).
     *
     * A remote plugin is not compiled in — after a restart its persisted
     * artifact is re-loaded by the runtime and this method re-joins the
     * materialized instance to the active registry. It does NOT touch the
     * install state (the plugin is already installed) and refuses to duplicate
     * an id already active. Validation is identical to [installPlugin].
     */
    @Synchronized
    fun registerRemotePlugin(plugin: NoteflowPlugin, context: Context? = null): PluginInstallResult {
        if (plugin.id in rejectedIds || plugin.id in validationErrors) {
            val errors = validationErrors[plugin.id]?.joinToString("; ") ?: "rejected"
            return PluginInstallResult.Refused(plugin.id, "Cannot restore: invalid manifest ($errors).")
        }
        val knownIds = basePlugins.map { it.id }.toSet() + optionalPlugins.map { it.id }.toSet()
        if (plugin.id !in knownIds) {
            val validation = PluginManifestValidator.validate(plugin.manifest, currentApiLevel)
            if (validation is ManifestValidation.Invalid) {
                return PluginInstallResult.Refused(
                    plugin.id, "Cannot restore: ${validation.errors.joinToString("; ")}."
                )
            }
        }
        if (extraPlugins.none { it.id == plugin.id }) {
            extraPlugins.add(plugin)
        }
        refreshAvailability(context)
        logger.lifecycle("restored", plugin.id, plugin.name)
        return PluginInstallResult.Installed(plugin.id)
    }

    /**
     * REPLACE the active instance of a downloadable plugin with a NEW instance
     * of the SAME id (Phase 24: the post-update artifact). After a verified
     * update the runtime materializes the new version's plugin and this method
     * swaps it into the live registry so the new version serves in-session
     * (the install state is untouched — the plugin stays installed). The
     * lifecycle hooks of the new instance fire at the next process start
     * (`onProcessStart`), matching how a restart picks up a new version; today's
     * session simply resolves + routes the loaded new instance.
     */
    @Synchronized
    fun replaceRemotePlugin(plugin: NoteflowPlugin, context: Context? = null): PluginInstallResult {
        if (plugin.id in rejectedIds || plugin.id in validationErrors) {
            val errors = validationErrors[plugin.id]?.joinToString("; ") ?: "rejected"
            return PluginInstallResult.Refused(plugin.id, "Cannot replace: invalid manifest ($errors).")
        }
        if (!installStore.isInstalled(plugin.id)) {
            return PluginInstallResult.Refused(plugin.id, "Cannot replace: the plugin is not installed.")
        }
        val replaced = extraPlugins.any { it.id == plugin.id }
        extraPlugins.removeAll { it.id == plugin.id }
        extraPlugins.add(plugin)
        refreshAvailability(context)
        logger.lifecycle("replaced-after-update", plugin.id, plugin.name)
        return if (replaced) PluginInstallResult.Installed(plugin.id)
        else PluginInstallResult.Refused(plugin.id, "No active instance of this plugin to replace.")
    }

    /**
     * Install a bundled plugin definition at runtime — the store's "Download".
     *
     * Honest semantics under the compile-time rule: this NEVER loads bytecode.
     * For a plugin already in [basePlugins] (built-in, previously deleted by
     * the user) it simply flips the persisted install state back on. For an
     * OPTIONAL bundled plugin ([compiledPlugins] only) it validates the manifest
     * (identical rules to [init] rejection) and activates the compiled instance.
     * On success the plugin appears in [allPlugins], can be enabled, and is
     * routable. No state surprises: a re-installed plugin starts REGISTERED
     * (off), because Delete wiped its enable + settings.
     */
    @Synchronized
    fun installPlugin(plugin: NoteflowPlugin, context: Context? = null): PluginInstallResult {
        if (installStore.isInstalled(plugin.id)) {
            return PluginInstallResult.Refused(plugin.id, "This plugin is already installed.")
        }
        if (plugin.id in rejectedIds || plugin.id in validationErrors) {
            val errors = validationErrors[plugin.id]?.joinToString("; ") ?: "rejected"
            return PluginInstallResult.Refused(plugin.id, "Cannot install: invalid manifest ($errors).")
        }
        // Built-in and optional bundled definitions already live in
        // [compiledPlugins]; installing flips their persisted state on. Only a
        // genuinely unknown definition (not a built-in/optional bundled one)
        // needs to be validated and added at runtime.
        val knownIds = basePlugins.map { it.id }.toSet() + optionalPlugins.map { it.id }.toSet()
        if (plugin.id !in knownIds) {
            val validation = PluginManifestValidator.validate(plugin.manifest, currentApiLevel)
            if (validation is ManifestValidation.Invalid) {
                return PluginInstallResult.Refused(
                    plugin.id, "Cannot install: ${validation.errors.joinToString("; ")}."
                )
            }
            extraPlugins.add(plugin)
        }
        installStore.setInstalled(plugin.id, true)
        refreshAvailability(context)
        logger.lifecycle("installed", plugin.id, plugin.name)
        return PluginInstallResult.Installed(plugin.id)
    }

    /**
     * Uninstall a plugin — the store's "Delete". Completely removes the plugin:
     * its opt-in flag, ever-enabled flag and every namespaced `plugins.<id>.*`
     * setting are wiped, runtime caches are dropped, any running resources are
     * torn down (guarded onDisable), and the plugin becomes absent from
     * [allPlugins] and capability routing until [installPlugin] re-installs it.
     */
    @Synchronized
    fun uninstallPlugin(pluginId: String, context: Context? = null): PluginUninstallResult {
        val plugin = pluginById(pluginId)
            ?: return PluginUninstallResult.Refused(pluginId, "This plugin is not installed.")
        if (enableStore.isEnabled(pluginId)) {
            guardedOnDisable(plugin, context)
        }
        // Wipe opt-in history + every namespaced setting (delete, not disable).
        enableStore.wipe(pluginId)
        settingsStore.removeAll(pluginId)
        // Drop per-process caches so a later re-install starts clean.
        enabledNotified.remove(pluginId)
        arbitrationDisabledNotified.remove(pluginId)
        availabilityCache.remove(pluginId)
        extraPlugins.removeAll { it.id == pluginId }
        // Mark not installed AFTER the settings wipe (which also resets the
        // shared store's uninstalled flag) so the two stay consistent.
        installStore.setInstalled(pluginId, false)
        refreshAvailability(context)
        logger.lifecycle("uninstalled", pluginId, plugin.name)
        return PluginUninstallResult.Uninstalled(pluginId)
    }

    // ---- queries -----------------------------------------------------------

    /** Every INSTALLED plugin, in registration order (including rejected ones).
     *  Plugins the user deleted from the store are absent — they are bundled but
     *  not installed (see [compiledPlugins] for the full compiled set). */
    val allPlugins: List<NoteflowPlugin> get() = activePlugins()

    /** Whether [pluginId] is currently installed ("downloaded") via the store. */
    fun isInstalled(pluginId: String): Boolean = installStore.isInstalled(pluginId)

    /** Whether [pluginId] is a built-in (default) plugin — i.e. NOT optional. */
    fun isBuiltIn(pluginId: String): Boolean = basePlugins.any { it.id == pluginId }

    /** Whether [pluginId] was rejected (invalid manifest / duplicate id). */
    fun isRejected(pluginId: String): Boolean = pluginId in rejectedIds

    /** Validation errors for a rejected plugin, or null when valid. */
    fun validationErrorsOf(pluginId: String): List<String>? = validationErrors[pluginId]

    /** Whether [pluginId] is currently opted-in by the user. */
    fun isEnabled(pluginId: String): Boolean = enableStore.isEnabled(pluginId)

    /** A [PluginSettings] slice scoped to [pluginId] (never leaks another's). */
    fun settingsFor(pluginId: String): PluginSettings = PluginSettings(settingsStore, pluginId)

    /**
     * All plugins that declare [capability], excluding rejected ones
     * (regardless of enabled/available state).
     */
    fun pluginsForCapability(capability: PluginCapability): List<NoteflowPlugin> =
        activePlugins().filter { it.id !in rejectedIds && capability in it.capabilities }

    /** Opted-in plugins that can run on this device/context (valid ones). */
    fun enabledPlugins(context: Context? = null): List<NoteflowPlugin> =
        activePlugins().filter { it.id !in rejectedIds && enableStore.isEnabled(it.id) && containedAvailable(it, context) }

    /** Opted-in, device-available plugins that serve [capability]. */
    fun availablePlugins(capability: PluginCapability, context: Context? = null): List<NoteflowPlugin> =
        enabledPlugins(context).filter { capability in it.capabilities }

    /**
     * Resolve the dependency graph into a valid enable order (topological sort,
     * dependencies first) over the VALID (non-rejected) plugins. Cyclic graphs
     * return [PluginOrderResolution.Cyclic] with the involved ids.
     */
    fun resolveEnableOrder(): PluginOrderResolution {
        val (order, cyclic) = computeOrdering()
        return if (cyclic.isEmpty()) PluginOrderResolution.Success(order)
        else PluginOrderResolution.Cyclic(cyclic.sorted())
    }

    /**
     * The derived lifecycle state map for every installed plugin, recomputed
     * FRESH on every call: device availability is re-evaluated for every plugin
     * ([refreshAvailability]) so permission loss / dependency loss / arbitration
     * changes are never stale. [context] is passed through to each plugin's
     * availability gate; a null context makes context-gated plugins report
     * [PluginAvailability.Unknown], which derives as ENABLED (not yet verified).
     */
    @Synchronized
    fun resolve(context: Context? = null): Map<String, PluginStateInfo> {
        refreshAvailability(context)
        val states = linkedMapOf<String, PluginStateInfo>()
        val conflicts = computeConflictLosers(context)
        val (order, cyclic) = computeOrdering()
        order.forEach { id ->
            states[id] = deriveState(id, context, conflicts, states, id in cyclic)
        }
        activePlugins().forEach { p ->
            if (p.id !in states) states[p.id] = deriveState(p.id, context, conflicts, states, inCycle = false)
        }
        return states
    }

    /** Convenience: derived state of a single plugin (freshly computed). */
    fun stateOf(pluginId: String, context: Context? = null): PluginStateInfo? = resolve(context)[pluginId]

    // ---- internals ---------------------------------------------------------

    private fun pluginSettingsFor(plugin: NoteflowPlugin) = PluginSettings(settingsStore, plugin.id)

    private fun guardedOnEnable(plugin: NoteflowPlugin, context: Context?) {
        try {
            plugin.onEnable(context, pluginSettingsFor(plugin))
            logger.lifecycle("onEnable", plugin.id, plugin.name)
        } catch (e: Throwable) {
            logger.error(plugin.id, plugin.name, "onEnable threw ${e::class.java.simpleName}")
        }
    }

    private fun guardedOnDisable(plugin: NoteflowPlugin, context: Context?) {
        try {
            plugin.onDisable(context, pluginSettingsFor(plugin))
            logger.lifecycle("onDisable", plugin.id, plugin.name)
        } catch (e: Throwable) {
            logger.error(plugin.id, plugin.name, "onDisable threw ${e::class.java.simpleName}")
        }
    }

    /**
     * [NoteflowPlugin.availability] evaluated under a guard: a throwing gate is
     * contained (logged, treated as unavailable) instead of propagating to the
     * callers of [refreshAvailability]/[resolve]/[setEnabled].
     */
    private fun containedAvailability(plugin: NoteflowPlugin, context: Context?): PluginAvailability {
        return try {
            plugin.availability(context)
        } catch (e: Throwable) {
            logger.error(plugin.id, plugin.name, "availability check threw ${e::class.java.simpleName}")
            PluginAvailability.Unavailable("availability check failed (${e::class.java.simpleName})")
        }
    }

    /** Convenience: [containedAvailability] is [PluginAvailability.Ok]. */
    private fun containedAvailable(plugin: NoteflowPlugin, context: Context?): Boolean =
        containedAvailability(plugin, context) is PluginAvailability.Ok

    /** Fire [NoteflowPlugin.onDisable] (at most once per process) for plugins the
     *  deterministic arbitration has just disabled as losers. */
    private fun disableNewlyArbitratedLosers(context: Context?) {
        computeConflictLosers(context).forEach { (loserId, _) ->
            val loser = pluginById(loserId) ?: return@forEach
            if (enabledNotified.contains(loserId) && arbitrationDisabledNotified.add(loserId)) {
                guardedOnDisable(loser, context)
                logger.lifecycle("disabled-by-arbitration", loserId, loser.name)
            }
        }
    }

    /** Forget arbitration tracking for ids that are no longer losers, so a future
     *  re-arbitration can fire [NoteflowPlugin.onDisable] again. */
    private fun releaseArbitratedLosers(context: Context?) {
        val currentLosers = computeConflictLosers(context).keys
        arbitrationDisabledNotified.retainAll(currentLosers)
    }

    /** (full topo order incl. cycle members appended, set of cycle ids). */
    private fun computeOrdering(): Pair<List<String>, Set<String>> {
        val active = activePlugins()
        val valid = active.map { it.id }.filter { it !in rejectedIds }.toSet()
        val edges = mutableMapOf<String, MutableSet<String>>()

        // Plugin-dependency edges: `dep` must be derived before `dependent`.
        active.forEach { p ->
            if (p.id !in valid) return@forEach
            p.manifest.dependencies.filter { it in valid }.forEach { d ->
                edges.getOrPut(d) { mutableSetOf() }.add(p.id)
            }
        }
        // Capability-requirement edges: every plugin that can serve a required
        // capability must be derived before the plugin requiring it, so the
        // serving plugin's derived state is available when we evaluate the
        // requirement. (Cycles introduced here are caught below.)
        active.forEach { p ->
            if (p.id !in valid) return@forEach
            p.manifest.requiresCapabilities.forEach { cap ->
                active.forEach { o ->
                    if (o.id != p.id && o.id in valid && cap in o.capabilities) {
                        edges.getOrPut(o.id) { mutableSetOf() }.add(p.id)
                    }
                }
            }
        }

        val inDegree = mutableMapOf<String, Int>()
        valid.forEach { inDegree[it] = 0 }
        edges.forEach { (_, dependents) -> dependents.forEach { inDegree[it] = inDegree.getValue(it) + 1 } }

        val queue = ArrayDeque(valid.filter { inDegree.getValue(it) == 0 }.sorted())
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            order.add(node)
            edges[node]?.toList()?.sorted()?.forEach { dependent ->
                inDegree[dependent] = inDegree.getValue(dependent) - 1
                if (inDegree.getValue(dependent) == 0) queue.add(dependent)
            }
        }
        val cyclic = valid.filter { it !in order }.sorted()
        order.addAll(cyclic)
        return order to cyclic.toSet()
    }

    /**
     * For each exclusive capability, among enabled + effective-available
     * plugins, choose ONE deterministic winner (higher version; tie → earlier
     * registration). Returns `loserId → winnerId`.
     */
    private fun computeConflictLosers(context: Context?): Map<String, String> {
        val active = activePlugins()
        val losers = mutableMapOf<String, String>()
        val caps = active.flatMap { it.capabilities }.toSet().filter { it.exclusive }
        caps.forEach { cap ->
            val candidates = active.filter { p ->
                p.id !in rejectedIds &&
                    cap in p.capabilities &&
                    enableStore.isEnabled(p.id) &&
                    effectiveAvailable(p, context)
            }
            if (candidates.size <= 1) return@forEach
            val sorted = candidates.sortedWith(
                compareByDescending<NoteflowPlugin> { it.version }.thenBy { registrationOrder().getValue(it.id) }
            )
            val winner = sorted.first()
            sorted.drop(1).forEach { losers[it.id] = winner.id }
        }
        return losers
    }

    private fun effectiveAvailable(plugin: NoteflowPlugin, context: Context?): Boolean {
        val availability = availabilityCache[plugin.id] ?: containedAvailability(plugin, context)
        return availability !is PluginAvailability.Unavailable
    }

    private fun deriveState(
        id: String,
        context: Context?,
        conflicts: Map<String, String>,
        states: Map<String, PluginStateInfo>,
        inCycle: Boolean
    ): PluginStateInfo {
        val plugin = pluginById(id) ?: return PluginStateInfo(
            id, id, PluginLifecycleState.REJECTED, "unknown plugin id", SemanticVersion(0, 0, 0),
            enabled = false, availableOnDevice = false, depsResolved = false, conflictWinnerId = null
        )
        val version = plugin.version
        val availableNow = availabilityCache[id] == PluginAvailability.Ok

        if (id in rejectedIds) {
            return PluginStateInfo(
                id, plugin.name, PluginLifecycleState.REJECTED,
                "Rejected: ${validationErrors[id]?.joinToString("; ") ?: "invalid manifest"}",
                version, enabled = false, availableOnDevice = availableNow,
                depsResolved = false, conflictWinnerId = null
            )
        }
        val conflictWinner = conflicts[id]
        if (conflictWinner != null) {
            val winnerName = pluginById(conflictWinner)?.name ?: conflictWinner
            return PluginStateInfo(
                id, plugin.name, PluginLifecycleState.DISABLED,
                "Disabled by arbitration: conflicts with '$winnerName' for a shared exclusive capability.",
                version, enabled = true, availableOnDevice = availableNow,
                depsResolved = true, conflictWinnerId = conflictWinner
            )
        }
        val userEnabled = enableStore.isEnabled(id)
        if (!userEnabled) {
            val everEnabled = enableStore.hasEverBeenEnabled(id)
            return PluginStateInfo(
                id, plugin.name,
                if (everEnabled) PluginLifecycleState.DISABLED else PluginLifecycleState.REGISTERED,
                if (everEnabled) "Disabled by the user" else null,
                version, enabled = false, availableOnDevice = availableNow,
                depsResolved = true, conflictWinnerId = null
            )
        }
        if (inCycle) {
            return PluginStateInfo(
                id, plugin.name, PluginLifecycleState.UNAVAILABLE,
                "Cannot resolve: a dependency cycle involves this plugin.",
                version, enabled = true, availableOnDevice = availableNow,
                depsResolved = false, conflictWinnerId = null
            )
        }
        val depIssue = firstDependencyIssue(plugin, states)
        if (depIssue != null) {
            return PluginStateInfo(
                id, plugin.name, PluginLifecycleState.UNAVAILABLE, depIssue,
                version, enabled = true, availableOnDevice = availableNow,
                depsResolved = false, conflictWinnerId = null
            )
        }
        val availability = availabilityCache[id] ?: PluginAvailability.Unknown
        return when (availability) {
            PluginAvailability.Unknown -> PluginStateInfo(
                id, plugin.name, PluginLifecycleState.ENABLED,
                "Enabled — device availability not yet verified",
                version, enabled = true, availableOnDevice = false,
                depsResolved = true, conflictWinnerId = null
            )
            PluginAvailability.Ok -> PluginStateInfo(
                id, plugin.name, PluginLifecycleState.AVAILABLE, null,
                version, enabled = true, availableOnDevice = true,
                depsResolved = true, conflictWinnerId = null
            )
            is PluginAvailability.Unavailable -> PluginStateInfo(
                id, plugin.name, PluginLifecycleState.UNAVAILABLE,
                availability.reason, version, enabled = true, availableOnDevice = false,
                depsResolved = true, conflictWinnerId = null
            )
        }
    }

    /**
     * Returns a user-facing message when [plugin]'s requirements are unmet, else
     * null. Dependencies are evaluated against the already-derived [states]
     * (computed in topological order, so dependencies come first).
     */
    private fun firstDependencyIssue(plugin: NoteflowPlugin, states: Map<String, PluginStateInfo>): String? {
        for (depId in plugin.manifest.dependencies) {
            val dep = pluginById(depId)
            if (dep == null) return "Requires plugin '$depId' which is not installed."
            if (depId in rejectedIds) return "Requires plugin '${dep.name}' which was rejected."
            val depState = states[depId]
            if (depState == null) return "Requires plugin '${dep.name}' which is not resolvable."
            if (depState.state != PluginLifecycleState.AVAILABLE) {
                return "Requires plugin '${dep.name}' to be enabled and available (currently ${depState.state.name})."
            }
        }
        for (cap in plugin.manifest.requiresCapabilities) {
            val served = states.any { (otherId, st) ->
                otherId != plugin.id && st.state == PluginLifecycleState.AVAILABLE &&
                    cap in (pluginById(otherId)?.capabilities ?: emptySet())
            }
            if (!served) return "Requires an available plugin serving '${cap.label}'."
        }
        return null
    }

    /** Reason to refuse enabling [pluginId], or null when it can be enabled. */
    private fun refusalReasonForEnable(pluginId: String, context: Context?): String? {
        val plugin = pluginById(pluginId) ?: return "Unknown plugin '$pluginId'."
        if (pluginId in rejectedIds) {
            return "Cannot enable: invalid manifest (${validationErrors[pluginId]?.joinToString("; ") ?: "rejected"})."
        }
        for (depId in plugin.manifest.dependencies) {
            val dep = pluginById(depId)
            if (dep == null) return "Cannot enable: requires plugin '$depId' which is not installed."
            if (depId in rejectedIds) return "Cannot enable: requires plugin '${dep.name}' which was rejected."
            if (!enableStore.isEnabled(depId)) return "Cannot enable: requires plugin '${dep.name}' — enable it first."
        }
        for (cap in plugin.manifest.requiresCapabilities) {
            val serving = activePlugins().any { other ->
                other.id != pluginId && other.id !in rejectedIds && enableStore.isEnabled(other.id) &&
                    cap in other.capabilities && containedAvailable(other, context)
            }
            if (!serving) return "Cannot enable: requires an enabled plugin serving '${cap.label}'."
        }
        for (cap in plugin.capabilities) {
            if (!cap.exclusive) continue
            val rivals = activePlugins().filter { other ->
                other.id != pluginId && other.id !in rejectedIds && enableStore.isEnabled(other.id) &&
                    cap in other.capabilities && containedAvailable(other, context)
            }
            for (rival in rivals) {
                val ranked = listOf(plugin, rival).sortedWith(
                    compareByDescending<NoteflowPlugin> { it.version }.thenBy { registrationOrder().getValue(it.id) }
                )
                if (ranked.first().id == rival.id) {
                    return "Cannot enable: conflicts with '${rival.name}' for ${cap.label} — " +
                        "'${rival.name}' wins deterministically (version ${rival.version} vs ${plugin.version}). " +
                        "Disable it first to enable this plugin."
                }
            }
        }
        return null
    }

    companion object {
        /**
         * The built-in plugin set. Extend this list to install a new plugin;
         * keep it as the single registration point (compile-time discovery).
         *
         * Phase 12: the two real high-level plugins (on-device OCR + keyless
         * web search) live here next to the ROT13 proof-of-wiring plugin. The
         * OCR plugin is engine-injected at construction (null engine → bundled
         * ML Kit created lazily on device); the Web Search plugin's network
         * client is the default DDG client.
         */
        fun defaultPlugins(): List<NoteflowPlugin> = listOf(
            Rot13TransformPlugin(),
            OnDeviceOcrPlugin(),
            DuckDuckGoWebSearchPlugin(),
            ExportEnginePlugin(),
            ClipToInkFlowPlugin(),
            TextToolsEngine(),
            LanguageDetectionEngine(),
            WebCaptureEngine(),
            // Phase 16: privacy-first on-device AI & media plugin pack.
            OnDeviceDictationPlugin(),
            OnDeviceReadAloudPlugin(),
            OnDeviceTranslationPlugin(),
            OnDeviceAssistantPlugin(),
            ScreenshotNotePluginImpl()
        )
    }
}

/**
 * Default [PluginInstallStore] used when a registry is constructed without one:
 * every plugin counts as installed, preserving the pre-store behaviour
 * (Phase 21 backward compatibility — existing callers/tests keep every plugin
 * active without opting into the store lifecycle).
 */
private object AllInstalledInstallStore : PluginInstallStore {
    override fun isInstalled(pluginId: String): Boolean = true
    override fun setInstalled(pluginId: String, installed: Boolean) {}
}