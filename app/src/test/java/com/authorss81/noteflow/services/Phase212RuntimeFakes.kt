package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.runtime.LoadedPlugin
import com.authorss81.noteflow.plugins.runtime.PluginArtifact
import com.authorss81.noteflow.plugins.runtime.PluginEntry
import com.authorss81.noteflow.plugins.runtime.PluginEntrySource
import com.authorss81.noteflow.plugins.runtime.PluginRuntime
import com.authorss81.noteflow.plugins.runtime.PluginUpdateResult
import com.authorss81.noteflow.plugins.runtime.PluginVerification
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import java.lang.reflect.Proxy
import java.io.File

/**
 * Phase 212 shared fakes for the downloadable-plugin runtime seams.
 */
internal object Phase212Fakes {

    fun remoteEntry(
        id: String = "com.example.remote.plugin",
        version: PluginVersion = PluginVersion(1, 0, 0),
        https: Boolean = true,
        sha256: String? = "c".repeat(64),
        pinnedCertHash: String? = "sha256/CCCC"
    ): PluginEntry = PluginEntry(
        id = id,
        name = "Remote Plugin",
        description = "A downloadable plugin under test",
        version = version,
        capabilities = setOf(PluginCapability.OCR),
        category = "Vision",
        downloadUrl = if (https) "https://plugins.example.com/$id-$version.apk"
        else "http://plugins.example.com/$id-$version.apk",
        sha256 = sha256,
        pinnedCertHash = pinnedCertHash,
        source = PluginEntrySource.REMOTE
    )

    /** A [PluginContext] proxy (the facade is deny-by-default; tests never call it). */
    @Suppress("UNCHECKED_CAST")
    fun pluginContext(): com.authorss81.noteflow.plugins.runtime.PluginContext =
        Proxy.newProxyInstance(
            Phase212Fakes::class.java.classLoader,
            arrayOf(com.authorss81.noteflow.plugins.runtime.PluginContext::class.java)
        ) { _, _, _ -> throw IllegalStateException("facade must not be called in these tests") }
            as com.authorss81.noteflow.plugins.runtime.PluginContext

    fun loadedPlugin(entry: PluginEntry): LoadedPlugin {
        val plugin = object : NoteflowPlugin {
            override val manifest = com.authorss81.noteflow.plugins.PluginManifest(
                id = entry.id,
                name = entry.name,
                version = com.authorss81.noteflow.plugins.SemanticVersion(
                    entry.version.major, entry.version.minor, entry.version.patch
                ),
                minSupportedApi = 26,
                description = entry.description,
                capabilities = setOf(PluginCapability.TextTransform)
            )

            override fun availability(context: android.content.Context?) =
                com.authorss81.noteflow.plugins.PluginAvailability.Ok

            override fun onEnable(context: android.content.Context?, settings: com.authorss81.noteflow.plugins.PluginSettings) {}
        }
        return LoadedPlugin(entry, plugin, pluginContext())
    }
}

/** Scriptable [PluginRuntime] recording every call. */
internal class FakePluginRuntime(
    var verifyOutcome: RuntimeOutcome<PluginVerification> =
        RuntimeOutcome.Success(
            PluginVerification(Phase212Fakes.remoteEntry(), "/unused", 0L, "c".repeat(64), "sha256/CCCC")
        ),
    var loadOutcome: RuntimeOutcome<LoadedPlugin> =
        RuntimeOutcome.Success(Phase212Fakes.loadedPlugin(Phase212Fakes.remoteEntry()))
) : PluginRuntime {

    val verifiedArtifacts = mutableListOf<PluginArtifact>()
    val loadedEntries = mutableListOf<PluginEntry>()
    val updateCalls = mutableListOf<Triple<PluginEntry, PluginEntry, Boolean>>()
    var updateOutcome: RuntimeOutcome<PluginUpdateResult> =
        RuntimeOutcome.Failed("update not scripted")
    val rollbackEntries = mutableListOf<String>()

    override fun verify(artifact: PluginArtifact): RuntimeOutcome<PluginVerification> {
        verifiedArtifacts.add(artifact)
        return verifyOutcome
    }

    override fun load(entry: PluginEntry): RuntimeOutcome<LoadedPlugin> {
        loadedEntries.add(entry)
        return loadOutcome
    }

    override suspend fun update(
        entry: PluginEntry,
        target: PluginEntry,
        userApproved: Boolean,
        onProgress: (Float) -> Unit
    ): RuntimeOutcome<PluginUpdateResult> {
        updateCalls.add(Triple(entry, target, userApproved))
        onProgress(0.5f)
        return updateOutcome
    }

    override suspend fun rollback(
        entry: PluginEntry
    ): RuntimeOutcome<com.authorss81.noteflow.plugins.runtime.PluginRollbackResult> {
        rollbackEntries.add(entry.id)
        return RuntimeOutcome.Failed("rollback not used in phase-212 tests")
    }
}

/** A [com.authorss81.noteflow.plugins.runtime.DownloadTransport] that writes bytes then completes. */
internal class WritingFakeTransport(private val payload: ByteArray = "apk-bytes".toByteArray()) :
    com.authorss81.noteflow.plugins.runtime.DownloadTransport {
    val requestedUrls = mutableListOf<String>()
    var failInstead = false

    override suspend fun download(
        request: com.authorss81.noteflow.plugins.runtime.DownloadRequest
    ): com.authorss81.noteflow.plugins.runtime.DownloadTransportResult {
        requestedUrls.add(request.url)
        return if (failInstead) {
            com.authorss81.noteflow.plugins.runtime.DownloadTransportResult.Failed("network unreachable")
        } else {
            request.target.parentFile?.mkdirs()
            request.target.writeBytes(payload)
            com.authorss81.noteflow.plugins.runtime.DownloadTransportResult.Completed(payload.size.toLong())
        }
    }
}

/** Asserts no partial install residue exists for [id]. */
internal fun assertNoResidue(
    entryStore: com.authorss81.noteflow.plugins.runtime.PluginEntryStore,
    storageDir: File,
    id: String
) {
    check(entryStore.find(id) == null) { "persisted entry must be removed after failure" }
    val files = storageDir.listFiles()?.map { it.name }.orEmpty()
    check(files.none { it.contains(id.substringAfterLast('.')) || it.endsWith(".part") }) {
        "artifact/part files must be cleaned after failure, found: $files"
    }
}
