package com.authorss81.noteflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Info
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
import com.authorss81.noteflow.services.DatabaseIntegrityPolicy
import com.authorss81.noteflow.theme.NoteflowTheme
import com.authorss81.noteflow.ui.screens.EditorScreen
import com.authorss81.noteflow.ui.screens.HomeScreen
import com.authorss81.noteflow.ui.screens.KnowledgeGraphScreen
import com.authorss81.noteflow.ui.screens.LockScreen
import com.authorss81.noteflow.ui.screens.MarkdownPreviewScreen
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight

enum class WindowSizeCategory {
    COMPACT, MEDIUM, EXPANDED
}

/**
 * B1-PLAT-2 (phase-58): content captured from the Android share sheet.
 *
 * [imagePaths] are local files already copied into app storage; empty until the
 * bounded copy runs. [rawUris] are the source content URIs, staged ONLY after an
 * explicit in-app "Clip into InkFlow?" confirmation AND only when the vault is
 * unlocked — the copy is performed by the post-unlock apply effect, never while
 * locked.
 */
private data class SharedContent(
    val text: String?,
    val imagePaths: List<String>,
    val rawUris: List<String> = emptyList()
)

/** B1-PLAT-2 (phase-58): an incoming share held behind an explicit confirmation. */
private data class PendingShareConfirm(
    val clip: com.authorss81.noteflow.plugins.SharedClip,
    val uriStrings: List<String>
)

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
class MainActivity : FragmentActivity() {

    private val viewModel: NoteflowViewModel by viewModels()

    // 22.1: last user interaction (touch) timestamp for the inactivity auto-lock.
    private var lastActivityAtMs = System.currentTimeMillis()

    // 22.5: pending share-sheet capture; applied once the vault is unlocked.
    private var pendingShare by mutableStateOf<SharedContent?>(null)

    // B1-PLAT-2 (phase-58): an incoming share that passed cli classification but
    // is HELD behind an explicit "Clip into InkFlow?" confirmation. Nothing is
    // copied or staged until the user confirms.
    private var pendingShareConfirm by mutableStateOf<PendingShareConfirm?>(null)

