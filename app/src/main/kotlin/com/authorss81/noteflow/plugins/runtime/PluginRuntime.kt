package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.NoteflowPlugin

/**
 * A downloaded (or previously-verified) plugin artifact awaiting verification
 * or load. Phases 23/24 fill in the actual file + digests; the type is fixed
 * here so those phases do not redesign the seam.
 *
 * @param entry the unified catalog entry describing WHAT was requested
 *   (downloadUrl, expected [PluginEntry.sha256] / [PluginEntry.pinnedCertHash]).
 * @param artifactPath absolute path of the downloaded artifact on disk.
 * @param expectedSha256 the exact SHA-256 the artifact must match.
 * @param expectedPinnedCertHash the pinned certificate hash the TLS session
 *   must match before the download is even trusted.
 */
data class PluginArtifact(
    val entry: PluginEntry,
    val artifactPath: String,
    val expectedSha256: String,
    val expectedPinnedCertHash: String
)

/**
 * Proof that an artifact was verified (Phase 23 fills the real implementation).
 * Deliberately a tiny immutable record — the runtime keeps the evidence, the
 * host keeps the trust boundary.
 */
data class PluginVerification(
    val entry: PluginEntry,
    val artifactPath: String,
    val verifiedAtMillis: Long,
    val sha256: String,
    val pinnedCertHash: String
)

/** Outcome of a successful update (Phase 24 fills the real implementation). */
data class PluginUpdateResult(
    val entry: PluginEntry,
    val fromVersion: PluginVersion,
    val toVersion: PluginVersion
)

/** Outcome of a successful rollback to the last-good version (Phase 24). */
data class PluginRollbackResult(
    val entry: PluginEntry,
    val restoredVersion: PluginVersion
)

/**
 * Typed outcome of a [PluginRuntime] operation. Never throws.
 *
 * - [Success] — the operation completed; [value] carries the evidence.
 * - [NotYetImplemented] — the HONEST stub: this phase deliberately did not
 *   implement the operation; [phase] names the phase that will ([23] for
 *   download/verify/load, [24] for update/rollback) and [message] says what.
 *   Nothing pretends to work.
 * - [Failed] — a real, user-facing failure.
 */
sealed class RuntimeOutcome<out T> {
    data class Success<T>(val value: T) : RuntimeOutcome<T>()
    data class NotYetImplemented(val phase: Int, val message: String) : RuntimeOutcome<Nothing>()
    data class Failed(val message: String) : RuntimeOutcome<Nothing>()

    companion object {
        /** Convenience: the operation is a Phase-22 stub and did not run. */
        fun <T> notYetImplemented(phase: Int, message: String): RuntimeOutcome<T> =
            NotYetImplemented(phase, message)
    }
}

/**
 * THE runtime seam of the hybrid plugin architecture (Phase 22 — see
 * `docs/plugin-architecture.md`).
 *
 * This interface is the ONLY contract the rest of the app holds against the
 * downloadable-plugin machinery. Phases 23/24 register a real implementation
 * through [PluginRuntimeRegistry]; nothing else changes — the store, the
 * registry and the UI already speak [PluginEntry].
 *
 * Trust rules the interface bakes in (the implementation MUST honour them):
 * - **Verify BEFORE load.** [verify] must reject tampered artifacts
 *   (sha256 mismatch, pinned-cert mismatch) with [RuntimeOutcome.Failed] — an
 *   artifact is never loaded unverified.
 * - **Re-verify on EVERY load.** [load] re-checks integrity even if the artifact
 *   was verified before; a bit-rotted or tampered file is refused.
 * - **Update keeps rollback.** [update] must not discard the previous version
 *   until the new one is verified; [rollback] restores it.
 */
interface PluginRuntime {

