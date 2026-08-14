package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginLogger
import java.io.File

/**
 * THE Phase-24 update orchestrator — fills the Phase-22 `PluginRuntime` update
 * and rollback seams (`docs/plugin-architecture.md` § State machine + Update
 * model). PURE JVM (the download runs through a [DownloadTransport]), so the
 * full verified-update lifecycle is unit-tested with fake transports and signed
 * test artifacts.
 *
 * ## Verified update install
 *
 * ```
 * approve (MANDATORY) → download new artifact → re-verify (sha256 + pinned
 * cert) → load smoke-test → keep previous files → atomic swap (new version
 * becomes the active persisted entry + artifact).
 * ```
 *
 * - **Approval gate.** [update] returns [RuntimeOutcome.Failed] unless
 *   [userApproved] is true. An update is NEVER applied silently; the store's
 *   approval dialog is the only path to `userApproved = true`.
 * - **No downgrade.** [target] must be strictly newer than the installed [entry]
 *   ([PluginVersion.isNewerThan]); an equal or older target is refused.
 * - **Verify before ANY load.** The freshly downloaded artifact must pass
 *   [ArtifactSignatureVerifier] (SHA-256 + pinned signing cert) BEFORE it is
 *   touch-loaded, and [update] additionally runs a **load smoke-test**
 *   ([RuntimePluginLoader]) so a signed-but-broken artifact is caught before it
 *   becomes active.
 * - **Keep-previous-until-verified + atomic swap.** The current version's files
 *   and entry are left intact until the new artifact is fully verified and
 *   smoke-tested; only then is the new entry persisted (the "swap"). The old
 *   artifact stays on disk as the rollback source. On ANY failure (download,
 *   hash, signature, load) the new artifact is deleted, the persisted entry is
 *   never touched, and [update] returns a clear "the previous version is still
 *   active" failure.
 * - **Rollback root.** The previously-active [PluginEntry] is recorded in
 *   [updateStore] before the download starts, so [rollback] can restore it even
 *   after a failed attempt or a mid-update process death.
 *
 * [rollback] restores the recorded previous version: re-verifies its artifact,
 * re-runs the load smoke-test, then makes it the active entry and removes the
 * (failed) new artifact. It can only go to an OLDER version by design — that is
 * the sanctioned exception to the no-downgrade rule (restoring a previously
 * verified version, never accepting a manifest/offer downgrade).
 *
 * Never logs artifact contents or any secret material.
 *
 * @param downloader fetches the new artifact (HTTPS + guards).
 * @param storageDir the app-private directory holding plugin artifacts.
 * @param artifactResolver resolves the on-disk artifact for an entry.
 * @param entryStore the persisted catalog (the ACTIVE entry lives here).
 * @param updateStore the previous-version record (rollback root).
 * @param verifier the security gate ([ArtifactSignatureVerifier]).
 * @param loader materializes + smoke-tests a verified artifact.
 * @param logger ids/names + exception class names only.
 */