    // B1-PLAT-4 (phase-60): lock the INSTANT the screen turns off. On a no-keyguard
    // / tablet device (a natural target for an ink-notes app) display-off may pause
    // the activity without stopping it, so ON_STOP alone is not enough — a paused
    // activity must not leave decrypted notes on screen for the next person. The
    // protected system broadcast is delivered to runtime-registered receivers on
    // every supported API level (26+), so no OS-floor fallback is needed.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && viewModel.authenticated.value) {
                viewModel.lock()
            }
        }
    }

    private var screenOffReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.authorss81.noteflow.services.PrivacyCrashReporter.initialize(applicationContext)
        AppStartupLogger.init(applicationContext)
        AppStartupLogger.logEvent(this, "MainActivity.onCreate started")
        enableEdgeToEdge()
        // Phase-130: FLAG_SECURE is applied ONLY in non-debug builds (AGENTS.md
        // hard rule). Debug / emulator streaming environments (cloud Android
        // emulators that mirror the display buffer) render the UI instead of a
        // pitch-black surface; release builds keep the screenshot / recording /
        // recents-thumbnail ban. The decision lives in
        // SecureWindowPolicy.shouldApplySecureFlag (pure JVM, unit-pinned).
        if (com.authorss81.noteflow.services.SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        readShareIntent(intent)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
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

        // B1-PLAT-4: runtime screen-off hook — lock immediately on display-off so
        // a no-keyguard device never leaves decrypted notes on screen on resume.
        // ACTION_SCREEN_OFF can only be sent by the system; below API 33 there is
        // no receiver flag to declare, on API 33+ the documented system-broadcast
        // pattern is the flagged registration.
        screenOffReceiverRegistered = true
        try {
            val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, screenOffFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenOffReceiver, screenOffFilter)
            }
        } catch (e: IllegalArgumentException) {
            screenOffReceiverRegistered = false
        }

        setContent {
            com.authorss81.noteflow.utils.JankStatsHelper.MonitorJank("MainActivity")
            val themeMode by viewModel.themeMode.collectAsState()
            val authenticated by viewModel.authenticated.collectAsState()
            val hasMasterPassword by viewModel.hasMasterPassword.collectAsState()
            val databaseTampered by viewModel.databaseTampered.collectAsState()
            val databaseIntegrityUnverified by viewModel.databaseIntegrityUnverified.collectAsState()
            val restoreBlocked by viewModel.restoreBlocked.collectAsState()
            val corruptionBlocked by viewModel.corruptionBlocked.collectAsState()
            val keystoreKeyLost by viewModel.keystoreKeyLost.collectAsState()
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
            var showCommandPalette by remember { mutableStateOf(false) }

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

            // B1-PLAT-4 (phase-60): the foreground inactivity auto-lock is a
            // CONTINUOUS poll while unlocked — not "lock on the next touch after
            // the idle window" (the phase-30 behavior left an unattended,
            // foregrounded vault readable until the user touched it again, which
            // on a no-keyguard device is exactly the attack). Restarted on every
            // unlock so the idle baseline is fresh; the touch handler below only
            // stamps lastActivityAtMs.
            LaunchedEffect(autoLockTimeoutSeconds, authenticated) {
                if (!authenticated) return@LaunchedEffect
                lastActivityAtMs = System.currentTimeMillis()
                while (viewModel.authenticated.value) {
                    delay(com.authorss81.noteflow.services.AutoLockPolicy.IDLE_CHECK_INTERVAL_MS)
                    if (com.authorss81.noteflow.services.AutoLockPolicy.shouldAutoLock(
                            nowMs = System.currentTimeMillis(),
                            lastActivityAtMs = lastActivityAtMs,
                            timeoutSeconds = autoLockTimeoutSeconds
                        )
                    ) {
                        viewModel.lock()
                        return@LaunchedEffect
                    }
                }
            }

            // 22.5 + B1-PLAT-2: apply a confirmed share-sheet capture once the
            // vault is unlocked. Bytes are copied ONLY here — never at intent
            // arrival and never while locked.
            LaunchedEffect(authenticated, pendingShare) {
                val share = pendingShare ?: return@LaunchedEffect
                if (!authenticated) return@LaunchedEffect
                pendingShare = null
                val paths = if (share.rawUris.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            copySharedUris(share.rawUris)
                        } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                            null
                        }
                    } ?: run {
                        viewModel.showSnackbar("Shared content is too large to clip.", isLong = true)
                        return@LaunchedEffect
                    }
                } else {
                    share.imagePaths
                }
                if (paths.isEmpty() && share.text.isNullOrBlank()) {
                    viewModel.showSnackbar("Nothing readable to clip.", isLong = true)
                    return@LaunchedEffect
                }
                viewModel.createNoteFromSharedContent(share.text, paths) { page ->
                    setActivePage(page)
                }
            }

            NoteflowTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // Phase 38: two-finger swipe down opens the global Command
                        // Palette (discoverable: keyboard icon on the Home bar).
                        .pointerInput(authenticated, showCommandPalette) {
                            if (authenticated) detectTwoFingerSwipeDown { showCommandPalette = true }
                        }
                        // 22.1 + B1-PLAT-4: every touch resets the foreground
                        // inactivity timer. The lock itself fires from the poller
                        // above — never gated on the next touch.
                        .pointerInput(autoLockTimeoutSeconds) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
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
                            IntegrityBannerCard(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                icon = Icons.Outlined.Security,
                                iconTint = MaterialTheme.colorScheme.error,
                                textColor = MaterialTheme.colorScheme.onErrorContainer,
                                okTextColor = MaterialTheme.colorScheme.error,
                                title = "Database Integrity Warning",
                                message = "The vault file may have been modified outside the app or integrity check failed. Your data might be compromised. Consider restoring from a backup.",
                                onDismiss = { dontShowAgain -> viewModel.dismissDatabaseIntegrityWarning(dontShowAgain) }
                            )
                        }
                        // B1-CRYPTO-06 (phase-91): DISTINCT, NON-ALARMING fail-closed
                        // notice for "cannot verify" (a missing/unreadable checksum
                        // baseline or an un-computable current HMAC). The vault is NOT
                        // locked and NOT proven compromised — but tamper detection
                        // could not run, so we never silently trust it. Restore-from-
                        // backup re-arms the baseline; per-session dismissal reuses
                        // the same gate as the tamper banner.
                        if (databaseIntegrityUnverified) {
                            IntegrityBannerCard(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                icon = Icons.Outlined.Info,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                okTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "Vault Integrity Could Not Be Verified",
                                message = DatabaseIntegrityPolicy.CANNOT_VERIFY_NOTICE,
                                onDismiss = { dontShowAgain -> viewModel.dismissDatabaseIntegrityWarning(dontShowAgain) }
                            )
                        }
                        if (hasMasterPassword && !authenticated) {
                            LockScreen(viewModel = viewModel)
                        } else if (corruptionBlocked) {
                            // H2 (phase-09): corrupt-DB recovery takes priority over the
                            // normal vault UI. The corrupted files were quarantined, not
                            // deleted; the user must restore from backup or explicitly
                            // start fresh before normal note access is shown.
                            CorruptionRecoveryScreen(viewModel = viewModel)
                        } else if (keystoreKeyLost) {
                            // B1-CRYPTO-05 (phase-64): the AndroidKeyStore key that
                            // wrapped the device DEK copy is lost while the blob is
                            // still stored. The vault DB is NOT corrupt — only the
                            // device wrapper is gone — so we never auto-quarantine and
                            // never silently re-key; the user restores from backup or
                            // explicitly starts fresh.
                            KeystoreKeyLostScreen(viewModel = viewModel)
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
                                            onOpenGraph = { showGraphView = true },
                                            onOpenCommandPalette = { showCommandPalette = true }
                                        )
                                    } else {
                                        com.authorss81.noteflow.ui.components.FluidPageReveal(pageKey = page.id) {
                                        if (page.title.endsWith(".md") || page.title.endsWith(".txt")) {
                                            val contentText by produceState(initialValue = "", page.id) {
                                                // B2-UI-5 (phase-74): the body read awaits any in-flight body
                                                // save for the page and re-reads the freshly-committed body from
                                                // the repository — never a possibly-stale flow snapshot — so
                                                // navigating the same page back-and-forth can never present
                                                // truncated/stale content that would be re-saved over newer data.
                                                value = withContext(Dispatchers.IO) {
                                                    viewModel.readMarkdownNoteBody(page.id, page.extractedText, page.sourceFilePath, page.sourceFileType)
                                                }
                                            }
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
                                                    // B1-DB-4 (phase-44): the body is written ONLY to the
                                                    // field-encrypted extractedText column — never to a plaintext
                                                    // .md/.txt file. This runs in the ViewModel scope so it survives
                                                    // the editor's composition teardown (the Phase-05 race the old
                                                    // NonCancellable file-write carried).
                                                    viewModel.saveMarkdownNoteBody(page, newText)
                                                    // Phase 15 (Language Detection): auto-tag
                                                    // lang:<iso> on save, honouring any override.
                                                    viewModel.autoTagLanguageOnSave(
                                                        page.id, page.title, page.tags, newText
                                                    )
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
                                                        onOpenGraph = { showGraphView = true },
                                                        onOpenCommandPalette = { showCommandPalette = true }
                                                    )
                                                }
                                                
                                                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                                // Right Pane (Editor / Preview / Empty State)
                                                Box(modifier = Modifier.weight(1.6f).fillMaxHeight()) {
                                                    val page = activePage
                                                    if (page != null) {
                                                        if (page.title.endsWith(".md") || page.title.endsWith(".txt")) {
                                                            val contentText by produceState(initialValue = "", page.id) {
                                                                // B2-UI-5 (phase-74): the body read awaits any in-flight body
                                                                // save for the page and re-reads the freshly-committed body
                                                                // from the repository — never a possibly-stale flow snapshot —
                                                                // so flipping the same page back-and-forth can never present
                                                                // truncated/stale content that would be re-saved over newer data.
                                                                value = withContext(Dispatchers.IO) {
                                                                    viewModel.readMarkdownNoteBody(page.id, page.extractedText, page.sourceFilePath, page.sourceFileType)
                                                                }
                                                            }
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
                                                                    // B1-DB-4 (phase-44): body written ONLY to the
                                                                    // field-encrypted extractedText column — never a
                                                                    // plaintext .md/.txt file. ViewModel-scoped so the
                                                                    // write survives composition teardown.
                                                                    viewModel.saveMarkdownNoteBody(page, newText)
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

                        // B1-PLAT-2 (phase-58): an incoming share is NEVER copied on
                        // arrival — it is held behind this explicit confirmation.
                        // Only a human tapping "Clip" stages it (and even then only
                        // after the vault is unlocked; see the apply LaunchedEffect).
                        pendingShareConfirm?.let { request ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { pendingShareConfirm = null },
                                title = { Text("Clip into InkFlow?") },
                                text = {
                                    Column {
                                        Text(com.authorss81.noteflow.plugins.clipshare.ClipShareConfirmNotice.summary(request.clip))
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            com.authorss81.noteflow.plugins.clipshare.ClipShareConfirmNotice.body(request.clip),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { confirmPendingShare() }) {
                                        Text("Clip")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingShareConfirm = null }) {
                                        Text("Not now")
                                    }
                                }
                            )
                        }
                    }

                    // Phase 38: global Command Palette HUD (two-finger swipe down).
                    if (authenticated && !corruptionBlocked && !restoreBlocked && showCommandPalette) {
                        com.authorss81.noteflow.ui.components.CommandPaletteOverlay(
                            viewModel = viewModel,
                            onOpenNote = { id, _ ->
                                showCommandPalette = false
                                val found = pages.find { it.id == id }
                                if (found != null) {
                                    showGraphView = false
                                    setActivePage(found)
                                }
                            },
                            onClose = { showCommandPalette = false }
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

    // B1-PLAT-4 (phase-60): the runtime action-screen-off receiver lives exactly
    // as long as the activity — dropping it on destroy prevents any leak, and the
    // guard keeps a config-change rebuild from double-unregistering.
    override fun onDestroy() {
        super.onDestroy()
        if (screenOffReceiverRegistered) {
            screenOffReceiverRegistered = false
            unregisterReceiver(screenOffReceiver)
        }
    }

    // 22.5 + Phase 15 + B1-PLAT-2 (phase-58): classify + validate incoming share
    // content through the ClipShare plugin BEFORE any bytes are copied into the
    // vault. A rejected clip (blank/oversized/unusable) shows the plugin's
    // reason instead of creating a note; an ACCEPTED clip is HELD behind an
    // explicit "Clip into InkFlow?" confirmation dialog — it is never copied on
    // arrival, and the bounded copy runs only after confirmation and unlock.
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
        val input = com.authorss81.noteflow.plugins.SharedInput(
            action = intent.action,
            text = text,
            streams = uriStrings.map { uriString ->
                val uri = Uri.parse(uriString)
                com.authorss81.noteflow.plugins.SharedStream(
                    uriString = uriString,
                    mimeType = runCatching { contentResolver.getType(uri) }.getOrNull(),
                    sizeBytes = null
                )
            }
        )
        lifecycleScope.launch(Dispatchers.IO) {
            when (val outcome = viewModel.parseSharedClip(input)) {
                is com.authorss81.noteflow.plugins.PluginResult.Success ->
                    when (val parsed = outcome.value) {
                        is com.authorss81.noteflow.plugins.ClipParseOutcome.Success -> {
                            // B1-PLAT-2: hold the share behind an explicit
                            // confirmation. NO bytes are copied here.
                            pendingShareConfirm = PendingShareConfirm(
                                clip = parsed.clip,
                                uriStrings = parsed.clip.streams.map { it.uriString }
                            )
                        }
                        is com.authorss81.noteflow.plugins.ClipParseOutcome.Rejected -> {
                            withContext(Dispatchers.Main) {
                                viewModel.showSnackbar(parsed.reason, isLong = true)
                            }
                        }
                    }
                else -> withContext(Dispatchers.Main) {
                    viewModel.showSnackbar(
                        "Clip to InkFlow is not enabled — enable it in Settings → Plugins.",
                        isLong = true
                    )
                }
            }
        }
    }

    // B1-PLAT-2 (phase-58): the user explicitly confirmed the pending share.
    // The share is staged for a POST-UNLOCK bounded copy (see the
    // LaunchedEffect above) — nothing has touched disk yet.
    private fun confirmPendingShare() {
        val request = pendingShareConfirm ?: return
        pendingShareConfirm = null
        pendingShare = SharedContent(
            text = request.clip.text,
            imagePaths = emptyList(),
            rawUris = request.uriStrings
        )
        if (!viewModel.authenticated.value) {
            viewModel.showSnackbar("Clip confirmed — it will be added once you unlock.", isLong = true)
        }
    }

    // 22.5 + B1-PLAT-2: copy shared content URIs into app-private storage with a
    // HARD byte budget (50 MB per item, 200 MB total — see BoundedStreamCopier).
    // An over-budget share throws ImportSizeLimitException; the caller surfaces a
    // non-alarming message. Because this runs only after confirmation AND after
    // unlock, a locked/attacker-fired share never touches disk.
    private fun copySharedUris(uriStrings: List<String>): List<String> {
        val copied = mutableListOf<String>()
        val dir = File(filesDir, "shared").apply { mkdirs() }
        var totalBytes = 0L
        for (uriString in uriStrings) {
            var target: File? = null
            try {
                val uri = Uri.parse(uriString)
                if (totalBytes >= com.authorss81.noteflow.services.BoundedStreamCopier.MAX_TOTAL_BYTES) {
                    throw com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException(
                        "Shared content is too large to clip."
                    )
                }
                val mime = contentResolver.getType(uri)
                val ext = mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "file"
                target = File(dir, "${System.currentTimeMillis()}-${copied.size}.$ext")
                val written = contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        com.authorss81.noteflow.services.BoundedStreamCopier.copyBounded(
                            input,
                            out,
                            minOf(
                                com.authorss81.noteflow.services.BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES,
                                com.authorss81.noteflow.services.BoundedStreamCopier.MAX_TOTAL_BYTES - totalBytes
                            )
                        )
                    }
                } ?: 0L
                totalBytes += written
                if (written > 0L) copied += target.absolutePath else target.delete()
            } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                // B1-PLAT-2: an over-budget share must leave NOTHING behind —
                // drop the partial target and any files already staged this call.
                target?.delete()
                copied.forEach { File(it).delete() }
                throw e
            } catch (e: Exception) {
                // unreadable share item — skip it
                target?.delete()
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
 * Phase 38: two-finger swipe DOWN anywhere in the unlocked vault opens the
 * global Command Palette HUD. Detects ≥2 pointers simultaneously pressed,
 * tracks their centroid, and fires when the centroid travelled >90px downward
 * (x-drift below 220px so horizontal pans/pinch don't mis-trigger). The palette
 * is harmless to open, so low-stakes false positives are acceptable.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTwoFingerSwipeDown(
    onOpen: () -> Unit
) {
    awaitPointerEventScope {
        var twoFingerMode = false
        var measuring = false
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                twoFingerMode = true
                val cx = pressed.map { it.position.x }.average().toFloat()
                val cy = pressed.map { it.position.y }.average().toFloat()
                if (!measuring) {
                    measuring = true
                    startX = cx
                    startY = cy
                }
                lastX = cx
                lastY = cy
            } else if (pressed.isEmpty() && twoFingerMode) {
                break
            }
        }
        if (twoFingerMode && measuring) {
            val dy = lastY - startY
            val dx = lastX - startX
            if (dy > 90f && kotlin.math.abs(dx) < 220f) onOpen()
        }
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

/**
 * H2 (phase-09): shown when the SQLCipher vault failed to open (corrupt /
 * wrong-key). The original files were RENAMED to *.corrupt-<timestamp> — never
 * deleted — so nothing is destroyed. The user must either restore from a backup
 * (recovery path, like RestoreBlockedScreen) or explicitly start a fresh vault.
 */
@Composable
private fun CorruptionRecoveryScreen(viewModel: NoteflowViewModel) {
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

    val quarantineStamp = viewModel.corruptionTimestamp.takeIf { it > 0L }?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it))
    } ?: "unknown time"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Vault Database Corrupted", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your vault database could not be opened ($quarantineStamp). " +
                "IMPORTANT: your data was NOT erased — the affected files were moved aside " +
                "as *.corrupt-$quarantineStamp so nothing is destroyed.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Restore from a backup to get your notes back. Only choose \u201cStart fresh\u201d " +
                "if you have no backup or know the old data is irrecoverable — it starts an empty vault.",
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.startFreshAfterCorruption() }
        ) {
            Text("Start fresh with an empty vault")
        }
        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * B1-CRYPTO-05 (phase-64): shown when the AndroidKeyStore key that wrapped the
 * device DEK copy is lost (app-data restore / ROM migration / keystore reset on
 * some OEMs) while the blob itself is still stored. This is DISTINCT from the
 * corruption screen: the vault database is intact and was NOT quarantined —
 * it is simply still encrypted under a key we can no longer unwrap. The pre-fix
 * behavior silently minted a fresh DEK over the stored wrapper, which made the
 * next open fail against the still-encrypted vault and the phase-09 H2 handler
 * quarantined survivable data as `*.corrupt-*`. The only sanctioned exits are
 * restore-from-backup (re-keys into a fresh DEK) or an explicit start-fresh.
 */