    /**
     * Verify [artifact] against its expected sha256 + pinned certificate hash.
     * Returns a [PluginVerification] on success, [RuntimeOutcome.Failed] on
     * tamper/mismatch. **Any** mismatch is a hard failure — never a partial
     * load. Implemented in Phase 23.
     */
    fun verify(artifact: PluginArtifact): RuntimeOutcome<PluginVerification>

    /**
     * Materialize [entry] into a runnable [NoteflowPlugin] (via `DexClassLoader`
     * for remote entries; direct instance for bundled ones). Re-verifies
     * integrity before returning. Implemented in Phase 23.
     */
    fun load(entry: PluginEntry): RuntimeOutcome<LoadedPlugin>

    /**
     * Apply an update to [entry] at [newVersion]: download, re-verify, keep the
     * previous version for rollback. Implemented in Phase 24.
     */
    fun update(entry: PluginEntry, newVersion: PluginVersion): RuntimeOutcome<PluginUpdateResult>

    /**
     * Restore the previous verified version of [entry]. Implemented in Phase 24.
     */
    fun rollback(entry: PluginEntry): RuntimeOutcome<PluginRollbackResult>
}

/** A [NoteflowPlugin] the runtime materialized + the context it must use. */
data class LoadedPlugin(
    val entry: PluginEntry,
    val plugin: NoteflowPlugin,
    /** The capability facade the plugin may call (deny-by-default in Phase 22). */
    val context: PluginContext
)

/**
 * HONEST Phase-22 stub: every operation answers [RuntimeOutcome.NotYetImplemented]
 * with the phase that will implement it. It never fabricates a "verified" or
 * "loaded" result — pretending the runtime works would be a fake feature.
 */
class NotYetImplementedPluginRuntime : PluginRuntime {

    override fun verify(artifact: PluginArtifact): RuntimeOutcome<PluginVerification> =
        RuntimeOutcome.notYetImplemented(
            phase = PHASE_DOWNLOAD_VERIFY,
            message = "verify() for plugin '${artifact.entry.id}' is not implemented yet — " +
                "pinned-cert-hash + sha256 verification of downloadable artifacts lands in Phase 23."
        )

    override fun load(entry: PluginEntry): RuntimeOutcome<LoadedPlugin> =
        RuntimeOutcome.notYetImplemented(
            phase = PHASE_DOWNLOAD_VERIFY,
            message = "load() for plugin '${entry.id}' is not implemented yet — " +
                "DexClassLoader materialization + capability-facade wiring land in Phase 23."
        )

    override fun update(entry: PluginEntry, newVersion: PluginVersion): RuntimeOutcome<PluginUpdateResult> =
        RuntimeOutcome.notYetImplemented(
            phase = PHASE_UPDATES,
            message = "update() for plugin '${entry.id}' -> $newVersion is not implemented yet — " +
                "user-approved updates land in Phase 24."
        )

    override fun rollback(entry: PluginEntry): RuntimeOutcome<PluginRollbackResult> =
        RuntimeOutcome.notYetImplemented(
            phase = PHASE_UPDATES,
            message = "rollback() for plugin '${entry.id}' is not implemented yet — " +
                "the keep-previous-until-verified rollback path lands in Phase 24."
        )

    private companion object {
        const val PHASE_DOWNLOAD_VERIFY = 23
        const val PHASE_UPDATES = 24
    }
}

/**
 * Where the app registers + reads the ACTIVE [PluginRuntime].
 *
 * Phase 22 registers the honest stub. Phases 23/24 swap in their real
 * implementation here WITHOUT touching the store/registry/UI — that is the
 * point of the seam.
 */
object PluginRuntimeRegistry {

    @Volatile
    private var runtime: PluginRuntime = NotYetImplementedPluginRuntime()

    /** Register the active runtime (Phase 23/24 call this once at startup). */
    fun register(runtime: PluginRuntime) {
        this.runtime = runtime
    }

    /** The currently active runtime (the honest stub until a phase registers its own). */
    fun current(): PluginRuntime = runtime
}
