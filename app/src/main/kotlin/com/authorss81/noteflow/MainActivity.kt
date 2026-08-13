package com.authorss81.noteflow

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.screens.EditorScreen
import com.authorss81.noteflow.ui.screens.HomeScreen
import com.authorss81.noteflow.ui.screens.KnowledgeGraphScreen
import com.authorss81.noteflow.ui.screens.LockScreen
import com.authorss81.noteflow.ui.screens.MarkdownPreviewScreen
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.lifecycleScope
import com.authorss81.noteflow.utils.AppStartupLogger
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight

enum class WindowSizeCategory {
    COMPACT, MEDIUM, EXPANDED
}

/** 22.5: content captured from the Android share sheet, pre-copied into app storage. */
private data class SharedContent(val text: String?, val imagePaths: List<String>)

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
class MainActivity : FragmentActivity() {

    private val viewModel: NoteflowViewModel by viewModels()

    // 22.1: last user interaction (touch) timestamp for the inactivity auto-lock.
    private var lastActivityAtMs = System.currentTimeMillis()

    // 22.5: pending share-sheet capture; applied once the vault is unlocked.
    private var pendingShare by mutableStateOf<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.authorss81.noteflow.services.PrivacyCrashReporter.initialize(applicationContext)
        AppStartupLogger.init(applicationContext)
        AppStartupLogger.logEvent(this, "MainActivity.onCreate started")
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        readShareIntent(intent)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            AppStartupLogger.logEvent(this@MainActivity, "Lifecycle Event: $event")
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    com.authorss81.noteflow.services.ClipboardGuard.scrubIfOwnCopy(this)
                }
                Lifecycle.Event.ON_STOP -> {
                    lastActivityAtMs = System.currentTimeMillis()
                    viewModel.lock()
                }
                else -> {}
            }
        })

        setContent {
            com.authorss81.noteflow.utils.JankStatsHelper.MonitorJank("MainActivity")
            val themeMode by viewModel.themeMode.collectAsState()
            val authenticated by viewModel.authenticated.collectAsState()
            val hasMasterPassword by viewModel.hasMasterPassword.collectAsState()
            val databaseTampered by viewModel.databaseTampered.collectAsState()
            val restoreBlocked by viewModel.restoreBlocked.collectAsState()
            val autoLockTimeoutSeconds by viewModel.autoLockTimeoutSeconds.collectAsState()

            // 22.9: status/nav-bar icon polarity must track the app's actual theme,
            // not the system default — dark-system + SEPIA would be light-on-light.
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isAppDark = remember(themeMode, systemDark) {
                com.authorss81.noteflow.theme.isAppDarkTheme(themeMode, systemDark)
            }
            LaunchedEffect(isAppDark) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        lightScrim = android.graphics.Color.TRANSPARENT,
                        darkScrim = android.graphics.Color.TRANSPARENT,
                        detectDarkMode = { isAppDark }
                    ),
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        lightScrim = android.graphics.Color.WHITE,
                        darkScrim = android.graphics.Color.BLACK,
                        detectDarkMode = { isAppDark }
                    )
                )
            }

            // 22.9: root SnackbarHost — visibility-critical feedback is Snackbars,
            // not transient Toasts (scratchable, and visible to TalkBack).
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.snackbarMessages.collect { message ->
                    snackbarHostState.showSnackbar(
                        message = message.text,
                        duration = if (message.isLong) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                }
            }

            val pages by viewModel.pages.collectAsState()
            var activePageId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            var showGraphView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

            val activePage = pages.find { it.id == activePageId }
            val setActivePage: (NotePageEntity?) -> Unit = { page ->
                activePageId = page?.id
                viewModel.settings.activePageId = page?.id
            }

            LaunchedEffect(authenticated, pages) {
                if (authenticated && activePageId == null) {
                    val savedPageId = viewModel.settings.activePageId
                    if (!savedPageId.isNullOrEmpty() && pages.any { it.id == savedPageId }) {
                        activePageId = savedPageId
                    }
                }
            }

            // 22.5: apply a share-sheet capture once the vault is unlocked.
            LaunchedEffect(authenticated, pendingShare) {
                val share = pendingShare ?: return@LaunchedEffect
                if (!authenticated) return@LaunchedEffect
                pendingShare = null
                viewModel.createNoteFromSharedContent(share.text, share.imagePaths) { page ->
                    setActivePage(page)
                }
            }

            NoteflowTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // 22.1: inactivity auto-lock — first touch after the idle
                        // window (or any touch when the timeout changed) locks.
                        .pointerInput(autoLockTimeoutSeconds) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                    val timeoutMs = autoLockTimeoutSeconds * 1000L
                                    if (timeoutMs > 0L && System.currentTimeMillis() - lastActivityAtMs > timeoutMs) {
                                        viewModel.lock()
                                    }
                                    lastActivityAtMs = System.currentTimeMillis()
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(Modifier.fillMaxSize()) {
                        if (databaseTampered) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                tonalElevation = 6.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            androidx.compose.material.icons.Icons.Outlined.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Database Integrity Warning",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "The vault file may have been modified outside the app or integrity check failed. Your data might be compromised. Consider restoring from a backup.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        var dontShowAgain by remember { mutableStateOf(false) }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { dontShowAgain = !dontShowAgain }
                                        ) {
                                            androidx.compose.material3.Checkbox(
                                                checked = dontShowAgain,
                                                onCheckedChange = { dontShowAgain = it }
                                            )
                                            Text(
                                                text = "Don't show again",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                viewModel.dismissDatabaseIntegrityWarning(dontShowAgain)
                                            }
                                        ) {
                                            Text(
                                                text = "OK",
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (hasMasterPassword && !authenticated) {
                            LockScreen(viewModel = viewModel)
                        } else if (restoreBlocked) {
                            RestoreBlockedScreen(viewModel = viewModel)
                        } else {
                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            val widthDp = configuration.screenWidthDp
                            val sizeClass = when {
                                widthDp < 600 -> WindowSizeCategory.COMPACT
                                widthDp < 840 -> WindowSizeCategory.MEDIUM
                                else -> WindowSizeCategory.EXPANDED
                            }

                            if (sizeClass == WindowSizeCategory.COMPACT) {
                                if (showGraphView) {
                                    KnowledgeGraphScreen(
                                        viewModel = viewModel,
                                        onOpenPage = { pageToOpen ->
                                            showGraphView = false
                                            setActivePage(pageToOpen)
                                        },
                                        onBack = { showGraphView = false }
                                    )
                                } else {
                                    val page = activePage
                                    if (page == null) {
                                        HomeScreen(
                                            viewModel = viewModel,
                                            onOpenPage = { pageToOpen -> setActivePage(pageToOpen) },
                                            onOpenGraph = { showGraphView = true }
                                        )
                                    } else if (page.title.endsWith(".md") || page.title.endsWith(".txt")) {
                                        val contentText by produceState(initialValue = "", page.sourceFilePath) {
                                            value = page.sourceFilePath?.let { path ->
                                                withContext(Dispatchers.IO) {
                                                    val f = File(path)
                                                    if (f.exists()) f.readText() else ""
                                                }
                                            } ?: ""
                                        }
                                        val contentSaveScope = rememberCoroutineScope()
                                        MarkdownPreviewScreen(
                                            page = page,
                                            initialContent = contentText,
                                            viewModel = viewModel,
                                            onBack = { setActivePage(null) },
                                            onOpenWikiLink = { targetTitle ->
                                                viewModel.openPageByTitle(targetTitle, this@MainActivity) { openedPage ->
                                                    setActivePage(openedPage)
                                                }
                                            },
                                            onOpenPage = { targetPage -> setActivePage(targetPage) },
                                            onSaveContent = { newText ->
                                                page.sourceFilePath?.let { path ->
                                                    contentSaveScope.launch {
                                                        withContext(Dispatchers.IO) {
                                                            File(path).writeText(newText)
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    } else {
                                        EditorScreen(
                                            page = page,
                                            viewModel = viewModel,
                                            onBack = { setActivePage(null) },
                                            onOpenPage = { targetPage -> setActivePage(targetPage) }
                                        )
                                    }
                                }
                            } else {
                                // MEDIUM or EXPANDED Layout
                                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
                                    if (sizeClass == WindowSizeCategory.EXPANDED) {
                                        NavigationRail(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                        ) {
                                            NavigationRailItem(
                                                selected = !showGraphView,
                                                onClick = { showGraphView = false },
                                                icon = { Icon(Icons.Outlined.Notes, contentDescription = "Notes") },
                                                label = { Text("Notes") }
                                            )
                                            NavigationRailItem(
                                                selected = showGraphView,
                                                onClick = { showGraphView = true },
                                                icon = { Icon(Icons.Outlined.Hub, contentDescription = "Knowledge Graph") },
                                                label = { Text("Graph") }
                                            )
                                        }
                                    }

                                    // Content Area
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        if (showGraphView && sizeClass == WindowSizeCategory.EXPANDED) {
                                            KnowledgeGraphScreen(
                                                viewModel = viewModel,
                                                onOpenPage = { pageToOpen ->
                                                    showGraphView = false
                                                    setActivePage(pageToOpen)
                                                },
                                                onBack = { showGraphView = false }
                                            )
                                        } else {
                                            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
                                                // Left Pane (List / HomeScreen)
                                                Box(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                                                    HomeScreen(
                                                        viewModel = viewModel,
                                                        onOpenPage = { pageToOpen -> setActivePage(pageToOpen) },
                                                        onOpenGraph = { showGraphView = true }
                                                    )
                                                }
                                                
                                                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                                // Right Pane (Editor / Preview / Empty State)
                                                Box(modifier = Modifier.weight(1.6f).fillMaxHeight()) {
                                                    val page = activePage
                                                    if (page != null) {
                                                        if (page.title.endsWith(".md") || page.title.endsWith(".txt")) {
                                                            val contentText by produceState(initialValue = "", page.sourceFilePath) {
                                                                value = page.sourceFilePath?.let { path ->
                                                                    withContext(Dispatchers.IO) {
                                                                        val f = File(path)
                                                                        if (f.exists()) f.readText() else ""
                                                                    }
                                                                } ?: ""
                                                            }
                                                            val contentSaveScope = rememberCoroutineScope()
                                                            MarkdownPreviewScreen(
                                                                page = page,
                                                                initialContent = contentText,
                                                                viewModel = viewModel,
                                                                onBack = { setActivePage(null) },
                                                                onOpenWikiLink = { targetTitle ->
                                                                    viewModel.openPageByTitle(targetTitle, this@MainActivity) { openedPage ->
                                                                        setActivePage(openedPage)
                                                                    }
                                                                },
                                                                onOpenPage = { targetPage -> setActivePage(targetPage) },
                                                                onSaveContent = { newText ->
                                                                    page.sourceFilePath?.let { path ->
                                                                        contentSaveScope.launch {
                                                                            withContext(Dispatchers.IO) {
                                                                                File(path).writeText(newText)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        } else {
                                                            EditorScreen(
                                                                page = page,
                                                                viewModel = viewModel,
                                                                onBack = { setActivePage(null) },
                                                                onOpenPage = { targetPage -> setActivePage(targetPage) }
                                                            )
                                                        }
                                                    } else {
                                                        // Elegant empty state
                                                        Box(
                                                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Column(
                                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                                modifier = Modifier.padding(24.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Outlined.EditNote,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(64.dp),
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                                )
                                                                Spacer(modifier = Modifier.height(16.dp))
                                                                Text(
                                                                    text = "No note selected",
                                                                    style = MaterialTheme.typography.titleMedium,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                Spacer(modifier = Modifier.height(4.dp))
                                                                Text(
                                                                    text = "Select a note from the left list or create a new one to begin editing.",
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                        }
                        }
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    // 22.5: ACTION_SEND / ACTION_SEND_MULTIPLE from the system share sheet.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShareIntent(intent)
    }

    private fun readShareIntent(intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uriStrings = when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri?.toString())
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.map { it.toString() }.orEmpty()
            }
            else -> emptyList()
        }
        if (text == null && uriStrings.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val paths = copySharedUris(uriStrings)
            pendingShare = SharedContent(text, paths)
        }
    }

    // 22.5: copy shared content URIs into app-private storage immediately so the
    // temporary read grant can never expire before the vault unlocks.
    private fun copySharedUris(uriStrings: List<String>): List<String> {
        val copied = mutableListOf<String>()
        val dir = File(filesDir, "shared").apply { mkdirs() }
        uriStrings.forEach { uriString ->
            try {
                val uri = Uri.parse(uriString)
                val mime = contentResolver.getType(uri)
                val ext = mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "file"
                val target = File(dir, "${System.currentTimeMillis()}-${copied.size}.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                    copied += target.absolutePath
                }
            } catch (e: Exception) {
                // unreadable share item — skip it
            }
        }
        return copied
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            com.authorss81.noteflow.utils.BitmapPool.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.authorss81.noteflow.utils.BitmapPool.clear()
    }
}

/**
 * 34.8: hard block after a restore whose HMAC baseline could not be verified.
 * The vault is unreachable until a fresh backup restores successfully.
 */
@Composable
private fun RestoreBlockedScreen(viewModel: NoteflowViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var backupPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            errorMessage = null
            viewModel.attemptRecoveryFromBackup(uri, backupPassword.ifBlank { null }) { msg ->
                errorMessage = msg
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Vault Blocked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "A restored database could not be verified. Your vault is locked until you restore from a backup that passes integrity and tamper checks.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = backupPassword,
            onValueChange = { backupPassword = it },
            label = { Text("Backup Password (if any)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { pickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) }) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose Backup & Restore")
        }
        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