@Composable
private fun KeystoreKeyLostScreen(viewModel: NoteflowViewModel) {
    var backupPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmStartFresh by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            errorMessage = null
            viewModel.attemptKeystoreKeyLostRecoveryFromBackup(uri, backupPassword.ifBlank { null }) { msg ->
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
        Text("Device Security Key Lost", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "The device security key that protects your vault could not be found on this " +
                "device. Your notes were NOT erased — your data is still intact and still " +
                "encrypted, but the key that unlocks it is gone (this can happen after a " +
                "device restore, a system update, or a security-key reset).",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Restore from a backup to get your notes back. Only choose \u201cStart fresh\u201d " +
                "if you have no backup — the old vault is moved aside, not deleted, so it can " +
                "still be recovered offline with the original key material.",
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
        Spacer(Modifier.height(12.dp))
        if (!confirmStartFresh) {
            OutlinedButton(onClick = { confirmStartFresh = true }) {
                Text("Start fresh with an empty vault")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Starting fresh moves your existing (still-encrypted) vault aside and " +
                        "creates a new empty vault. This cannot be undone in the app.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.startFreshAfterKeystoreKeyLoss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Yes — start a fresh empty vault")
                }
                TextButton(onClick = { confirmStartFresh = false }) {
                    Text("Cancel")
                }
            }
        }
        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * B1-CRYPTO-06 review (phase-91): the SINGLE shared banner card for the two
 * vault-integrity surfaces — the ALARMING tamper banner (`databaseTampered`,
 * errorContainer) and the DISTINCT, non-alarming "cannot verify" notice
 * (`databaseIntegrityUnverified`, tertiaryContainer). Identical layout and
 * chrome, identical per-session "Don't show again this session" checkbox +
 * OK, both wired to the same `dismissDatabaseIntegrityWarning`. The
 * dismissal is per-session only (see `IntegrityWarningDismissalGate`) — a
 * single tap can never permanently kill either tripwire.
 */
@Composable
    private fun IntegrityBannerCard(
        containerColor: Color,
        icon: ImageVector,
        iconTint: Color,
        textColor: Color,
        okTextColor: Color,
        title: String,
        message: String,
        onDismiss: (Boolean) -> Unit
    ) {
    Surface(
        color = containerColor,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
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
                Icon(icon, contentDescription = null, tint = iconTint)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
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
                        text = "Don't show again this session",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
TextButton(onClick = { onDismiss(dontShowAgain) }) {
                        Text("OK", color = okTextColor, fontWeight = FontWeight.Bold)
                    }
            }
        }
    }
}
