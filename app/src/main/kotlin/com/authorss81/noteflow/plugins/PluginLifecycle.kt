package com.authorss81.noteflow.plugins

/**
 * Lifecycle states of an installed plugin, derived fresh by
 * [PluginRegistry.resolve] on every change so no stale state is ever served.
 *
 * The happy-path flow is `REGISTERED → ENABLED → AVAILABLE`; `UNAVAILABLE` and
 * `DISABLED` are failure exits, and `REJECTED` marks a plugin whose manifest
 * failed validation (it is never enabled, never routed).
 *
 * - [REGISTERED] — installed and known to the registry; the user has never
 *   enabled it (off by default).
 * - [ENABLED] — the user opted in and requirements are met, but the plugin's
 *   device availability is not yet known (it reported [PluginAvailability.Unknown]
 *   — e.g. no `Context` to check against). Awaiting verification.
 * - [AVAILABLE] — opted in, requirements met, `availability(context)` returned
 *   [PluginAvailability.Ok]: the plugin can serve requests right now.
 * - [UNAVAILABLE] — opted in and requirements met, but `availability(context)`
 *   returned [PluginAvailability.Unavailable] (device/AGSL/API/permission
 *   gate) OR a required dependency is unavailable. Has a user-facing reason.
 * - [DISABLED] — off: either the user disabled it, or the registry disabled it
 *   (deterministic capability-conflict arbitration). Has a reason.
 * - [REJECTED] — manifest failed validation (duplicate id, missing field,
 *   incompatible api, unparseable version). Rejected plugins are excluded from
 *   enabling and routing; reason lists the validation errors.
 */
enum class PluginLifecycleState {
    REGISTERED,
    ENABLED,
    AVAILABLE,
    UNAVAILABLE,
    DISABLED,
    REJECTED
}

/**
 * Derived per-plugin state exposed by [PluginRegistry.resolve]. Fully
 * recomputed on every query so enable/disable, permission loss, dependency loss
 * and conflict arbitration are always reflected.
 *
 * @param state the effective lifecycle state.
 * @param reason user-facing explanation for anything other than AVAILABLE.
 * @param enabled whether the user has opted the plugin in (persisted).
 * @param availableOnDevice whether `availability(context)` returned Ok.
 * @param depsResolved whether all declared dependencies / capabilities are met.
 * @param conflictWinnerId when this plugin lost a capability conflict, the id
 *   of the deterministic winner (null otherwise).
 */
data class PluginStateInfo(
    val pluginId: String,
    val pluginName: String,
    val state: PluginLifecycleState,
    val reason: String?,
    val version: SemanticVersion,
    val enabled: Boolean,
    val availableOnDevice: Boolean,
    val depsResolved: Boolean,
    val conflictWinnerId: String?
)

/** Result of attempting to enable (or disable) a plugin. */
sealed class PluginEnableResult {
    /** The opt-in state change was applied. */
    data class Changed(val pluginId: String, val nowEnabled: Boolean) : PluginEnableResult()

    /** The enable request was refused because requirements are unmet. */
    data class Refused(val pluginId: String, val reason: String) : PluginEnableResult()
}

/**
 * Result of resolving a valid enable-order for the dependency graph
 * (topological sort — dependencies sort before dependents).
 */
sealed class PluginOrderResolution {
    /** Ids in a valid enable order (dependencies first). */
    data class Success(val order: List<String>) : PluginOrderResolution()

    /** A dependency cycle exists; no valid order exists for the listed plugins. */
    data class Cyclic(val pluginIds: List<String>) : PluginOrderResolution()
}