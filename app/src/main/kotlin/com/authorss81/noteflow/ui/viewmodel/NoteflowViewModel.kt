package com.authorss81.noteflow.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.authorss81.noteflow.data.db.NoteflowDatabase
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.data.repository.NoteRepository
import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.ClipParseOutcome
import com.authorss81.noteflow.plugins.ClipSharePlugin
import com.authorss81.noteflow.plugins.ShapeFromInkOutcome
import com.authorss81.noteflow.plugins.ShapeFromInkPlugin
import com.authorss81.noteflow.plugins.AssistantOutcome
import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.DictationPlugin
import com.authorss81.noteflow.plugins.DiffHunk
import com.authorss81.noteflow.plugins.ExportFormat
import com.authorss81.noteflow.plugins.ExportOutcome
import com.authorss81.noteflow.plugins.ExportPlugin
import com.authorss81.noteflow.plugins.ExportRequest
import com.authorss81.noteflow.plugins.LanguageDetectionOutcome
import com.authorss81.noteflow.plugins.LanguageDetectionPlugin
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.OcrPlugin
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginDiagnostics
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.CitationOutcome
import com.authorss81.noteflow.plugins.CitationPlugin
import com.authorss81.noteflow.plugins.DictionaryOutcome
import com.authorss81.noteflow.plugins.DictionaryPlugin
import com.authorss81.noteflow.plugins.OutlineGeneratorPlugin
import com.authorss81.noteflow.plugins.OutlineOutcome
import com.authorss81.noteflow.plugins.OutlineStyle
import com.authorss81.noteflow.plugins.UnitConversionOutcome
import com.authorss81.noteflow.plugins.UnitConverterPlugin
import com.authorss81.noteflow.plugins.WebCaptureOutcome
import com.authorss81.noteflow.plugins.WebCapturePlugin
import com.authorss81.noteflow.plugins.WebSearchOutcome
import com.authorss81.noteflow.plugins.WebSearchPlugin
import com.authorss81.noteflow.plugins.WeatherOutcome
import com.authorss81.noteflow.plugins.WeatherPlugin
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.plugins.ReadAloudOutcome
import com.authorss81.noteflow.plugins.ReadAloudPlugin
import com.authorss81.noteflow.plugins.ScreenshotCaptureOutcome
import com.authorss81.noteflow.plugins.ScreenshotNotePlugin
import com.authorss81.noteflow.plugins.SharedInput
import com.authorss81.noteflow.plugins.TextAnalysis
import com.authorss81.noteflow.plugins.TextToolsPlugin
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.TranslationLanguage
import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.authorss81.noteflow.plugins.TranslationPlugin
import com.authorss81.noteflow.plugins.TtsChunk
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.SecurityService
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.services.SettingsPluginEnableStore
import com.authorss81.noteflow.services.SettingsPluginInstallStore
import com.authorss81.noteflow.services.SettingsPluginSettingsStore
import com.authorss81.noteflow.plugins.runtime.PluginRuntime
import com.authorss81.noteflow.plugins.runtime.PluginRuntimeRegistry
import com.authorss81.noteflow.plugins.runtime.RuntimeOutcome
import com.authorss81.noteflow.plugins.runtime.SignatureVerifiedPluginRuntime
import com.authorss81.noteflow.plugins.runtime.HttpsPluginDownloadTransport
import com.authorss81.noteflow.plugins.runtime.PluginDownloader
import com.authorss81.noteflow.plugins.runtime.PluginContextFactory
import com.authorss81.noteflow.plugins.runtime.PluginManifestFetcher
import com.authorss81.noteflow.plugins.runtime.HttpsManifestTransport
import com.authorss81.noteflow.plugins.runtime.PluginUpdateEngine
import com.authorss81.noteflow.plugins.runtime.PluginUpdateInfo
import com.authorss81.noteflow.plugins.runtime.RuntimePluginLoader
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.services.AppClassLoaderFactory
import com.authorss81.noteflow.services.AppFacadeHost
import com.authorss81.noteflow.services.DownloadablePluginInstaller
import com.authorss81.noteflow.services.DownloadablePluginUpdater
import com.authorss81.noteflow.services.PluginArtifactStorage
import com.authorss81.noteflow.services.SettingsPluginEntryStore
import com.authorss81.noteflow.services.SettingsPluginUpdateStore
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.services.graph.CommandPaletteMath
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.ui.components.WorkspaceTemplate
import com.authorss81.noteflow.plugins.AndroidPluginLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NoteflowViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Application get() = getApplication()

    val settings = SettingsManager(appContext)
    val security = SecurityService(appContext)
    private val db by lazy { NoteflowDatabase.getDatabase(appContext) }
    val repository by lazy { NoteRepository(db) }

    // Phase 10/11: plugin framework (see docs/PLUGINS.md + docs/PLUGIN_SDK.md).
    // Core registry/manager are dependency-free; persist opt-in + per-plugin
    // settings via SettingsManager; log lifecycle events to logcat (ids/names
    // and exception classes only — never content).
    private val pluginEnableStore = SettingsPluginEnableStore(settings)
    private val pluginSettingsStore = SettingsPluginSettingsStore(settings)
    private val pluginInstallStore = SettingsPluginInstallStore(settings)
    val pluginRegistry = PluginRegistry(
        pluginEnableStore,
        pluginSettingsStore,
        installStore = pluginInstallStore,
        // Phase 21: the store's OPTIONAL plugin. The factory is compiled in the
        // APK but NOT active until the user downloads it; an installed optional
        // plugin is re-materialized from this factory on process restart.
        optionalPluginFactories = listOf({ CaseChangePlugin() }),
        logger = AndroidPluginLogger()
    )
    val pluginManager = PluginManager(pluginRegistry, AndroidPluginLogger())
    val pluginDiagnostics = PluginDiagnostics(pluginRegistry, pluginManager)

    // Phase 21/22: plugin store — bundled catalog + install/uninstall lifecycle
    // over the unified PluginEntryStore (persists REMOTE/downloadable entries).
    private val pluginEntryStore = SettingsPluginEntryStore(settings)

    // Phase 23: the downloadable-plugin runtime. The artifact storage, HTTPS
    // transport, DexClassLoader factory, capability facade host and installer
    // are all wired here; PluginRuntimeRegistry is swapped from the honest
    // Phase-22 stub to the real SignatureVerifiedPluginRuntime. Phase 24 adds
    // the update engine (verified user-approved updates + rollback) and the
    // store coordinator that drives them.
    private val pluginArtifactStorage = PluginArtifactStorage(appContext)
    private val pluginDownloader = PluginDownloader(
        transport = HttpsPluginDownloadTransport(),
        freeSpace = { pluginArtifactStorage.freeBytes() },
        logger = AndroidPluginLogger()
    )
    private val pluginUpdateStore = SettingsPluginUpdateStore(settings)
    private val pluginClassLoaderFactory =
        AppClassLoaderFactory(pluginArtifactStorage.optimizedDir().absolutePath)
    private val pluginContextFactory = PluginContextFactory.capabilityAware(AppFacadeHost())
    private val pluginUpdateEngine: PluginUpdateEngine by lazy {
        PluginUpdateEngine(
            downloader = pluginDownloader,
            storageDir = pluginArtifactStorage.dir(),
            artifactResolver = pluginArtifactStorage,
            entryStore = pluginEntryStore,
            updateStore = pluginUpdateStore,
            verifier = com.authorss81.noteflow.plugins.runtime.ArtifactSignatureVerifier(),
            loader = RuntimePluginLoader(
                classLoaderFactory = pluginClassLoaderFactory,
                contextFactory = pluginContextFactory,
                parentClassLoader = appContext.classLoader
            ),
            logger = AndroidPluginLogger()
        )
    }
    val pluginRuntime: PluginRuntime by lazy {
        SignatureVerifiedPluginRuntime(
            artifactResolver = pluginArtifactStorage,
            classLoaderFactory = pluginClassLoaderFactory,
            contextFactory = pluginContextFactory,
            parentClassLoader = appContext.classLoader,
            updateEngine = pluginUpdateEngine,
        )
    }
    private val pluginRemoteInstaller = DownloadablePluginInstaller(
        settings = settings,
        registry = pluginRegistry,
        entryStore = pluginEntryStore,
        storage = pluginArtifactStorage,
        runtime = pluginRuntime,
        downloader = pluginDownloader,
        logger = AndroidPluginLogger()
    )
    private val pluginUpdateCoordinator = DownloadablePluginUpdater(
        registry = pluginRegistry,
        runtime = pluginRuntime,
        manifestFetcher = PluginManifestFetcher(HttpsManifestTransport()),
        logger = AndroidPluginLogger()
    )

    val pluginStoreCatalog = PluginStoreCatalog(pluginRegistry, pluginEntryStore)
    val pluginStoreController = PluginStoreController(
        pluginRegistry,
        pluginStoreCatalog,
        AndroidPluginLogger(),
        remoteInstaller = pluginRemoteInstaller,
        updateCoordinator = pluginUpdateCoordinator
    )

    // Declared ABOVE the first init {} block on purpose: init calls
    // refreshPluginStates(), which writes these MutableStateFlow backing
    // properties. If they were declared below the init block they would still
    // be null when init runs and ViewModel creation would NPE on cold start.
    private val _pluginEnabledIds = MutableStateFlow(pluginRegistry.allPlugins.associate { it.id to pluginRegistry.isEnabled(it.id) })
    val pluginEnabledIds: StateFlow<Map<String, Boolean>> = _pluginEnabledIds.asStateFlow()

    private val _pluginStates = MutableStateFlow<Map<String, PluginStateInfo>>(emptyMap())
    val pluginStates: StateFlow<Map<String, PluginStateInfo>> = _pluginStates.asStateFlow()

    private val _pluginDiagnostics = MutableStateFlow<List<PluginDiagnostics.Entry>>(emptyList())
    val pluginDiagnosticsEntries: StateFlow<List<PluginDiagnostics.Entry>> = _pluginDiagnostics.asStateFlow()

    // Phase 21: plugin store UI state (rows + per-plugin download progress/busy/messages).
    private val _storeRows = MutableStateFlow<List<PluginStoreController.StoreRow>>(emptyList())
    val storeRows: StateFlow<List<PluginStoreController.StoreRow>> = _storeRows.asStateFlow()

    init {
        // Phase 23: register the real downloadable-plugin runtime (the lazy
        // `pluginRuntime` above swaps the Phase-22 stub via the registry seam).
        PluginRuntimeRegistry.register(pluginRuntime)
        // Re-materialize downloadable plugins installed in a previous session:
        // the persisted entry + on-disk artifact are re-verified and re-loaded
        // into the registry BEFORE onProcessStart so opt-in hooks fire.
        pluginEntryStore.all().forEach { entry ->
            if (pluginRegistry.isInstalled(entry.id)) {
                when (val loaded = pluginRuntime.load(entry)) {
                    is RuntimeOutcome.Success ->
                        pluginRegistry.registerRemotePlugin(loaded.value.plugin, appContext)
                    is RuntimeOutcome.Failed -> {
                        // Artifact missing/corrupt: keep the plugin "installed"
                        // state honest by refusing silently; the store still
                        // lists it and Delete cleans up. Never log contents.
                    }
                    is RuntimeOutcome.NotYetImplemented -> Unit
                }
            }
        }
        // Fire onEnable once per process for plugins already enabled in a
        // previous session (see PluginRegistry.onProcessStart).
        pluginRegistry.onProcessStart(appContext)
        refreshPluginStates()
    }

    private val _storeProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val storeProgress: StateFlow<Map<String, Float>> = _storeProgress.asStateFlow()

    private val _storeBusy = MutableStateFlow<Set<String>>(emptySet())
    val storeBusy: StateFlow<Set<String>> = _storeBusy.asStateFlow()

    private val _storeMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val storeMessages: StateFlow<Map<String, String>> = _storeMessages.asStateFlow()

    // Phase 23: a pending remote-download consent request (set when the store
    // answers NeedsConsent; null when no dialog is pending).
    private val _pendingConsentPluginId = MutableStateFlow<String?>(null)
    val pendingConsentPluginId: StateFlow<String?> = _pendingConsentPluginId.asStateFlow()

    // Phase 24: plugin-update UI state. `storeUpdates` (pluginId → offered
    // update) is populated by "Check for updates"; the per-plugin busy/progress
    // flows show the verified-update install; `pendingUpdatePluginId` carries a
    // per-update approval dialog; `storeGeneralMessage` shows check results that
    // are not specific to one row ("up to date", fetch errors).
    private val _storeUpdates = MutableStateFlow<Map<String, PluginUpdateInfo>>(emptyMap())
    val storeUpdates: StateFlow<Map<String, PluginUpdateInfo>> = _storeUpdates.asStateFlow()

    private val _updateBusy = MutableStateFlow<Set<String>>(emptySet())
    val updateBusy: StateFlow<Set<String>> = _updateBusy.asStateFlow()

    private val _updateProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val updateProgress: StateFlow<Map<String, Float>> = _updateProgress.asStateFlow()

    private val _pendingUpdatePluginId = MutableStateFlow<String?>(null)
    val pendingUpdatePluginId: StateFlow<String?> = _pendingUpdatePluginId.asStateFlow()

    private val _storeGeneralMessage = MutableStateFlow<String?>(null)
    val storeGeneralMessage: StateFlow<String?> = _storeGeneralMessage.asStateFlow()

    /** Respond to the pending remote-download consent dialog. */
    fun respondStoreConsent(grant: Boolean) {
        val pluginId = _pendingConsentPluginId.value ?: return
        _pendingConsentPluginId.value = null
        if (!grant) return
        if (pluginStoreController.grantRemoteConsent(pluginId)) {
            storeDownload(pluginId)
        } else {
            _storeMessages.update { it + (pluginId to "Remote downloads are not available in this build.") }
        }
    }

    private fun refreshPluginStates() {
        _pluginEnabledIds.value = pluginRegistry.allPlugins.associate { it.id to pluginRegistry.isEnabled(it.id) }
        _pluginStates.value = pluginRegistry.resolve(appContext)
        _pluginDiagnostics.value = pluginDiagnostics.snapshot(appContext)
        _storeRows.value = pluginStoreController.rows(appContext)
    }

    /**
     * Toggle a plugin's opt-in. Enabling may be refused by the registry with a
     * clear reason (unmet dependency / conflict / invalid manifest); the result
     * is typed so the UI can surface the refusal instead of silently failing.
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean): PluginEnableResult {
        val result = pluginRegistry.setEnabled(pluginId, enabled, appContext)
        refreshPluginStates()
        return result
    }

    /** Run a plugin's self-check for the diagnostics "Test now" action. */
    fun testPlugin(pluginId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            pluginDiagnostics.testNow(pluginId, appContext)
            withContext(Dispatchers.Main) { refreshPluginStates() }
        }
    }

    // ---- Phase 21: plugin store actions --------------------------------------

    /**
     * Store "Download". Two honest paths:
     * - **Bundled** definitions are installed via [PluginStoreController] (offline,
     *   no APK loading; progress reported).
     * - **Remote** (downloadable) entries go through the same controller: the
     *   FIRST download requires explicit user consent (Phase 23) before any byte
     *   moves, then HTTPS download → pinned-cert + sha256 verification → load →
     *   registry install (REGISTERED — off by default). Progress is real.
     */
    fun storeDownload(pluginId: String) {
        if (pluginId in _storeBusy.value) return
        val storeEntry = pluginStoreCatalog.entryFor(pluginId)
        if (storeEntry != null && !storeEntry.bundled) {
            viewModelScope.launch {
                _storeBusy.update { it + pluginId }
                _storeProgress.update { it + (pluginId to 0f) }
                _storeMessages.update { it - pluginId }
                val outcome = pluginStoreController.download(pluginId, appContext) { progress ->
                    _storeProgress.update { it + (pluginId to progress) }
                }
                _storeBusy.update { it - pluginId }
                when (outcome) {
                    is PluginStoreController.DownloadOutcome.Installed ->
                        _storeMessages.update {
                            it + (pluginId to "Downloaded — this remote plugin is now available.")
                        }
                    is PluginStoreController.DownloadOutcome.NeedsConsent -> {
                        _pendingConsentPluginId.value = pluginId
                        _storeMessages.update { it + (pluginId to outcome.message) }
                    }
                    is PluginStoreController.DownloadOutcome.Failed ->
                        _storeMessages.update { it + (pluginId to outcome.message) }
                }
                refreshPluginStates()
            }
            return
        }
        viewModelScope.launch {
            _storeBusy.update { it + pluginId }
            _storeProgress.update { it + (pluginId to 0f) }
            _storeMessages.update { it - pluginId }
            val outcome = pluginStoreController.download(pluginId, appContext) { progress ->
                _storeProgress.update { it + (pluginId to progress) }
            }
            _storeBusy.update { it - pluginId }
            when (outcome) {
                is PluginStoreController.DownloadOutcome.Installed -> {
                    // A previously-deleted plugin with on-device model assets
                    // (e.g. the assistant's GGUF) had them wiped by Delete; the
                    // store re-installs the definition only, so tell the user
                    // where to restore the model.
                    val needsModel = pluginStoreCatalog.entryFor(pluginId)?.installSizeBytes != null
                    _storeMessages.update {
                        it + (pluginId to if (needsModel) {
                            "Downloaded — this plugin is now available, but its on-device model was " +
                                "deleted earlier and must be re-downloaded from the plugin's own flow."
                        } else {
                            "Downloaded — this plugin is now available."
                        })
                    }
                }
                is PluginStoreController.DownloadOutcome.NeedsConsent -> Unit
                is PluginStoreController.DownloadOutcome.Failed ->
                    _storeMessages.update { it + (pluginId to outcome.message) }
            }
            refreshPluginStates()
        }
    }

    /**
     * Store "Delete": completely remove the plugin — its opt-in + namespaced
     * settings are wiped and it is gone from the registry (distinct from
     * disable, which keeps everything re-enableable). Destructive; the caller
     * confirms first.
     */
    fun storeDelete(pluginId: String, onResult: (Boolean) -> Unit = {}) {
        if (pluginId in _storeBusy.value) return
        viewModelScope.launch(Dispatchers.Default) {
            _storeBusy.update { it + pluginId }
            _storeMessages.update { it - pluginId }
            val outcome = pluginStoreController.delete(pluginId, appContext)
            _storeBusy.update { it - pluginId }
            when (outcome) {
                is PluginStoreController.DeleteOutcome.Deleted ->
                    _storeMessages.update { it + (pluginId to "Deleted — plugin and its settings were removed.") }
                is PluginStoreController.DeleteOutcome.Failed ->
                    _storeMessages.update { it + (pluginId to outcome.message) }
            }
            withContext(Dispatchers.Main) {
                refreshPluginStates()
                onResult(outcome is PluginStoreController.DeleteOutcome.Deleted)
            }
        }
    }

    /** Dismiss a store result/error message shown for [pluginId]. */
    fun clearStoreMessage(pluginId: String) {
        _storeMessages.update { it - pluginId }
        _storeProgress.update { it - pluginId }
        _updateProgress.update { it - pluginId }
    }

    /** Dismiss the store's general (non-row-specific) message. */
    fun dismissStoreGeneralMessage() {
        _storeGeneralMessage.value = null
    }

    // ---- Phase 24: user-approved dynamic updates ------------------------------

    /**
     * Store "Check for updates": fetch the hosted version manifest (HTTPS,
     * keyless, user-initiated) and populate [storeUpdates] only for INSTALLED
     * downloadable plugins whose manifest version is strictly newer (never a
     * downgrade, never an equal no-op). Results that are not per-row (up to
     * date / fetch failure) land in [storeGeneralMessage].
     */
    fun checkPluginUpdates() {
        viewModelScope.launch {
            _storeGeneralMessage.value = null
            when (val outcome = pluginStoreController.checkForUpdates(appContext)) {
                is PluginStoreController.UpdateCheckOutcome.UpdatesAvailable -> {
                    _storeUpdates.value = outcome.updates.associateBy { it.pluginId }
                    _storeGeneralMessage.value =
                        "${outcome.updates.size} update(s) available. Review and approve each one."
                }
                is PluginStoreController.UpdateCheckOutcome.UpToDate -> {
                    _storeUpdates.value = emptyMap()
                    _storeGeneralMessage.value = "All installed plugins are up to date."
                }
                is PluginStoreController.UpdateCheckOutcome.Failed -> {
                    _storeGeneralMessage.value = outcome.message
                }
            }
            refreshPluginStates()
        }
    }

    /** Request an update for [pluginId]: opens the per-update approval dialog. */
    fun requestPluginUpdate(pluginId: String) {
        if (pluginId in _updateBusy.value) return
        _pendingUpdatePluginId.value = pluginId
    }

    /** The user's answer to the pending update-approval dialog. */
    fun respondUpdateApproval(grant: Boolean) {
        val pluginId = _pendingUpdatePluginId.value ?: return
        _pendingUpdatePluginId.value = null
        if (grant) storePluginUpdate(pluginId)
    }

    /**
     * Run the user-APPROVED verified update of [pluginId] (this is only ever
     * called from [respondUpdateApproval]). The controller re-fetches a fresh
     * manifest, re-checks the offer is newer, then downloads → re-verifies
     * (pinned cert + SHA-256) → smoke-tests → swaps; any failure reports a
     * roll back to the previous version. Progress is real; nothing auto-updates.
     */
    private fun storePluginUpdate(pluginId: String) {
        if (pluginId in _updateBusy.value) return
        viewModelScope.launch {
            _updateBusy.update { it + pluginId }
            _updateProgress.update { it + (pluginId to 0f) }
            _storeMessages.update { it - pluginId }
            val outcome = pluginStoreController.update(pluginId, userApproved = true) { progress ->
                _updateProgress.update { it + (pluginId to progress) }
            }
            _updateBusy.update { it - pluginId }
            when (outcome) {
                is PluginStoreController.UpdateOutcome.Updated ->
                    _storeMessages.update {
                        it + (pluginId to "Updated to v${outcome.toVersion} — verified and active.")
                    }
                is PluginStoreController.UpdateOutcome.RolledBack ->
                    _storeMessages.update { it + (pluginId to outcome.message) }
                is PluginStoreController.UpdateOutcome.NeedsApproval ->
                    _pendingUpdatePluginId.value = pluginId
                is PluginStoreController.UpdateOutcome.Failed ->
                    _storeMessages.update { it + (pluginId to outcome.message) }
            }
            _storeUpdates.update { it - pluginId }
            _updateProgress.update { it - pluginId }
            refreshPluginStates()
        }
    }

    /**
     * Route a text-transform request through the plugin manager on a background
     * dispatcher, so a slow/hung plugin can never block the main thread.
     * Returns a typed result; failures carry a user-facing message (never throws).
     */
    suspend fun transformNoteText(text: String): PluginResult<String> =
        pluginManager.withPluginAsync(PluginCapability.TextTransform, appContext) { plugin ->
            val transformer = plugin as? TextTransformPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TextTransformPlugin")
            transformer.transformText(text)
        }

    /**
     * Route an on-demand InkStroke→Shape conversion through the plugin manager.
     * The geometry core is pure CPU so it runs on the framework's background
     * dispatcher and can never block the main thread. Returns a typed
     * [ShapeFromInkOutcome] — Success (crisp shape + replace/keep decision),
     * NotAShape (honest rejection — stroke untouched) or Error — never throws.
     */
    suspend fun convertStrokeToShape(stroke: com.authorss81.noteflow.data.model.Stroke): PluginResult<ShapeFromInkOutcome> =
        pluginManager.withPluginAsync(PluginCapability.ShapeFromInk, appContext) { plugin ->
            val converter = plugin as? ShapeFromInkPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement ShapeFromInkPlugin")
            converter.convertToShape(stroke)
        }

    /**
     * Route an on-device OCR request (image file → text) through the plugin
     * manager off the main thread. The plugin runs the model on
     * `Dispatchers.IO` and is cancelable end-to-end. Returns a typed result:
     * `Success(OcrOutcome)` (recognized text / no-text / validated error),
     * or Failure/Unavailable from the framework — never throws.
     */
    suspend fun extractTextFromImage(imagePath: String): PluginResult<OcrOutcome> =
        pluginManager.withPluginAsync(PluginCapability.OCR, appContext) { plugin ->
            val ocr = plugin as? OcrPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement OcrPlugin")
            ocr.recognizeText(appContext, imagePath)
        }

    /**
     * Route a web-search request through the plugin manager off the main thread.
     * The plugin makes the network call on `Dispatchers.IO` and returns typed
     * results for insertion as `[title](url)` links; connectivity failures
     * surface as `WebSearchOutcome.Error` ("offline — check connection").
     */
    suspend fun searchWeb(query: String): PluginResult<WebSearchOutcome> =
        pluginManager.withPluginAsync(PluginCapability.WebSearch, appContext) { plugin ->
            val searcher = plugin as? WebSearchPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement WebSearchPlugin")
            searcher.searchWeb(query.trim())
        }

    /**
     * Phase 15 (Export Engine): route a note export through the plugin manager
     * on `Dispatchers.IO`. Returns a typed [ExportOutcome] — Success carries the
     * written, shareable file; Error carries a validated, user-facing reason.
     */
    suspend fun exportNote(
        request: ExportRequest,
        format: ExportFormat
    ): PluginResult<ExportOutcome> =
        pluginManager.withPluginAsync(PluginCapability.Export, appContext) { plugin ->
            val exporter = plugin as? ExportPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement ExportPlugin")
            exporter.exportNote(appContext, request, format)
        }

    /**
     * Phase 15 (Text Tools): structural statistics of a note's text. PURE JVM.
     */
    suspend fun analyzeNoteText(text: String): PluginResult<TextAnalysis> =
        pluginManager.withPluginAsync(PluginCapability.TextTools, appContext) { plugin ->
            val tools = plugin as? TextToolsPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TextToolsPlugin")
            tools.analyzeText(text)
        }

    /** Phase 15 (Text Tools): simple line-diff of two note texts. PURE JVM. */
    suspend fun diffNoteTexts(oldText: String, newText: String): PluginResult<List<DiffHunk>> =
        pluginManager.withPluginAsync(PluginCapability.TextTools, appContext) { plugin ->
            val tools = plugin as? TextToolsPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TextToolsPlugin")
            tools.diffTexts(oldText, newText)
        }

    /** Phase 15 (Language Detection): detect a note's language. PURE JVM. */
    suspend fun detectNoteLanguage(text: String): PluginResult<LanguageDetectionOutcome> =
        pluginManager.withPluginAsync(PluginCapability.LanguageDetection, appContext) { plugin ->
            val detector = plugin as? LanguageDetectionPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement LanguageDetectionPlugin")
            detector.detectLanguage(text)
        }

    /**
     * Phase 15 (Language Detection): merge a freshly-detected `lang:<iso>` tag
     * into [existingTags], honouring any `lang:*`/`language:*` override. PURE JVM.
     */
    suspend fun autoTagNoteLanguage(text: String, existingTags: String): PluginResult<String> =
        pluginManager.withPluginAsync(PluginCapability.LanguageDetection, appContext) { plugin ->
            val detector = plugin as? LanguageDetectionPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement LanguageDetectionPlugin")
            detector.autoTagLanguage(text, existingTags)
        }

    /**
     * Phase 15 (Web Capture): fetch [url] and reduce it to Markdown via the
     * Web Capture plugin. Runs the network call on `Dispatchers.IO` and returns
     * typed results — connectivity failures surface as `WebCaptureOutcome.Error`.
     */
    suspend fun captureWebPage(url: String): PluginResult<WebCaptureOutcome> =
        pluginManager.withPluginAsync(PluginCapability.WebCapture, appContext) { plugin ->
            val capturer = plugin as? WebCapturePlugin
                ?: throw IllegalStateException("${plugin.name} does not implement WebCapturePlugin")
            capturer.captureWebPage(appContext, url)
        }

    /**
     * Phase 26 (Dictionary): route a word lookup through the plugin manager. The
     * plugin tries the keyless dictionaryapi.dev over HTTPS on `Dispatchers.IO`
     * and honestly falls back to its bundled offline word list (the result is
     * labelled with its source) — a lookup genuinely works offline.
     */
    suspend fun lookupDictionaryWord(word: String): PluginResult<DictionaryOutcome> =
        pluginManager.withPluginAsync(PluginCapability.Dictionary, appContext) { plugin ->
            val dictionary = plugin as? DictionaryPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement DictionaryPlugin")
            dictionary.lookupWord(word.trim())
        }

    /**
     * Phase 26 (Weather): route a dated weather snapshot through the plugin
     * manager. The plugin calls the keyless Open-Meteo API on `Dispatchers.IO`
     * (no GPS — location comes from the plugin's settings/default city) and
     * returns a typed [WeatherOutcome]; offline surfaces a clear error.
     */
    suspend fun fetchWeatherSnapshot(): PluginResult<WeatherOutcome> =
        pluginManager.withPluginAsync(PluginCapability.Weather, appContext) { plugin ->
            val weather = plugin as? WeatherPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement WeatherPlugin")
            weather.currentWeather()
        }

    /**
     * Phase 26 (Unit Converter): route an inline conversion query ("2 km to mi")
     * through the plugin manager. PURE JVM, fully offline — runs on the
     * framework's background dispatcher and can never block the UI.
     */
    suspend fun convertUnits(query: String): PluginResult<UnitConversionOutcome> =
        pluginManager.withPluginAsync(PluginCapability.UnitConversion, appContext) { plugin ->
            val converter = plugin as? UnitConverterPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement UnitConverterPlugin")
            converter.convert(query)
        }

    /**
     * Phase 26 (Outline & Checklist): route an outline/checklist generation
     * request through the plugin manager. PURE JVM — runs on the framework's
     * background dispatcher. The result is previewed in the UI before insertion.
     */
    suspend fun generateOutline(text: String, style: OutlineStyle): PluginResult<OutlineOutcome> =
        pluginManager.withPluginAsync(PluginCapability.OutlineGenerator, appContext) { plugin ->
            val generator = plugin as? OutlineGeneratorPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement OutlineGeneratorPlugin")
            generator.generateOutline(text, style)
        }

    /**
     * Phase 26 (Citation Formatter): route a URL → `[title](url)` formatting
     * request through the plugin manager. The plugin fetches the page `<title>`
     * over HTTPS on `Dispatchers.IO` (strictly user-initiated) and honestly
     * falls back to a host-derived label on any failure.
     */
    suspend fun formatCitation(url: String, title: String?): PluginResult<CitationOutcome> =
        pluginManager.withPluginAsync(PluginCapability.CitationFormatter, appContext) { plugin ->
            val formatter = plugin as? CitationPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement CitationPlugin")
            formatter.formatCitation(url, title)
        }

    /**
     * Phase 15 (Clip to InkFlow): classify + validate incoming share content
     * BEFORE anything is copied into the vault. PURE JVM; the ViewModel stores a
     * returned [com.authorss81.noteflow.plugins.SharedClip] through the same
     * encrypted NoteRepository path as any note.
     */
    suspend fun parseSharedClip(input: SharedInput): PluginResult<ClipParseOutcome> =
        pluginManager.withPluginAsync(PluginCapability.ClipShare, appContext) { plugin ->
            val clipper = plugin as? ClipSharePlugin
                ?: throw IllegalStateException("${plugin.name} does not implement ClipSharePlugin")
            clipper.parse(input)
        }

    /**
     * True when a plugin currently sits in a state that can serve requests.
     * Computed FRESH from the registry (which re-evaluates device availability
     * under a guard on every call), so a revoked permission or lost dependency
     * is reflected immediately — never stale.
     */
    fun isPluginUsable(pluginId: String): Boolean =
        pluginRegistry.stateOf(pluginId, appContext)?.state == PluginLifecycleState.AVAILABLE

    /**
     * A short menu label for [pluginId], disambiguating the three visible
     * states so "off" never mislabels an offline plugin: AVAILABLE shows the
     * active label, UNAVAILABLE gets an "offline" suffix (device/network
     * gate — the plugin is ON but cannot serve right now), and anything else
     * is genuinely off/not-enabled.
     */
    fun pluginMenuLabel(pluginId: String, activeLabel: String): String {
        val state = pluginRegistry.stateOf(pluginId, appContext)?.state
        return if (state == PluginLifecycleState.AVAILABLE) {
            activeLabel
        } else {
            when (state) {
                PluginLifecycleState.UNAVAILABLE -> "$activeLabel (offline)"
                PluginLifecycleState.ENABLED -> "$activeLabel (checking…)"
                else -> "$activeLabel (off)"
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase 16 — privacy-first on-device AI & media plugin routes.
    // Everything is routed through PluginManager (guarded, typed, never throws)
    // and every feature is user-initiated: dictation needs an explicit mic tap,
    // read-aloud needs a Play tap (and refuses in SilentToggle), model downloads
    // (translation + assistant) need explicit consent with progress.
    // -----------------------------------------------------------------------

    /** The dictation plugin (enabled + available), or a typed failure. */
    suspend fun dictationPlugin(): PluginResult<DictationPlugin> =
        pluginManager.withPluginAsync(PluginCapability.Dictation, appContext) { plugin ->
            plugin as DictationPlugin
        }

    /** Read [passage] aloud via the read-aloud plugin. Quiet mode refused loudly. */
    suspend fun readAloud(passage: String, quietMode: Boolean): PluginResult<ReadAloudOutcome> =
        pluginManager.withPluginAsync(PluginCapability.ReadAloud, appContext) { plugin ->
            val reader = plugin as? ReadAloudPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement ReadAloudPlugin")
            reader.play(appContext, passage, quietMode)
        }

    /** Stop read-aloud playback (dismissed dialog / user stop). */
    fun stopReadAloud() {
        runCatching {
            (pluginForCapabilityOrNull(PluginCapability.ReadAloud) as? ReadAloudPlugin)?.stop(appContext)
        }
    }

    /** Translate [text] into [targetLanguage] on-device. */
    suspend fun translateText(targetLanguage: String, text: String): PluginResult<TranslationOutcome> =
        pluginManager.withPluginAsync(PluginCapability.Translation, appContext) { plugin ->
            val translator = plugin as? TranslationPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TranslationPlugin")
            translator.translate(targetLanguage, text)
        }

    /** Download the [targetLanguage] model on explicit user action (consent). */
    suspend fun downloadTranslationModel(targetLanguage: String): PluginResult<TranslationModelStatus> =
        pluginManager.withPluginAsync(PluginCapability.Translation, appContext) { plugin ->
            val translator = plugin as? TranslationPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TranslationPlugin")
            translator.downloadModel(targetLanguage)
        }

    /** Whether the [targetLanguage] model is already stored on-device. */
    suspend fun isTranslationModelDownloaded(targetLanguage: String): PluginResult<Boolean> =
        pluginManager.withPluginAsync(PluginCapability.Translation, appContext) { plugin ->
            val translator = plugin as? TranslationPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement TranslationPlugin")
            translator.isModelDownloaded(targetLanguage)
        }

    /** The target languages the on-device translator offers. */
    fun translationTargetLanguages(): List<TranslationLanguage> =
        (pluginForCapabilityOrNull(PluginCapability.Translation) as? TranslationPlugin)
            ?.supportedTargetLanguages() ?: emptyList()

    /** The on-device assistant plugin (enabled + available), or a typed failure. */
    suspend fun assistantPlugin(): PluginResult<AssistantPlugin> =
        pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
            plugin as AssistantPlugin
        }

    suspend fun assistantSummarize(noteText: String): PluginResult<AssistantOutcome> =
        assistantRewire("summarize") {
            assistantSummarizeInner(noteText)
        }

    private suspend fun assistantSummarizeInner(noteText: String): PluginResult<AssistantOutcome> =
        pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
            val assistant = plugin as? AssistantPlugin
                ?: throw IllegalStateException("${plugin.name} does not implement AssistantPlugin")
            assistant.summarize(appContext, noteText)
        }

    suspend fun assistantExtractActionItems(noteText: String): PluginResult<AssistantOutcome> =
        assistantRewire("extract action items") {
            pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
                val assistant = plugin as? AssistantPlugin
                    ?: throw IllegalStateException("${plugin.name} does not implement AssistantPlugin")
                assistant.extractActionItems(appContext, noteText)
            }
        }

    suspend fun assistantAnswerQuestion(noteText: String, question: String): PluginResult<AssistantOutcome> =
        assistantRewire("answer questions") {
            pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
                val assistant = plugin as? AssistantPlugin
                    ?: throw IllegalStateException("${plugin.name} does not implement AssistantPlugin")
                assistant.answerQuestion(appContext, noteText, question)
            }
        }

    suspend fun assistantSuggestTags(noteText: String): PluginResult<AssistantOutcome> =
        assistantRewire("suggest tags") {
            pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
                val assistant = plugin as? AssistantPlugin
                    ?: throw IllegalStateException("${plugin.name} does not implement AssistantPlugin")
                assistant.suggestTags(appContext, noteText)
            }
        }

    /** Download the assistant's small LLM (user consent + progress callback). */
    suspend fun assistantDownloadModel(onProgress: (Float) -> Unit): PluginResult<AssistantOutcome> =
        assistantRewire("download the model") {
            pluginManager.withPluginAsync(PluginCapability.Assistant, appContext) { plugin ->
                val assistant = plugin as? AssistantPlugin
                    ?: throw IllegalStateException("${plugin.name} does not implement AssistantPlugin")
                assistant.downloadModel(appContext, onProgress)
            }
        }

    /**
     * PHASE 29: the base APK no longer compiles the local-LLM engine — the
     * Assistant capability is served by the DOWNLOADABLE `plugins/llm` plugin
     * (installed from the Plugin Store) or by the compile-time Cloud AI plugin
     * (after adding an API key in Settings → API Keys). When no assistant
     * plugin is installed, rewrite the manager's generic "no plugin" message
     * into an honest, actionable store hint instead of a dead end.
     */
    private suspend fun <T> assistantRewire(action: String, block: suspend () -> PluginResult<T>): PluginResult<T> {
        val result = block()
        return when (result) {
            is PluginResult.Failure ->
                if (result.reason == PluginFailureReason.NO_PLUGIN_INSTALLED) {
                    PluginResult.Failure(
                        result.reason,
                        "The AI assistant isn't installed. Open the ⋮ menu → Plugin Store to install the " +
                            "on-device LLM (Downloads a ~50 MB engine), or add an OpenAI-compatible API key in " +
                            "Settings → API Keys to use the cloud assistant instead. This request was not sent anywhere."
                    )
                } else {
                    result
                }
            else -> result
        }
    }

    /**
     * Capture the current canvas as an image note via the Screenshot plugin
     * (reuses the existing annotated-page renderer) and, when the user asked
     * for it, OCR the result through the existing OCR plugin route so the note
     * is text-searchable. The new image parse is then created in the active
     * section with the extracted text.
     */
    suspend fun captureScreenshotNote(
        strokes: List<Stroke>,
        layers: List<LayerEntity>,
        stickyNotes: List<CanvasStickyNote>,
        mediaEmbeds: List<CanvasMediaEmbed>,
        bgBitmap: android.graphics.Bitmap?,
        template: String,
        pageIndex: Int,
        shouldOcr: Boolean,
        onCreated: (NotePageEntity) -> Unit
    ): PluginResult<ScreenshotCaptureOutcome> {
        val ocrPluginAvailable = pluginRegistry
            .availablePlugins(PluginCapability.OCR, appContext)
            .isNotEmpty()
        val result = pluginManager.withPluginAsync(PluginCapability.ScreenshotNote, appContext) { plugin ->
            val capturer = plugin as? ScreenshotNotePlugin
                ?: throw IllegalStateException("${plugin.name} does not implement ScreenshotNotePlugin")
            capturer.captureAnnotatedPage(
                context = appContext,
                pageTitle = selectedPage.value?.title ?: "Untitled",
                strokes = strokes,
                layers = layers,
                stickyNotes = stickyNotes,
                mediaEmbeds = mediaEmbeds,
                bgBitmap = bgBitmap,
                template = template,
                pageIndex = pageIndex,
                shouldOcr = shouldOcr,
                ocrPluginAvailable = ocrPluginAvailable
            )
        }
        if (result is PluginResult.Success && result.value is ScreenshotCaptureOutcome.Success) {
            val success = result.value as ScreenshotCaptureOutcome.Success
            var ocrText: String? = null
            if (success.plan.shouldOcr) {
                when (val ocr = extractTextFromImage(success.imagePath)) {
                    is PluginResult.Success ->
                        if (ocr.value is OcrOutcome.Success) ocrText = ocr.value.text
                    else -> ocrText = null
                }
            }
            addPage(
                title = success.plan.title,
                sourceFilePath = success.imagePath,
                sourceFileType = "image",
                extractedText = ocrText ?: "",
                onCreated = onCreated
            )
        }
        return result
    }

    /** The enabled, device-available plugin serving [capability], or null. */
    fun pluginForCapabilityOrNull(capability: PluginCapability): com.authorss81.noteflow.plugins.NoteflowPlugin? =
        pluginRegistry.availablePlugins(capability, appContext).firstOrNull()

    private val _databaseTampered = MutableStateFlow(false)
    val databaseTampered: StateFlow<Boolean> = _databaseTampered.asStateFlow()

    private val _databaseIntegrityCheckEnabled = MutableStateFlow(settings.databaseIntegrityCheckEnabled)
    val databaseIntegrityCheckEnabled: StateFlow<Boolean> = _databaseIntegrityCheckEnabled.asStateFlow()

    /** 34.8: a restored DB failed its own HMAC — hard-block the vault. */
    private val _restoreBlocked = MutableStateFlow(DatabaseSecurityHelper.hasRestoreBlock(appContext))
    val restoreBlocked: StateFlow<Boolean> = _restoreBlocked.asStateFlow()

    /** H2 (phase-09): the vault DB failed to open (corrupt/wrong-key) and its
     *  files were quarantined, NOT deleted. Show a dedicated recovery screen
     *  until the user restores from backup or explicitly starts fresh. */
    private val _corruptionBlocked = MutableStateFlow(DatabaseSecurityHelper.hasCorruptionDetected(appContext))
    val corruptionBlocked: StateFlow<Boolean> = _corruptionBlocked.asStateFlow()
    val corruptionTimestamp: Long = DatabaseSecurityHelper.getCorruptionTimestamp(appContext)

    /**
     * H2 (phase-09): the user explicitly chose to discard the quarantined vault
     * and continue with the (already created) empty database. Clears the flag
     * and re-arms the tamper baseline so the fresh vault is the new baseline.
     */
    fun startFreshAfterCorruption() {
        DatabaseSecurityHelper.clearCorruptionDetected(appContext)
        DatabaseSecurityHelper.clearStoredChecksum(appContext)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.stampDatabaseChecksum(appContext)
            }
        }
        _corruptionBlocked.value = false
        _databaseTampered.value = false
        // B1-DB-1 (phase-43): initializeData() bails with the corruption flag on a
        // failed open, so a fresh empty vault never got its default notebook/section.
        // Re-run it now that the flag is cleared (guard re-armed) so "start fresh"
        // yields a usable empty vault instead of a blank, uninitialized home screen.
        dataInitialized = false
        initializeData()
    }

    init {
        viewModelScope.launch {
            if (settings.databaseIntegrityCheckEnabled) {
                val tampered = withContext(Dispatchers.IO) {
                    !DatabaseSecurityHelper.verifyDatabaseIntegrity(appContext)
                }
                _databaseTampered.value = tampered && !settings.databaseIntegrityWarningDismissed
            } else {
                _databaseTampered.value = false
            }
        }
    }

    fun dismissDatabaseIntegrityWarning(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            settings.databaseIntegrityCheckEnabled = false
            _databaseIntegrityCheckEnabled.value = false
        }
        settings.databaseIntegrityWarningDismissed = true
        _databaseTampered.value = false
    }

    fun setDatabaseIntegrityCheckEnabled(enabled: Boolean) {
        settings.databaseIntegrityCheckEnabled = enabled
        _databaseIntegrityCheckEnabled.value = enabled
        if (enabled) {
            settings.databaseIntegrityWarningDismissed = false
            viewModelScope.launch {
                val tampered = withContext(Dispatchers.IO) {
                    !DatabaseSecurityHelper.verifyDatabaseIntegrity(appContext)
                }
                _databaseTampered.value = tampered
            }
        } else {
            _databaseTampered.value = false
        }
    }

    private val _failedUnlockAttempts = MutableStateFlow(settings.failedUnlockAttempts)
    val failedUnlockAttempts: StateFlow<Int> = _failedUnlockAttempts.asStateFlow()

    private val _lockoutRemainingMs = MutableStateFlow(
        (settings.lockoutUntilEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
    )
    val lockoutRemainingMs: StateFlow<Long> = _lockoutRemainingMs.asStateFlow()

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _useSidebarLayout = MutableStateFlow(settings.useSidebarLayout)
    val useSidebarLayout: StateFlow<Boolean> = _useSidebarLayout.asStateFlow()

    private val _showStrokePreviewsInPicker = MutableStateFlow(settings.showStrokePreviewsInPicker)
    val showStrokePreviewsInPicker: StateFlow<Boolean> = _showStrokePreviewsInPicker.asStateFlow()

    fun toggleShowStrokePreviewsInPicker(enabled: Boolean) {
        settings.showStrokePreviewsInPicker = enabled
        _showStrokePreviewsInPicker.value = enabled
    }

    fun updatePagePaperColor(id: String, paperColor: String?) {
        viewModelScope.launch {
            repository.updatePagePaperColor(id, paperColor)
        }
    }

    private val _authenticated = MutableStateFlow(!settings.hasMasterPassword)
    val authenticated: StateFlow<Boolean> = _authenticated.asStateFlow()

    // B1-DB-1 (phase-43): the note-data flows gate on BOTH auth and the corruption
    // flag. While the vault is quarantined the corruption-recovery screen is shown
    // and no flow may open the DB — the quarantined helper guard makes any open
    // fail (never auto-create an empty DB behind the user's back). Restore success
    // and "start fresh" clear the flag and re-arm these flows.
    private val dbGate: Flow<Boolean> = combine(_authenticated, _corruptionBlocked) { isAuth, blocked ->
        isAuth && !blocked
    }.distinctUntilChanged()

    private val _hasMasterPassword = MutableStateFlow(settings.hasMasterPassword)
    val hasMasterPassword: StateFlow<Boolean> = _hasMasterPassword.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(settings.biometricAuthEnabled)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    // 22.1: seconds of foreground inactivity before auto-lock (0 = off).
    private val _autoLockTimeoutSeconds = MutableStateFlow(settings.autoLockTimeoutSeconds)
    val autoLockTimeoutSeconds: StateFlow<Int> = _autoLockTimeoutSeconds.asStateFlow()

    private val _isFirstRun = MutableStateFlow(settings.isFirstRun)
    val isFirstRun: StateFlow<Boolean> = _isFirstRun.asStateFlow()

    private val _tutorialCompleted = MutableStateFlow(settings.tutorialCompleted)
    val tutorialCompleted: StateFlow<Boolean> = _tutorialCompleted.asStateFlow()

    private val _confettiTrigger = MutableStateFlow(0L)
    val confettiTrigger: StateFlow<Long> = _confettiTrigger.asStateFlow()

    /** 22.9: root snackbar pipeline — replaces transient, TalkBack-invisible Toasts. */
    data class SnackbarMessage(val text: String, val isLong: Boolean = false)

    private val _snackbarMessages = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<SnackbarMessage> = _snackbarMessages.asSharedFlow()

    fun showSnackbar(text: String, isLong: Boolean = false) {
        _snackbarMessages.tryEmit(SnackbarMessage(text, isLong))
    }

    private val _p2pNotification = MutableStateFlow<String?>(null)
    val p2pNotification: StateFlow<String?> = _p2pNotification.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val notebooks: StateFlow<List<NotebookEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.notebooks else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allSections: StateFlow<List<SectionEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.getAllSections() else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allActivePages: StateFlow<List<NotePageEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.getAllActivePagesFlow() else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val paletteItems: StateFlow<List<PaletteItemEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.allPaletteItems else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedNotebook = MutableStateFlow<NotebookEntity?>(null)
    val selectedNotebook: StateFlow<NotebookEntity?> = _selectedNotebook.asStateFlow()

    private val _sections = MutableStateFlow<List<SectionEntity>>(emptyList())
    val sections: StateFlow<List<SectionEntity>> = _sections.asStateFlow()

    private val _selectedSection = MutableStateFlow<SectionEntity?>(null)
    val selectedSection: StateFlow<SectionEntity?> = _selectedSection.asStateFlow()

    private val _pages = MutableStateFlow<List<NotePageEntity>>(emptyList())
    val pages: StateFlow<List<NotePageEntity>> = _pages.asStateFlow()

    private val _selectedPage = MutableStateFlow<NotePageEntity?>(null)
    val selectedPage: StateFlow<NotePageEntity?> = _selectedPage.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recentPages: StateFlow<List<NotePageEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.getRecentPages() else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trashedPages: StateFlow<List<NotePageEntity>> = dbGate
        .flatMapLatest { enabled ->
            if (enabled) repository.getTrashedPages() else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var sectionsJob: Job? = null
    private var pagesJob: Job? = null

    private var lockoutTickerJob: Job? = null
    private var dataInitialized = false

    private fun initializeData() {
        if (dataInitialized) return
        dataInitialized = true
        viewModelScope.launch {
            try {
                initializeDataCore()
            } catch (e: Exception) {
                // B1-DB-1 (phase-43): the vault open failed and the quarantined-helper
                // already set the persistent corruption flag before rethrowing. Surface
                // the corruption-recovery screen IN THIS SESSION instead of crashing a
                // dead DB. Any OTHER exception is rethrown (data corruption must never
                // be silently swallowed).
                if (DatabaseSecurityHelper.hasCorruptionDetected(appContext)) {
                    _corruptionBlocked.value = true
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun initializeDataCore() {
            if (!settings.fieldAadMigrated) {
                // B2-CRYPTO-09 (phase-107): bind pre-phase-107 field ciphertexts to
                // their record context before any page/stroke/version is served.
                // Runs on the DEK (never plaintext), is idempotent, and leaves
                // plaintext rows untouched (that is reencryptPlaintextFields' job).
                val dek = repository.encryptionKey
                if (dek != null) {
                    try {
                        repository.migrateFieldRecordAad(dek)
                        // Success: no legacy rows remain for the fallback to read.
                        // On failure the flag stays unset and the pass re-runs on
                        // the next unlock (reads stay safe via the legacy AAD fallback).
                        settings.fieldAadMigrated = true
                    } catch (e: Exception) {
                        // Keep the flag unset and retry on the next unlock.
                    }
                }
            }
            val lastNbId = settings.activeNotebookId
            val lastSecId = settings.activeSectionId
            var restoredNb: NotebookEntity? = null
            var restoredSec: SectionEntity? = null

            if (!lastNbId.isNullOrEmpty()) {
                restoredNb = repository.getNotebookById(lastNbId)
            }
            if (!lastSecId.isNullOrEmpty()) {
                restoredSec = repository.getSectionById(lastSecId)
            }

            if (restoredNb != null && restoredSec != null && restoredSec.notebookId == restoredNb.id) {
                _selectedNotebook.value = restoredNb
                _selectedSection.value = restoredSec
                observeSections(restoredNb.id)
                observePages(restoredSec.id)
            } else {
                val (defaultNb, defaultSec) = repository.ensureDefaultNotebookAndSection()
                _selectedNotebook.value = defaultNb
                _selectedSection.value = defaultSec
                observeSections(defaultNb.id)
                observePages(defaultSec.id)
            }
    }

    init {
        if (settings.lockoutUntilEpochMs > System.currentTimeMillis()) {
            startLockoutTicker()
        }
        if (settings.hasMasterPassword) {
            // Vault locked: DB opens only after successful unlock.
        } else {
            // No master password → DEK is device-wrapped and available immediately.
            var dek = security.readDek()
            if (dek == null) {
                dek = EncryptionService.generateDek()
                security.storeDek(dek, authRequired = false)
            }
            repository.encryptionKey = dek
            initializeData()
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        settings.themeMode = mode
        _themeMode.value = mode
    }

    // Phase 28: GLASS-theme frosted-blur master switch (GFX gate in GlassBlurGate).
    private val _glassBlurEnabled = MutableStateFlow(settings.glassBlurEnabled)
    val glassBlurEnabled: StateFlow<Boolean> = _glassBlurEnabled.asStateFlow()

    fun setGlassBlurEnabled(enabled: Boolean) {
        settings.glassBlurEnabled = enabled
        _glassBlurEnabled.value = enabled
    }

    fun setUseSidebarLayout(enabled: Boolean) {
        settings.useSidebarLayout = enabled
        _useSidebarLayout.value = enabled
    }

    fun setAutoLockTimeoutSeconds(seconds: Int) {
        settings.autoLockTimeoutSeconds = seconds
        _autoLockTimeoutSeconds.value = seconds
    }

    fun markFirstRunComplete() {
        settings.isFirstRun = false
        settings.tutorialCompleted = true
        _isFirstRun.value = false
        _tutorialCompleted.value = true
    }

    fun markTutorialCompleted() {
        settings.tutorialCompleted = true
        _tutorialCompleted.value = true
    }

    fun triggerConfetti() {
        _confettiTrigger.value = System.currentTimeMillis()
    }

    fun clearNotification() {
        _p2pNotification.value = null
    }

    fun selectNotebook(notebook: NotebookEntity) {
        _selectedNotebook.value = notebook
        _selectedSection.value = null
        settings.activeNotebookId = notebook.id
        observeSections(notebook.id)
    }

    private fun observeSections(notebookId: String) {
        sectionsJob?.cancel()
        sectionsJob = viewModelScope.launch {
            repository.getSectionsForNotebook(notebookId).collect { list ->
                _sections.value = list
                if (list.isNotEmpty() && (selectedSection.value == null || list.none { it.id == selectedSection.value?.id })) {
                    selectSection(list.first())
                } else if (list.isEmpty()) {
                    _selectedSection.value = null
                    _pages.value = emptyList()
                }
            }
        }
    }

    fun selectSection(section: SectionEntity) {
        _selectedSection.value = section
        settings.activeSectionId = section.id
        observePages(section.id)
    }

    private fun observePages(sectionId: String) {
        pagesJob?.cancel()
        pagesJob = viewModelScope.launch {
            repository.getPagesForSection(sectionId).collect { list ->
                _pages.value = list
            }
        }
    }

    fun selectPage(page: NotePageEntity?) {
        _selectedPage.value = page
        if (page != null) {
            settings.activePageId = page.id
        }
    }

    fun addNotebook(name: String, tags: String = "") {
        viewModelScope.launch {
            val newNb = repository.createNotebook(name, tags)
            selectNotebook(newNb)
        }
    }

    fun renameNotebook(id: String, name: String) {
        viewModelScope.launch {
            repository.renameNotebook(id, name)
            if (selectedNotebook.value?.id == id) {
                _selectedNotebook.value = repository.getNotebookById(id)
            }
        }
    }

    fun updateNotebookNameAndTags(id: String, name: String, tags: String) {
        viewModelScope.launch {
            repository.updateNotebookNameAndTags(id, name, tags)
            if (selectedNotebook.value?.id == id) {
                _selectedNotebook.value = repository.getNotebookById(id)
            }
        }
    }

    fun updateNotebookTags(id: String, tags: String) {
        viewModelScope.launch {
            repository.updateNotebookTags(id, tags)
            if (selectedNotebook.value?.id == id) {
                _selectedNotebook.value = repository.getNotebookById(id)
            }
        }
    }

    fun deleteNotebook(id: String) {
        viewModelScope.launch {
            repository.deleteNotebook(id)
            val remaining = notebooks.value.filter { it.id != id }
            if (remaining.isNotEmpty()) {
                selectNotebook(remaining.first())
            } else {
                _selectedNotebook.value = null
                _selectedSection.value = null
                _pages.value = emptyList()
            }
        }
    }

    fun addSection(name: String) {
        val nb = selectedNotebook.value ?: return
        viewModelScope.launch {
            val sec = repository.createSection(nb.id, name)
            selectSection(sec)
        }
    }

    fun renameSection(id: String, name: String) {
        viewModelScope.launch {
            repository.renameSection(id, name)
            if (selectedSection.value?.id == id) {
                _selectedSection.value = repository.getSectionById(id)
            }
        }
    }

    fun deleteSection(id: String) {
        viewModelScope.launch {
            repository.deleteSection(id)
            val remaining = sections.value.filter { it.id != id }
            if (remaining.isNotEmpty()) {
                selectSection(remaining.first())
            } else {
                _selectedSection.value = null
                _pages.value = emptyList()
            }
        }
    }

    fun applyWorkspaceTemplate(
        template: WorkspaceTemplate,
        createNewNotebook: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val targetSection: SectionEntity
            if (createNewNotebook) {
                val newNb = repository.createNotebook(template.defaultNotebookName)
                val sec = repository.createSection(newNb.id, template.defaultSectionName)
                selectNotebook(newNb)
                selectSection(sec)
                targetSection = sec
            } else {
                val currentSec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
                if (_selectedSection.value == null) {
                    _selectedSection.value = currentSec
                }
                targetSection = currentSec
            }

            var firstCreatedPage: NotePageEntity? = null
            template.pagesToCreate.forEachIndexed { index, (title, content) ->
                val page = repository.createPage(
                    sectionId = targetSection.id,
                    title = title,
                    template = template.paperTemplate,
                    extractedText = content,
                    tags = "template"
                )
                if (index == 0) firstCreatedPage = page
            }
            observePages(targetSection.id)
            if (firstCreatedPage != null) {
                selectPage(firstCreatedPage)
            }
            onComplete()
        }
    }

    fun addPage(
        title: String = "Untitled",
        sourceFilePath: String? = null,
        sourceFileType: String? = null,
        template: String = "blank",
        extractedText: String? = "",
        tags: String = "",
        onCreated: ((NotePageEntity) -> Unit)? = null
    ): Job {
        return viewModelScope.launch {
            val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
            if (_selectedSection.value == null) {
                _selectedSection.value = sec
            }
            val page = repository.createPage(
                sectionId = sec.id,
                title = title,
                sourceFilePath = sourceFilePath,
                sourceFileType = sourceFileType,
                template = template,
                extractedText = extractedText,
                tags = tags
            )
            observePages(sec.id)
            selectPage(page)
            onCreated?.invoke(page)
        }
    }

    /** 22.5: create a note from ACTION_SEND content (shared text + already-copied image paths). */
    fun createNoteFromSharedContent(
        sharedText: String?,
        imagePaths: List<String>,
        onCreated: (NotePageEntity) -> Unit
    ) {
        viewModelScope.launch {
            val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
            val firstLine = sharedText?.lineSequence()?.firstOrNull()?.trim()?.take(40)
                ?.takeIf { it.isNotBlank() }
            val title = firstLine
                ?: if (imagePaths.isNotEmpty()) "Shared Images (${imagePaths.size})" else "Shared Note"
            val newPage = repository.createPage(
                sectionId = sec.id,
                title = title,
                sourceFileType = when {
                    imagePaths.isNotEmpty() -> "image"
                    !sharedText.isNullOrBlank() -> "text"
                    else -> null
                },
                extractedText = sharedText ?: ""
            )
            if (imagePaths.isNotEmpty()) {
                repository.saveMediaEmbedsForPage(
                    newPage.id,
                    imagePaths.mapIndexed { index, path ->
                        CanvasMediaEmbed(
                            pageId = newPage.id,
                            type = MediaEmbedType.PHOTO,
                            x = 40f,
                            y = 60f + index * 280f,
                            width = 520f,
                            height = 260f,
                            contentUrlOrPath = path
                        )
                    }
                )
            }
            selectPage(newPage)
            onCreated(newPage)
        }
    }

    fun renamePage(id: String, title: String) {
        viewModelScope.launch {
            repository.renamePage(id, title)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = repository.getPageById(id)
            }
        }
    }

    fun updatePageTitleAndTags(id: String, title: String, tags: String) {
        viewModelScope.launch {
            repository.updatePageTitleAndTags(id, title, tags)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = repository.getPageById(id)
            }
        }
    }

    /**
     * Phase 15 (Language Detection): auto-tag [pageId] with a freshly detected
     * `lang:<iso>` tag on save. Respects the per-plugin `lang_auto_tag` setting
     * (default on) and the plugin's own override rule (an existing `lang:*` /
     * `language:*` tag is never overwritten). Callers fire-and-forget; no
     * user-visible message is produced for a silent, safe tag merge.
     */
    fun autoTagLanguageOnSave(pageId: String, title: String, tags: String, text: String) {
        val pluginIds = pluginRegistry.pluginsForCapability(PluginCapability.LanguageDetection)
        val enabled = pluginIds.any { pluginRegistry.isEnabled(it.id) }
        if (!enabled || text.trim().length < 20) return
        val settings = pluginRegistry.settingsFor(pluginIds.first { pluginRegistry.isEnabled(it.id) }.id)
        if (!settings.getBoolean("lang_auto_tag", true)) return
        viewModelScope.launch {
            val merged = autoTagNoteLanguage(text, tags)
            if (merged is PluginResult.Success) {
                repository.updatePageTitleAndTags(pageId, title, merged.value)
                if (selectedPage.value?.id == pageId) {
                    _selectedPage.value = repository.getPageById(pageId)
                }
            }
        }
    }

    fun updatePageTags(id: String, tags: String) {
        viewModelScope.launch {
            repository.updatePageTags(id, tags)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = repository.getPageById(id)
            }
        }
    }

    fun renameTag(oldTag: String, newTag: String) {
        val cleanNewTag = newTag.trim().lowercase().removePrefix("#")
        if (cleanNewTag.isEmpty()) return
        viewModelScope.launch {
            val allNotebooks = repository.getAllNotebooks()
            allNotebooks.forEach { nb ->
                val tagList = nb.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (tagList.contains(oldTag)) {
                    val updatedTags = tagList.map { if (it == oldTag) cleanNewTag else it }.distinct().joinToString(",")
                    repository.updateNotebookTags(nb.id, updatedTags)
                }
            }
            val allPages = repository.getAllActivePages()
            allPages.forEach { pg ->
                val tagList = pg.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (tagList.contains(oldTag)) {
                    val updatedTags = tagList.map { if (it == oldTag) cleanNewTag else it }.distinct().joinToString(",")
                    repository.updatePageTags(pg.id, updatedTags)
                }
            }
            // If the selected page was modified, refresh it
            selectedPage.value?.id?.let { id ->
                _selectedPage.value = repository.getPageById(id)
            }
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            val allNotebooks = repository.getAllNotebooks()
            allNotebooks.forEach { nb ->
                val tagList = nb.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (tagList.contains(tag)) {
                    val updatedTags = tagList.filter { it != tag }.joinToString(",")
                    repository.updateNotebookTags(nb.id, updatedTags)
                }
            }
            val allPages = repository.getAllActivePages()
            allPages.forEach { pg ->
                val tagList = pg.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (tagList.contains(tag)) {
                    val updatedTags = tagList.filter { it != tag }.joinToString(",")
                    repository.updatePageTags(pg.id, updatedTags)
                }
            }
            // If the selected page was modified, refresh it
            selectedPage.value?.id?.let { id ->
                _selectedPage.value = repository.getPageById(id)
            }
        }
    }

    fun togglePinPage(id: String, currentPinned: Boolean) {
        viewModelScope.launch {
            repository.togglePin(id, !currentPinned)
        }
    }

    fun trashPage(id: String) {
        viewModelScope.launch {
            repository.trashPage(id)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = null
            }
        }
    }

    fun updatePageTemplate(id: String, template: String) {
        viewModelScope.launch {
            repository.updatePageTemplate(id, template)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = _selectedPage.value?.copy(template = template)
            }
        }
    }

    fun updatePageSource(id: String, sourceFilePath: String?, sourceFileType: String?) {
        viewModelScope.launch {
            repository.updatePageSource(id, sourceFilePath, sourceFileType)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = _selectedPage.value?.copy(sourceFilePath = sourceFilePath, sourceFileType = sourceFileType)
            }
        }
    }

    fun exportNotebookVaultZip(context: Context, notebookId: String, onComplete: (File?) -> Unit) {
        viewModelScope.launch {
            val notebook = repository.getNotebookById(notebookId)
            val title = notebook?.name ?: "Notebook"
            val pages = repository.getPagesForNotebookOnce(notebookId)
            val zipFile = ImportExportService.exportVaultToZip(context, title, pages, repository)
            onComplete(zipFile)
        }
    }

    fun exportSectionVaultZip(context: Context, sectionId: String, onComplete: (File?) -> Unit) {
        viewModelScope.launch {
            val section = repository.getSectionById(sectionId)
            val title = section?.name ?: "Section"
            val pages = repository.getPagesForSectionOnce(sectionId)
            val zipFile = ImportExportService.exportVaultToZip(context, title, pages, repository)
            onComplete(zipFile)
        }
    }

    fun searchVault(query: String, onResult: (List<NotePageEntity>) -> Unit) {
        viewModelScope.launch {
            val results = repository.searchPages(query)
            onResult(results)
        }
    }

    /**
     * 34.8: recovery path for the hard restore-block — the only way in from the
     * blocked state is a fresh, verifiable backup. Success re-arms the baseline
     * (restoreFromZip does it pre-swap), clears the block and unlocks the vault.
     */
    fun attemptRecoveryFromBackup(uri: android.net.Uri, backupPassword: String?, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val bytes = ImportExportService.readUriBytes(getApplication(), uri)
                    ?: throw IllegalStateException("Could not read the selected backup file.")
                // H1 (phase-09): reject a wrong password BEFORE closing the live DB
                // so the common failure case leaves the vault fully intact.
                if (backupPassword != null) {
                    ImportExportService.validateBackupPassword(bytes, backupPassword)
                }
                repository.closeDatabase()
                ImportExportService.importBackup(getApplication(), bytes, repository.encryptionKey, backupPassword)
                DatabaseSecurityHelper.clearRestoreBlock(getApplication())
                // B1-DB-1 (phase-43): the corruption flag was set when the vault open
                // was quarantined; a successful restore must clear it too, otherwise the
                // quarantined-open guard keeps every open failing behind the recovery
                // screen after the post-restore process restart.
                DatabaseSecurityHelper.clearCorruptionDetected(getApplication())
                _restoreBlocked.value = false
                _corruptionBlocked.value = false
                _databaseTampered.value = false
                // B2-CRYPTO-09 (phase-107): a restored backup may carry legacy
                // global-AAD field ciphertexts (even on this device the DEK is
                // unchanged, so the restore re-key skips them). Force re-migration
                // on the next launch so every restored row is record-bound.
                settings.fieldAadMigrated = false
                delay(500)
                kotlin.system.exitProcess(0)
            } catch (e: Exception) {
                // H1 (phase-09): a failed recovery (wrong password, corrupt backup)
                // must never leave a dead Room instance behind — reopen it so any
                // subsequent operation hits a live connection, then surface the error.
                runCatching { repository.reopenDatabase(getApplication()) }
                onError(e.message ?: "Recovery failed.")
            }
        }
    }

    fun restorePage(id: String) {
        viewModelScope.launch {
            repository.restorePage(id)
        }
    }

    fun deletePagePermanently(id: String) {
        viewModelScope.launch {
            repository.deletePagePermanently(id)
            // Phase 07: drop the page's paper-texture pref so the orphan-file sweep
            // in EditorScreen can reclaim the stored file too.
            settings.setPaperTexturePathForPage(id, null)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = null
            }
        }
    }

    fun movePage(id: String, targetSectionId: String) {
        viewModelScope.launch {
            repository.movePage(id, targetSectionId)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
        }
    }

    fun insertPaletteItem(item: PaletteItemEntity) {
        viewModelScope.launch {
            repository.insertPaletteItem(item)
        }
    }

    fun deletePaletteItem(id: String) {
        viewModelScope.launch {
            repository.deletePaletteItem(id)
        }
    }

    fun clearPaletteItemsByType(type: String) {
        viewModelScope.launch {
            repository.clearPaletteItemsByType(type)
        }
    }

    // ---------- Phase 3: Connected Knowledge Vault (Obsidian PKM Engine) ----------
    fun openOrCreateDailyNote(context: android.content.Context, onOpen: (NotePageEntity) -> Unit) {
        viewModelScope.launch {
            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val targetTitle = "$todayStr.md"

            val activePages = repository.getAllActivePages()
            val existing = activePages.find {
                it.title.equals(targetTitle, ignoreCase = true) ||
                it.title.equals(todayStr, ignoreCase = true)
            }

            if (existing != null) {
                onOpen(existing)
            } else {
                val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
                val journalTemplate = """
                    # 📅 Journal - $todayStr

                    #journal #daily

                    ## 🎯 Today's Focus & Goals
                    - [ ] 

                    ## 📝 Notes & Reflection


                    ---
                    *Linked Notes: [[Home]]*
                """.trimIndent()

                val filePath = ImportExportService.persistFile(context, targetTitle, journalTemplate.toByteArray())
                val newPage = repository.createPage(
                    sectionId = sec.id,
                    title = targetTitle,
                    sourceFilePath = filePath,
                    sourceFileType = "text",
                    template = "blank",
                    extractedText = journalTemplate
                )
                selectPage(newPage)
                onOpen(newPage)
            }
        }
    }

    fun openPageByTitle(title: String, context: android.content.Context, onOpen: (NotePageEntity) -> Unit) {
        viewModelScope.launch {
            val activePages = repository.getAllActivePages()
            val cleanTarget = title.replace(".md", "").replace(".txt", "").trim()
            val existing = activePages.find {
                val pageTitleClean = it.title.replace(".md", "").replace(".txt", "").trim()
                pageTitleClean.equals(cleanTarget, ignoreCase = true)
            }

            if (existing != null) {
                onOpen(existing)
            } else {
                val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
                val targetFileName = "$cleanTarget.md"
                val initialContent = "# $cleanTarget\n\nCreated via WikiLink `[[$cleanTarget]]`."
                val filePath = ImportExportService.persistFile(context, targetFileName, initialContent.toByteArray())
                val newPage = repository.createPage(
                    sectionId = sec.id,
                    title = targetFileName,
                    sourceFilePath = filePath,
                    sourceFileType = "text",
                    template = "blank",
                    extractedText = initialContent
                )
                selectPage(newPage)
                onOpen(newPage)
            }
        }
    }

    // ---------- Security & Master Password ----------
    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
    }

    suspend fun setMasterPassword(password: String): Boolean {
        // B2-CRYPTO-07 (phase-113): length and emptiness are judged on the
        // NFKC-NORMALIZED password, in grapheme clusters — this is exactly the
        // byte sequence deriveKey will hash, so the check can never be undone
        // by normalization collapsing the input differently at unlock time.
        val normalized = EncryptionService.normalizePassword(password)
        if (normalized.isBlank() || !EncryptionService.isValidPasswordLength(normalized)) return false
        var kek: ByteArray? = null
        val dek: ByteArray
        return try {
            val salt = EncryptionService.generateSalt()
            // Reuse an existing DEK when one is already in play (e.g. previously
            // device-wrapped), so existing ciphertext stays valid; otherwise mint a new one.
            val existingDek = repository.encryptionKey
            val (targetDek, wrappedDek) = withContext(Dispatchers.Default) {
                val existing = existingDek ?: security.readDek()
                val d = existing ?: EncryptionService.generateDek()
                kek = EncryptionService.deriveKey(normalized, salt)
                d to EncryptionService.encrypt(d, kek)
            }
            dek = targetDek

            settings.masterPasswordSalt = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
            settings.masterPasswordWrappedDek = wrappedDek

            repository.encryptionKey = dek
            _hasMasterPassword.value = true
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            viewModelScope.launch {
                repository.reencryptPlaintextFields(dek)
                repository.checkpointWal()
                withContext(Dispatchers.IO) {
                    repository.stampDatabaseChecksum(getApplication())
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            kek?.fill(0.toByte())
        }
    }

    suspend fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean {
        // B2-CRYPTO-07 (phase-113): same normalized-form length gate as
        // setMasterPassword, so a new password is always stored+derived in the
        // single NFKC form and survives re-typing on any keyboard/IME.
        val newPasswordNormalized = EncryptionService.normalizePassword(newPassword)
        if (!EncryptionService.isValidPasswordLength(newPasswordNormalized)) return false
        if (!verifyMasterPassword(oldPassword)) return false
        val currentDek = repository.encryptionKey ?: return false
        var kek: ByteArray? = null

        return try {
            val newSalt = EncryptionService.generateSalt()
            val newWrappedDek = withContext(Dispatchers.Default) {
                kek = EncryptionService.deriveKey(newPasswordNormalized, newSalt)
                EncryptionService.encrypt(currentDek, kek)
            }

            settings.masterPasswordSalt = android.util.Base64.encodeToString(newSalt, android.util.Base64.NO_WRAP)
            settings.masterPasswordWrappedDek = newWrappedDek

            if (settings.biometricAuthEnabled) {
                security.storeDek(currentDek, authRequired = true)
            }

            _hasMasterPassword.value = true
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            true
        } catch (e: Exception) {
            false
        } finally {
            kek?.fill(0.toByte())
        }
    }

    private fun computeLockoutDelayMs(failures: Int): Long {
        // Exponential backoff: 30s, 1m, 2m, 4m, ... capped at 15 minutes.
        val exponent = (failures - MAX_FAILED_ATTEMPTS).coerceAtLeast(0)
        val delay = 30_000L * (1L shl exponent.coerceAtMost(5))
        return delay.coerceAtMost(15 * 60 * 1000L)
    }

    private fun startLockoutTicker() {
        lockoutTickerJob?.cancel()
        lockoutTickerJob = viewModelScope.launch {
            try {
                while (true) {
                    val remaining = (settings.lockoutUntilEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    _lockoutRemainingMs.value = remaining
                    if (remaining <= 0L) {
                        settings.lockoutUntilEpochMs = 0L
                        break
                    }
                    delay(1000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    fun lockoutActive(): Boolean = settings.lockoutUntilEpochMs > System.currentTimeMillis()

    suspend fun verifyMasterPassword(password: String): Boolean {
        if (lockoutActive()) return false
        if (settings.masterPasswordSalt == null || settings.masterPasswordWrappedDek == null) return false
        return try {
            val dek = unwrapMasterDek(password)
                ?: throw IllegalStateException("wrong master password")
            repository.encryptionKey = dek
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            settings.failedUnlockAttempts = 0
            settings.lockoutUntilEpochMs = 0L
            _lockoutRemainingMs.value = 0L
            initializeData()
            true
        } catch (e: Exception) {
            val newCount = _failedUnlockAttempts.value + 1
            _failedUnlockAttempts.value = newCount
            settings.failedUnlockAttempts = newCount
            if (newCount >= MAX_FAILED_ATTEMPTS) {
                // Persisted lockout + exponential backoff; survives app restarts.
                settings.lockoutUntilEpochMs = System.currentTimeMillis() + computeLockoutDelayMs(newCount)
                _lockoutRemainingMs.value = computeLockoutDelayMs(newCount)
                repository.zeroizeKey()
                _authenticated.value = false
                startLockoutTicker()
            }
            false
        }
    }

    /**
     * Unlocks the wrapped master DEK from a typed password (B2-CRYPTO-07).
     *
     * Always tries the NFKC-normalized password first — the single derivation
     * path used to WRITE every key since phase-113 — so a password typed once
     * as NFC and retyped as NFD (or vice versa) unlocks. ONLY when the raw
     * input is NOT already normalized does it then try the legacy pre-fix raw
     * bytes ([EncryptionService.deriveKeyLegacyRaw]) so a vault created before
     * normalization with a non-NFKC byte sequence still opens — never a new
     * lockout introduced by the fix itself.
     *
     * Side-effect free (no failed-attempt counters, no lockout). Returns the
     * DEK bytes or null; callers own the returned bytes and MUST zeroize them.
     * Every rejected KEK is zeroized here.
     */
    private suspend fun unwrapMasterDek(password: String): ByteArray? = withContext(Dispatchers.Default) {
        val saltStr = settings.masterPasswordSalt ?: return@withContext null
        val wrappedDek = settings.masterPasswordWrappedDek ?: return@withContext null
        val salt = android.util.Base64.decode(saltStr, android.util.Base64.NO_WRAP)
        for (candidateKey in EncryptionService.deriveKeyCandidates(password, salt)) {
            try {
                val dek = EncryptionService.decrypt(wrappedDek, candidateKey)
                candidateKey.fill(0.toByte())
                return@withContext dek
            } catch (e: javax.crypto.AEADBadTagException) {
                // Wrong key for this candidate — zeroize it and try the next
                // (the legacy raw form) if any.
                candidateKey.fill(0.toByte())
            }
        }
        null
    }

    /**
     * Side-effect-free master password check for password-derived backups.
     * Must not bump failed-attempt counters or trigger lockout — a typo in the
     * backup dialog is not an attack.
     */
    suspend fun isMasterPasswordValid(password: String): Boolean {
        val dek = try {
            unwrapMasterDek(password)
        } catch (e: Exception) {
            null
        } ?: return false
        dek.fill(0.toByte())
        return true
    }

    suspend fun setBiometricEnabled(enabled: Boolean, password: String): Boolean {
        if (!verifyMasterPassword(password)) return false
        val dek = repository.encryptionKey ?: return false
        
        try {
            security.storeDek(dek, authRequired = enabled)
            settings.biometricAuthEnabled = enabled
            _biometricEnabled.value = enabled
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun getBiometricCipher(): androidx.biometric.BiometricPrompt.CryptoObject? {
        val cipher = security.getDecryptionCipher() ?: return null
        return androidx.biometric.BiometricPrompt.CryptoObject(cipher)
    }

    fun verifyBiometricsAndUnlock(result: androidx.biometric.BiometricPrompt.AuthenticationResult): Boolean {
        if (lockoutActive()) return false
        val cipher = result.cryptoObject?.cipher ?: return false
        val dek = security.decryptWithCipher(cipher) ?: return false
        repository.encryptionKey = dek
        _authenticated.value = true
        _failedUnlockAttempts.value = 0
        settings.failedUnlockAttempts = 0
        settings.lockoutUntilEpochMs = 0L
        _lockoutRemainingMs.value = 0L
        initializeData()
        return true
    }

    fun disableBiometricFallback() {
        settings.biometricAuthEnabled = false
        _biometricEnabled.value = false
        security.clearDek()
    }

    suspend fun removeMasterPassword(password: String): Boolean {
        if (!verifyMasterPassword(password)) return false
        val dek = repository.encryptionKey ?: return false
        // Re-wrap the DEK under a device-only keystore key so the vault remains
        // readable after the password is removed (rows stay encrypted at rest).
        return try {
            security.storeDek(dek, authRequired = false)
            settings.clearSecuritySettings()
            _hasMasterPassword.value = false
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            settings.failedUnlockAttempts = 0
            settings.lockoutUntilEpochMs = 0L
            true
        } catch (e: Exception) {
            false
        }
    }

    fun createNoteVersion(pageId: String, title: String, extractedText: String?, versionNote: String = "Saved version") {
        viewModelScope.launch {
            repository.createNoteVersion(pageId, title, extractedText, versionNote)
        }
    }

    suspend fun getNoteVersions(pageId: String): List<com.authorss81.noteflow.data.model.NoteVersionEntity> {
        return repository.getNoteVersions(pageId)
    }

    /**
     * C2b: builds a genuine encrypted backup archive for WebDAV upload. Runs a
     * full WAL checkpoint FIRST (otherwise recent committed transactions living
     * in the -wal file would silently miss the backup) and re-stamps the HMAC
     * tamper baseline before packaging via ImportExportService.exportBackup
     * (the same device-keyed encrypted-archive format the local backup uses).
     */
    fun exportEncryptedBackupToZip(targetZip: java.io.File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repository.checkpointWal()
                withContext(Dispatchers.IO) {
                    repository.stampDatabaseChecksum(getApplication())
                }
                val backupFile = ImportExportService.exportBackup(getApplication(), repository.encryptionKey)
                backupFile.copyTo(targetZip, overwrite = true)
                onComplete(true)
            } catch (e: Exception) {
                onComplete(false)
            }
        }
    }

    /**
     * C2c: restores a downloaded WebDAV backup through the SAME transactional
     * path as a local restore (ImportExportService.importBackup →
     * restoreFromZip): temp extract → PRAGMA integrity_check → re-key to the
     * current DEK → user_version guard → HMAC re-arm → atomic swap. NEVER a
     * blind copy over the live vault. The DB is closed first and the caller
     * must restart the app (like the local restore flow).
     */
    fun restoreEncryptedBackupFromZip(sourceZip: java.io.File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { sourceZip.readBytes() }
                repository.closeDatabase()
                ImportExportService.importBackup(getApplication(), bytes, repository.encryptionKey)
                // B2-CRYPTO-09 (phase-107): re-migrate restored rows to per-record
                // AAD on next launch (see attemptRecoveryFromBackup comment).
                settings.fieldAadMigrated = false
                onComplete(true)
            } catch (e: Exception) {
                // H1 (phase-09): a failed WebDAV restore leaves the live DB closed —
                // reopen it so the vault behind the dialog is not bricked. The caller
                // (WebDavSyncDialog) tells the user to restart to fully re-initialize.
                runCatching { repository.reopenDatabase(getApplication()) }
                onComplete(false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase 38 — command palette (HUD) search + plugin-action routing.
    // Pure math lives in CommandPaletteMath; this layer only fixes it to the
    // cached corpus (title/extractedText, tags from the cached tag hierarchy)
    // and to PluginManager invocations. No background scanning, no network.
    // -----------------------------------------------------------------------

    /** Cached per-epoch palette documents + page tag map (never re-scanned). */
    private data class PaletteIndex(
        val docs: List<CommandPaletteMath.PaletteDoc>,
        val pageTags: Map<String, Set<String>>,
        val corpusGeneration: Long
    )

    @Volatile
    private var paletteIndex: PaletteIndex? = null

    /**
     * Build (once per corpus generation) the palette's document set from the
     * CACHED decrypted corpus — the exact source `NoteRepository` already keeps
     * for search — plus the epoch-cached tag hierarchy flattened to per-page
     * tag sets. Building is the only "scan": it happens once, reads no files,
     * and is reused across keystrokes. Returns empty on vault lock (corpus path
     * auto-empties).
     */
    private suspend fun buildPaletteIndex(): PaletteIndex {
        val generation = repository.currentSearchCorpusGeneration
        paletteIndex?.let { existing ->
            if (existing.corpusGeneration == generation) return existing
        }
        val pages = repository.cachedCorpus()
        val tagHierarchy = WikiLinkParser.buildTagHierarchy(pages)
        val pageTags = WikiLinkParser.flattenPageTags(tagHierarchy)
        val docs = pages.map { page ->
            CommandPaletteMath.PaletteDoc(
                id = page.id,
                title = page.title,
                body = page.extractedText ?: "",
                tags = pageTags[page.id] ?: page.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                updatedAt = page.updatedAt
            )
        }
        val index = PaletteIndex(docs, pageTags, generation)
        if (repository.currentSearchCorpusGeneration == generation) {
            paletteIndex = index
        }
        return index
    }

    /**
     * Reset the palette cache immediately (mutation already bumped the corpus
     * generation; this forces the next query to rebuild even if generation was
     * raced). Also called on lock.
     */
    fun invalidatePaletteIndex() {
        paletteIndex = null
    }

    /**
     * Query the cached palette index with optional tag filter. Runs on the IO
     * dispatcher so a keystroke never blocks the UI thread. Returns ranked
     * notes + a parallel list of matching tag suggestions.
     */
    suspend fun commandPaletteSearch(
        query: String,
        selectedTags: Set<String> = emptySet(),
        requireAllTags: Boolean = true
    ): CommandPaletteSearchResult = withContext(Dispatchers.IO) {
        val index = buildPaletteIndex()
        val notes = if (query.isBlank()) {
            // Blank query → show most recently updated notes (recency browse).
            index.docs
                .sortedByDescending { it.updatedAt }
                .take(8)
                .map { d ->
                    CommandPaletteMath.RankedNote(
                        doc = d,
                        score = d.updatedAt.toFloat() / 1000f,
                        snippet = ""
                    )
                }
        } else {
            CommandPaletteMath.rank(query, index.docs, selectedTags, requireAllTags)
        }
        val tagSuggestions = if (query.isBlank()) emptyList() else suggestTags(query, index)
        CommandPaletteSearchResult(notes, tagSuggestions)
    }

    private fun suggestTags(
        query: String,
        index: PaletteIndex
    ): List<TagSuggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val occurrences = HashMap<String, Int>()
        index.pageTags.values.forEach { tags ->
            tags.forEach { tag ->
                if (tag.lowercase().contains(q)) occurrences[tag] = (occurrences[tag] ?: 0) + 1
            }
        }
        return occurrences.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .map { TagSuggestion(it.key, it.value) }
    }

    /**
     * Run a palette action through PluginManager. [arg] is the palette query
     * tail for keyword actions (e.g. `web: android 16` → "android 16");
     * [noteText] is the currently-open note's text so text-scoped actions
     * (read-aloud, assistant, transform) have a real subject. Never throws;
     * every path returns a user-facing summary.
     */
    suspend fun runPaletteAction(
        action: CommandPaletteMath.ActionMatch,
        arg: String,
        noteText: String?
    ): PaletteActionResult = when (action.action.capabilityKey) {
        PluginCapability.WebSearch.key -> when (val r = searchWeb(arg)) {
            is PluginResult.Success -> when (val o = r.value) {
                is WebSearchOutcome.Success -> {
                    val first = o.results.firstOrNull()
                    if (first == null) PaletteActionResult.Text("Web search found no results.")
                    else PaletteActionResult.Text("[${first.title}](${first.url})")
                }
                is WebSearchOutcome.Error -> PaletteActionResult.Error(o.message)
            }
            is PluginResult.Failure -> PaletteActionResult.Error(r.message)
            is PluginResult.Unavailable -> PaletteActionResult.Error(r.message)
        }
        PluginCapability.TextTransform.key -> {
            val commandPaletteText = routeTextArg(arg, noteText)
            when (val r = transformNoteText(commandPaletteText)) {
                is PluginResult.Success -> PaletteActionResult.Text((r.value as? String) ?: "Transformed.")
                else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Transform unavailable.")
            }
        }
        PluginCapability.UnitConversion.key -> when (val r = convertUnits(arg)) {
            is PluginResult.Success -> when (val o = r.value) {
                is UnitConversionOutcome.Success -> PaletteActionResult.Text(o.text)
                is UnitConversionOutcome.Error -> PaletteActionResult.Error(o.message)
            }
            else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Unit conversion unavailable.")
        }
        PluginCapability.Dictionary.key -> when (val r = lookupDictionaryWord(arg)) {
            is PluginResult.Success -> when (val o = r.value) {
                is DictionaryOutcome.Success -> {
                    val def = o.lookup.definitions.firstOrNull()
                    val text = def?.let { "“${o.lookup.word}” (${o.lookup.source}): ${it.definition}" }
                        ?: "“${o.lookup.word}”: no definition returned."
                    PaletteActionResult.Text(text)
                }
                is DictionaryOutcome.NotFound -> PaletteActionResult.Text(o.message)
                is DictionaryOutcome.Error -> PaletteActionResult.Error(o.message)
            }
            else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Dictionary unavailable.")
        }
        PluginCapability.Weather.key -> when (val r = fetchWeatherSnapshot()) {
            is PluginResult.Success -> when (val o = r.value) {
                is WeatherOutcome.Success -> {
                    val s = o.snapshot
                    PaletteActionResult.Text(
                        "${s.city}: ${s.weatherDescription} — ${s.tempMinC}° / ${s.tempMaxC}°C"
                    )
                }
                is WeatherOutcome.Error -> PaletteActionResult.Error(o.message)
            }
            else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Weather unavailable.")
        }
        PluginCapability.ReadAloud.key -> {
            val passage = noteText ?: arg
            when (val r = readAloud(passage, quietMode = false)) {
                is PluginResult.Success -> when (val o = r.value) {
                    is ReadAloudOutcome.Started -> PaletteActionResult.Text("Reading aloud (${o.chunkCount} chunks).")
                    is ReadAloudOutcome.Empty -> PaletteActionResult.Text(o.message)
                    is ReadAloudOutcome.Quiet -> PaletteActionResult.Text(o.message)
                    is ReadAloudOutcome.Error -> PaletteActionResult.Error(o.message)
                }
                else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Read-aloud unavailable.")
            }
        }
        PluginCapability.Assistant.key -> {
            val question = routeTextArg(arg, noteText)
            val subject = noteText ?: ""
            when (val r = assistantAnswerQuestion(subject, question)) {
                is PluginResult.Success -> when (val o = r.value) {
                    is AssistantOutcome.Success -> PaletteActionResult.Text(o.text)
                    else -> PaletteActionResult.Error(
                        (r.value as? AssistantOutcome.ModelNotReady)?.message
                            ?: (r.value as? AssistantOutcome.Error)?.message
                            ?: "Assistant unavailable."
                    )
                }
                else -> PaletteActionResult.Error((r as? PluginResult.Failure)?.message ?: "Assistant unavailable.")
            }
        }
        PluginCapability.OCR.key ->
            PaletteActionResult.Error("OCR needs an image — open a note containing a photo, then use the editor's OCR.")
        PluginCapability.Dictation.key ->
            PaletteActionResult.Error("Dictation needs the microphone — open a note and use the editor's dictation button.")
        else -> PaletteActionResult.Error("This action isn't installed. Check ⋮ menu → Plugin Store.")
    }

    private fun routeTextArg(
        arg: String,
        noteText: String?
    ): String = if (arg.isNotBlank()) arg else (noteText ?: "")

    /** Result of a palette action invocation, shaped for a dialog/snackbar. */
    sealed class PaletteActionResult {
        data class Text(val text: String) : PaletteActionResult()
        data class Error(val message: String) : PaletteActionResult()
    }

    data class TagSuggestion(
        val tag: String,
        val count: Int
    )

    data class CommandPaletteSearchResult(
        val notes: List<CommandPaletteMath.RankedNote>,
        val tagSuggestions: List<TagSuggestion>
    )

    fun lock() {

        repository.zeroizeKey()
        invalidatePaletteIndex()
        // N6/A1: decrypted content must not stay resident in StateFlows after lock.
        _pages.value = emptyList()
        _selectedPage.value = null
        _sections.value = emptyList()
        _selectedSection.value = null
        _selectedNotebook.value = null
        if (settings.hasMasterPassword) {
            _authenticated.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.zeroizeKey()
    }
}
