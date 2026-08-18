package com.authorss81.noteflow.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.NotebookEntity
import com.authorss81.noteflow.data.model.SectionEntity
import com.authorss81.noteflow.services.BackupPasswordPolicy
import com.authorss81.noteflow.services.DocumentTextExtractor
import com.authorss81.noteflow.services.ExportDestinationPolicy
import com.authorss81.noteflow.services.ImportArchivePolicy
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.OrphanImportCleanupPolicy
import com.authorss81.noteflow.services.isPlainPkBackupFile
import com.authorss81.noteflow.services.isNflbBackupFile
import com.authorss81.noteflow.services.RestoreFailSafe
import com.authorss81.noteflow.theme.AppThemeMode
import com.authorss81.noteflow.ui.components.*
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onOpenGraph: () -> Unit = {},
    onOpenCommandPalette: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // B2-UI-6 (phase-96): vault-wide imports/exports/restore run on the ViewModel
    // scope so a lock/teardown disposing this composable can no longer cancel them
    // mid-operation (the pre-fix composition-scoped `scope` was torn down on every
    // lock — multi-entry imports stopped partway leaving orphaned files in
    // imports/, and exports abandoned silently). `scope` remains for pure-UI
    // chores (drawer state, count previews) that are fine to cancel with the screen.
    val vaultScope = viewModel.viewModelScope

    // B1-PLAT-3 (phase-59): every export/backup goes to a user-picked SAF
    // destination — never straight into public Downloads. The exporter's own
    // copy+delete also runs on the VM scope (B2-UI-6) so the SAF transfer that
    // follows a picker is not abandoned when the screen leaves composition.
    val exporter = rememberSaFExporter(vaultScope)

    val notebooks by viewModel.notebooks.collectAsState()
    val selectedNotebook by viewModel.selectedNotebook.collectAsState()
    val sections by viewModel.sections.collectAsState()
    val selectedSection by viewModel.selectedSection.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val recentPages by viewModel.recentPages.collectAsState()
    val trashedPages by viewModel.trashedPages.collectAsState()

    val useSidebarLayout by viewModel.useSidebarLayout.collectAsState()
    val allSections by viewModel.allSections.collectAsState()
    val allActivePages by viewModel.allActivePages.collectAsState()

    val isFirstRun by viewModel.isFirstRun.collectAsState()
    val tutorialCompleted by viewModel.tutorialCompleted.collectAsState()
    val confettiTrigger by viewModel.confettiTrigger.collectAsState()

    var isInitializingLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        isInitializingLoading = false
    }

    var showSecurityDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showPluginsDialog by remember { mutableStateOf(false) }
    var showPluginStoreDialog by remember { mutableStateOf(false) }
    var showBackupPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showLegacyRestoreConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var backupPasswordInput by rememberSaveable { mutableStateOf("") }
    var backupPasswordError by rememberSaveable { mutableStateOf<String?>(null) }
    var isValidating by rememberSaveable { mutableStateOf(false) }
    // R2-b2b1-UI-03/-06 (phase-135): the restore buttons disable while a restore
    // is in flight — the one-in-flight gate lives in the ViewModel (survives
    // rotation), so this is the UI half of the shared gate.
    val isRestoring by viewModel.isRestoring.collectAsState()
    // Phase 125: the interactive tutorial is the first-run experience (armed once,
    // never auto-reopens once tutorialCompleted); reopen anytime via ⋮ → Tutorial.
    var showTutorial by remember { mutableStateOf(isFirstRun && !tutorialCompleted) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Pages, 1 = Recent, 2 = Tag Vault, 3 = Trash
    var pageViewMode by remember { mutableIntStateOf(0) } // 0 = List, 1 = Gallery, 2 = Kanban, 3 = Calendar, 4 = Table
    var showTemplateLibrary by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var showLocalSendDialog by remember { mutableStateOf(false) }
    var showWebCaptureDialog by remember { mutableStateOf(false) }
    var activeTagFilterPath by remember { mutableStateOf<String?>(null) }
    var activeTagMatchingIds by remember { mutableStateOf<Set<String>?>(null) }

    val isWide = BoxWithConstraintsScope_isWide()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var promptDialogType by remember { mutableStateOf<String?>(null) } // "add_nb", "add_sec", "rename_nb", "rename_sec", "rename_page"
    var targetEntityId by remember { mutableStateOf<String?>(null) }
    var initialDialogText by remember { mutableStateOf("") }

    var deleteConfirmType by remember { mutableStateOf<String?>(null) } // "nb", "sec", "page", "empty_trash"
    var deleteWarningMessage by remember { mutableStateOf("") }

    // 22.9: restore needs a restart — confirm visibly instead of a snackbar that
    // is killed by exitProcess before it can ever be shown.
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    var restartDialogTitle by remember { mutableStateOf("Restore successful") }
    var restartDialogMessage by remember { mutableStateOf("Your vault has been restored. The app will restart to load the restored data.") }

    fun performRestore(context: android.content.Context, file: File, password: String? = null) {
        // R2-b2b1-UI-03 (phase-135): the local restore shares the SAME one-in-flight
        // gate as the recovery/WebDAV paths — refuse a second restore instead of
        // racing two file swaps of the same SQLCipher file.
        if (!viewModel.tryBeginRestore()) {
            // R2-B1D-04 review (phase-138): the staged copy is transient — drop it
            // even when the in-flight gate blocks this restore.
            file.delete()
            restartDialogTitle = "Restore already in progress"
            restartDialogMessage = "A restore is already running. Wait for it to finish before starting another."
            showRestartConfirmDialog = true
            return
        }
        // B2-UI-6 (phase-96): the restore runs on the ViewModel scope — a lock
        // disposing this screen mid-swap must not abandon the restore silently.
        // Completion posts through the snackbarMessages pipeline as well as the
        // restart dialog, so the outcome is visible even if this screen left
        // composition.
        vaultScope.launch {
            try {
                // H1 (phase-09): reject a wrong backup password BEFORE the live
                // vault is closed — the common failure case never touches the DB
                // and the user can simply correct the password.
                if (password != null) {
                    ImportExportService.validateBackupPasswordFile(file, password)
                }
                // R2-b2b1-UI-03 (phase-135): never import into a vault that locked
                // while the file was being picked/read — fail closed and reopen.
                if (viewModel.repository.encryptionKey == null) {
                    runCatching { viewModel.repository.reopenDatabase(context) }
                    throw IllegalStateException("The vault locked before the restore — please unlock and try again.")
                }
                // R2-B1D-04 (phase-138): close + restore through the failsafe seam —
                // ANY post-close failure (incl. an unchecked Throwable) reopens the
                // vault, so a corrupt/key-wrong archive can never brick the DB.
                RestoreFailSafe.guaranteeReopenAfterRestore(
                    closeDatabase = { viewModel.repository.closeDatabase() },
                    restore = {
                        // R2-B1D-02 (phase-135): a valid-schema-but-empty backup fails
                        // here with a truthful message (no "start fresh" confirm on
                        // this screen) — it is never swapped in silently.
                        ImportExportService.importBackup(context, file, viewModel.repository.encryptionKey, password, allowEmptyVault = false)
                    },
                    reopenDatabase = { viewModel.repository.reopenDatabase(context) }
                )
                restartDialogTitle = "Restore successful"
                restartDialogMessage = "Your vault has been restored. The app will restart to load the restored data."
                showRestartConfirmDialog = true
                viewModel.showSnackbar("Restore completed — the app will restart to load the restored data.", isLong = true)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // H1 (phase-09) + R2-B1D-04: the DB was already closed for the swap.
                // A failure here must never leave a dead Room instance behind — the
                // failsafe already reopens on post-close failures; this re-reopen
                // covers pre-close failures (e.g. a lock mid-pick), and the process
                // restarts so the vault flows re-initialize cleanly (same pattern
                // the success path uses).
                runCatching { viewModel.repository.reopenDatabase(context) }
                restartDialogTitle = "Restore failed"
                restartDialogMessage = "Restore failed: ${e.message}. The app will restart with your current vault unchanged."
                showRestartConfirmDialog = true
                viewModel.showSnackbar("Restore failed: ${e.message}", isLong = true)
            } finally {
                // R2-B1D-04 review (phase-138): the staged cache copy is consumed
                // by validateBackupPasswordFile/importBackup above and must never
                // accumulate in cacheDir — delete it on EVERY outcome (success,
                // wrong password, corrupt archive, over-budget, OOM-including).
                file.delete()
                viewModel.endRestore()
            }
        }
    }

    // R2-B1D-04 review (phase-138): the picker stages the chosen backup into a
    // cache file; a callback that abandons it (dialog dismiss/cancel) MUST delete
    // it, or every cancelled pick leaks a full backup copy in cacheDir. The
    // confirm paths do NOT call this — they hand the file to performRestore,
    // which owns the delete on every outcome.
    fun clearPendingRestore() {
        pendingRestoreFile?.delete()
        pendingRestoreFile = null
    }

    // File picker for import
    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // B2-UI-6 (phase-96): restore feed reads run on the VM scope so a
            // lock/teardown cannot abandon the read (and the subsequent
            // performRestore) silently.
            vaultScope.launch {
                try {
                    // R2-B1D-04 (phase-138): the picked URI is staged to a cache
                    // file under the same 400 MB budget — the whole archive is never
                    // materialized in heap.
                    val staged = ImportExportService.stageBackupUriToFile(
                        context,
                        uri,
                        ImportExportService.MAX_BACKUP_INPUT_BYTES
                    ) ?: return@launch
                    when {
                        isNflbBackupFile(staged) -> {
                            // B2-CRYPTO-05/06 (phase-93): NFLB2 (v2, password-protected)
                            // and NFLB3 (v3, scrypt-derived) backups both carry a
                            // wrapped-DEK header — ask for the password, never treat
                            // them as legacy.
                            pendingRestoreFile = staged
                            backupPasswordInput = ""
                            backupPasswordError = null
                            showBackupPasswordDialog = true
                        }
                        isPlainPkBackupFile(staged) -> {
                            // B1-DB-7 (phase-56): an unencrypted (unsigned) plain zip is
                            // never restoreable — refuse before any confirm dialog, the
                            // same gate importBackup enforces.
                            staged.delete()
                            viewModel.showSnackbar(
                                "Restore rejected: this is an unencrypted (unsigned) backup. " +
                                    "Only password-protected or device-keyed backups can be restored.",
                                isLong = true
                            )
                        }
                        else -> {
                            // B4/34.1: legacy (device-keyed) restores replace the whole vault —
                            // require explicit user confirmation instead of silently doing it.
                            pendingRestoreFile = staged
                            showLegacyRestoreConfirmDialog = true
                        }
                    }
                } catch (e: Exception) {
                    viewModel.showSnackbar("Restore failed: ${e.message}", isLong = true)
                }
            }
        }
    }

    // Global Vault Search state
    var globalSearchResults by remember { mutableStateOf<List<NotePageEntity>?>(null) }

    // B2-DOS-02 (phase-78): the capped-window "search all pages" refine notice is
    // ONE-TIME per query session — shown when the cached search window was capped
    // (vault > VaultSearchPolicy.SEARCH_CORPUS_CAP pages), hidden once the user
    // explicitly opts into the deep full-vault scan.
    var refinedSearchDone by remember { mutableStateOf(false) }

    // Tag Manager and Tag Editor dialog state
    var showTagManagerDialog by remember { mutableStateOf(false) }
    var tagEditorTargetNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var tagEditorTargetPage by remember { mutableStateOf<NotePageEntity?>(null) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            refinedSearchDone = false
            kotlinx.coroutines.delay(300)
            viewModel.searchVault(searchQuery) { results ->
                globalSearchResults = results
            }
        } else {
            globalSearchResults = null
        }
    }
    var pendingImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showMultiPageImportDialog by remember { mutableStateOf(false) }
    var selectedImportOrientation by remember { mutableStateOf("AUTO") } // AUTO, PORTRAIT, LANDSCAPE

    fun processImportedUris(uris: List<Uri>, importAsSeparatePages: Boolean, orientationChoice: String = selectedImportOrientation) {
        // B2-UI-6 (phase-96): the import loop runs on the ViewModel scope so a
        // lock/teardown disposing this composable can no longer cancel it at the
        // next suspension point mid-iteration (pre-fix: multi-entry imports
        // stopped partway, leaving already-persisted files whose DB rows were
        // never created). Persisted artifacts are tracked per run and swept if
        // the loop is genuinely cancelled, so no orphaned files accumulate.
        vaultScope.launch {
            var importedCount = 0
            val isSingleImport = uris.size == 1
            val orphanRun = OrphanImportCleanupPolicy.Run()
            try {
                for (uri in uris) {
                    // B1-DB-5 (phase-55): an oversized share/download raises
                    // ImportSizeLimitException — surface it as a non-alarming
                    // snackbar instead of silently skipping the file.
                    val bytes = try {
                        ImportExportService.readUriBytes(context, uri)
                    } catch (e: ImportArchivePolicy.ImportSizeLimitException) {
                        viewModel.showSnackbar("Import skipped: ${e.message}", isLong = true)
                        continue
                    } ?: continue
                    val fileName = ImportExportService.getUriFileName(context, uri)
                    val ext = ImportExportService.extensionOf(fileName)

                if (ext == "html" || ext == "htm") {
                    val activeNb = viewModel.selectedNotebook.value?.id ?: "nb_default"
                    val activeSec = viewModel.selectedSection.value?.id ?: "sec_default"
                    val page = runCatching {
                        ImportExportService.importHtmlFile(context, uri, viewModel.repository, activeNb, activeSec)
                    }.getOrElse { e ->
                        if (e is ImportArchivePolicy.ImportSizeLimitException) {
                            viewModel.showSnackbar("Import skipped: ${e.message}", isLong = true)
                        }
                        null
                    }
                    if (page != null) {
                        viewModel.selectedSection.value?.let { viewModel.selectSection(it) }
                        if (isSingleImport) onOpenPage(page)
                        importedCount++
                    }
                } else if (ext == "zip") {
                    val activeNb = viewModel.selectedNotebook.value?.id ?: "nb_default"
                    val activeSec = viewModel.selectedSection.value?.id ?: "sec_default"
                    var count = runCatching {
                        val c = ImportExportService.importObsidianVaultZip(context, uri, viewModel.repository, activeNb, activeSec)
                        if (c == 0) {
                            ImportExportService.importHtmlZipOrFolder(context, uri, viewModel.repository, activeNb, activeSec)
                        } else c
                    }.getOrElse { e ->
                        // B1-DB-5: a zip bomb (entry/total/ratio/count breach)
                        // must fail the import with a clean, visible error.
                        if (e is ImportArchivePolicy.ImportSizeLimitException) {
                            viewModel.showSnackbar("Import skipped: ${e.message}", isLong = true)
                        }
                        0
                    }
                    if (count > 0) {
                        viewModel.selectedSection.value?.let { viewModel.selectSection(it) }
                        importedCount += count
                    }
                } else if (ext == "docx") {
                    val markdownText = ImportExportService.convertDocxToMarkdown(bytes)
                    val mdTitle = fileName.replace(".docx", ".md")
                    // phase-44 (B1-DB-4): the note body is stored ONLY in the
                    // field-encrypted extractedText column — never persisted as a
                    // plaintext .md/.txt file under filesDir/noteflow/imports.
                    viewModel.addPage(
                        title = mdTitle,
                        sourceFilePath = null,
                        sourceFileType = "text",
                        extractedText = markdownText,
                        onCreated = if (isSingleImport) onOpenPage else null
                    )
                    importedCount++
                } else if (ext == "md" || ext == "txt") {
                    // phase-44 (B1-DB-4): body goes to the field-encrypted
                    // extractedText column only — no plaintext companion file.
                    val textContent = String(bytes, Charsets.UTF_8)
                    viewModel.addPage(
                        title = fileName,
                        sourceFilePath = null,
                        sourceFileType = "text",
                        extractedText = textContent,
                        onCreated = if (isSingleImport) onOpenPage else null
                    )
                    importedCount++
                } else {
                    val type = if (ImportExportService.isPdf(ext)) "pdf" else if (ImportExportService.isImage(ext)) "image" else "text"
                    // phase-44 review fix (B1-DB-4): unknown text-like extensions
                    // (e.g. .markdown/.rst) must NOT land as a plaintext body file at
                    // rest — same column-only contract as .md/.txt. Only genuine
                    // PDF/image sources are persisted as binary artifacts.
                    val isTextBodyType = type == "text"
                    val path: String? = if (isTextBodyType) null else ImportExportService.persistFile(context, fileName, bytes)
                    // B2-UI-6 (phase-96): track this persisted artifact until its
                    // DB page row commits below — a cancellation between this write
                    // and the page create would otherwise leave an orphaned file in
                    // imports/ with no row referencing it.
                    path?.let { orphanRun.trackPersisted(it) }

                    val (extractedText, pageCount, isLandscapeFormat) = withContext(Dispatchers.IO) {
                        val extracted = if (isTextBodyType) {
                            String(bytes, Charsets.UTF_8)
                        } else {
                            DocumentTextExtractor.extractText(File(path!!), type)
                        }
                        val count = if (isTextBodyType) 1 else ImportExportService.getPdfPageCount(path!!)
                        var landscapeFormat = false
                        try {
                            if (type == "image") {
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(path!!, options)
                                landscapeFormat = options.outWidth > options.outHeight
                            } else if (type == "pdf") {
                                val pfd = android.os.ParcelFileDescriptor.open(File(path!!), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                                if (renderer.pageCount > 0) {
                                    val pdfPage = renderer.openPage(0)
                                    landscapeFormat = pdfPage.width > pdfPage.height
                                    pdfPage.close()
                                }
                                renderer.close()
                                pfd.close()
                            }
                        } catch (e: Exception) {
                            landscapeFormat = false
                        }
                        Triple(extracted, count, landscapeFormat)
                    }

                    // Determine format / orientation tag
                    val orientationTag = when (orientationChoice) {
                        "LANDSCAPE" -> "orientation_landscape"
                        "PORTRAIT" -> "orientation_portrait"
                        else -> if (isLandscapeFormat) "orientation_landscape" else "orientation_portrait"
                    }

                    if (type == "pdf" && pageCount > 1 && importAsSeparatePages) {
                        // Option A: Split into Separate Note Pages
                        val baseTitle = fileName.substringBeforeLast('.')
                        for (i in 0 until pageCount) {
                            viewModel.addPage(
                                title = "$baseTitle - Page ${i + 1}",
                                sourceFilePath = path,
                                sourceFileType = "pdf",
                                extractedText = if (i == 0) extractedText else "",
                                tags = orientationTag
                            )
                            importedCount++
                        }
                    } else {
                        // Option B: Continuous Infinite Canvas (single entry displaying infinite canvas)
                        viewModel.addPage(
                            title = fileName,
                            sourceFilePath = path,
                            sourceFileType = type,
                            extractedText = extractedText,
                            tags = orientationTag,
                            onCreated = if (isSingleImport) onOpenPage else null
                        )
                        importedCount++
                    }
                    // B2-UI-6 (phase-96): every page-create for this file has been
                    // issued — from here on the artifact is referenced by a page row
                    // and must never be swept.
                    path?.let { orphanRun.markCommitted(it) }
                }

                if (importedCount > 0) {
                    viewModel.showSnackbar("Imported $importedCount page(s)")
                }
            }

            } catch (e: kotlinx.coroutines.CancellationException) {
            // B2-UI-6 (phase-96): the run was cancelled between a persist and its
            // page create. Delete every tracked file whose page row was never
            // created so no orphaned plaintext/binary artifacts stay in imports/;
            // completion still posts a visible notice. Re-throw to honour
            // structured-concurrency cancellation.
            val swept = orphanRun.sweepOrphans()
            if (swept.isNotEmpty() || importedCount > 0) {
                viewModel.showSnackbar(OrphanImportCleanupPolicy.CANCELLED_NOTICE, isLong = true)
            }
            throw e
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val hasPdf = uris.any { uri ->
                val name = ImportExportService.getUriFileName(context, uri)
                ImportExportService.isPdf(ImportExportService.extensionOf(name))
            }
            if (hasPdf || uris.size > 1) {
                pendingImportUris = uris
                showMultiPageImportDialog = true
            } else {
                processImportedUris(uris, importAsSeparatePages = false)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = useSidebarLayout && !isWide,
        drawerContent = {
            if (useSidebarLayout && !isWide) {
                ModalDrawerSheet(
                    modifier = Modifier.width(300.dp)
                ) {
                    UnifiedSidebar(
                        notebooks = notebooks,
                        allSections = allSections,
                        allActivePages = allActivePages,
                        selectedNotebook = selectedNotebook,
                        selectedSection = selectedSection,
                        onSelectNotebook = { viewModel.selectNotebook(it) },
                        onSelectSection = { viewModel.selectSection(it) },
                        onSelectPage = { page ->
                            scope.launch { drawerState.close() }
                            onOpenPage(page)
                        },
                        onAddNotebook = {
                            promptDialogType = "add_nb"
                            initialDialogText = ""
                        },
                        onAddSection = { nb ->
                            viewModel.selectNotebook(nb)
                            promptDialogType = "add_sec"
                            initialDialogText = ""
                        },
                        onAddPage = { sec ->
                            viewModel.selectSection(sec)
                            viewModel.addPage("New Page", onCreated = onOpenPage)
                        },
                        onRenameNotebook = { nb ->
                            promptDialogType = "rename_nb"
                            targetEntityId = nb.id
                            initialDialogText = nb.name
                        },
                        onDeleteNotebook = { nb ->
                            targetEntityId = nb.id
                            scope.launch {
                                val (secCount, pageCount) = viewModel.loadNotebookCounts(nb.id)
                                deleteWarningMessage = "Are you sure you want to delete '${nb.name}'? Deleting this notebook will permanently delete $secCount section(s) and $pageCount page(s)."
                                deleteConfirmType = "nb"
                            }
                        },
                        onRenameSection = { sec ->
                            promptDialogType = "rename_sec"
                            targetEntityId = sec.id
                            initialDialogText = sec.name
                        },
                        onDeleteSection = { sec ->
                            targetEntityId = sec.id
                            scope.launch {
                                val pageCount = viewModel.loadSectionCounts(sec.id)
                                deleteWarningMessage = "Are you sure you want to delete '${sec.name}'? Deleting this section/quick notes will permanently delete $pageCount page(s)."
                                deleteConfirmType = "sec"
                            }
                        },
                        onRenamePage = { page ->
                            initialDialogText = page.title
                            targetEntityId = page.id
                            promptDialogType = "rename_page"
                        },
                        onDeletePage = { page ->
                            scope.launch {
                                viewModel.trashPage(page.id)
                            }
                        },
                        onTogglePinPage = { page ->
                            scope.launch {
                                viewModel.togglePinPage(page.id, page.pinned)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("InkFlow") },
                        navigationIcon = {
                            if (useSidebarLayout && !isWide) {
                                IconButton(onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }) {
                                    Icon(Icons.Outlined.Menu, contentDescription = "Open Navigation Sidebar")
                                }
                            }
                        },
                        actions = {
                        IconButton(onClick = {
                            viewModel.openOrCreateDailyNote(context) { dailyPage ->
                                onOpenPage(dailyPage)
                            }
                        }) {
                            Icon(Icons.Outlined.Today, contentDescription = "Today's Journal Note", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onOpenCommandPalette) {
                            Icon(Icons.Outlined.Keyboard, contentDescription = "Command Palette (two-finger swipe down)", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onOpenGraph) {
                            Icon(Icons.Outlined.Hub, contentDescription = "Knowledge Graph View", tint = MaterialTheme.colorScheme.primary)
                        }
                        ThemeMenu(viewModel = viewModel)
                        val showStrokePreviewsInPicker by viewModel.showStrokePreviewsInPicker.collectAsState()
                        val databaseIntegrityCheckEnabled by viewModel.databaseIntegrityCheckEnabled.collectAsState()
                        MaintenanceMenu(
                            useSidebarLayout = useSidebarLayout,
                            onToggleSidebarLayout = { viewModel.setUseSidebarLayout(it) },
                            showStrokePreviews = showStrokePreviewsInPicker,
                            onToggleStrokePreviews = { viewModel.toggleShowStrokePreviewsInPicker(it) },
                            databaseIntegrityCheckEnabled = databaseIntegrityCheckEnabled,
                            onToggleDatabaseIntegrityCheck = { viewModel.setDatabaseIntegrityCheckEnabled(it) },
                            onOpenTagManager = { showTagManagerDialog = true },
                            onOpenTutorial = { showTutorial = true },
                            onOpenSecurity = { showSecurityDialog = true },
                            onOpenUpdate = { showUpdateDialog = true },
                            onOpenPlugins = { showPluginsDialog = true },
                            onOpenPluginStore = { showPluginStoreDialog = true },
                            onOpenLocalSend = { showLocalSendDialog = true },
                            onOpenWebCapture = { showWebCaptureDialog = true },
                            onOpenWebDavSync = { showWebDavDialog = true },
                            onBackup = {
                                if (viewModel.hasMasterPassword.value) {
                                    backupPasswordInput = ""
                                    backupPasswordError = null
                                    showBackupPasswordDialog = true
                                } else {
                                    // B2-UI-6 (phase-96): the backup runs on the VM
                                    // scope and completion posts through the snackbar
                                    // pipeline, so a lock/teardown never abandons it
                                    // silently.
                                    vaultScope.launch {
                                        try {
                                            // R2-B1D-05/03 (phase-137): the checkpoint
                                            // + HMAC re-stamp + verified DB snapshot now
                                            // live INSIDE exportBackup (single producer).
                                            val cacheFile = ImportExportService.exportBackup(
                                                context,
                                                viewModel.repository.encryptionKey,
                                                repository = viewModel.repository
                                            )
                                            exporter.export(
                                                ExportDestinationPolicy.ExportKind.ENCRYPTED_BACKUP,
                                                cacheFile
                                            ) { result ->
                                                when (result) {
                                                    SaFExportResult.SAVED -> viewModel.showSnackbar("Backup created and saved.")
                                                    SaFExportResult.CANCELLED -> viewModel.showSnackbar("Backup cancelled")
                                                    SaFExportResult.FAILED -> viewModel.showSnackbar("Backup could not be written to the chosen destination")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            viewModel.showSnackbar("Backup failed: ${e.message}")
                                        }
                                    }
                                }
                            },
                            onRestore = {
                                restorePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onExportObsidianVault = {
                                // B2-UI-6 (phase-96): VM-scoped so a lock/teardown never
                                // abandons the export silently.
                                vaultScope.launch {
                                    val pages = viewModel.pages.value
                                    val zipFile = ImportExportService.exportObsidianVaultZip(context, "SmoothNotes_Vault", pages, viewModel.repository)
                                    if (zipFile != null && zipFile.exists()) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.OBSIDIAN_VAULT,
                                            zipFile
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> viewModel.showSnackbar("Obsidian vault exported.")
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Obsidian vault export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Obsidian vault export failed to write")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Failed to export Obsidian vault")
                                    }
                                }
                            },
                            onExportHtmlVault = {
                                // B2-UI-6 (phase-96): VM-scoped so a lock/teardown never
                                // abandons the export silently.
                                vaultScope.launch {
                                    val pages = viewModel.pages.value
                                    val zipFile = ImportExportService.exportVaultToHtmlZip(context, "SmoothNotes_Site", pages, viewModel.repository)
                                    if (zipFile != null && zipFile.exists()) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.HTML_SITE,
                                            zipFile
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> viewModel.showSnackbar("HTML site exported.")
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("HTML site export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("HTML site export failed to write")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Failed to export HTML site")
                                    }
                                }
                            }
                        )
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        viewModel.addPage("New Page", onCreated = onOpenPage)
                    }
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "New Page")
                }
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Wide Screen Notebook & Section Sidebar or Mobile layout
                val isWide = BoxWithConstraintsScope_isWide()

                if (isWide) {
                    if (useSidebarLayout) {
                        UnifiedSidebar(
                            notebooks = notebooks,
                            allSections = allSections,
                            allActivePages = allActivePages,
                            selectedNotebook = selectedNotebook,
                            selectedSection = selectedSection,
                            onSelectNotebook = { viewModel.selectNotebook(it) },
                            onSelectSection = { viewModel.selectSection(it) },
                            onSelectPage = onOpenPage,
                            onAddNotebook = {
                                promptDialogType = "add_nb"
                                initialDialogText = ""
                            },
                            onAddSection = { nb ->
                                viewModel.selectNotebook(nb)
                                promptDialogType = "add_sec"
                                initialDialogText = ""
                            },
                            onAddPage = { sec ->
                                viewModel.selectSection(sec)
                                viewModel.addPage("New Page", onCreated = onOpenPage)
                            },
                            onRenameNotebook = { nb ->
                                promptDialogType = "rename_nb"
                                targetEntityId = nb.id
                                initialDialogText = nb.name
                            },
                            onDeleteNotebook = { nb ->
                                targetEntityId = nb.id
                                scope.launch {
                                    val (secCount, pageCount) = viewModel.loadNotebookCounts(nb.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${nb.name}'? Deleting this notebook will permanently delete $secCount section(s) and $pageCount page(s)."
                                    deleteConfirmType = "nb"
                                }
                            },
                            onRenameSection = { sec ->
                                promptDialogType = "rename_sec"
                                targetEntityId = sec.id
                                initialDialogText = sec.name
                            },
                            onDeleteSection = { sec ->
                                targetEntityId = sec.id
                                scope.launch {
                                    val pageCount = viewModel.loadSectionCounts(sec.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${sec.name}'? Deleting this section/quick notes will permanently delete $pageCount page(s)."
                                    deleteConfirmType = "sec"
                                }
                            },
                            onRenamePage = { page ->
                                initialDialogText = page.title
                                targetEntityId = page.id
                                promptDialogType = "rename_page"
                            },
                            onDeletePage = { page ->
                                scope.launch {
                                    viewModel.trashPage(page.id)
                                }
                            },
                            onTogglePinPage = { page ->
                                scope.launch {
                                    viewModel.togglePinPage(page.id, page.pinned)
                                }
                            },
                            modifier = Modifier
                                .width(280.dp)
                                .fillMaxHeight()
                        )
                        VerticalDivider()
                    } else {
                        NotebookPanel(
                            notebooks = notebooks,
                            selectedNotebook = selectedNotebook,
                            onSelectNotebook = { viewModel.selectNotebook(it) },
                            onAddNotebook = {
                                promptDialogType = "add_nb"
                                initialDialogText = ""
                            },
                            onRenameNotebook = { nb ->
                                promptDialogType = "rename_nb"
                                targetEntityId = nb.id
                                initialDialogText = nb.name
                            },
                            onDeleteNotebook = { nb ->
                                targetEntityId = nb.id
                                scope.launch {
                                    val (secCount, pageCount) = viewModel.loadNotebookCounts(nb.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${nb.name}'? Deleting this notebook will permanently delete $secCount section(s) and $pageCount page(s)."
                                    deleteConfirmType = "nb"
                                }
                            },
                            onEditTagsNotebook = { nb ->
                                tagEditorTargetNotebook = nb
                            },
                            onExportVaultNotebook = { nb ->
                                viewModel.showSnackbar("Exporting Notebook Vault ZIP...")
                                viewModel.exportNotebookVaultZip(context, nb.id) { zipFile ->
                                    if (zipFile != null) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.VAULT_ZIP,
                                            zipFile
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Vault export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Vault export failed to write")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Vault export failed")
                                    }
                                }
                            },
                            modifier = Modifier
                                .width(260.dp)
                                .fillMaxHeight()
                        )

                        VerticalDivider()

                        SectionPanel(
                            sections = sections,
                            selectedSection = selectedSection,
                            onSelectSection = { viewModel.selectSection(it) },
                            onAddSection = {
                                promptDialogType = "add_sec"
                                initialDialogText = ""
                            },
                            onRenameSection = { sec ->
                                promptDialogType = "rename_sec"
                                targetEntityId = sec.id
                                initialDialogText = sec.name
                            },
                            onDeleteSection = { sec ->
                                targetEntityId = sec.id
                                scope.launch {
                                    val pageCount = viewModel.loadSectionCounts(sec.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${sec.name}'? Deleting this section/quick notes will permanently delete $pageCount page(s)."
                                    deleteConfirmType = "sec"
                                }
                            },
                            onExportVaultSection = { sec ->
                                viewModel.showSnackbar("Exporting Section Vault ZIP...")
                                viewModel.exportSectionVaultZip(context, sec.id) { zipFile ->
                                    if (zipFile != null) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.VAULT_ZIP,
                                            zipFile
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Vault export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Vault export failed to write")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Vault export failed")
                                    }
                                }
                            },
                            modifier = Modifier
                                .width(240.dp)
                                .fillMaxHeight()
                        )

                        VerticalDivider()
                    }
                }

                // Main Content Panel (Pages)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    if (useSidebarLayout) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = buildString {
                                    append(selectedNotebook?.name ?: "No Notebook")
                                    append("  /  ")
                                    append(selectedSection?.name ?: "No Section")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (!isWide) {
                        NotebookAndSectionSelectorBar(
                            notebooks = notebooks,
                            selectedNotebook = selectedNotebook,
                            sections = sections,
                            selectedSection = selectedSection,
                            onSelectNotebook = { viewModel.selectNotebook(it) },
                            onSelectSection = { viewModel.selectSection(it) },
                            onAddNotebook = {
                                promptDialogType = "add_nb"
                                initialDialogText = ""
                            },
                            onAddSection = {
                                promptDialogType = "add_sec"
                                initialDialogText = ""
                            },
                            onRenameNotebook = { nb ->
                                promptDialogType = "rename_nb"
                                targetEntityId = nb.id
                                initialDialogText = nb.name
                            },
                            onDeleteNotebook = { nb ->
                                targetEntityId = nb.id
                                scope.launch {
                                    val (secCount, pageCount) = viewModel.loadNotebookCounts(nb.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${nb.name}'? Deleting this notebook will permanently delete $secCount section(s) and $pageCount page(s)."
                                    deleteConfirmType = "nb"
                                }
                            },
                            onRenameSection = { sec ->
                                promptDialogType = "rename_sec"
                                targetEntityId = sec.id
                                initialDialogText = sec.name
                            },
                            onDeleteSection = { sec ->
                                targetEntityId = sec.id
                                scope.launch {
                                    val pageCount = viewModel.loadSectionCounts(sec.id)
                                    deleteWarningMessage = "Are you sure you want to delete '${sec.name}'? Deleting this section/quick notes will permanently delete $pageCount page(s)."
                                    deleteConfirmType = "sec"
                                }
                            },
                            onEditTagsNotebook = { nb ->
                                tagEditorTargetNotebook = nb
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Search & Import Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search notes...", maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
                                    }
                                }
                            } else null,
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalIconButton(
                            onClick = { showTemplateLibrary = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Outlined.Dashboard, contentDescription = "Templates")
                        }

                        FilledTonalIconButton(
                            onClick = {
                                filePickerLauncher.launch(
                                    arrayOf("*/*")
                                )
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = "Import files")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Navigation Tabs: Current Section, Recent, Tag Vault, Trash
                    PrimaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Pages") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Recent") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Tag Vault") }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("Trash") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedTab == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = pageViewMode == 0,
                                onClick = { pageViewMode = 0 },
                                label = { Text("List", maxLines = 1) }
                            )
                            FilterChip(
                                selected = pageViewMode == 1,
                                onClick = { pageViewMode = 1 },
                                label = { Text("Gallery", maxLines = 1) }
                            )
                            FilterChip(
                                selected = pageViewMode == 2,
                                onClick = { pageViewMode = 2 },
                                label = { Text("Kanban", maxLines = 1) }
                            )
                            FilterChip(
                                selected = pageViewMode == 3,
                                onClick = { pageViewMode = 3 },
                                label = { Text("Calendar", maxLines = 1) }
                            )
                            FilterChip(
                                selected = pageViewMode == 4,
                                onClick = { pageViewMode = 4 },
                                label = { Text("Table", maxLines = 1) }
                            )
                        }
                    }

                    if (selectedTab == 2) {
                        TagExplorerView(
                            viewModel = viewModel,
                            activeTagFilter = activeTagFilterPath,
                            onSelectTagFilter = { tagPath, pageIds ->
                                activeTagFilterPath = tagPath
                                activeTagMatchingIds = pageIds
                                if (tagPath != null) {
                                    selectedTab = 0 // Switch to Pages tab to show filtered notes!
                                }
                            }
                        )
                    } else {
                        val activePageList = if (searchQuery.isNotBlank()) {
                            globalSearchResults ?: emptyList()
                        } else if (activeTagMatchingIds != null) {
                            val matching = activeTagMatchingIds!!
                            pages.filter { it.id in matching }
                        } else {
                            when (selectedTab) {
                                1 -> recentPages
                                3 -> trashedPages
                                else -> pages
                            }
                        }

                        if (activeTagFilterPath != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Filtered by tag: #${activeTagFilterPath}", style = MaterialTheme.typography.bodyMedium)
                                    IconButton(
                                        onClick = {
                                            activeTagFilterPath = null
                                            activeTagMatchingIds = null
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear Tag Filter")
                                    }
                                }
                            }
                        }

                        if (searchQuery.isNotBlank() &&
                            viewModel.repository.searchCorpusCapped &&
                            !refinedSearchDone
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Search covers the most recent pages. Search all pages in the vault for older notes.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(
                                        onClick = {
                                            refinedSearchDone = true
                                            viewModel.deepSearchVault(searchQuery) { results ->
                                                globalSearchResults = results
                                            }
                                        }
                                    ) {
                                        Text("Search all pages")
                                    }
                                }
                            }
                        }

                        if (selectedTab == 3 && trashedPages.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { deleteConfirmType = "empty_trash" }
                                ) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Empty Trash")
                                }
                            }
                        }

                        if (activePageList.isEmpty()) {
                            val emptyDecision = EmptyStateResolver.decide(
                                kind = if (selectedTab == 3) {
                                    EmptyStateKind.TRASH
                                } else if (searchQuery.isNotBlank()) {
                                    EmptyStateKind.HOME_GRID
                                } else {
                                    EmptyStateKind.HOME_GRID
                                },
                                hasQuery = searchQuery.isNotBlank(),
                                isFirstRun = isFirstRun,
                                query = searchQuery
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TactileEmptyState(decision = emptyDecision)
                            }
                        } else {
                            if (selectedTab == 0) {
                                when (pageViewMode) {
                                    1 -> GalleryView(pages = activePageList, viewModel = viewModel, onOpenPage = onOpenPage)
                                    2 -> KanbanBoardView(pages = activePageList, viewModel = viewModel, onOpenPage = onOpenPage)
                                    3 -> CalendarView(pages = activePageList, viewModel = viewModel, onOpenPage = onOpenPage)
                                    4 -> SpreadsheetTableView(pages = activePageList, viewModel = viewModel, onOpenPage = onOpenPage)
                                    else -> {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(activePageList, key = { it.id }) { page ->
                                                NotePageCard(
                                                    page = page,
                                                    isTrash = false,
                                                    onClick = { onOpenPage(page) },
                                                    onTogglePin = { viewModel.togglePinPage(page.id, page.pinned) },
                                                    onRename = {
                                                        targetEntityId = page.id
                                                        initialDialogText = page.title
                                                        promptDialogType = "rename_page"
                                                    },
                                                    onTrash = { viewModel.trashPage(page.id) },
                                                    onRestore = { viewModel.restorePage(page.id) },
                                                    onDeletePermanent = {
                                                        targetEntityId = page.id
                                                        deleteConfirmType = "page_perm"
                                                    },
                                                    onEditTags = {
                                                        tagEditorTargetPage = page
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(activePageList, key = { it.id }) { page ->
                                        NotePageCard(
                                            page = page,
                                            isTrash = selectedTab == 3,
                                            onClick = { onOpenPage(page) },
                                            onTogglePin = { viewModel.togglePinPage(page.id, page.pinned) },
                                            onRename = {
                                                targetEntityId = page.id
                                                initialDialogText = page.title
                                                promptDialogType = "rename_page"
                                            },
                                            onTrash = { viewModel.trashPage(page.id) },
                                            onRestore = { viewModel.restorePage(page.id) },
                                            onDeletePermanent = {
                                                targetEntityId = page.id
                                                deleteConfirmType = "page_perm"
                                            },
                                            onEditTags = {
                                                tagEditorTargetPage = page
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTemplateLibrary) {
            TemplateLibraryDialog(
                viewModel = viewModel,
                onDismiss = { showTemplateLibrary = false },
                onTemplateApplied = {
                    viewModel.showSnackbar("Workspace template applied successfully!")
                }
            )
        }

        if (showSecurityDialog) {
            SecuritySettingsDialog(
                viewModel = viewModel,
                onDismiss = { showSecurityDialog = false }
            )
        }

        if (showPluginsDialog) {
            PluginSettingsDialog(
                viewModel = viewModel,
                onDismiss = { showPluginsDialog = false }
            )
        }

        if (showPluginStoreDialog) {
            PluginStoreDialog(
                viewModel = viewModel,
                onDismiss = { showPluginStoreDialog = false }
            )
        }

        if (showLegacyRestoreConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    showLegacyRestoreConfirmDialog = false
                    clearPendingRestore()
                },
                title = { Text("Restore legacy backup?") },
                text = {
                    Text(
                        "This is an older device-keyed backup that is NOT protected by a backup " +
                            "password and carries NO digital signature — treat it as an UNTRUSTED, " +
                            "UNSIGNED backup and verify it came from your own device. " +
                            "Restoring it will REPLACE ALL pages, strokes and settings on this device " +
                            "with the backup's contents. Continue?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLegacyRestoreConfirmDialog = false
                            // R2-B1D-04 review: performRestore owns the staged-file
                            // delete — do NOT clearPendingRestore() here or the file
                            // vanishes before importBackup reads it.
                            val pending = pendingRestoreFile
                            pendingRestoreFile = null
                            if (pending != null) performRestore(context, pending)
                        },
                        enabled = !isRestoring
                    ) { Text("Restore & Replace All") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showLegacyRestoreConfirmDialog = false
                            clearPendingRestore()
                        }
                    ) { Text("Cancel") }
                }
            )
        }

        if (showBackupPasswordDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBackupPasswordDialog = false
                    clearPendingRestore()
                },
                title = { Text(if (pendingRestoreFile != null) "Restore Backup" else "Backup Password") },
                text = {
                    Column {
                        Text(
                            if (pendingRestoreFile != null) {
                                "Enter the password used to create this backup."
                            } else {
                                "Enter your Master Password to encrypt this backup. It can be restored on any device with this password."
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // B2-CRYPTO-04 (phase-84): the offline-cost warning is never
                        // silent — a backup seeded to Downloads/WebDAV is only as
                        // strong as this password (no lockout protects an offline
                        // PBKDF2 crack of a leaked file).
                        if (pendingRestoreFile == null) {
                            Text(
                                BackupPasswordPolicy.OFFLINE_BACKUP_NOTICE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = backupPasswordInput,
                            onValueChange = {
                                backupPasswordInput = it
                                backupPasswordError = null
                            },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            isError = backupPasswordError != null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        backupPasswordError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val pending = pendingRestoreFile
                            if (pending != null) {
                                if (backupPasswordInput.isBlank()) {
                                    backupPasswordError = "Password required for this backup"
                                } else {
                                    // H1 (phase-09) + R2-B1D-04 (phase-138): the wrong
                                    // password is rejected against the staged FILE
                                    // before any closeDatabase — the archive never
                                    // re-enters heap, and the user simply corrects the
                                    // password.
                                    showBackupPasswordDialog = false
                                    // R2-B1D-04 review: performRestore owns the
                                    // staged-file delete — do NOT clearPendingRestore()
                                    // here or the file vanishes before the import.
                                    pendingRestoreFile = null
                                    performRestore(context, pending, backupPasswordInput)
                                }
                            } else {
                                // B2-CRYPTO-04 (phase-84): the backup password must
                                // clear the SAME strength bar as the master password
                                // (was a bare `>= 6` length check); its verdict
                                // message is surfaced as the dialog error.
                                val strength = BackupPasswordPolicy.evaluate(backupPasswordInput)
                                if (!strength.accepted) {
                                    backupPasswordError = strength.message
                                } else if (!isValidating) {
                                    isValidating = true
                                    // B2-UI-6 (phase-96): VM-scoped so the password
                                    // backup export survives composition teardown.
                                    vaultScope.launch {
                                        try {
                                            if (!viewModel.isMasterPasswordValid(backupPasswordInput)) {
                                                // B1-AUTH-07 (phase-92): the check shares the LockScreen's
                                                // lockout counters — an active lockout (or one just tripped
                                                // by this attempt) is surfaced honestly, never as a generic
                                                // "incorrect password", and a tripped 5th attempt locks the
                                                // vault before any export byte moves.
                                                backupPasswordError = if (viewModel.lockoutActive()) {
                                                    "Too many failed attempts. Try again after the lockout countdown."
                                                } else {
                                                    "Incorrect master password"
                                                }
                                                return@launch
                                            }
                                            // R2-B1D-05/03 (phase-137): the checkpoint
                                            // + HMAC re-stamp + verified DB snapshot now
                                            // live INSIDE exportBackup (single producer).
                                            val cacheFile = ImportExportService.exportBackup(
                                                context,
                                                viewModel.repository.encryptionKey,
                                                backupPasswordInput,
                                                repository = viewModel.repository
                                            )
                                            showBackupPasswordDialog = false
                                            exporter.export(
                                                ExportDestinationPolicy.ExportKind.ENCRYPTED_BACKUP,
                                                cacheFile
                                            ) { result ->
                                                when (result) {
                                                    SaFExportResult.SAVED -> viewModel.showSnackbar("Backup created and saved.")
                                                    SaFExportResult.CANCELLED -> viewModel.showSnackbar("Backup cancelled")
                                                    SaFExportResult.FAILED -> viewModel.showSnackbar("Backup could not be written to the chosen destination")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            backupPasswordError = "Backup failed: ${e.message}"
                                        } finally {
                                            isValidating = false
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isRestoring
                    ) {
                        Text(if (pendingRestoreFile != null) "Restore" else "Backup")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showBackupPasswordDialog = false
                        clearPendingRestore()
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRestartConfirmDialog) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(restartDialogTitle) },
                text = { Text(restartDialogMessage) },
                confirmButton = {
                    TextButton(onClick = { kotlin.system.exitProcess(0) }) {
                        Text("Restart now")
                    }
                }
            )
        }

        if (showUpdateDialog) {
            AppUpdateDialog(
                onDismiss = { showUpdateDialog = false },
                onSnackbar = { text, isLong -> viewModel.showSnackbar(text, isLong) }
            )
        }

        if (showWebDavDialog) {
            com.authorss81.noteflow.ui.components.WebDavSyncDialog(
                viewModel = viewModel,
                onDismiss = { showWebDavDialog = false },
                onRestoreSuccess = {
                    // A WebDAV restore closes and swaps the live DB — the app
                    // must restart to reopen the vault (same as local restore).
                    showWebDavDialog = false
                    showRestartConfirmDialog = true
                }
            )
        }

        if (showLocalSendDialog) {
            com.authorss81.noteflow.ui.components.LocalSendSendDialog(
                viewModel = viewModel,
                pages = allActivePages,
                onDismiss = { showLocalSendDialog = false }
            )
        }

        if (showWebCaptureDialog) {
            WebCaptureDialog(
                viewModel = viewModel,
                onCaptured = { capturedMarkdown ->
                    // A captured page is stored through the SAME encrypted
                    // createNoteFromSharedContent path as a share-sheet clip.
                    viewModel.createNoteFromSharedContent(capturedMarkdown, emptyList()) { }
                    viewModel.showSnackbar("Web page captured as a note", isLong = true)
                },
                onDismiss = { showWebCaptureDialog = false }
            )
        }

        if (showMultiPageImportDialog && pendingImportUris.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = {
                    showMultiPageImportDialog = false
                    pendingImportUris = emptyList()
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = {
                            showMultiPageImportDialog = false
                            pendingImportUris = emptyList()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                        Text("Import Multi-Page Document")
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "Page Format & Orientation:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedImportOrientation == "AUTO",
                                onClick = { selectedImportOrientation = "AUTO" },
                                label = { Text("Auto (Detect)") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                            FilterChip(
                                selected = selectedImportOrientation == "PORTRAIT",
                                onClick = { selectedImportOrientation = "PORTRAIT" },
                                label = { Text("Portrait") },
                                leadingIcon = { Icon(Icons.Outlined.CropPortrait, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                            FilterChip(
                                selected = selectedImportOrientation == "LANDSCAPE",
                                onClick = { selectedImportOrientation = "LANDSCAPE" },
                                label = { Text("Landscape") },
                                leadingIcon = { Icon(Icons.Outlined.CropLandscape, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Select document layout structure:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            onClick = {
                                showMultiPageImportDialog = false
                                val uris = pendingImportUris
                                pendingImportUris = emptyList()
                                processImportedUris(uris, importAsSeparatePages = false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Layers, contentDescription = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Infinite Canvas (Continuous)", style = MaterialTheme.typography.titleMedium)
                                    Text("All pages rendered vertically on a zoomable, scrollable infinite canvas.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            onClick = {
                                showMultiPageImportDialog = false
                                val uris = pendingImportUris
                                pendingImportUris = emptyList()
                                processImportedUris(uris, importAsSeparatePages = true)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.FindInPage, contentDescription = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Separate Note Pages", style = MaterialTheme.typography.titleMedium)
                                    Text("Import each document page as an individual note entry.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = {
                        showMultiPageImportDialog = false
                        pendingImportUris = emptyList()
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showTutorial) {
            // Phase 125: the enhanced tutorial is one interactive, curriculum-driven
            // run. `initialResumeIndex` is snapshotted at each OPEN so the persisted
            // "Skip → resume later" point is honoured without re-initializing the
            // session on every persisted-index write (which would clear the
            // progress-check state). Completion / "don't show again" are persisted
            // so the gate truly never re-opens.
            val initialResumeIndex = remember(showTutorial) { viewModel.tutorialResumeIndex.value }
            InteractiveTutorial(
                initialIndex = initialResumeIndex,
                onProgress = { idx -> viewModel.updateTutorialResumeIndex(idx) },
                onComplete = {
                    showTutorial = false
                    viewModel.markFirstRunComplete()
                    viewModel.triggerConfetti()
                },
                onSkip = {
                    // Resume later: leave the last reached slide persisted.
                    showTutorial = false
                },
                onDontShowAgain = {
                    showTutorial = false
                    viewModel.markFirstRunComplete()
                }
            )
        }

        promptDialogType?.let { type ->
            val title = when (type) {
                "add_nb" -> "New Notebook"
                "add_sec" -> "New Section"
                "rename_nb" -> "Rename Notebook"
                "rename_sec" -> "Rename Section"
                "rename_page" -> "Rename Page"
                else -> ""
            }
            PromptNameDialog(
                title = title,
                initialValue = initialDialogText,
                onDismiss = { promptDialogType = null },
                onSubmit = { name ->
                    when (type) {
                        "add_nb" -> viewModel.addNotebook(name)
                        "add_sec" -> viewModel.addSection(name)
                        "rename_nb" -> targetEntityId?.let { viewModel.renameNotebook(it, name) }
                        "rename_sec" -> targetEntityId?.let { viewModel.renameSection(it, name) }
                        "rename_page" -> targetEntityId?.let { viewModel.renamePage(it, name) }
                    }
                    promptDialogType = null
                }
            )
        }

        deleteConfirmType?.let { type ->
            val title = when (type) {
                "nb" -> "Delete Notebook?"
                "sec" -> "Delete Section?"
                "empty_trash" -> "Empty Trash?"
                "page_perm" -> "Permanently Delete Note?"
                else -> "Delete Item?"
            }
            val msg = when (type) {
                "nb" -> if (deleteWarningMessage.isNotBlank()) deleteWarningMessage else "All sections and pages inside will be permanently deleted."
                "sec" -> if (deleteWarningMessage.isNotBlank()) deleteWarningMessage else "All pages inside will be permanently deleted."
                "empty_trash" -> "All trashed notes will be permanently destroyed."
                "page_perm" -> "This action cannot be undone. This note will be permanently destroyed."
                else -> ""
            }
            ConfirmDeleteDialog(
                title = title,
                message = msg,
                onDismiss = { deleteConfirmType = null },
                onConfirm = {
                    when (type) {
                        "nb" -> targetEntityId?.let { viewModel.deleteNotebook(it) }
                        "sec" -> targetEntityId?.let { viewModel.deleteSection(it) }
                        "empty_trash" -> viewModel.emptyTrash()
                        "page_perm" -> targetEntityId?.let { viewModel.deletePagePermanently(it) }
                    }
                    deleteConfirmType = null
                }
            )
        }

        if (showTagManagerDialog) {
            TagManagerDialog(
                viewModel = viewModel,
                onDismiss = { showTagManagerDialog = false }
            )
        }

        tagEditorTargetNotebook?.let { notebook ->
            TagEditorDialog(
                itemTitle = notebook.name,
                currentTagsString = notebook.tags,
                onDismiss = { tagEditorTargetNotebook = null },
                onSaveTags = { newTags ->
                    viewModel.updateNotebookTags(notebook.id, newTags)
                }
            )
        }

        tagEditorTargetPage?.let { page ->
            TagEditorDialog(
                itemTitle = page.title,
                currentTagsString = page.tags,
                onDismiss = { tagEditorTargetPage = null },
                onSaveTags = { newTags ->
                    viewModel.updatePageTags(page.id, newTags)
                }
            )
        }

        if (isInitializingLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope_isWide(): Boolean {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return configuration.screenWidthDp >= 600
}

@Composable
private fun NotebookPanel(
    notebooks: List<NotebookEntity>,
    selectedNotebook: NotebookEntity?,
    onSelectNotebook: (NotebookEntity) -> Unit,
    onAddNotebook: () -> Unit,
    onRenameNotebook: (NotebookEntity) -> Unit,
    onDeleteNotebook: (NotebookEntity) -> Unit,
    onEditTagsNotebook: (NotebookEntity) -> Unit = {},
    onExportVaultNotebook: (NotebookEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    val filteredNotebooks = remember(notebooks, searchQuery) {
        if (searchQuery.isBlank()) notebooks
        else notebooks.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Notebooks", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    showSearch = !showSearch
                    if (!showSearch) searchQuery = ""
                }) {
                    Icon(
                        imageVector = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = "Search Notebooks"
                    )
                }
                IconButton(onClick = onAddNotebook) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add Notebook")
                }
            }
        }
        if (showSearch) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search notebooks...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filteredNotebooks, key = { it.id }) { nb ->
                val selected = selectedNotebook?.id == nb.id
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(nb.name)
                            if (nb.tags.isNotBlank()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    nb.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    selected = selected,
                    onClick = { onSelectNotebook(nb) },
                    icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                    badge = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More Options")
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                scrollState = overflowMenuScrollState(),
                                modifier = overflowMenuScrollModifier()
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { expanded = false; onRenameNotebook(nb) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit Tags") },
                                    onClick = { expanded = false; onEditTagsNotebook(nb) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Vault (ZIP)") },
                                    onClick = { expanded = false; onExportVaultNotebook(nb) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { expanded = false; onDeleteNotebook(nb) }
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionPanel(
    sections: List<SectionEntity>,
    selectedSection: SectionEntity?,
    onSelectSection: (SectionEntity) -> Unit,
    onAddSection: () -> Unit,
    onRenameSection: (SectionEntity) -> Unit,
    onDeleteSection: (SectionEntity) -> Unit,
    onExportVaultSection: (SectionEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    val filteredSections = remember(sections, searchQuery) {
        if (searchQuery.isBlank()) sections
        else sections.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sections", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    showSearch = !showSearch
                    if (!showSearch) searchQuery = ""
                }) {
                    Icon(
                        imageVector = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = "Search Sections"
                    )
                }
                IconButton(onClick = onAddSection) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add Section")
                }
            }
        }
        if (showSearch) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search sections...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filteredSections, key = { it.id }) { sec ->
                val selected = selectedSection?.id == sec.id
                NavigationDrawerItem(
                    label = { Text(sec.name) },
                    selected = selected,
                    onClick = { onSelectSection(sec) },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    badge = {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More Options")
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                scrollState = overflowMenuScrollState(),
                                modifier = overflowMenuScrollModifier()
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { expanded = false; onRenameSection(sec) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Vault (ZIP)") },
                                    onClick = { expanded = false; onExportVaultSection(sec) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { expanded = false; onDeleteSection(sec) }
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookAndSectionSelectorBar(
    notebooks: List<NotebookEntity>,
    selectedNotebook: NotebookEntity?,
    sections: List<SectionEntity>,
    selectedSection: SectionEntity?,
    onSelectNotebook: (NotebookEntity) -> Unit,
    onSelectSection: (SectionEntity) -> Unit,
    onAddNotebook: () -> Unit,
    onAddSection: () -> Unit,
    onRenameNotebook: (NotebookEntity) -> Unit = {},
    onDeleteNotebook: (NotebookEntity) -> Unit = {},
    onRenameSection: (SectionEntity) -> Unit = {},
    onDeleteSection: (SectionEntity) -> Unit = {},
    onEditTagsNotebook: (NotebookEntity) -> Unit = {}
) {
    var showNotebookSheet by remember { mutableStateOf(false) }
    var showSectionSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Notebook Selector Chip
        AssistChip(
            onClick = { showNotebookSheet = true },
            label = {
                Text(
                    text = selectedNotebook?.name ?: "Select Notebook",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.weight(1f)
        )

        // Section / Quick Notes Selector Chip
        AssistChip(
            onClick = { showSectionSheet = true },
            label = {
                Text(
                    text = selectedSection?.name ?: "Quick Notes",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.weight(1f)
        )
    }

    if (showNotebookSheet) {
        var notebookQuery by remember { mutableStateOf("") }
        val filteredNotebooks = remember(notebooks, notebookQuery) {
            if (notebookQuery.isBlank()) notebooks
            else notebooks.filter { it.name.contains(notebookQuery, ignoreCase = true) }
        }

        ModalBottomSheet(
            onDismissRequest = { showNotebookSheet = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Notebook",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = {
                        showNotebookSheet = false
                        onAddNotebook()
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add Notebook")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = notebookQuery,
                    onValueChange = { notebookQuery = it },
                    placeholder = { Text("Search notebooks...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (notebookQuery.isNotEmpty()) {
                            IconButton(onClick = { notebookQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (filteredNotebooks.isEmpty()) {
                    TactileEmptyState(
                        decision = EmptyStateResolver.decide(
                            EmptyStateKind.NOTEBOOK_PICKER,
                            hasQuery = notebookQuery.isNotEmpty(),
                            query = notebookQuery
                        )
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                    ) {
                        items(filteredNotebooks, key = { it.id }) { nb ->
                            val selected = selectedNotebook?.id == nb.id
                            var menuExpanded by remember { mutableStateOf(false) }

                            Surface(
                                onClick = {
                                    onSelectNotebook(nb)
                                    showNotebookSheet = false
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.MenuBook,
                                            contentDescription = null,
                                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = nb.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selected) {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(
                                                    Icons.Outlined.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false },
                                                scrollState = overflowMenuScrollState(),
                                                modifier = overflowMenuScrollModifier()
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showNotebookSheet = false
                                                        onRenameNotebook(nb)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Edit Tags") },
                                                    leadingIcon = { Icon(Icons.Outlined.LocalOffer, contentDescription = null) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showNotebookSheet = false
                                                        onEditTagsNotebook(nb)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showNotebookSheet = false
                                                        onDeleteNotebook(nb)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showSectionSheet) {
        var sectionQuery by remember { mutableStateOf("") }
        val filteredSections = remember(sections, sectionQuery) {
            if (sectionQuery.isBlank()) sections
            else sections.filter { it.name.contains(sectionQuery, ignoreCase = true) }
        }

        ModalBottomSheet(
            onDismissRequest = { showSectionSheet = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Notes & Sections",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = {
                        showSectionSheet = false
                        onAddSection()
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add Section")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = sectionQuery,
                    onValueChange = { sectionQuery = it },
                    placeholder = { Text("Search quick notes & sections...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (sectionQuery.isNotEmpty()) {
                            IconButton(onClick = { sectionQuery = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (filteredSections.isEmpty()) {
                    TactileEmptyState(
                        decision = EmptyStateResolver.decide(
                            EmptyStateKind.SECTION_PICKER,
                            hasQuery = sectionQuery.isNotEmpty(),
                            query = sectionQuery
                        )
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                    ) {
                        items(filteredSections, key = { it.id }) { sec ->
                            val selected = selectedSection?.id == sec.id
                            var menuExpanded by remember { mutableStateOf(false) }

                            Surface(
                                onClick = {
                                    onSelectSection(sec)
                                    showSectionSheet = false
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = sec.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selected) {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(
                                                    Icons.Outlined.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false },
                                                scrollState = overflowMenuScrollState(),
                                                modifier = overflowMenuScrollModifier()
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Rename") },
                                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showSectionSheet = false
                                                        onRenameSection(sec)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showSectionSheet = false
                                                        onDeleteSection(sec)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NotePageCard(
    page: NotePageEntity,
    isTrash: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanent: () -> Unit,
    onEditTags: () -> Unit = {}
) {
    // 36.0: record the tapped card's window bounds so the editor can morph from
    // it (shared-element reveal). Fall back to a plain fade when reduce-motion.
    var cardWindowBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Card(
        onClick = {
            cardWindowBounds?.let { com.authorss81.noteflow.ui.components.SharedElementState.rememberCard(it) }
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                cardWindowBounds = coords.boundsInWindow()
            },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (page.sourceFileType == "pdf") Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (page.sourceFileType != null) "Type: ${page.sourceFileType}" else "Canvas Note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (page.tags.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            page.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isTrash) {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (page.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (page.pinned) "Unpin" else "Pin",
                            tint = if (page.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More Options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        scrollState = overflowMenuScrollState(),
                        modifier = overflowMenuScrollModifier()
                    ) {
                        if (!isTrash) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menuExpanded = false; onRename() }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit Tags") },
                                onClick = { menuExpanded = false; onEditTags() }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Trash") },
                                onClick = { menuExpanded = false; onTrash() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Restore") },
                                onClick = { menuExpanded = false; onRestore() }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Permanently") },
                                onClick = { menuExpanded = false; onDeletePermanent() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeMenu(viewModel: NoteflowViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val currentMode by viewModel.themeMode.collectAsState()

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Palette, contentDescription = "Theme")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = overflowMenuScrollState(),
            modifier = overflowMenuScrollModifier()
        ) {
            for (mode in AppThemeMode.values()) {
                DropdownMenuItem(
                    text = { Text(mode.name.lowercase().capitalize()) },
                    trailingIcon = if (currentMode == mode) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        viewModel.setThemeMode(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MaintenanceMenu(
    useSidebarLayout: Boolean,
    onToggleSidebarLayout: (Boolean) -> Unit,
    showStrokePreviews: Boolean = false,
    onToggleStrokePreviews: (Boolean) -> Unit = {},
    databaseIntegrityCheckEnabled: Boolean = true,
    onToggleDatabaseIntegrityCheck: (Boolean) -> Unit = {},
    onOpenTagManager: () -> Unit,
    onOpenTutorial: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenPlugins: () -> Unit = {},
    onOpenPluginStore: () -> Unit = {},
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportObsidianVault: () -> Unit = {},
    onExportHtmlVault: () -> Unit = {},
    onOpenLocalSend: () -> Unit = {},
    onOpenWebCapture: () -> Unit = {},
    onOpenWebDavSync: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Settings & More")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = overflowMenuScrollState(),
            modifier = overflowMenuScrollModifier()
        ) {
            DropdownMenuItem(
                text = { Text("Unified Sidebar Layout") },
                leadingIcon = { Icon(Icons.Outlined.ViewSidebar, contentDescription = null) },
                trailingIcon = {
                    Switch(
                        checked = useSidebarLayout,
                        onCheckedChange = { onToggleSidebarLayout(it) }
                    )
                },
                onClick = { onToggleSidebarLayout(!useSidebarLayout) }
            )
            DropdownMenuItem(
                text = { Text("Show Pen Stroke Previews") },
                leadingIcon = { Icon(Icons.Outlined.Brush, contentDescription = null) },
                trailingIcon = {
                    Switch(
                        checked = showStrokePreviews,
                        onCheckedChange = { onToggleStrokePreviews(it) }
                    )
                },
                onClick = { onToggleStrokePreviews(!showStrokePreviews) }
            )
            DropdownMenuItem(
                text = { Text("Database Integrity Check") },
                leadingIcon = { Icon(Icons.Outlined.Security, contentDescription = null) },
                trailingIcon = {
                    Switch(
                        checked = databaseIntegrityCheckEnabled,
                        onCheckedChange = { onToggleDatabaseIntegrityCheck(it) }
                    )
                },
                onClick = { onToggleDatabaseIntegrityCheck(!databaseIntegrityCheckEnabled) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            DropdownMenuItem(
                text = { Text("Tag Manager") },
                leadingIcon = { Icon(Icons.Outlined.LocalOffer, contentDescription = null) },
                onClick = { expanded = false; onOpenTagManager() }
            )
            DropdownMenuItem(
                text = { Text("Interactive Tutorial") },
                leadingIcon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null) },
                onClick = { expanded = false; onOpenTutorial() }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            DropdownMenuItem(
                text = { Text("Security Settings") },
                leadingIcon = { Icon(Icons.Outlined.Security, contentDescription = null) },
                onClick = { expanded = false; onOpenSecurity() }
            )
            DropdownMenuItem(
                text = { Text("Plugins") },
                leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                onClick = { expanded = false; onOpenPlugins() }
            )
            DropdownMenuItem(
                text = { Text("Plugin Store") },
                leadingIcon = { Icon(Icons.Outlined.Storefront, contentDescription = null) },
                onClick = { expanded = false; onOpenPluginStore() }
            )
            DropdownMenuItem(
                text = { Text("App Version & Updates") },
                leadingIcon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                onClick = { expanded = false; onOpenUpdate() }
            )
            DropdownMenuItem(
                text = { Text("Export Vault (Obsidian ZIP)") },
                leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                onClick = { expanded = false; onExportObsidianVault() }
            )
            DropdownMenuItem(
                text = { Text("Export Vault (HTML Website)") },
                leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                onClick = { expanded = false; onExportHtmlVault() }
            )
            DropdownMenuItem(
                text = { Text("Send to Nearby Device (LocalSend)") },
                leadingIcon = { Icon(Icons.Outlined.NearMe, contentDescription = null) },
                onClick = { expanded = false; onOpenLocalSend() }
            )
            DropdownMenuItem(
                text = { Text("Capture Web Page as Note") },
                leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                onClick = { expanded = false; onOpenWebCapture() }
            )
            DropdownMenuItem(
                text = { Text("WebDAV / Nextcloud E2EE Sync") },
                leadingIcon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                onClick = { expanded = false; onOpenWebDavSync() }
            )
            DropdownMenuItem(
                text = { Text("Backup to File") },
                leadingIcon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
                onClick = { expanded = false; onBackup() }
            )
            DropdownMenuItem(
                text = { Text("Restore from File") },
                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                onClick = { expanded = false; onRestore() }
            )
        }
    }
}

/**
 * Phase 15 (Web Capture): fetch a user-supplied http(s) URL and store the
 * readable content as a new encrypted note. Runs the fetch + extraction on a
 * background dispatcher via the ViewModel; errors surface the plugin's reason.
 */
@Composable
private fun WebCaptureDialog(
    viewModel: NoteflowViewModel,
    onCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultTitle by remember { mutableStateOf<String?>(null) }
    var resultMarkdown by remember { mutableStateOf("") }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        resultTitle = null
        scope.launch {
            when (val result = viewModel.captureWebPage(urlInput.trim())) {
                is com.authorss81.noteflow.plugins.PluginResult.Success -> {
                    when (val outcome = result.value) {
                        is com.authorss81.noteflow.plugins.WebCaptureOutcome.Success -> {
                            resultTitle = outcome.result.title
                            resultMarkdown = outcome.result.markdown
                        }
                        is com.authorss81.noteflow.plugins.WebCaptureOutcome.Error ->
                            error = outcome.message
                    }
                }
                is com.authorss81.noteflow.plugins.PluginResult.Failure ->
                    error = result.message
                is com.authorss81.noteflow.plugins.PluginResult.Unavailable ->
                    error = result.message
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture Web Page as Note") },
        text = {
            Column {
                if (resultTitle != null) {
                    Text("Captured: ${resultTitle}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Preview (first 300 chars):\n${resultMarkdown.take(300)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("https://…") },
                    singleLine = true,
                    enabled = !busy && resultTitle == null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (resultTitle != null) {
                TextButton(onClick = {
                    onCaptured(resultMarkdown)
                    onDismiss()
                }) { Text("Save as Note") }
            } else {
                TextButton(
                    enabled = !busy && urlInput.isNotBlank(),
                    onClick = { submit() }
                ) { Text("Capture") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    onDismiss()
                }
            ) { Text(if (resultTitle != null) "Cancel" else "Close") }
        }
    )
}
