package com.authorss81.noteflow.plugins

import android.content.Context
import android.os.Build
import com.authorss81.noteflow.plugins.clipshare.ClipToInkFlowPlugin
import com.authorss81.noteflow.plugins.export.ExportEnginePlugin
import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionEngine
import com.authorss81.noteflow.plugins.ocr.OnDeviceOcrPlugin
import com.authorss81.noteflow.plugins.texttools.TextToolsEngine
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
 * @param plugins the installed plugins (defaults to the built-in set).
 * @param currentApiLevel device API level used for manifest validation
 *   (injected for JVM testability; production passes Build.VERSION.SDK_INT).
 * @param logger lifecycle/failure logging (NoOp by default for JVM tests).
 */
class PluginRegistry(
    private val enableStore: PluginEnableStore,
    private val settingsStore: PluginSettingsStore = InMemoryPluginSettingsStore(),
    private val plugins: List<NoteflowPlugin> = defaultPlugins(),
    private val currentApiLevel: Int = Build.VERSION.SDK_INT,
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    private val byId: Map<String, NoteflowPlugin> = plugins.associateBy { it.id }

    private val registrationOrder: Map<String, Int> = run {
        val seen = mutableSetOf<String>()
        val out = mutableMapOf<String, Int>()
        plugins.forEachIndexed { index, p ->
            if (p.id !in seen) {
                seen.add(p.id)
                out[p.id] = index
            }
        }
        out
    }

    /** Plugin ids that failed manifest validation or are duplicate ids. */
    private val rejectedIds: Set<String>
    private val validationErrors: Map<String, List<String>>

    private val enabledNotified = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val availabilityCache = java.util.concurrent.ConcurrentHashMap<String, PluginAvailability>()
    private val arbitrationDisabledNotified = mutableSetOf<String>()

    init {
        val occurrences = mutableMapOf<String, MutableList<Int>>()
        plugins.forEachIndexed { i, p -> occurrences.getOrPut(p.id) { mutableListOf() }.add(i) }
        val rejected = mutableSetOf<String>()
        val errors = mutableMapOf<String, List<String>>()
        plugins.forEachIndexed { i, p ->
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
                val plugin = byId[id] ?: return@forEach
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
        plugins.forEach { p ->
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
        val plugin = byId[pluginId]
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
        val plugin = byId[pluginId] ?: return
        try {
            plugin.onConfigChanged(context, pluginSettingsFor(plugin))
            logger.lifecycle("config-changed", pluginId, plugin.name)
        } catch (e: Throwable) {
            logger.error(pluginId, plugin.name, "onConfigChanged threw ${e::class.java.simpleName}")
        }
    }

    // ---- queries -----------------------------------------------------------

    /** Every installed plugin, in registration order (including rejected). */
    val allPlugins: List<NoteflowPlugin> get() = plugins

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
        plugins.filter { it.id !in rejectedIds && capability in it.capabilities }

    /** Opted-in plugins that can run on this device/context (valid ones). */
    fun enabledPlugins(context: Context? = null): List<NoteflowPlugin> =
        plugins.filter { it.id !in rejectedIds && enableStore.isEnabled(it.id) && containedAvailable(it, context) }

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
        plugins.forEach { p ->
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
            val loser = byId[loserId] ?: return@forEach
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
        val valid = plugins.map { it.id }.filter { it !in rejectedIds }.toSet()
        val edges = mutableMapOf<String, MutableSet<String>>()

        // Plugin-dependency edges: `dep` must be derived before `dependent`.
        plugins.forEach { p ->
            if (p.id !in valid) return@forEach
            p.manifest.dependencies.filter { it in valid }.forEach { d ->
                edges.getOrPut(d) { mutableSetOf() }.add(p.id)
            }
        }
        // Capability-requirement edges: every plugin that can serve a required
        // capability must be derived before the plugin requiring it, so the
        // serving plugin's derived state is available when we evaluate the
        // requirement. (Cycles introduced here are caught below.)
        plugins.forEach { p ->
            if (p.id !in valid) return@forEach
            p.manifest.requiresCapabilities.forEach { cap ->
                plugins.forEach { o ->
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
        val losers = mutableMapOf<String, String>()
        val caps = plugins.flatMap { it.capabilities }.toSet().filter { it.exclusive }
        caps.forEach { cap ->
            val candidates = plugins.filter { p ->
                p.id !in rejectedIds &&
                    cap in p.capabilities &&
                    enableStore.isEnabled(p.id) &&
                    effectiveAvailable(p, context)
            }
            if (candidates.size <= 1) return@forEach
            val sorted = candidates.sortedWith(
                compareByDescending<NoteflowPlugin> { it.version }.thenBy { registrationOrder.getValue(it.id) }
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
        val plugin = byId[id] ?: return PluginStateInfo(
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
            val winnerName = byId[conflictWinner]?.name ?: conflictWinner
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
            val dep = byId[depId]
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
                    cap in (byId[otherId]?.capabilities ?: emptySet())
            }
            if (!served) return "Requires an available plugin serving '${cap.label}'."
        }
        return null
    }

    /** Reason to refuse enabling [pluginId], or null when it can be enabled. */
    private fun refusalReasonForEnable(pluginId: String, context: Context?): String? {
        val plugin = byId[pluginId] ?: return "Unknown plugin '$pluginId'."
        if (pluginId in rejectedIds) {
            return "Cannot enable: invalid manifest (${validationErrors[pluginId]?.joinToString("; ") ?: "rejected"})."
        }
        for (depId in plugin.manifest.dependencies) {
            val dep = byId[depId]
            if (dep == null) return "Cannot enable: requires plugin '$depId' which is not installed."
            if (depId in rejectedIds) return "Cannot enable: requires plugin '${dep.name}' which was rejected."
            if (!enableStore.isEnabled(depId)) return "Cannot enable: requires plugin '${dep.name}' — enable it first."
        }
        for (cap in plugin.manifest.requiresCapabilities) {
            val serving = plugins.any { other ->
                other.id != pluginId && other.id !in rejectedIds && enableStore.isEnabled(other.id) &&
                    cap in other.capabilities && containedAvailable(other, context)
            }
            if (!serving) return "Cannot enable: requires an enabled plugin serving '${cap.label}'."
        }
        for (cap in plugin.capabilities) {
            if (!cap.exclusive) continue
            val rivals = plugins.filter { other ->
                other.id != pluginId && other.id !in rejectedIds && enableStore.isEnabled(other.id) &&
                    cap in other.capabilities && containedAvailable(other, context)
            }
            for (rival in rivals) {
                val ranked = listOf(plugin, rival).sortedWith(
                    compareByDescending<NoteflowPlugin> { it.version }.thenBy { registrationOrder.getValue(it.id) }
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
            WebCaptureEngine()
        )
    }
}