package com.authorss81.noteflow.plugins.runtime

import java.io.File

/**
 * Where the runtime finds the downloaded artifact for a [PluginEntry] (Phase 23).
 * Production: [com.authorss81.noteflow.services.PluginArtifactStorage] over
 * app-private files; tests inject a map/lambda-based fake. A plugin survives a
 * process restart because the artifact stays on disk with its persisted entry.
 */
fun interface PluginArtifactResolver {
    fun artifactFor(entry: PluginEntry): File?
}

/**
 * THE Phase-23 runtime — fills the Phase-22 `PluginRuntime` seams
 * (`docs/plugin-architecture.md` § Security model).
 *
 * - **[verify]** — REAL: [ArtifactSignatureVerifier] checks the artifact's
 *   SHA-256 against [PluginEntry.sha256] AND its signing certificate against
 *   the compile-time [PluginEntry.pinnedCertHash]. ANY mismatch is a hard
 *   [RuntimeOutcome.Failed] — the artifact is never loaded, never partially
 *   executed.
 * - **[load]** — REAL: [RuntimePluginLoader] re-verifies integrity on EVERY
 *   load (a bit-rotted or replaced file is refused), then materializes the
 *   verified artifact through a plugin [ClassLoader] that implements
 *   [NoteflowPlugin] and receives a capability-aware [PluginContext].
 * - **[update]/[rollback]** — still honest Phase-24 stubs; the previous
 *   version is never discarded here.
 *
 * Register through [PluginRuntimeRegistry] at startup; the store/registry/UI
 * already speak [PluginEntry] and need no other change.
 *
 * @param artifactResolver finds the on-disk artifact for an entry.
 * @param classLoaderFactory the plugin [ClassLoader] (DexClassLoader in prod).
 * @param contextFactory the capability facade factory (capability-aware in prod).
 * @param parentClassLoader parent for the plugin loader (the app classloader).
 * @param verifier the security gate (default [ArtifactSignatureVerifier]).
 */
class SignatureVerifiedPluginRuntime(
    private val artifactResolver: PluginArtifactResolver,
    private val classLoaderFactory: ClassLoaderFactory,
    private val contextFactory: PluginContextFactory = PluginContextFactory.DEFAULT,
    private val parentClassLoader: ClassLoader = SignatureVerifiedPluginRuntime::class.java.classLoader,
    private val verifier: ArtifactSignatureVerifier = ArtifactSignatureVerifier()
) : PluginRuntime {

    private val loader = RuntimePluginLoader(classLoaderFactory, contextFactory, parentClassLoader)

    override fun verify(artifact: PluginArtifact): RuntimeOutcome<PluginVerification> {
        val file = File(artifact.artifactPath)
        if (!file.isFile) {
            return RuntimeOutcome.Failed(
                "artifact for '${artifact.entry.id}' was not found on device — download it again."
            )
        }
        return when (val result = verifier.verify(file, artifact.expectedSha256, artifact.expectedPinnedCertHash)) {
            is ArtifactSignatureVerifier.Result.Verified -> RuntimeOutcome.Success(
                PluginVerification(
                    entry = artifact.entry,
                    artifactPath = artifact.artifactPath,
                    verifiedAtMillis = System.currentTimeMillis(),
                    sha256 = result.sha256Hex,
                    pinnedCertHash = result.signingCertHash
                )
            )
            is ArtifactSignatureVerifier.Result.Invalid -> RuntimeOutcome.Failed(
                "plugin '${artifact.entry.id}' FAILED signature verification: ${result.reason}"
            )
        }
    }

    override fun load(entry: PluginEntry): RuntimeOutcome<LoadedPlugin> {
        if (entry.source != PluginEntrySource.REMOTE) {
            return RuntimeOutcome.Failed(
                "plugin '${entry.id}' is bundled — bundled plugins are compiled in and never loaded dynamically."
            )
        }
        val artifactPath = artifactResolver.artifactFor(entry)?.canonicalPath
            ?: return RuntimeOutcome.Failed(
                "no downloaded artifact for '${entry.id}' is on device — download it from the plugin store first."
            )
        // Integrity re-check on EVERY load (the security model's hard rule).
        val recheck = verify(
            PluginArtifact(
                entry = entry,
                artifactPath = artifactPath,
                expectedSha256 = entry.sha256.orEmpty(),
                expectedPinnedCertHash = entry.pinnedCertHash.orEmpty()
            )
        )
        when (recheck) {
            is RuntimeOutcome.Success -> Unit
            is RuntimeOutcome.Failed -> return recheck
            is RuntimeOutcome.NotYetImplemented -> return recheck
        }
        return loader.load(entry, artifactPath)
    }

    override fun update(entry: PluginEntry, newVersion: PluginVersion): RuntimeOutcome<PluginUpdateResult> =
        RuntimeOutcome.notYetImplemented(
            phase = 24,
            message = "update() for plugin '${entry.id}' -> $newVersion is not implemented yet — " +
                "user-approved updates land in Phase 24."
        )

    override fun rollback(entry: PluginEntry): RuntimeOutcome<PluginRollbackResult> =
        RuntimeOutcome.notYetImplemented(
            phase = 24,
            message = "rollback() for plugin '${entry.id}' is not implemented yet — " +
                "the keep-previous-until-verified rollback path lands in Phase 24."
        )
}