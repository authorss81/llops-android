package com.authorss81.noteflow.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.authorss81.noteflow.data.db.NoteflowDatabase
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.data.repository.NoteRepository
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginDiagnostics
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.PluginStateInfo
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.SecurityService
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.services.SettingsPluginEnableStore
import com.authorss81.noteflow.services.SettingsPluginSettingsStore
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
    val pluginRegistry = PluginRegistry(
        pluginEnableStore,
        pluginSettingsStore,
        logger = AndroidPluginLogger()
    )
    val pluginManager = PluginManager(pluginRegistry, AndroidPluginLogger())
    val pluginDiagnostics = PluginDiagnostics(pluginRegistry, pluginManager)

    init {
        // Fire onEnable once per process for plugins already enabled in a
        // previous session (see PluginRegistry.onProcessStart).
        pluginRegistry.onProcessStart(appContext)
        refreshPluginStates()
    }

    private val _pluginEnabledIds = MutableStateFlow(pluginRegistry.allPlugins.associate { it.id to pluginRegistry.isEnabled(it.id) })
    val pluginEnabledIds: StateFlow<Map<String, Boolean>> = _pluginEnabledIds.asStateFlow()

    private val _pluginStates = MutableStateFlow<Map<String, PluginStateInfo>>(emptyMap())
    val pluginStates: StateFlow<Map<String, PluginStateInfo>> = _pluginStates.asStateFlow()

    private val _pluginDiagnostics = MutableStateFlow<List<PluginDiagnostics.Entry>>(emptyList())
    val pluginDiagnosticsEntries: StateFlow<List<PluginDiagnostics.Entry>> = _pluginDiagnostics.asStateFlow()

    private fun refreshPluginStates() {
        _pluginEnabledIds.value = pluginRegistry.allPlugins.associate { it.id to pluginRegistry.isEnabled(it.id) }
        _pluginStates.value = pluginRegistry.resolve(appContext)
        _pluginDiagnostics.value = pluginDiagnostics.snapshot(appContext)
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
     * True when a plugin currently sits in a state that can serve requests.
     * Computed FRESH from the registry (which re-evaluates device availability
     * under a guard on every call), so a revoked permission or lost dependency
     * is reflected immediately — never stale.
     */
    fun isPluginUsable(pluginId: String): Boolean =
        pluginRegistry.stateOf(pluginId, appContext)?.state == PluginLifecycleState.AVAILABLE

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
    val notebooks: StateFlow<List<NotebookEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.notebooks else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allSections: StateFlow<List<SectionEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.getAllSections() else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allActivePages: StateFlow<List<NotePageEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.getAllActivePagesFlow() else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val paletteItems: StateFlow<List<PaletteItemEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.allPaletteItems else flowOf(emptyList())
        }
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
    val recentPages: StateFlow<List<NotePageEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.getRecentPages() else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val trashedPages: StateFlow<List<NotePageEntity>> = _authenticated
        .flatMapLatest { isAuth ->
            if (isAuth) repository.getTrashedPages() else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var sectionsJob: Job? = null
    private var pagesJob: Job? = null

    private var lockoutTickerJob: Job? = null
    private var dataInitialized = false

    private fun initializeData() {
        if (dataInitialized) return
        dataInitialized = true
        viewModelScope.launch {
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
                _restoreBlocked.value = false
                _databaseTampered.value = false
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
        private const val MIN_PASSWORD_LENGTH = 6
        const val MAX_FAILED_ATTEMPTS = 5
    }

    suspend fun setMasterPassword(password: String): Boolean {
        if (password.trim().isEmpty() || password.length < MIN_PASSWORD_LENGTH) return false
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
                kek = EncryptionService.deriveKey(password, salt)
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
        if (newPassword.length < MIN_PASSWORD_LENGTH) return false
        if (!verifyMasterPassword(oldPassword)) return false
        val currentDek = repository.encryptionKey ?: return false
        var kek: ByteArray? = null

        return try {
            val newSalt = EncryptionService.generateSalt()
            val newWrappedDek = withContext(Dispatchers.Default) {
                kek = EncryptionService.deriveKey(newPassword, newSalt)
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
        var kek: ByteArray? = null
        val dek: ByteArray
        return try {
            val saltStr = settings.masterPasswordSalt ?: return false
            val wrappedDek = settings.masterPasswordWrappedDek ?: return false

            val salt = android.util.Base64.decode(saltStr, android.util.Base64.NO_WRAP)
            dek = withContext(Dispatchers.Default) {
                kek = EncryptionService.deriveKey(password, salt)
                EncryptionService.decrypt(wrappedDek, kek)
            }

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
        } finally {
            kek?.fill(0.toByte())
        }
    }

    /**
     * Side-effect-free master password check for password-derived backups.
     * Must not bump failed-attempt counters or trigger lockout — a typo in the
     * backup dialog is not an attack.
     */
    suspend fun isMasterPasswordValid(password: String): Boolean {
        var kek: ByteArray? = null
        return try {
            val saltStr = settings.masterPasswordSalt ?: return false
            val wrappedDek = settings.masterPasswordWrappedDek ?: return false
            val salt = android.util.Base64.decode(saltStr, android.util.Base64.NO_WRAP)
            val dek = withContext(Dispatchers.Default) {
                kek = EncryptionService.deriveKey(password, salt)
                EncryptionService.decrypt(wrappedDek, kek)
            }
            dek.fill(0.toByte())
            true
        } catch (e: Exception) {
            false
        } finally {
            kek?.fill(0.toByte())
        }
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

    fun lock() {

        repository.zeroizeKey()
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
