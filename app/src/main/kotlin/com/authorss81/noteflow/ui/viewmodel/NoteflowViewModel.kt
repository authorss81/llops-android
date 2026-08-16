package com.authorss81.noteflow.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
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
import com.authorss81.noteflow.services.BiometricKeyBindingPolicy
import com.authorss81.noteflow.services.ClipboardGuard
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.DecryptFailurePolicy
import com.authorss81.noteflow.services.DekAtRestMode
import com.authorss81.noteflow.services.DekAtRestPolicy
import com.authorss81.noteflow.services.DekReadResult
import com.authorss81.noteflow.services.EditorFlushPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.IntegrityWarningDismissalGate
import com.authorss81.noteflow.services.KeystoreKeyLostException
import com.authorss81.noteflow.services.MarkdownBodySaveCoordinator
import com.authorss81.noteflow.services.NoteBodyVaultPolicy
import com.authorss81.noteflow.services.PasswordStrengthPolicy
import com.authorss81.noteflow.services.SecurityService
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.services.SettingsPluginEnableStore
import com.authorss81.noteflow.services.SettingsPluginInstallStore
import com.authorss81.noteflow.services.SettingsPluginSettingsStore
import com.authorss81.noteflow.services.VaultLockedWriteException
import com.authorss81.noteflow.services.VaultWriteGate
import com.authorss81.noteflow.services.VoiceNoteCrypto
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NoteflowViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Application get() = getApplication()

    val settings = SettingsManager(appContext)
    val security = SecurityService.forDevice(appContext)
    private val db by lazy { NoteflowDatabase.getDatabase(appContext) }
    val repository by lazy { NoteRepository(db, ImportExportService.getImportsDir(appContext)) }

    // B2-UI-1 (phase-49): stash of page snapshots that a vault lock raced.
    // EditorScreen flushes route through this: DEK present ⇒ persist now;
    // DEK zeroized ⇒ defer here and flush encrypted after the next unlock.
    private val editorFlushPolicy = EditorFlushPolicy()

    // B2-UI-5 (phase-74): serializes + latest-wins every markdown/text note-body
    // save, so a slow older write can never land after a newer one (torn-file
    // analog moved into the DB), and coordinates the body READ with any in-flight
    // save so a re-opened page never shows stale content that would be re-saved.
    private val markdownBodySaveCoordinator = MarkdownBodySaveCoordinator()

    // B2-DOS-01 (phase-50): the geometry-cap notice is a ONE-TIME message per
    // page per session (AGENTS.md "never silent degradation — one-time
    // non-alarming message"). Keyed by page id so a debounced autosave that
    // keeps re-encountering an oversized stroke cannot re-notify every second;
    // cleared at lock so a new unlock session can warn again if it matters.
    private val geometryCappedNotifiedPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun maybeNotifyGeometryCapped(pageId: String, gate: com.authorss81.noteflow.services.StrokeGeometryGateResult) {
        if (!gate.geometryWasCapped) return
        if (geometryCappedNotifiedPages.add(pageId)) showSnackbar(gate.noticeText)
    }

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

    // B1-AUTH-03 (phase-67): true once the plugin layer has been booted for the
    // current session. Reset by lock() so the next successful unlock re-boots it
    // (bounded exactly once per authenticated session). Declared above the init
    // block for the same reason as the flow backings above.
    // phase-67 review-fix: @Volatile so lock()'s reset (any thread) is visible to
    // a subsequent unlock running on another thread.
    @Volatile
    private var pluginLifecycleStarted = false

    init {
        // B1-AUTH-03 (phase-67): NO plugin runtime loading or lifecycle hook may
        // run before the vault is unlocked. A passwordless vault is authenticated
        // from boot (its device-wrapped DEK is the boot credential), so the plugin
        // layer boots immediately here; a password-protected vault sits on the
        // LockScreen and defers ALL of it — the store re-materialization loop,
        // onProcessStart → onEnable, and the plugin state flows — to the first
        // successful unlock (verifyMasterPassword / verifyBiometricsAndUnlock call
        // startPluginLifecycle()). lock() tears the hooks down via
        // PluginRegistry.pauseLifecycle so no plugin ever runs with a live
        // application context while locked.
        if (!settings.hasMasterPassword) {
            startPluginLifecycle()
        }
    }

    /**
     * B1-AUTH-03 (phase-67): the ONLY sanctioned way to boot the plugin layer.
     *
     * Re-materializes downloadable plugins installed in a previous session (the
     * persisted entry + on-disk artifact are re-verified and re-loaded into the
     * registry), fires the process-start onEnable hooks for plugins already
     * enabled in the persisted store, and refreshes the plugin state flows.
     * Re-arm/teardown symmetry: [PluginRegistry.pauseLifecycle] on lock, this on
     * unlock — so a plugin's `onEnable(context)` can never run before the user
     * authenticates. Idempotent per authenticated session.
     */
    private fun startPluginLifecycle() {
        // phase-67 review-fix: double-checked under a short critical section —
        // the @Volatile flag + this lock make the "exactly once per authenticated
        // session" claim a guarantee even if two unlock paths race the boot (each
        // step below is registry-idempotent: registerRemotePlugin refuses
        // duplicates, resumeLifecycle is guarded by enabledNotified).
        if (pluginLifecycleStarted) return
        synchronized(this) {
            if (pluginLifecycleStarted) return
            pluginLifecycleStarted = true
        }
        // Phase 23: register the real downloadable-plugin runtime (the lazy
        // `pluginRuntime` swaps the Phase-22 stub via the registry seam).
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
        // previous session (see PluginRegistry.onProcessStart / resumeLifecycle).
        pluginRegistry.resumeLifecycle(appContext)
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
        // B1-AUTH-03 (phase-67 review-fix): while the vault is locked the plugin
        // layer is quiesced (pluginRegistry.isLifecyclePaused) — do not re-run
        // plugin availability/snapshot bytecode on the LockScreen; keep the last
        // pre-lock flows, which the next unlock's startPluginLifecycle refreshes.
        if (pluginRegistry.isLifecyclePaused) return
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
        // B1-AUTH-03 (phase-67 review-fix): a self-check runs plugin bytecode —
        // never execute it while the vault is locked. The diagnostics UI is behind
        // the LockScreen anyway; the check runs on the next unlock.
        if (pluginRegistry.isLifecyclePaused) return
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

    /** B1-DB-6 (phase-87): per-session dismissal gate for the tamper banner — a
     *  single "Don't show again" tap can never permanently disable the vault's
     *  only tamper tripwire. The gate lives for the process/session; a fresh
     *  launch re-arms it (a new undismissed session). */
    private val integrityWarningDismissal = IntegrityWarningDismissalGate()

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
     * B1-CRYPTO-05 (phase-64): the AndroidKeyStore key that wraps the device DEK
     * copy is LOST (app-data restore / ROM migration / keystore reset on some
     * OEMs) while the blob itself is still stored — or the blob is unreadable.
     * The vault database is NOT corrupt (only the device wrapper is gone), so we
     * show the dedicated keystore-key-lost recovery screen (restore-from-backup
     * / explicit start-fresh) INSTEAD of silently minting a fresh DEK over the
     * still-encrypted vault. Never auto-quarantine in this state.
     */
    private val _keystoreKeyLost = MutableStateFlow(false)
    val keystoreKeyLost: StateFlow<Boolean> = _keystoreKeyLost.asStateFlow()

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
                _databaseTampered.value = tampered && integrityWarningDismissal.mayShow()
            } else {
                _databaseTampered.value = false
            }
        }
    }

    /**
     * B1-DB-6 (phase-87): dismissing the banner is scoped to the CURRENT session
     * ONLY. "Don't show again" hides it for the rest of this session, but it can
     * never permanently kill the integrity check — the pre-fix persistent write
     * `databaseIntegrityCheckEnabled = false` (and the persisted
     * `databaseIntegrityWarningDismissed` latch) are gone, so the vault's tamper
     * tripwire is re-armed and can flag again on the next launch.
     */
    fun dismissDatabaseIntegrityWarning(dontShowAgain: Boolean) {
        integrityWarningDismissal.onDismiss(dontShowAgain)
        _databaseTampered.value = false
    }

    fun setDatabaseIntegrityCheckEnabled(enabled: Boolean) {
        settings.databaseIntegrityCheckEnabled = enabled
        _databaseIntegrityCheckEnabled.value = enabled
        if (enabled) {
            integrityWarningDismissal.onReenable()
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
    // B1-CRYPTO-05 (phase-64): the keystore-key-lost state is a THIRD gate — while
    // it is set no flow may open the DB either (the passwordless factory would
    // throw KeystoreKeyLostException), so the recovery screen can offer
    // restore/start-fresh without any open racing it.
    private val dbGate: Flow<Boolean> = combine(_authenticated, _corruptionBlocked, _keystoreKeyLost) { isAuth, blocked, keyLost ->
        isAuth && !blocked && !keyLost
    }.distinctUntilChanged()

    private val _hasMasterPassword = MutableStateFlow(settings.hasMasterPassword)
    val hasMasterPassword: StateFlow<Boolean> = _hasMasterPassword.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(settings.biometricAuthEnabled)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    // B1-CRYPTO-07 (phase-65): one-shot NON-alarming message when a biometric-unlock
    // enable is refused because the platform cannot bind a key to AUTH_BIOMETRIC_STRONG
    // (API < 30). Cleared on every successful enable; the settings dialog reads it to
    // explain a refused toggle instead of the generic "Incorrect Master Password".
    private val _biometricRefusalMessage = MutableStateFlow<String?>(null)
    val biometricRefusalMessage: StateFlow<String?> = _biometricRefusalMessage.asStateFlow()

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

    /**
     * B1-DB-8 (phase-88): true once THIS session's persistent-decrypt-failure
     * escalation fired (see [initializeDataCore]). Reset at every session
     * boundary (initialize, re-key, restore) so a fresh unlock recounts instead
     * of being blocked by a stale flag; the corruption flag itself persists in
     * SharedPreferences until the user restores/starts-fresh.
     */
    @Volatile
    private var decryptPersistenceEscalated = false

    // B1-AUTH-02 (phase-47): true after lock() disposes the SQLCipher connection.
    // A successful explicit unlock (password or biometrics) must reinstate a live
    // connection BEFORE any data-layer flow re-subscribes, so the lock boundary is
    // enforced at the data layer, not just by the Compose LockScreen boolean.
    private var databaseDisposedByLock = false

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
                // dead DB.
                // B1-CRYPTO-05 (phase-64): a failed open whose device DEK copy is
                // stored-but-undecryptable is a LOST KEYSTORE KEY, not corruption —
                // the quarantiner never set its flag here (a key-lost passwordless
                // boot is blocked before any open). Surface the keystore-key-lost
                // recovery screen instead of treating survivable data as corrupt.
                if (DatabaseSecurityHelper.hasCorruptionDetected(appContext)) {
                    _corruptionBlocked.value = true
                } else if (runCatching { security.readDekResult() is DekReadResult.KeyLost }.getOrDefault(false)) {
                    _keystoreKeyLost.value = true
                } else {
                    // Any other exception is rethrown (data corruption must never
                    // be silently swallowed).
                    throw e
                }
            }
        }
    }

    private suspend fun initializeDataCore() {
            // B1-DB-8 (phase-88): fresh per-session decrypt-failure ledger. Any
            // genuine ciphertext that fails AES-GCM auth while a DEK is present is
            // recorded by [NoteRepository]; once [DecryptFailurePolicy]'s threshold
            // of DISTINCT records is crossed the failure is judged PERSISTENT (a
            // re-key/restore mismatch or a manipulated DB — not an isolated note)
            // and this listener escalates to the existing corruption/restore event:
            // the flag raises the recovery screen (restore-from-backup / re-key /
            // start-fresh) instead of silently degrading the vault to "Unreadable"
            // markers. The listener runs on a repository reader thread and only
            // performs thread-safe work (StateFlow writes + snackbar + prefs).
            repository.resetDecryptFailures()
            decryptPersistenceEscalated = false
            repository.decryptFailureListener = {
                if (!decryptPersistenceEscalated && repository.decryptFailuresPersistent) {
                    decryptPersistenceEscalated = true
                    DatabaseSecurityHelper.setCorruptionDetected(appContext)
                    _corruptionBlocked.value = true
                    showSnackbar(DecryptFailurePolicy.PERSISTENT_DECRYPT_FAILURE_NOTICE, isLong = true)
                }
            }
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
            if (!settings.noteBodyPlaintextMigrated) {
                // B1-DB-4 (phase-44): pre-fix vaults kept a note's body as a
                // plaintext .md/.txt file under filesDir/noteflow/imports — only
                // the DB copy was field-encrypted. Sweep every text page's body
                // into the encrypted column and delete the plaintext file so no
                // note body remains at rest in the clear. The file's content is
                // written (encrypted) BEFORE the file is deleted and undecryptable
                // pages are left untouched (see migrateLegacyPlaintextNoteBodies).
                try {
                    val result = repository.migrateLegacyPlaintextNoteBodies()
                    // B1-DB-6: WAL content is outside the DB-file HMAC, so flush it
                    // and re-stamp the baseline (a later verify must not flag the
                    // migrated rows as tampered). Re-arms even on a partial run so
                    // already-written rows are covered; the flag stays unset while
                    // any file remains, re-running the sweep on a later unlock.
                    if (result.rowsMigrated + result.filesDeleted > 0) {
                        repository.checkpointWal()
                        repository.stampDatabaseChecksum(appContext)
                    }
                    if (result.isComplete) {
                        settings.noteBodyPlaintextMigrated = true
                    }
                } catch (e: Exception) {
                    // Keep the flag unset; re-run on the next unlock.
                }
            }
            if (!settings.voiceNotesEncryptedMigrated) {
                // B1-DB-3 (phase-54): pre-fix builds recorded voice memos as
                // PLAINTEXT .m4a under filesDir/voice_notes. Sweep every
                // referenced recording into an AES-GCM `.enc` blob (DEK) and
                // delete the plaintext so no private memo remains at rest in
                // the clear; the DB rows (media_embeds.contentUrlOrPath) are
                // retargeted to the blob. Also clear any stale plaintext
                // recording/playback temps in cacheDir from an interrupted
                // pre-fix session. The plaintext is never deleted before its
                // encryption completed; rows that could not be encrypted keep
                // their file and the flag stays unset for a later unlock.
                try {
                    VoiceNoteCrypto.sweepPlaintextTemps(appContext.cacheDir)
                    val result = repository.migrateLegacyPlaintextVoiceNotes()
                    // B1-DB-6: WAL content is outside the DB-file HMAC, so flush
                    // and re-stamp after the media_embeds row mutations.
                    if (result.rowsMigrated + result.orphansDeleted > 0) {
                        repository.checkpointWal()
                        repository.stampDatabaseChecksum(appContext)
                    }
                    if (result.isComplete) {
                        settings.voiceNotesEncryptedMigrated = true
                    }
                } catch (e: Exception) {
                    // Keep the flag unset; re-run on the next unlock.
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
            // B1-CRYPTO-05 (phase-64): distinguish "no device copy stored" (true
            // first run ⇒ mint + persist) from "a copy IS stored but its wrapping
            // keystore key is lost/unreadable" (⇒ keystore-key-lost recovery screen,
            // NEVER a silent re-key). The pre-fix `readDek() == null ⇒ mint` collapse
            // silently overwrote the stored wrapper on keystore loss and the next
            // SQLCipher open quarantined the survivable vault as "corrupt".
            when (val result = security.readDekResult()) {
                is DekReadResult.Unlocked -> {
                    repository.encryptionKey = result.dek
                    initializeData()
                }
                DekReadResult.NoBlob -> {
                    // True first run (or the device copy was deliberately cleared by
                    // removeMasterPassword): mint + persist the passwordless copy.
                    val dek = EncryptionService.generateDek()
                    repository.encryptionKey = dek
                    security.storeDek(dek, authRequired = false)
                    initializeData()
                }
                DekReadResult.AuthRequired -> {
                    // A passwordless vault whose device copy is the biometric-gated
                    // wrapper (anomalous — biometrics requires a master password).
                    // It cannot be read without the biometric flow; surface the
                    // recovery screen instead of minting over it.
                    _keystoreKeyLost.value = true
                }
                is DekReadResult.KeyLost -> {
                    // Keystore key lost / stored blob unreadable: explicit recovery.
                    _keystoreKeyLost.value = true
                }
            }
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
            // B2-UI-1 (phase-49): reject template creation while locked — the
            // repository refuses to write plaintext encrypted columns.
            // B2-UI-1 review-fix: a lock racing the create no longer crashes —
            // writeGuardedAgainstLock turns the fail-closed throw into a notice.
            writeGuardedAgainstLock("Vault is locked — template not applied") {
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
            } // writeGuardedAgainstLock: null here ⇒ lock rejected (notice shown, no crash).
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
            // B2-UI-1 (phase-49): reject while locked — never create a plaintext page.
            // B2-UI-1 review-fix: a lock racing the create is a notice, not a crash.
            val pair = writeGuardedAgainstLock("Vault is locked — page not created") {
                val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
                if (_selectedSection.value == null) {
                    _selectedSection.value = sec
                }
                val created = repository.createPage(
                    sectionId = sec.id,
                    title = title,
                    sourceFilePath = sourceFilePath,
                    sourceFileType = sourceFileType,
                    template = template,
                    extractedText = extractedText,
                    tags = tags
                )
                sec to created
            } ?: return@launch
            val (sec, page) = pair
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
            // B2-UI-1 (phase-49): reject while locked — never persist plaintext.
            // B2-UI-1 review-fix: a lock racing the create is a notice, not a crash.
            val newPage = writeGuardedAgainstLock("Vault is locked — shared note not created") {
                val sec = selectedSection.value ?: repository.ensureDefaultNotebookAndSection().second
                val firstLine = sharedText?.lineSequence()?.firstOrNull()?.trim()?.take(40)
                    ?.takeIf { it.isNotBlank() }
                val title = firstLine
                    ?: if (imagePaths.isNotEmpty()) "Shared Images (${imagePaths.size})" else "Shared Note"
                val created = repository.createPage(
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
                        created.id,
                        imagePaths.mapIndexed { index, path ->
                            CanvasMediaEmbed(
                                pageId = created.id,
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
                created
            } ?: return@launch
            selectPage(newPage)
            onCreated(newPage)
        }
    }

    fun renamePage(id: String, title: String) {
        viewModelScope.launch {
            // B2-UI-1 (phase-49): reject while locked — never store a plaintext title.
            // B2-UI-1 review-fix: a lock racing the rename is a notice, not a crash.
            writeGuardedAgainstLock("Vault is locked — rename not saved") {
                repository.renamePage(id, title)
                if (selectedPage.value?.id == id) {
                    _selectedPage.value = repository.getPageById(id)
                }
            }
        }
    }

    fun updatePageTitleAndTags(id: String, title: String, tags: String) {
        viewModelScope.launch {
            // B2-UI-1 (phase-49): reject while locked — never store a plaintext title.
            // B2-UI-1 review-fix: a lock racing the save is a notice, not a crash.
            writeGuardedAgainstLock("Vault is locked — title not saved") {
                repository.updatePageTitleAndTags(id, title, tags)
                if (selectedPage.value?.id == id) {
                    _selectedPage.value = repository.getPageById(id)
                }
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
            // B2-UI-1 (phase-49): reject while locked — never store a plaintext title.
            // B2-UI-1 review-fix: the background tag merge may lock mid-write; that
            // stays a SILENT no-op (per this function's no-message contract) — but
            // it must never crash the process from the gate's fail-closed throw.
            // Note: this is a fire-and-forget cosmetic merge; the page body itself
            // is persisted by the caller's gated save, so nothing is lost.
            try {
                val merged = autoTagNoteLanguage(text, tags)
                if (merged is PluginResult.Success) {
                    repository.updatePageTitleAndTags(pageId, title, merged.value)
                    if (selectedPage.value?.id == pageId) {
                        _selectedPage.value = repository.getPageById(pageId)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isLockRacedWrite(e)) throw e
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
            // B1-AUTH-05 (phase-69): only a value confined under the app-private
            // imports root is stored (the repository re-confines it too); the
            // in-memory selection mirrors the confined value.
            val confined = com.authorss81.noteflow.services.SourceFilePathPolicy.confine(
                sourceFilePath, com.authorss81.noteflow.services.ImportExportService.getImportsDir(appContext)
            )
            repository.updatePageSource(id, confined, sourceFileType)
            if (selectedPage.value?.id == id) {
                _selectedPage.value = _selectedPage.value?.copy(sourceFilePath = confined, sourceFileType = sourceFileType)
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

    /**
     * B2-DOS-02 (phase-78): vault search shares ONE cancellable Job. Every new
     * keystroke (after HomeScreen's 300 ms debounce) cancels the previous
     * in-flight search before launching, so concurrent full-corpus decrypts can
     * never pile up on a 2-core device. The results callback is dropped if the
     * job was superseded before the search completed.
     */
    private var searchVaultJob: Job? = null

    fun searchVault(query: String, onResult: (List<NotePageEntity>) -> Unit) {
        searchVaultJob?.cancel()
        searchVaultJob = viewModelScope.launch {
            val results = repository.searchPages(query)
            coroutineContext.ensureActive()
            onResult(results)
        }
    }

    /**
     * B2-DOS-02 (phase-78): the explicit "search all pages" refine path for a
     * vault whose cached search window was capped. Shares the same Job as
     * [searchVault], so a subsequent keystroke cancels the deep scan mid-batch
     * instead of letting it keep grinding through the vault.
     */
    fun deepSearchVault(query: String, onResult: (List<NotePageEntity>) -> Unit) {
        searchVaultJob?.cancel()
        searchVaultJob = viewModelScope.launch(Dispatchers.IO) {
            val results = repository.deepSearchPages(query)
            coroutineContext.ensureActive()
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
                val bytes = ImportExportService.readUriBytes(
                    getApplication(),
                    uri,
                    ImportExportService.MAX_BACKUP_INPUT_BYTES
                )
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

    /**
     * B1-CRYPTO-05 (phase-64): restore-from-backup path for the keystore-key-lost
     * recovery screen. Unlike [attemptRecoveryFromBackup] (which reuses the live
     * DEK), the old DEK is genuinely GONE here, so this flow mints a FRESH DEK,
     * persists its device-wrapped copy (the post-restart passwordless boot
     * credential), and imports the backup re-keyed into it. This is the ONLY
     * sanctioned mint on the key-loss path — the user explicitly chose recovery.
     */
    fun attemptKeystoreKeyLostRecoveryFromBackup(uri: android.net.Uri, backupPassword: String?, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val bytes = ImportExportService.readUriBytes(
                    getApplication(),
                    uri,
                    ImportExportService.MAX_BACKUP_INPUT_BYTES
                )
                    ?: throw IllegalStateException("Could not read the selected backup file.")
                // H1 (phase-09): reject a wrong password BEFORE closing the live DB.
                if (backupPassword != null) {
                    ImportExportService.validateBackupPassword(bytes, backupPassword)
                }
                // Mint a FRESH DEK to re-key the restored vault into. The wrapper is
                // persisted ONLY after the restore succeeds, so a failed restore never
                // overwrites the (still-stored, still-unreadable) old wrapper — the
                // recovery screen stays shown and the user can simply retry.
                val newDek = EncryptionService.generateDek()
                repository.encryptionKey = newDek
                repository.closeDatabase()
                ImportExportService.importBackup(getApplication(), bytes, newDek, backupPassword)
                if (!security.storeDek(newDek, authRequired = false)) {
                    throw IllegalStateException("Could not persist the new device key — recovery aborted.")
                }
                DatabaseSecurityHelper.clearCorruptionDetected(getApplication())
                DatabaseSecurityHelper.clearRestoreBlock(getApplication())
                _keystoreKeyLost.value = false
                _restoreBlocked.value = false
                _corruptionBlocked.value = false
                _databaseTampered.value = false
                // B2-CRYPTO-09 (phase-107): the restored DB may carry legacy global-AAD
                // field ciphertexts — force the record-AAD migration on the next launch.
                settings.fieldAadMigrated = false
                delay(500)
                kotlin.system.exitProcess(0)
            } catch (e: Exception) {
                runCatching { repository.reopenDatabase(getApplication()) }
                onError(e.message ?: "Recovery failed.")
            }
        }
    }

    /**
     * B1-CRYPTO-05 (phase-64): "start fresh" for the keystore-key-lost recovery
     * screen. The old vault file is still encrypted under the LOST device key and
     * cannot be opened by this app — its bytes are moved aside as
     * `noteflow.sqlite.keystore-lost-<ts>` (nothing deleted, for offline recovery
     * with the original key material), the stale device wrapper is cleared, and a
     * brand-new passwordless vault is booted with a fresh DEK.
     */
    fun startFreshAfterKeystoreKeyLoss() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                quarantineVaultFiles("keystore-lost")
                security.clearDek()
            }
            _keystoreKeyLost.value = false
            // Re-run the passwordless boot with a deliberately fresh DEK.
            val dek = EncryptionService.generateDek()
            security.storeDek(dek, authRequired = false)
            repository.encryptionKey = dek
            dataInitialized = false
            initializeData()
        }
    }

    /** Renames the live vault DB (+wal/shm/journal) aside, preserving its bytes. */
    private fun quarantineVaultFiles(suffixTag: String) {
        val context: android.content.Context = getApplication()
        val baseFile = context.getDatabasePath("noteflow.sqlite")
        val dir = baseFile.parentFile ?: return
        val timestamp = System.currentTimeMillis()
        val suffix = ".$suffixTag-$timestamp"
        for (name in listOf("noteflow.sqlite", "noteflow.sqlite-wal", "noteflow.sqlite-shm", "noteflow.sqlite-journal")) {
            val source = File(dir, name)
            if (source.exists()) {
                runCatching { source.renameTo(File(dir, name + suffix)) }
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
            // B2-UI-1 (phase-49): the create branch writes an encrypted page body —
            // reject the whole open/create while locked.
            // B2-UI-1 review-fix: a lock racing the read/write is a notice, not a
            // crash; the existing-page read and new-page create stay together so
            // neither can run against a disposed pool.
            writeGuardedAgainstLock("Vault is locked — daily note not created") {
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

                    // phase-44 (B1-DB-4): the body is stored ONLY in the
                    // field-encrypted extractedText column — never persisted to a
                    // plaintext .md/.txt file under filesDir/noteflow/imports.
                    val newPage = repository.createPage(
                        sectionId = sec.id,
                        title = targetTitle,
                        sourceFilePath = null,
                        sourceFileType = "text",
                        template = "blank",
                        extractedText = journalTemplate
                    )
                    selectPage(newPage)
                    onOpen(newPage)
                }
            }
        }
    }

    fun openPageByTitle(title: String, context: android.content.Context, onOpen: (NotePageEntity) -> Unit) {
        viewModelScope.launch {
            // B2-UI-1 (phase-49): the create branch writes an encrypted page body —
            // reject the whole open/create while locked.
            // B2-UI-1 review-fix: a lock racing the read/write is a notice, not a
            // crash; the existing-page read and new-page create stay together.
            writeGuardedAgainstLock("Vault is locked — page not created") {
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
                    // phase-44 (B1-DB-4): body stored ONLY in the field-encrypted
                    // extractedText column — never a plaintext .md/.txt file.
                    val newPage = repository.createPage(
                        sectionId = sec.id,
                        title = targetFileName,
                        sourceFilePath = null,
                        sourceFileType = "text",
                        template = "blank",
                        extractedText = initialContent
                    )
                    selectPage(newPage)
                    onOpen(newPage)
                }
            }
        }
    }

    /**
     * B1-DB-4 (phase-44): front-door save for a markdown/text note body. The
     * body is written ONLY to the field-encrypted `pages.extractedText` column
     * — never to a plaintext `.md`/`.txt` file. Any legacy companion plaintext
     * file is deleted AFTER the column write. Runs in the ViewModel scope so
     * the write survives the editor composable's teardown (the Phase-05
     * NonCancellable race the old file-write carried). No fallback to plaintext
     * on failure — the failure is surfaced so the user can retry.
     *
     * B2-UI-1 (phase-49 review-fix): a lock racing this save no longer drops the
     * body behind an error snackbar — the snapshot is stashed in the same
     * defer/re-write-encrypted-after-unlock policy as the ink flushes.
     *
     * B2-UI-5 (phase-74): the save is REGISTERED here on the calling (UI) thread
     * via [MarkdownBodySaveCoordinator.issue] so the latest-wins order is the UI
     * issue order; the actual encrypted-column write runs inside
     * [MarkdownBodySaveCoordinator.commitLatest], which only commits when this
     * request is still the newest for the page (a superseded older write never
     * lands after a newer one). The reader side waits out the settle via
     * [readMarkdownNoteBody] before reading the body back, so a re-opened page
     * can never show a stale snapshot that later gets edited + saved over newer
     * content.
     */
    fun saveMarkdownNoteBody(page: NotePageEntity, body: String) {
        val pageId = page.id
        val legacyPath = page.sourceFilePath
        val legacyType = page.sourceFileType
        val deferred = EditorFlushPolicy.DeferredBody(pageId, body, legacyPath, legacyType)
        if (!VaultWriteGate.persistNow(repository.encryptionKey != null)) {
            editorFlushPolicy.deferBody(deferred)
            return
        }
        val request = markdownBodySaveCoordinator.issue(pageId, body, legacyPath, legacyType)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val committed = markdownBodySaveCoordinator.commitLatest(request) {
                    repository.updatePageBody(pageId, body)
                    // B1-AUTH-05 (phase-69): only a legacy file confined under the
                    // imports root may be deleted.
                    NoteBodyVaultPolicy.deleteLegacyNoteTextBody(
                        legacyPath, legacyType, ImportExportService.getImportsDir(appContext)
                    )
                }
                if (!committed) {
                    // A newer save for this page superseded this stale snapshot —
                    // that request commits the body; this one must not overwrite it.
                    return@launch
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: VaultLockedWriteException) {
                // Vault locked mid-write: stash, never plaintext, never dropped.
                editorFlushPolicy.deferBody(deferred)
            } catch (e: Exception) {
                if (repository.encryptionKey == null) {
                    // A lock zeroized the DEK / disposed the pool mid-save.
                    editorFlushPolicy.deferBody(deferred)
                } else {
                    showSnackbar("Failed to save note body", isLong = true)
                }
            }
        }
    }

    /**
     * B2-UI-5 (phase-74): the READ side of the markdown-body save contract — with
     * the settle of any in-flight save for [pageId] awaited AND a fresh repository
     * read (never the possibly-stale flow snapshot), a page that was just
     * navigated away and back shows the latest committed body, so editing +
     * flushing can never write a stale snapshot back over newer content.
     *
     * The composition's in-memory snapshot ([fallbackExtractedText] etc.) is used
     * only as a deflate fallback if the fresh read cannot decrypt (a lock wiping
     * the DEK racing the read) — so a transient key-lost race can never surface as
     * an empty editor that would then be saved over the real body. Blocking file
     * I/O (legacy coalesce) — call on a background dispatcher (the produceState
     * callers do). The body is only ever held in memory; nothing persists
     * plaintext anywhere.
     */
    suspend fun readMarkdownNoteBody(
        pageId: String,
        fallbackExtractedText: String? = null,
        fallbackSourceFilePath: String? = null,
        fallbackSourceFileType: String? = null
    ): String {
        markdownBodySaveCoordinator.awaitSettled(pageId)
        val fresh = try {
            repository.getPageById(pageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A lock zeroized the DEK / disposed the pool mid-read — fall back to
            // the in-memory snapshot the composition already holds (decrypted by
            // the pages flow). Never surface this as a corrupt/empty body.
            null
        }
        // B1-AUTH-05 (phase-69): a legacy source file is read only when confined
        // under the app-private imports root (null root refuses the file read).
        return NoteBodyVaultPolicy.resolveBodyForDisplay(
            fresh?.extractedText ?: fallbackExtractedText,
            fresh?.sourceFilePath ?: fallbackSourceFilePath,
            fresh?.sourceFileType ?: fallbackSourceFileType,
            ImportExportService.getImportsDir(appContext)
        )
    }

    // ---------- Security & Master Password ----------
    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
    }

    /**
     * B1-CRYPTO-02 (phase-45): enforce the [DekAtRestPolicy] invariant after any
     * password set/change/unlock. When a master password exists and biometrics are
     * OFF, the device-wrapped DEK copy (`noteflow_sec_dek`, non-auth AndroidKeyStore
     * blob) is REMOVED so the only at-rest wrapping of the DEK is the password-derived
     * KEK. When biometrics are explicitly ON, the device copy is (re)persisted with
     * `authRequired = true` (biometric-gated) — the pre-fix code re-wrapped the DEK
     * under the NON-auth keystore key here, a second instantiation of the bypass.
     *
     * Returns true only when the at-rest DEK state was actually achieved (the
     * device copy is durably absent in PASSWORD_ONLY mode, or the auth-gated blob
     * was successfully written in BIOMETRIC_GATED mode) — phase-45 review fix so
     * callers never report success over a failed keystore write or failed clear.
     */
    private fun enforceDekAtRestPolicy(): Boolean {
        // B1-CRYPTO-07 (phase-65): a platform that cannot bind an AndroidKeyStore key
        // to AUTH_BIOMETRIC_STRONG (API 26-29) must never hold an auth-gated device
        // copy. If a legacy biometricAuthEnabled=true survived from a pre-fix install
        // on such a device, DOWNGRADE to password-only now — clear the weak-bound blob
        // and flip the setting off with a one-time non-alarming message — instead of
        // re-writing it on this (and every future) unlock.
        if (settings.biometricAuthEnabled &&
            !BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)
        ) {
            settings.biometricAuthEnabled = false
            _biometricEnabled.value = false
            _biometricRefusalMessage.value =
                BiometricKeyBindingPolicy.refuseEnableMessage(Build.VERSION.SDK_INT)
        }
        return when (
            DekAtRestPolicy.modeFor(
                hasMasterPassword = settings.hasMasterPassword,
                biometricAuthEnabled = settings.biometricAuthEnabled,
                strongBiometricBindingSupported =
                    BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT),
            )
        ) {
            DekAtRestMode.PASSWORD_ONLY -> security.clearDek()
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY -> {
                val dek = repository.encryptionKey
                if (dek != null) security.storeDek(dek, authRequired = true) else false
            }
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED -> {
                // No master password: the passwordless-boot path owns the device copy.
                true
            }
        }
    }

    suspend fun setMasterPassword(password: String): Boolean {
        // B2-CRYPTO-07 (phase-113): length and emptiness are judged on the
        // NFKC-NORMALIZED password, in grapheme clusters — this is exactly the
        // byte sequence deriveKey will hash, so the check can never be undone
        // by normalization collapsing the input differently at unlock time.
        // B1-CRYPTO-04 (phase-63) + B1-PLAT-8 (phase-90): a NEW master password
        // must additionally clear the strength policy — ≥ 10 graphemes, no
        // sequential/keyboard/common-word/prefix-suffix patterns, class
        // diversity for short passwords. The policy measures the same
        // NFKC-normalized form, so the stored+derived bytes always satisfy it.
        // Enforced here (authoritative) AND in the dialog (human-readable);
        // never at verify/unlock, so a pre-existing weaker vault keeps
        // unlocking. IMPORTANT (B1-PLAT-8): offline brute force on a copied
        // vault is only mitigated by this entropy — never by the on-device
        // lockout (which only throttles attempts typed on the device).
        val normalized = EncryptionService.normalizePassword(password)
        if (!PasswordStrengthPolicy.evaluate(password).accepted) return false
        var kek: ByteArray? = null
        val dek: ByteArray
        return try {
            val salt = EncryptionService.generateSalt()
            // Reuse an existing DEK when one is already in play (e.g. previously
            // device-wrapped), so existing ciphertext stays valid; otherwise mint a new one.
            val existingDek = repository.encryptionKey
            val (targetDek, wrappedDek) = withContext(Dispatchers.Default) {
                // B1-CRYPTO-05 (phase-64): re-use the existing DEK (in-memory, or the
                // readable device copy) so existing ciphertext stays valid; on a true
                // first run there is none, so mint. A STORED-BUT-UNDECRYPTABLE device
                // copy (keystore key lost) must NEVER fall through to a mint here —
                // that would wrap a fresh DEK under the new password while the vault
                // is still encrypted under the lost one (silent re-key, data loss).
                val existing = existingDek ?: when (val r = security.readDekResult()) {
                    is DekReadResult.Unlocked -> r.dek
                    DekReadResult.NoBlob -> null
                    DekReadResult.AuthRequired -> null
                    is DekReadResult.KeyLost -> throw KeystoreKeyLostException(
                        "Stored device DEK copy cannot be unwrapped — refuse to set a " +
                            "password over a vault whose device key is lost. Restore from " +
                            "a backup or start fresh first.",
                        r.wrapperAlias
                    )
                }
                val d = existing ?: EncryptionService.generateDek()
                val derivedKek = EncryptionService.deriveKey(normalized, salt)
                kek = derivedKek
                val wrapped = EncryptionService.encrypt(d, derivedKek)
                // B1-CRYPTO-03 (phase-62): round-trip validation — the freshly
                // wrapped DEK must decrypt back under this KEK before it is
                // committed; a malformed wrap would be permanently un-unlockable.
                val check = EncryptionService.decrypt(wrapped, derivedKek)
                check.fill(0.toByte())
                d to wrapped
            }
            dek = targetDek

            // B1-CRYPTO-03 (phase-62): salt + wrapped DEK + format land as ONE
            // versioned blob in a single disk-sync-acknowledged commit(), not two
            // independent pref writes. All-or-nothing: on a failed commit the OLD
            // credential (if any) is still on disk and nothing in-memory has
            // changed — the vault remains unlockable (or, on first run, stays
            // passwordless) and the user can simply retry.
            if (!settings.commitMasterPasswordCredential(salt, wrappedDek)) return false

            repository.encryptionKey = dek
            _hasMasterPassword.value = true
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            // B1-CRYPTO-02 (phase-45): the device-wrapped DEK copy must not survive
            // the transition to password protection when biometrics are off.
            enforceDekAtRestPolicy()
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
        // B1-CRYPTO-04 (phase-63) + B1-PLAT-8 (phase-90): the NEW password must
        // clear the strength policy (≥ 10 graphemes, no sequential/keyboard/
        // common-word/prefix-suffix patterns, class diversity for short
        // passwords), exactly as setMasterPassword requires.
        // The OLD password is only VERIFIED (never strength-gated) so a
        // pre-existing weaker vault can always be rotated and keep unlocking.
        val newPasswordNormalized = EncryptionService.normalizePassword(newPassword)
        if (!PasswordStrengthPolicy.evaluate(newPassword).accepted) return false
        // B1-CRYPTO-03 (phase-62) review fix: snapshot the pre-verify session
        // BEFORE verifying — verifyMasterPassword installs the DEK and flips
        // _authenticated, and a failed credential commit below must restore this
        // prior state so a `false` return is never paired with a silently
        // unlocked (or silently locked) session.
        val wasAuthenticated = _authenticated.value
        val priorFailedAttempts = _failedUnlockAttempts.value
        if (!verifyMasterPassword(oldPassword)) return false
        val currentDek = repository.encryptionKey ?: return false
        var kek: ByteArray? = null

        return try {
            val newSalt = EncryptionService.generateSalt()
            val newWrappedDek = withContext(Dispatchers.Default) {
                val derivedKek = EncryptionService.deriveKey(newPasswordNormalized, newSalt)
                kek = derivedKek
                val wrapped = EncryptionService.encrypt(currentDek, derivedKek)
                // B1-CRYPTO-03 (phase-62): round-trip validation — the re-wrapped
                // DEK must decrypt back under this KEK before it is committed.
                val check = EncryptionService.decrypt(wrapped, derivedKek)
                check.fill(0.toByte())
                wrapped
            }

            // B1-CRYPTO-03 (phase-62): atomic single-commit swap. If the new
            // credential cannot be durably written, the OLD (salt, wrappedDEK)
            // pair still on disk stays valid — the vault remains unlockable with
            // the old password and no partial pair can ever brick it.
            if (!settings.commitMasterPasswordCredential(newSalt, newWrappedDek)) {
                // B1-CRYPTO-03 (phase-62) review fix: the intermediate
                // verifyMasterPassword already authenticated this session. On a
                // failed swap, restore the pre-call session: an already-unlocked
                // session stays unlocked with the (still durable, still-valid)
                // OLD credential; a session that was locked is returned to locked
                // so the returned `false` is never a lie.
                if (!wasAuthenticated) {
                    repository.zeroizeKey()
                    _authenticated.value = false
                    _failedUnlockAttempts.value = priorFailedAttempts
                    settings.failedUnlockAttempts = priorFailedAttempts
                }
                return false
            }

            _hasMasterPassword.value = true
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            // B1-CRYPTO-02 (phase-45): biometrics OFF ⇒ the device copy is removed
            // (never re-wrapped under the non-auth keystore key); biometrics ON ⇒
            // re-wrapped auth-gated only.
            enforceDekAtRestPolicy()
            // B1-DB-8 (phase-88): a re-key is a session boundary — recount from a
            // clean ledger so a stale roll of a single old-broken row can't linger.
            repository.resetDecryptFailures()
            decryptPersistenceEscalated = false
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

    /**
     * B1-AUTH-02 (phase-47): reinstates the live SQLCipher connection torn down
     * by [lock] — only when it was actually disposed. No-op for cold starts and
     * for in-session password re-verification (changeMasterPassword /
     * removeMasterPassword / setBiometricEnabled), where the connection was never
     * torn down. Runs with the DEK already placed in [VaultKeyHolder], so the
     * rebuilt factory open is a legitimate (unlocked) open. Returns false
     * (fail closed) if the rebuild fails; callers must then zeroize the DEK and
     * refuse the unlock WITHOUT lockout bookkeeping.
     */
    private fun reinstateDatabaseAfterLock(): Boolean {
        if (!databaseDisposedByLock) return true
        return try {
            repository.reopenDatabase(getApplication())
            databaseDisposedByLock = false
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun verifyMasterPassword(password: String): Boolean {
        if (lockoutActive()) return false
        if (settings.masterPasswordCredentialOrLegacy == null) return false
        return try {
            val dek = unwrapMasterDek(password)
                ?: throw IllegalStateException("wrong master password")
            repository.encryptionKey = dek
            // B1-AUTH-02 (phase-47): a previous lock() disposed the live connection;
            // reinstate it BEFORE the dbGate flows flip on. An open failure here is
            // NOT a wrong password — zeroize and fail closed without lockout
            // bookkeeping so a transient error can never lock the user out.
            if (!reinstateDatabaseAfterLock()) {
                repository.zeroizeKey()
                _authenticated.value = false
                return false
            }
            _authenticated.value = true
            _failedUnlockAttempts.value = 0
            settings.failedUnlockAttempts = 0
            settings.lockoutUntilEpochMs = 0L
            _lockoutRemainingMs.value = 0L
            initializeData()
            // B1-AUTH-03 (phase-67): a successful password unlock is the moment the
            // vault is no longer locked — boot the plugin layer here (store
            // re-materialization + onEnable hooks, quiesced by lock()).
            startPluginLifecycle()
            // B2-UI-1 (phase-49): flush page saves that a lock deferred, now that
            // the DEK is live again — rows are written encrypted, never plaintext.
            flushPendingEditorSaves()
            // B1-CRYPTO-02 (phase-45): every password unlock enforces the at-rest
            // DEK policy (remove the non-auth device copy; keep only the auth-gated
            // one when biometrics are enabled).
            enforceDekAtRestPolicy()
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
        // B1-CRYPTO-03 (phase-62): the unlock path reads the credential through
        // the single blob-or-legacy accessor. A stored blob is one value, so the
        // salt and the wrapped DEK can never be half-written relative to each
        // other — the exact state that used to permanently brick the vault.
        val credential = settings.masterPasswordCredentialOrLegacy ?: return@withContext null
        val salt = try {
            credential.saltBytes()
        } catch (e: IllegalArgumentException) {
            return@withContext null
        }
        val wrappedDek = credential.wrappedDek
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
        if (repository.encryptionKey == null) return false

        // B1-CRYPTO-07 (phase-65): the AUTHORITATIVE gate. On API 26-29 the platform
        // cannot create a key bound to AUTH_BIOMETRIC_STRONG — the best binding there
        // is "any biometric per use", and the pre-fix bare
        // setUserAuthenticationRequired(true) even accepted a device credential.
        // Refuse the enable with a clear, non-alarming message; the setting is never
        // flipped and no weak-bound device copy is ever written. The user keeps their
        // (verified) master-password-only protection.
        if (enabled && !BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)) {
            _biometricRefusalMessage.value =
                BiometricKeyBindingPolicy.refuseEnableMessage(Build.VERSION.SDK_INT)
            return false
        }

        val previous = settings.biometricAuthEnabled
        return try {
            // B1-CRYPTO-02 (phase-45): the pre-fix code flipped the setting and
            // wrote the device copy with authRequired bound to the `enabled` flag —
            // which, on disabling, wrote a NON-auth copy (a second instantiation of
            // the bypass), and on ANY store failure returned success anyway. Apply the
            // TARGET state FIRST so enforceDekAtRestPolicy evaluates the new mode,
            // then only persist the setting once the at-rest state was actually
            // achieved: enabling stores the device copy ONLY as the auth-required
            // (biometric-gated) blob, disabling removes it entirely.
            settings.biometricAuthEnabled = enabled
            val enforced = enforceDekAtRestPolicy()
            if (!enforced) {
                settings.biometricAuthEnabled = previous
                return false
            }
            if (enabled) _biometricRefusalMessage.value = null
            _biometricEnabled.value = enabled
            true
        } catch (e: Exception) {
            settings.biometricAuthEnabled = previous
            false
        }
    }

    fun getBiometricCipher(): androidx.biometric.BiometricPrompt.CryptoObject? {
        // B1-CRYPTO-07 (phase-65): never hand a DEK-wrapping cipher to the biometric
        // prompt on a platform that cannot bind the key to AUTH_BIOMETRIC_STRONG
        // (API 26-29). The null return makes LockScreen fall back to the master
        // password and call disableBiometricFallback(), which clears the setting and
        // the device copy.
        if (!BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)) return null
        val cipher = security.getDecryptionCipher() ?: return null
        return androidx.biometric.BiometricPrompt.CryptoObject(cipher)
    }

    fun verifyBiometricsAndUnlock(result: androidx.biometric.BiometricPrompt.AuthenticationResult): Boolean {
        if (lockoutActive()) return false
        val cipher = result.cryptoObject?.cipher ?: return false
        val dek = security.decryptWithCipher(cipher) ?: return false
        repository.encryptionKey = dek
        // B1-AUTH-02 (phase-47): reinstate the connection disposed by lock() before
        // any dbGate flow re-subscribes; fail closed (not a lockout) on open failure.
        if (!reinstateDatabaseAfterLock()) {
            repository.zeroizeKey()
            _authenticated.value = false
            return false
        }
        _authenticated.value = true
        _failedUnlockAttempts.value = 0
        settings.failedUnlockAttempts = 0
        settings.lockoutUntilEpochMs = 0L
        _lockoutRemainingMs.value = 0L
        initializeData()
        // B1-AUTH-03 (phase-67): biometric unlock is equally a successful unlock —
        // boot the plugin layer here just like the password unlock path.
        startPluginLifecycle()
        // B2-UI-1 (phase-49): flush page saves deferred during the lock.
        flushPendingEditorSaves()
        // B1-CRYPTO-02 (phase-45): re-assert the policy after a biometric unlock so
        // a stale non-auth wrapper (if any survived an old-version connect) is purged
        // and the biometric-gated copy is pinned to the current DEK.
        enforceDekAtRestPolicy()
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
        // B2-UI-1 (phase-49): guard — a locked vault must not (a) write plaintext
        // rows (the repository throws VaultLockedWriteException) nor (b) crash the
        // process from that fail-closed throw. A snapshot skipped because the vault
        // locked is "rejected", never written in the clear.
        // B2-UI-1 review-fix: the rejection is no longer silent — the user gets a
        // non-alarming notice so the dropped snapshot is never invisible.
        if (VaultWriteGate.persistNow(repository.encryptionKey != null)) {
            viewModelScope.launch {
                try {
                    repository.createNoteVersion(pageId, title, extractedText, versionNote)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: VaultLockedWriteException) {
                    // vault locked mid-write: rejected, never plaintext, never silent.
                    showSnackbar("Vault is locked — version snapshot not saved")
                } catch (e: Exception) {
                    if (repository.encryptionKey == null) {
                        // A lock zeroized the DEK / disposed the pool mid-write.
                        showSnackbar("Vault is locked — version snapshot not saved")
                    } else {
                        throw e
                    }
                }
            }
        } else {
            showSnackbar("Vault is locked — version snapshot not saved")
        }
    }

    // -----------------------------------------------------------------------
    // B2-UI-1 (phase-49) — locked-safe editor page flushes.
    // EditorScreen no longer touches the repository directly for page writes;
    // every flush routes through one of the three entry points below. Unlocked
    // (DEK present) ⇒ persist now. Locked (DEK zeroized — auto-lock, manual
    // "Lock Vault Now", ON_STOP, or a lock that races mid-write) ⇒ the snapshot
    // is stashed in [editorFlushPolicy] and flushed ENCRYPTED after the next
    // successful unlock. A plaintext row can never be written.
    // -----------------------------------------------------------------------

    /** Full-page flush (dispose flush, navigation/back flush, embed/sticky changes). */
    fun flushEditorPageSave(
        pageId: String,
        strokes: List<Stroke>,
        stickyNotes: List<CanvasStickyNote>,
        embeds: List<CanvasMediaEmbed>,
        layers: List<LayerEntity>
    ) {
        persistOrDefer(
            EditorFlushPolicy.DeferredSave(pageId, strokes, stickyNotes, embeds, layers),
            unlockedPersist = { repo ->
                maybeNotifyGeometryCapped(pageId, repo.saveStrokesForPage(pageId, strokes))
                repo.saveCanvasItemsForPage(pageId, stickyNotes, embeds)
                repo.saveLayersForPage(pageId, layers)
            }
        )
    }

    /**
     * B2-UI-3 (phase-73): the editor's dispose/flush entry point that CANCELS a
     * pending debounced autosave and AWAITS its settlement before persisting the
     * final (newest) page snapshot.
     *
     * On composition disposal `DisposableEffect.onDispose` is synchronous and the
     * composition scope is already being torn down, so this must run in
     * [viewModelScope] (which survives the editor leaving composition). The
     * await matters: [EditorScreen] now debounces the WHOLE write (delay + the
     * suspended [autosaveStrokes]) in one cancellable job, so cancelling it stops
     * a stale snapshot from ever firing, and joining it settles any write the
     * debounce already dispatched BEFORE the flush is issued — the flush (rooted
     * in the newest state) then commits last.
     */
    fun disposeEditorPageFlush(
        pageId: String,
        strokes: List<Stroke>,
        stickyNotes: List<CanvasStickyNote>,
        embeds: List<CanvasMediaEmbed>,
        layers: List<LayerEntity>,
        pendingDebounce: Job?
    ) {
        viewModelScope.launch {
            pendingDebounce?.cancel()
            // B2-UI-3 (phase-73): await settlement. The cancelled debounce either
            // never started its write (delay pending) or had already launched it —
            // joining guarantees the stale write is done/cancelled before the
            // final flush below is issued, so the newest snapshot lands last.
            pendingDebounce?.join()
            flushEditorPageSave(pageId, strokes, stickyNotes, embeds, layers)
        }
    }

    /**
     * Strokes-only debounced autosave. When the vault is unlocked only the
     * changed stroke rows are written (the historical 1s-debounce behaviour);
     * when a lock beats the debounce the FULL page snapshot is stashed so the
     * deferred flush reconstitutes the whole page after unlock.
     *
     * B2-UI-3 (phase-73): SUSPENDED now, and the write runs INLINE in the
     * caller's coroutine (the editor's debounce job), so [EditorScreen]'s
     * `saveJob` covers the actual persistence — it can be cancelled to stop the
     * stale snapshot and awaited to settle it before the dispose flush. The old
     * fire-and-forget `persistOrDefer` launch left the write detached from the
     * debounce job, so cancel-then-flush could not guarantee ordering.
     */
    suspend fun autosaveStrokes(
        pageId: String,
        strokes: List<Stroke>,
        stickyNotes: List<CanvasStickyNote>,
        embeds: List<CanvasMediaEmbed>,
        layers: List<LayerEntity>
    ) {
        persistEditorSaveSuspend(
            EditorFlushPolicy.DeferredSave(pageId, strokes, stickyNotes, embeds, layers),
            unlockedPersist = { repo ->
                maybeNotifyGeometryCapped(pageId, repo.saveStrokesForPage(pageId, strokes))
            }
        )
    }

    /** Layers-only write (layer rename/order/visibility). Layers hold no secret
     *  payload, but a post-lock write would hit the disposed pool — route it
     *  through the same deferred flush as the rest of the page. */
    fun saveLayersGated(
        pageId: String,
        layers: List<LayerEntity>,
        strokes: List<Stroke>,
        stickyNotes: List<CanvasStickyNote>,
        embeds: List<CanvasMediaEmbed>
    ) {
        persistOrDefer(
            EditorFlushPolicy.DeferredSave(pageId, strokes, stickyNotes, embeds, layers),
            unlockedPersist = { repo -> repo.saveLayersForPage(pageId, layers) }
        )
    }

    /**
     * B2-UI-1 (phase-49 review-fix): single predicate for "this write failed
     * BECAUSE the vault locked" — either the repository threw the fail-closed
     * gate exception, or a lock zeroized the DEK / disposed the pool before the
     * failure surfaced. Callers of the non-flush page writes (create/rename/tag/
     * daily/wiki/template) use it to turn a lock-race crash into a handled
     * rejection with a non-alarming notice.
     */
    private fun isLockRacedWrite(e: Exception): Boolean =
        e is VaultLockedWriteException || repository.encryptionKey == null

    /**
     * B2-UI-1 (phase-49 review-fix): wraps a page write so a lock racing it
     * cannot crash the process. Returns null when the write was rejected
     * because the vault locked (or was locked before the write), and the block's
     * value otherwise. Unlock-gated reads/state updates inside [block] are kept
     * together so the pool-closed race on follow-up reads is caught too.
     */
    private suspend fun <T> writeGuardedAgainstLock(
        lockedNotice: String,
        block: suspend () -> T
    ): T? {
        if (repository.encryptionKey == null) return null
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isLockRacedWrite(e)) {
                showSnackbar(lockedNotice)
                null
            } else {
                throw e
            }
        }
    }

    /**
     * B2-UI-3 (phase-73): the SUSPENDED (awaitable) form of the persist-vs-defer
     * decision (see [VaultWriteGate]). Runs [unlockedPersist] INLINE in the
     * caller's coroutine so the caller can cancel/await the actual write (the
     * editor's debounce + dispose-flush ordering depends on this). Unlocked ⇒
     * persist; locked ⇒ stash. If a lock races the gate check the repository
     * throws [VaultLockedWriteException] (or the DB pool is closed) — catch and
     * stash, never crash, never lose the user's edits.
     */
    private suspend fun persistEditorSaveSuspend(
        save: EditorFlushPolicy.DeferredSave,
        unlockedPersist: suspend (NoteRepository) -> Unit
    ) {
        if (!VaultWriteGate.persistNow(repository.encryptionKey != null)) {
            editorFlushPolicy.defer(save)
            return
        }
        try {
            unlockedPersist(repository)
        } catch (e: VaultLockedWriteException) {
            editorFlushPolicy.defer(save)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A lock can zeroize the DEK / dispose the pool after the gate
            // check; any save-path failure is re-queued for the next unlock.
            if (repository.encryptionKey == null) {
                editorFlushPolicy.defer(save)
            } else {
                throw e
            }
        }
    }

    /**
     * The single persist-vs-defer decision (see [VaultWriteGate]).
     * Unlocked ⇒ persist [unlockedPersist] on the IO dispatcher; locked ⇒ stash.
     * If a lock races the gate check the repository throws
     * [VaultLockedWriteException] (or the DB pool is closed) — catch and stash,
     * never crash, never lose the user's edits. `sampled` is re-stashed only for
     * deferrals; an in-flight save that succeeded needs no resurrection.
     *
     * B2-UI-3 (phase-73): the fire-and-forget wrapper over
     * [persistEditorSaveSuspend] — kept for the non-dispose flush entry points
     * ([flushEditorPageSave] / [saveLayersGated]); the debounced autosave uses
     * the suspended form directly so its write is cancellable/awaitable.
     */
    private fun persistOrDefer(
        save: EditorFlushPolicy.DeferredSave,
        unlockedPersist: suspend (NoteRepository) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            persistEditorSaveSuspend(save, unlockedPersist)
        }
    }

    /**
     * B2-UI-1 (phase-49): flushes everything stashed while locked, once the vault
     * is unlocked again and the DEK is live. Called by every unlock path right
     * after `_authenticated = true`. Re-writes are idempotent (stroke hashes +
     * upserts; export-equivalent deletes+reinserts on embeds).
     */
    private fun flushPendingEditorSaves() {
        val toFlush = editorFlushPolicy.drain()
        val toFlushBodies = editorFlushPolicy.drainBodies()
        if (toFlush.isEmpty() && toFlushBodies.isEmpty()) return
        for (save in toFlush) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    maybeNotifyGeometryCapped(
                        save.pageId,
                        repository.saveStrokesForPage(save.pageId, save.strokes)
                    )
                    repository.saveCanvasItemsForPage(save.pageId, save.stickyNotes, save.embeds)
                    repository.saveLayersForPage(save.pageId, save.layers)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: VaultLockedWriteException) {
                    editorFlushPolicy.defer(save)
                } catch (e: Exception) {
                    // Re-lock raced the flush (or a transient error): re-stash so
                    // it retries after the next unlock instead of losing the page.
                    if (repository.encryptionKey == null) {
                        editorFlushPolicy.defer(save)
                    } else {
                        throw e
                    }
                }
            }
        }
        // B2-UI-5 (phase-74): deferred markdown bodies are issued on the calling
        // (main) thread — BEFORE any user save can be issued for the page — so
        // the unlock flush is strictly older than any subsequent edit; the
        // latest-wins commitLatest skip then guarantees the newest body wins even
        // if a user save races the flush.
        for (body in toFlushBodies) {
            val request = markdownBodySaveCoordinator.issue(
                body.pageId, body.body, body.legacySourceFilePath, body.legacySourceFileType
            )
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val committed = markdownBodySaveCoordinator.commitLatest(request) {
                        repository.updatePageBody(request.pageId, request.body)
                        // B1-AUTH-05 (phase-69): only a legacy file confined under
                        // the imports root may be deleted.
                        NoteBodyVaultPolicy.deleteLegacyNoteTextBody(
                            request.legacySourceFilePath, request.legacySourceFileType,
                            ImportExportService.getImportsDir(appContext)
                        )
                    }
                    if (!committed) return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: VaultLockedWriteException) {
                    editorFlushPolicy.deferBody(body)
                } catch (e: Exception) {
                    if (repository.encryptionKey == null) {
                        editorFlushPolicy.deferBody(body)
                    } else {
                        showSnackbar("Failed to save note body", isLong = true)
                    }
                }
            }
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
    fun restoreEncryptedBackupFromZip(sourceZip: java.io.File, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                // B2-DOS-05 (phase-81): never slurp the whole downloaded archive
                // into heap unbounded. Bound the read during the read with the
                // same 400 MB backup budget the local restore path enforces, so a
                // huge/malicious WebDAV download cannot OOM the restore.
                val bytes = withContext(Dispatchers.IO) {
                    java.io.FileInputStream(sourceZip).use { input ->
                        com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(
                            input,
                            ImportExportService.MAX_BACKUP_INPUT_BYTES
                        )
                    }
                }
                repository.closeDatabase()
                ImportExportService.importBackup(getApplication(), bytes, repository.encryptionKey)
                // B2-CRYPTO-09 (phase-107): re-migrate restored rows to per-record
                // AAD on next launch (see attemptRecoveryFromBackup comment).
                settings.fieldAadMigrated = false
                // B1-DB-8 (phase-88): a successful restore replaces the whole DB —
                // the decrypt-failure ledger (and any in-memory escalation) must
                // not survive into the restored vault.
                repository.resetDecryptFailures()
                decryptPersistenceEscalated = false
                onComplete(true, null)
            } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                // B2-DOS-05 (phase-81 review fix): an over-budget WebDAV archive
                // fails CLOSED with a truthful, non-alarming message (never a
                // generic "failed") — the vault is reopened untouched.
                runCatching { repository.reopenDatabase(getApplication()) }
                onComplete(false, "Backup is too large to restore (max 400 MB).")
            } catch (e: Exception) {
                // H1 (phase-09): a failed WebDAV restore leaves the live DB closed —
                // reopen it so the vault behind the dialog is not bricked. The caller
                // (WebDavSyncDialog) tells the user to restart to fully re-initialize.
                runCatching { repository.reopenDatabase(getApplication()) }
                onComplete(false, null)
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
        val tagHierarchy = WikiLinkParser.buildTagHierarchy(
            pages,
            // B1-AUTH-05 (phase-69): legacy source-file reads are confined to the
            // app-private imports root.
            com.authorss81.noteflow.services.ImportExportService.getImportsDir(appContext)
        )
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
        // B2-UI-2 (phase-72): decrypted note content this app copied to the
        // system clipboard (code blocks, OCR results) must not survive an in-app
        // lock. The in-app lock paths (manual "Lock Vault Now", idle auto-lock,
        // ACTION_SCREEN_OFF) keep the app foregrounded, so ON_PAUSE — the only
        // existing scrub trigger — may never fire. Centralizing the scrub here
        // (instead of in every viewModel.lock() call site) makes EVERY lock path
        // clear an app-owned copy inside its window and forget the timestamp,
        // while a foreign (other-app) copy on the clipboard is never wiped.
        // Best-effort by design — the guard swallows platform failures so the
        // lock itself can never break.
        ClipboardGuard.scrubIfOwnCopy(appContext)

        repository.zeroizeKey()
        // B1-DB-8 (phase-88): the session ledger must not survive a lock — the
        // next unlock recomputes it from fresh reads (and a locked vault never
        // inflates it: reads record only when a DEK is actually present).
        repository.resetDecryptFailures()
        // B1-AUTH-02 (phase-47): the lock boundary must reach the DATA LAYER, not
        // just the Compose LockScreen boolean. dispose() closes and forgets the
        // Room/SQLCipher instance so NO keyed SQLCipher handle survives — a stale
        // coroutine/plugin handle now fails closed ("connection pool has been
        // closed") instead of reading plaintext, and a fresh open while locked
        // throws in NoteflowSqlcipherFactory (LockedOpenGuard-driven) instead of
        // re-deriving the DEK. Observer jobs are dropped so nothing keeps
        // collecting from the closed vault; the unlock paths
        // (verifyMasterPassword / verifyBiometricsAndUnlock) reinstate a live
        // connection + observers. Skipped for passwordless vaults: there is no
        // lock boundary there (the device-wrapped DEK is the boot credential by
        // design), so closing the still-active session would only break the UI.
        if (settings.hasMasterPassword) {
            sectionsJob?.cancel()
            pagesJob?.cancel()
            NoteflowDatabase.dispose()
            databaseDisposedByLock = true
            dataInitialized = false
            // B1-AUTH-03 (phase-67): tear down + quiesce the plugin lifecycle —
            // every enabled plugin whose onEnable ran gets onDisable and the
            // registry refuses further hooks while the vault is locked, so no
            // plugin keeps a live application Context on the LockScreen. The
            // next successful unlock re-boots the layer via startPluginLifecycle().
            pluginRegistry.pauseLifecycle(appContext)
            pluginLifecycleStarted = false
        }
        invalidatePaletteIndex()
        // B2-DOS-01 (phase-50): a new unlock session may re-notify a capped page.
        geometryCappedNotifiedPages.clear()
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
        // B1-AUTH-02 (phase-47): the DEK is zeroized here; drop the keyed
        // SQLCipher connection too so no SQLCipher handle outlives the token that
        // opened it (a subsequently constructed ViewModel rebuilds via getDatabase).
        NoteflowDatabase.dispose()
        repository.zeroizeKey()
    }
}
