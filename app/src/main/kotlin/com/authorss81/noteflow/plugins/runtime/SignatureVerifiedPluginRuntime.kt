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
 *   [PluginEntry.pinnedCertHash]. For a freshly-updated entry those fields came
 *   from the hosted manifest, which itself reached the device ONLY through the
 *   compile-time-pinned [HttpsManifestTransport] (B1-CRYPTO-01) — so the
 *   re-verification here always runs against publisher-committed values, never
 *   attacker-supplied ones. ANY mismatch is a hard [RuntimeOutcome.Failed] —
 *   the artifact is never loaded, never partially executed.
 * - **[load]** — REAL: [RuntimePluginLoader] re-verifies integrity on EVERY
 *   load (a bit-rotted or replaced file is refused), then materializes the
 *   verified artifact through a plugin [ClassLoader] that implements
 *   [NoteflowPlugin] and receives a capability-aware [PluginContext].
 * - **[update]** — REAL (Phase 24): delegated to an injected [PluginUpdateEngine].
 *   A user-approved update downloads the new artifact, re-verifies pinned cert +
 *   SHA-256, runs a load smoke-test, keeps the previous version for rollback,
 *   then atomically swaps. Any failure leaves the previous version active.
 * - **[rollback]** — REAL (Phase 24): restores the recorded previous verified
 *   version through the same engine.
 *
 * Register through [PluginRuntimeRegistry] at startup; the store/registry/UI
 * already speak [PluginEntry] and need no other change.
 *
 * @param artifactResolver finds the on-disk artifact for an entry.
 * @param classLoaderFactory the plugin [ClassLoader] (DexClassLoader in prod).
 * @param contextFactory the capability facade factory (capability-aware in prod).
 * @param parentClassLoader parent for the plugin loader (the app classloader).
 * @param verifier the security gate (default [ArtifactSignatureVerifier]).
 * @param updateEngine the Phase-24 update/rollback orchestrator. When null the
 *   runtime is read-only: update/rollback answer an honest failure and the
 *   previous version is untouched.
 */
class SignatureVerifiedPluginRuntime(
    private val artifactResolver: PluginArtifactResolver,
    private val classLoaderFactory: ClassLoaderFactory,
    private val contextFactory: PluginContextFactory = PluginContextFactory.DEFAULT,
    private val parentClassLoader: ClassLoader = SignatureVerifiedPluginRuntime::class.java.classLoader,
    private val verifier: ArtifactSignatureVerifier = ArtifactSignatureVerifier(),
    private val updateEngine: PluginUpdateEngine? = null
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

    override suspend fun update(
        entry: PluginEntry,
        target: PluginEntry,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): RuntimeOutcome<PluginUpdateResult> {
        val engine = updateEngine ?: return RuntimeOutcome.Failed(
            "update() for plugin '${entry.id}' cannot run — this runtime was built without an update engine."
        )
        return engine.update(entry, target, userApproved, onProgress)
    }

    override suspend fun rollback(entry: PluginEntry): RuntimeOutcome<PluginRollbackResult> {
        val engine = updateEngine ?: return RuntimeOutcome.Failed(
            "rollback() for plugin '${entry.id}' cannot run — this runtime was built without an update engine."
        )
        return engine.rollback(entry)
    }

    /**
     * Build the Phase-24 [PluginUpdateEngine] for a production runtime. Uses the
     * SAME class-loader factory/context factory/parent as [this] runtime, so a
     * verified artifact resolves identically whether it is loaded by [load] or
     * smoke-tested during an update. The engine's downloader/storage/entry
     * store are supplied by the caller (pure-JVM injectable).
     */
    fun buildUpdateEngine(
        downloader: PluginDownloader,
        storageDir: java.io.File,
        entryStore: PluginEntryStore,
        updateStore: PluginUpdateStore,
        logger: com.authorss81.noteflow.plugins.PluginLogger = com.authorss81.noteflow.plugins.PluginLogger.NoOp
    ): PluginUpdateEngine = PluginUpdateEngine(
        downloader = downloader,
        storageDir = storageDir,
        artifactResolver = artifactResolver,
        entryStore = entryStore,
        updateStore = updateStore,
        verifier = verifier,
        loader = loader,
        logger = logger
    )
}