class PluginUpdateEngine(
    private val downloader: PluginDownloader,
    private val storageDir: File,
    private val artifactResolver: PluginArtifactResolver,
    private val entryStore: PluginEntryStore,
    private val updateStore: PluginUpdateStore,
    private val verifier: ArtifactSignatureVerifier,
    private val loader: RuntimePluginLoader,
    private val logger: PluginLogger = PluginLogger.NoOp
) {

    /**
     * Perform a user-approved, verified update of the installed [entry] to the
     * manifest-provided [target]. Returns the successful swap, or a clear
     * failure that keeps the previous version active. Never throws.
     */
    suspend fun update(
        entry: PluginEntry,
        target: PluginEntry,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): RuntimeOutcome<PluginUpdateResult> {
        if (!userApproved) {
            return RuntimeOutcome.Failed(
                "Update of '${entry.id}' was refused: this update was not approved. Every plugin update requires explicit user approval."
            )
        }
        if (entry.source != PluginEntrySource.REMOTE || target.source != PluginEntrySource.REMOTE) {
            return RuntimeOutcome.Failed(
                "Update of '${entry.id}' was refused: only downloadable (remote) plugins are updated by this mechanism; built-ins are managed by app update."
            )
        }
        val validation = target.validationErrors()
        if (validation.isNotEmpty()) {
            return RuntimeOutcome.Failed(
                "Update of '${entry.id}' was refused — the manifest target is invalid: ${validation.joinToString("; ")}"
            )
        }
        if (!target.version.isNewerThan(entry.version)) {
            return RuntimeOutcome.Failed(
                "Update of '${entry.id}' to ${target.version} was refused: it is not newer than the installed ${entry.version} (no downgrades, no no-op updates)."
            )
        }
        // Record the previously-active version BEFORE any byte moves, so a
        // failure (or process death) always leaves a rollback root.
        updateStore.savePrevious(entry)

        onProgress(0f)
        // 1) Download the new artifact (HTTPS only; the downloader re-checks).
        val artifactFile = when (val download = downloader.download(
            entry = target,
            targetDir = storageDir,
            userConsented = true, // the approval gate above already passed
            onProgress = onProgress
        )) {
            is PluginDownloader.DownloadOutcome.Success -> {
                onProgress(0.55f)
                download.file
            }
            is PluginDownloader.DownloadOutcome.Failed -> return failedUpdateKeepsPrevious(
                entry = entry,
                target = target,
                reason = download.message
            )
        }

        // 2) Re-verify the new artifact: sha256 + pinned signing cert (the
        //    Phase-23 gate is RE-RUN for every update — an offer is never trusted).
        onProgress(0.7f)
        when (val verification = verifier.verify(artifactFile, target.sha256.orEmpty(), target.pinnedCertHash.orEmpty())) {
            is ArtifactSignatureVerifier.Result.Verified -> Unit
            is ArtifactSignatureVerifier.Result.Invalid -> {
                cleanupNewArtifact(artifactFile, target)
                return failedUpdateKeepsPrevious(
                    entry = entry,
                    target = target,
                    reason = "update artifact failed signature verification: ${verification.reason}"
                )
            }
        }

        // 3) Load smoke-test: a signed-but-broken artifact must never become active.
        onProgress(0.85f)
        val artifactPath = artifactFile.canonicalPath
        when (val smokeTest = loader.load(target, artifactPath)) {
            is RuntimeOutcome.Success -> Unit
            is RuntimeOutcome.Failed -> {
                cleanupNewArtifact(artifactFile, target)
                return failedUpdateKeepsPrevious(
                    entry = entry,
                    target = target,
                    reason = "update artifact passed verification but failed its load smoke-test: ${smokeTest.message}"
                )
            }
            is RuntimeOutcome.NotYetImplemented -> {
                cleanupNewArtifact(artifactFile, target)
                return failedUpdateKeepsPrevious(
                    entry = entry,
                    target = target,
                    reason = "the runtime cannot smoke-test ${target.version} yet (${smokeTest.message})"
                )
            }
        }

        // 4) Atomic swap: the new entry becomes active; the previous version's
        //    files stay on disk as the rollback source. Stale older artifacts
        //    are removed (they can no longer be rolled back to).
        val previousVersion = updateStore.previousFor(entry.id)
        try {
            entryStore.save(target)
        } catch (e: Throwable) {
            cleanupNewArtifact(artifactFile, target)
            logger.error(entry.id, target.name, "update swap persist threw ${e::class.java.simpleName}")
            return failedUpdateKeepsPrevious(
                entry = entry,
                target = target,
                reason = "could not persist ${target.version} as the active version"
            )
        }
        cleanupStaleArtifacts(target, previousVersion)
        onProgress(1f)
        logger.lifecycle("remote-update", target.id, target.name)
        return RuntimeOutcome.Success(
            PluginUpdateResult(entry = target, fromVersion = entry.version, toVersion = target.version)
        )
    }

    /**
     * Restore the previously-active verified version of [entry]. Never applies
     * a manifest/offer downgrade — it restores the recorded PREVIOUS version
     * (the sanctioned exception to the no-downgrade rule). Never throws.
     */
    suspend fun rollback(entry: PluginEntry): RuntimeOutcome<PluginRollbackResult> {
        val previous = updateStore.previousFor(entry.id)
            ?: return RuntimeOutcome.Failed(
                "No previous version of '${entry.id}' is recorded — nothing to roll back to."
            )
        if (previous.version >= entry.version) {
            // previous == active (e.g. after an update that failed before its
            // swap): the previous version IS the active one — nothing to undo.
            return RuntimeOutcome.Success(
                PluginRollbackResult(entry = previous, restoredVersion = previous.version)
            )
        }
        val previousArtifact = artifactResolver.artifactFor(previous)
            ?: return RuntimeOutcome.Failed(
                "The previous version v${previous.version} of '${entry.id}' is recorded, but its artifact is no longer on device."
            )
        when (val verification = verifier.verify(
            previousArtifact,
            previous.sha256.orEmpty(),
            previous.pinnedCertHash.orEmpty()
        )) {
            is ArtifactSignatureVerifier.Result.Verified -> Unit
            is ArtifactSignatureVerifier.Result.Invalid -> return RuntimeOutcome.Failed(
                "Rollback of '${entry.id}' refused: the previous artifact no longer passes verification (${verification.reason})."
            )
        }
        when (val smokeTest = loader.load(previous, previousArtifact.canonicalPath)) {
            is RuntimeOutcome.Success -> Unit
            is RuntimeOutcome.Failed -> return RuntimeOutcome.Failed(
                "Rollback of '${entry.id}' refused: the previous version failed its load smoke-test (${smokeTest.message})."
            )
            is RuntimeOutcome.NotYetImplemented -> return RuntimeOutcome.Failed(
                "Rollback of '${entry.id}' cannot be completed yet (${smokeTest.message})."
            )
        }
        try {
            entryStore.save(previous)
        } catch (e: Throwable) {
            logger.error(entry.id, previous.name, "rollback persist threw ${e::class.java.simpleName}")
            return RuntimeOutcome.Failed("Could not persist v${previous.version} as the active version.")
        }
        // The failed/new version's artifact is no longer wanted.
        File(storageDir, PluginDownloader.artifactFileNameFor(entry)).let { orphan ->
            if (orphan.name != PluginDownloader.artifactFileNameFor(previous)) orphan.delete()
        }
        updateStore.clearPrevious(entry.id)
        logger.lifecycle("remote-rollback", entry.id, previous.name)
        return RuntimeOutcome.Success(
            PluginRollbackResult(entry = previous, restoredVersion = previous.version)
        )
    }

    private fun failedUpdateKeepsPrevious(
        entry: PluginEntry,
        target: PluginEntry,
        reason: String
    ): RuntimeOutcome<Nothing> {
        logger.error(entry.id, target.name, "update failed (${reason.substringBefore('.')}); previous version kept")
        return RuntimeOutcome.Failed(
            "Update of '${entry.id}' to ${target.version} did not complete: $reason. " +
                "The previous verified version v${entry.version} is still active (nothing was replaced)."
        )
    }

    private fun cleanupNewArtifact(artifactFile: File, target: PluginEntry) {
        artifactFile.delete()
        File(storageDir, "${PluginDownloader.artifactFileNameFor(target)}.part").delete()
    }

    /** Remove this plugin's artifact files that are neither the new active version
     *  nor the recorded previous (they can no longer be a rollback source). The
     *  previous + new artifacts are kept so a later [rollback] always has bytes. */
    private fun cleanupStaleArtifacts(target: PluginEntry, previous: PluginEntry?) {
        val keep = mutableSetOf(PluginDownloader.artifactFileNameFor(target))
        previous?.let { keep += PluginDownloader.artifactFileNameFor(it) }
        val prefix = PluginDownloader.artifactFileNameFor(target).substringBeforeLast("-") + "-"
        storageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(prefix) && file.extension == "apk" && file.name !in keep) {
                file.delete()
            }
        }
    }
}