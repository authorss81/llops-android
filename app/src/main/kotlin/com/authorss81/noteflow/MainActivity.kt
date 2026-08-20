package com.authorss81.noteflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.DatabaseIntegrityPolicy
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.PendingSharePolicy
import com.authorss81.noteflow.services.ShareCaptureMode
import com.authorss81.noteflow.services.UpdateApkDecisionPolicy
import com.authorss81.noteflow.services.WidgetLaunchPolicy
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

/** Phase 158 review-fix: saved-state key for the pending quick-capture flag. */
private const val STATE_KEY_QUICK_CAPTURE_REQUESTED = "quick_capture_requested"

/**
 * B1-PLAT-2 (phase-58) + R2-B1P-05 (phase-140): the share-confirmation flow
 * state now lives in the ViewModel (`NoteflowViewModel.pendingShareConfirm` /
 * `pendingShare`) so it survives rotation and is dropped at the lock boundary.
 * The [imagePaths] below are local files already copied into app storage; empty
 * until the bounded copy runs. [rawUris] are the source content URIs, staged
 * ONLY after an explicit in-app "Clip into InkFlow?" confirmation AND only when
 * the vault is unlocked — the copy is performed by the post-unlock apply
 * effect, never while locked.
 */

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
class MainActivity : FragmentActivity() {

    private val viewModel: NoteflowViewModel by viewModels()

    // 22.1: last user interaction (touch) timestamp for the inactivity auto-lock.
    private var lastActivityAtMs = System.currentTimeMillis()

    // R2-B1P-05 (phase-140): the share-confirmation flow is held by the
    // ViewModel (`pendingShareConfirm` = un-confirmed hold, `pendingShare` =
    // confirmed, deferred post-unlock copy). Hoisted off the activity so a
    // rotation (no configChanges → recreation with the ORIGINAL SEND intent)
    // cannot re-prompt a confirm the user already answered, and so lock() can
    // drop both at the lock boundary.

    // R2-B1A-03 (phase-140): an opaque full-screen cover, raised the instant the
    // activity pauses while a has-master-password vault is authenticated, and
    // cleared on ANY resume (picker / biometric / share-sheet returns). See
    // OnPauseCoverPolicy for the decision table — showing decrypted content
    // beneath a SYSTEM_ALERT_WINDOW overlay / OEM in-call UI / translucent
    // anti-theft app is exactly the exposure this closes.
    private var pauseCoverActive by mutableStateOf(false)

    // Phase 158 (22.5b): a home-widget "New note" tap while the vault is LOCKED
    // must not create anything (no DEK). We remember the launcher request and
    // fire the quick-capture the moment `authenticated` turns true — the app
    // opens straight to a fresh note exactly as the widget promised.
    private var quickCaptureRequested by mutableStateOf(false)

    // Phase 38: the global Command Palette HUD is activity-level state so the
    // ON_PAUSE cover can also dismiss its window (it is a separate Dialog window
    // and spans above the activity content).
    private var showCommandPalette by mutableStateOf(false)

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
        // Phase 158 review-fix: a home-widget tap made while the vault is LOCKED
        // must survive a rotation (onSaveInstanceState restores the pending flag
        // here before the fresher intent may re-raise it).
        quickCaptureRequested = savedInstanceState?.getBoolean(STATE_KEY_QUICK_CAPTURE_REQUESTED, false) ?: false
        // Phase 158 (22.5b): the home widget is a launcher shortcut carrying the
        // explicit EXTRA_QUICK_CAPTURE boolean — remember it and fire the new-note
        // flow once the vault is authenticated (see the LaunchedEffect below).
        handleQuickCaptureIntent(intent)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    com.authorss81.noteflow.services.ClipboardGuard.scrubIfOwnCopy(this)
                    // R2-B1A-03 (phase-140): cover the decrypted vault the moment
                    // a has-master-password + authenticated activity pauses. A
                    // SYSTEM_ALERT_WINDOW overlay / OEM in-call UI / translucent
                    // anti-theft app can sit over the activity at ON_PAUSE
                    // indefinitely, and the old ON_STOP-only lock left the
                    // screen-away window open. The opaque cover is dismissed on
                    // ANY resume (SAF picker / biometric prompt / share-sheet
                    // return — the phase-60 reason locking on ON_PAUSE was
                    // rejected), so picks still work while cover apps never see
                    // decrypted content. Also dismiss the un-confirmed
                    // share-confirm dialog (attacker-chosen preview text) and the
                    // Command Palette (decrypted note-title list) — both are
                    // separate windows that would float ABOVE the activity cover.
                    if (com.authorss81.noteflow.services.OnPauseCoverPolicy.shouldCoverOnPause(
                            hasMasterPassword = viewModel.hasMasterPassword.value,
                            authenticated = viewModel.authenticated.value
                        )
                    ) {
                        pauseCoverActive = true
                        viewModel.cancelPendingShareConfirm()
                        showCommandPalette = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // R2-B1A-03: every legitimate return path clears the cover.
                    if (com.authorss81.noteflow.services.OnPauseCoverPolicy.shouldDismissOnResume(pauseCoverActive)) {
                        pauseCoverActive = false
                    }
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
            // R2-b2b1-UI-04 (phase-153): the root collector is GATED on
            // `authenticated`. The host is composed OUTSIDE the LockScreen branch
            // (`:670-673`), so the collector is the guard: while locked it is NOT
            // running (nothing can render over the LockScreen), `lock()` cleared
            // the pre-lock queue, and `showSnackbar` only ever queues survive-lock
            // notices (the voice-discard notice) while locked — so restarting on
            // unlock drains exactly the notice that must surface. Cancelling the
            // effect mid-display dismisses a snackbar left over from the unlocked
            // period.
            LaunchedEffect(authenticated) {
                if (!authenticated) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    return@LaunchedEffect
                }
                viewModel.snackbarMessages.collect {
                    // Drain from the LIVE FIFO on every wake-up — never a stale
                    // snapshot (the collector re-wakes after each ack, and a
                    // message appended while one is showing is still queued).
                    while (authenticated) {
                        val message = viewModel.nextSnackbarMessage() ?: break
                        viewModel.consumeSnackbar(message)
                        snackbarHostState.showSnackbar(
                            message = message.text,
                            duration = if (message.isLong) SnackbarDuration.Long else SnackbarDuration.Short
                        )
                    }
                }
            }

            val pages by viewModel.pages.collectAsState()
            // Phase 133: the global all-active-pages flow is a SECOND fallback for
            // resolving the active page — a page created in a section that is not
            // the currently observed one still resolves through it.
            val allActivePages by viewModel.allActivePages.collectAsState()
            var activePageId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            // Phase 133: synchronous in-memory tracking of the page being opened.
            // Room-backed lists emit ASYNC, so on the immediate creation frame
            // (Add Page FAB / Daily Journal / wiki-link create / shared note) the
            // page is not yet in `pages` — the old `pages.find { id }` returned
            // null and the screen transition was lost. The tracker carries the
            // freshly created/opened entity so the editor opens on the exact frame
            // of creation; see ActivePageTracker.
            var activeTracker by remember { mutableStateOf(com.authorss81.noteflow.services.ActivePageTrackerState()) }
            var showGraphView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            // Phase 158 (22.5): a note opened FROM a share-sheet capture should
            // start in reader/focus mode — the clip is for reading. Holds the
            // captured page's id until MarkdownPreviewScreen consumes it.
            var readerModeRequestedFor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            // Phase 158 review-fix: the result of an "append to current" capture,
            // pushed into the open editor so it shows the appended text instead of
            // the stale pre-append snapshot (which a later save would clobber).
            // One-shot Pair(pageId, newBody); consumed by MarkdownPreviewScreen.
            var appendedBodyForPage by remember { mutableStateOf<Pair<String, String>?>(null) }
            // R2-B1P-05 (phase-140): share-flow state + the command-palette HUD
            // open/close live at activity level (ViewModel-scoped / activity
            // field) so rotation can neither re-prompt an answered confirm nor
            // reopen a palette the cover dismissed.
            val pendingShareConfirm by viewModel.pendingShareConfirm.collectAsState()
            val pendingShare by viewModel.pendingShare.collectAsState()

            // Phase 133: resolve with fallback matching — synchronous in-memory
            // copy first, then allActivePages, then the section-filtered pages
            // (order of precedence pinned by ActivePageResolutionTest).
            val activePage = com.authorss81.noteflow.services.ActivePageResolution.resolve(
                activePageId = activePageId,
                synchronous = activeTracker.synchronous,
                allActivePages = allActivePages,
                sectionPages = pages
            )
            val setActivePage: (NotePageEntity?) -> Unit = { page ->
                activePageId = page?.id
                viewModel.settings.activePageId = page?.id
                activeTracker = com.authorss81.noteflow.services.ActivePageTracker.open(
                    activeTracker, page, allActivePages, pages
                )
            }

            // Phase 133: restore the persisted active page (launch / unlock /
            // config change). The synchronous copy is re-armed from the
            // authoritative lists so the editor reopens without waiting on Room.
            LaunchedEffect(authenticated, pages, allActivePages) {
                if (authenticated && activePageId == null) {
                    val restored = com.authorss81.noteflow.services.ActivePageTracker.restore(
                        savedId = viewModel.settings.activePageId,
                        allActivePages = allActivePages,
                        sectionPages = pages
                    )
                    if (restored.id != null) {
                        activePageId = restored.id
                        activeTracker = restored
                    }
                }
            }

            // Phase 133: keep the synchronous copy in lock-step with the
            // authoritative Room lists. Once Room emits, refresh the copy to the
            // authoritative instance (fresh title/tags/updatedAt); if the page was
            // DB-confirmed and is now absent from every source (deleted/trashed),
            // drop id + copy so the editor never renders a stale entity.
            LaunchedEffect(allActivePages, pages) {
                val before = activeTracker
                val after = com.authorss81.noteflow.services.ActivePageTracker.onAuthoritative(
                    before, allActivePages, pages
                )
                if (after != before) {
                    activeTracker = after
                    if (after.id == null && before.id != null) {
                        activePageId = null
                        viewModel.settings.activePageId = null
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
            // arrival and never while locked. The state is consumed from the
            // ViewModel (R2-B1P-05) so a rotation mid-deferral keeps the deferred
            // clip without re-prompting, and lock() drops it so it never
            // auto-applies at the NEXT unlock.
            // Phase 158 review-fix: resolved here because the apply body and the
            // onDone lambda are non-composable (stringResource is composable).
            val appendSuccessLabel = stringResource(R.string.share_append_success)
            LaunchedEffect(authenticated, pendingShare) {
                if (!authenticated) return@LaunchedEffect
                val share = viewModel.consumePendingShare() ?: return@LaunchedEffect
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
                // Phase 158 (22.5a): "capture as new note vs append to current".
                // The honest decision table (PendingSharePolicy.resolveAppendTarget)
                // routes APPEND_TO_ACTIVE to the open note ONLY for a text-only
                // clip with an active page; everything else lands as a new note.
                val target = activePage
                val resolution = com.authorss81.noteflow.services.PendingSharePolicy.resolveAppendTarget(
                    hasActivePage = target != null,
                    clipHasImages = paths.isNotEmpty(),
                    mode = share.captureMode
                )
                if (resolution == com.authorss81.noteflow.services.AppendResolution.APPEND_TO_ACTIVE && target != null) {
                    viewModel.appendSharedContentToPage(target, share.text) { newBody ->
                        // Review-fix: only surface the result while the SAME note is
                        // still the active one — a note navigated away from must not
                        // receive a stale one-shot body later. The DB write itself is
                        // not gated on this; only the UI push is.
                        if (newBody != null && activePageId == target.id) {
                            appendedBodyForPage = target.id to newBody
                            viewModel.showSnackbar(appendSuccessLabel, isLong = false)
                        }
                    }
                    return@LaunchedEffect
                }
                viewModel.createNoteFromSharedContent(share.text, paths) { page ->
                    // Phase 158 (22.5): reader mode is the default post-capture
                    // destination — the fresh note opens in focus/reading mode.
                    readerModeRequestedFor = page.id
                    setActivePage(page)
                }
            }

            // Phase 158 (22.5b): the home-widget quick-capture fires the same
            // new-note flow the Add FAB uses, but ONLY once the vault is
            // authenticated (a locked vault has no DEK and B2-UI-1 rejects any
            // write). The flag is consumed so a later unlock never re-creates.
            LaunchedEffect(authenticated, quickCaptureRequested) {
                if (!authenticated) return@LaunchedEffect
                if (!quickCaptureRequested) return@LaunchedEffect
                quickCaptureRequested = false
                viewModel.addPage("New Page", onCreated = setActivePage)
            }

            // Phase 158 review-fix: the NON-SECRET captured marker is now READ.
            // When the process died with a capture still in flight, the clip itself
            // was never persisted (nothing secret survives a kill), so the honest
            // post-unlock notice is "a shared clip from a previous session wasn't
            // captured". Skipped when a confirm or deferred clip is still live —
            // those cover the in-app cases — and the marker is cleared once shown.
            // (stringResource is composable — resolved here, used in the effect.)
            val capturedPendingNotice = stringResource(R.string.share_captured_previous_session)
            LaunchedEffect(authenticated) {
                if (!authenticated) return@LaunchedEffect
                if (viewModel.pendingShareConfirm.value != null || viewModel.pendingShare.value != null) return@LaunchedEffect
                if (viewModel.settings.capturedSharePending) {
                    viewModel.settings.capturedSharePending = false
                    viewModel.settings.capturedSharePendingAtMs = 0L
                    viewModel.showSnackbar(capturedPendingNotice, isLong = true)
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
                                        if (page.title.endsWith(".md") || page.title.endsWith(".txt") || page.sourceFileType == "text") {
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
                                            // Phase 158 (22.5): a note opened from a share-sheet
                                            // capture starts in reader/focus mode (see the apply
                                            // LaunchedEffect). The request is one-shot — consumed
                                            // by this screen and cleared so a later unlock never
                                            // re-applies it.
                                            val initialReaderMode = readerModeRequestedFor == page.id
                                            MarkdownPreviewScreen(
                                                page = page,
                                                initialContent = contentText,
                                                viewModel = viewModel,
                                                initialReaderMode = initialReaderMode,
                                                onConsumeReaderMode = {
                                                    if (readerModeRequestedFor == page.id) readerModeRequestedFor = null
                                                },
                                                externalBodyUpdate = if (page.id == appendedBodyForPage?.first) appendedBodyForPage?.second else null,
                                                onConsumeExternalBodyUpdate = { if (appendedBodyForPage?.first == page.id) appendedBodyForPage = null },
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
                                                        if (page.title.endsWith(".md") || page.title.endsWith(".txt") || page.sourceFileType == "text") {
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
                                                            // Phase 158 (22.5): one-shot reader-mode request consumed here.
                                                            val initialReaderMode = readerModeRequestedFor == page.id
                                                            MarkdownPreviewScreen(
                                                                page = page,
                                                                initialContent = contentText,
                                                                viewModel = viewModel,
                                                                initialReaderMode = initialReaderMode,
                                                                onConsumeReaderMode = {
                                                                    if (readerModeRequestedFor == page.id) readerModeRequestedFor = null
                                                                },
                                                                externalBodyUpdate = if (page.id == appendedBodyForPage?.first) appendedBodyForPage?.second else null,
                                                                onConsumeExternalBodyUpdate = { if (appendedBodyForPage?.first == page.id) appendedBodyForPage = null },
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
                        // Phase 167: this host lives at the ROOT Box, outside any
                        // Scaffold — without an inset the snackbar is drawn at the
                        // very bottom edge and hides UNDER the system navigation bar
                        // (gesture pill / 3-button bar) in edge-to-edge mode. The
                        // inset is read dynamically so it matches whatever nav mode
                        // and rotation the device is in — never a fixed pixel.
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                        )

                        // B1-PLAT-2 (phase-58) + R2-B1P-05 (phase-140): an incoming
                        // share is NEVER copied on arrival — it is held behind this
                        // explicit confirmation. Only a human tapping "Clip" stages it
                        // (and even then only after the vault is unlocked; see the apply
                        // LaunchedEffect). State lives in the ViewModel (survives
                        // rotation), and the dialog is rendered ONLY while authenticated
                        // — it must never float above the LockScreen after a screen-off
                        // lock.
                        //
                        // Phase 158 (22.5a): the dialog also carries the new-vs-append
                        // capture choice and a per-session expiry. The expiry auto-
                        // dismisses a stale hold (CONFIRM_HOLD_EXPIRY_MS) so an
                        // unnoticed dialog never lingers; the CONFIRMED clip stays
                        // exempt (it was explicitly approved and applies on the very
                        // next authenticated frame, and lock() drops it for password
                        // vaults — no content ever survives a lock).
                        if (authenticated) {
                            pendingShareConfirm?.let { request ->
                                // Expire a stale, un-confirmed hold: schedule a
                                // cancel at the hold deadline (re-checked so an
                                // already-answered confirm is never clobbered).
                                // (stringResource is composable — resolved first.)
                                val expiredNotice = stringResource(R.string.share_confirm_expired)
                                LaunchedEffect(authenticated, pendingShareConfirm) {
                                    val req = pendingShareConfirm ?: return@LaunchedEffect
                                    val remaining = PendingSharePolicy.CONFIRM_HOLD_EXPIRY_MS -
                                        (System.currentTimeMillis() - req.stagedAtMs)
                                    if (remaining <= 0L) {
                                        viewModel.cancelPendingShareConfirm()
                                    } else {
                                        delay(remaining)
                                        if (viewModel.pendingShareConfirm.value == req) {
                                            viewModel.cancelPendingShareConfirm()
                                            viewModel.showSnackbar(expiredNotice, isLong = false)
                                        }
                                    }
                                }
                                // 22.5: the capture mode choice is hoisted out of the
                                // ViewModel state (it is purely presentational) but
                                // remembered across rotation; it resets to new-note
                                // whenever a fresh clip arrives.
                                var clipModeToken by rememberSaveable(pendingShareConfirm) {
                                    mutableStateOf(ShareCaptureMode.NEW_NOTE.name)
                                }
                                val clipMode = ShareCaptureMode.fromToken(clipModeToken)
                                val appendUsable = activePage != null && request.uriStrings.isEmpty()
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { viewModel.cancelPendingShareConfirm() },
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
                                            Spacer(Modifier.height(12.dp))
                                            // 22.5: honest new-vs-append choice. Append
                                            // is disabled (never silently re-routed) when
                                            // there is no active note or the clip carries
                                            // images — those degrade to a new note and the
                                            // button says so.
                                            Text(
                                                stringResource(R.string.share_capture_add_as),
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .padding(top = 4.dp)
                                                    .clickable(enabled = true) {
                                                        clipModeToken = ShareCaptureMode.NEW_NOTE.name
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                            ) {
                                                androidx.compose.material3.RadioButton(
                                                    selected = clipMode == ShareCaptureMode.NEW_NOTE,
                                                    onClick = {
                                                        clipModeToken = ShareCaptureMode.NEW_NOTE.name
                                                    }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.share_capture_new_note))
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .clickable(enabled = appendUsable) {
                                                        clipModeToken = ShareCaptureMode.APPEND_TO_ACTIVE.name
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                            ) {
                                                androidx.compose.material3.RadioButton(
                                                    selected = clipMode == ShareCaptureMode.APPEND_TO_ACTIVE,
                                                    enabled = appendUsable,
                                                    onClick = {
                                                        clipModeToken = ShareCaptureMode.APPEND_TO_ACTIVE.name
                                                    }
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    if (appendUsable) stringResource(R.string.share_capture_append)
                                                    else stringResource(R.string.share_capture_append_unavailable)
                                                )
                                            }
                                            if (!appendUsable) {
                                                Text(
                                                    if (request.uriStrings.isEmpty())
                                                        stringResource(R.string.share_capture_no_active_note)
                                                    else
                                                        stringResource(R.string.share_capture_attachments_new_note),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { viewModel.confirmPendingShare(clipMode) }) {
                                            Text("Clip")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { viewModel.cancelPendingShareConfirm() }) {
                                            Text("Not now")
                                        }
                                    }
                                )
                            }
                        }

                        // R2-B1A-03 (phase-140): opaque cover over the WHOLE content
                        // once ON_PAUSE fires for an authenticated has-master-password
                        // vault. Last child of the Box = drawn on top of the content,
                        // the snackbar host, and the inactive command palette — a
                        // SYSTEM_ALERT_WINDOW overlay, in-call UI or translucent
                        // anti-theft app drawn over the paused activity can never read
                        // decrypted notes through it. Cleared on every legitimate
                        // resume (picker / biometric / share-sheet return).
                        if (pauseCoverActive &&
                            com.authorss81.noteflow.services.OnPauseCoverPolicy.shouldCoverOnPause(
                                hasMasterPassword = hasMasterPassword,
                                authenticated = authenticated
                            )
                        ) {
                            androidx.compose.material3.Surface(
                                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                // Intentionally empty — an opaque barrier, never a
                                // content preview.
                            }
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
        handleQuickCaptureIntent(intent)
    }

    /**
     * Phase 158 (22.5b): the home widget taps MainActivity with an explicit
     * [WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE] boolean. We never create a note
     * here (a locked vault has no DEK and B2-UI-1 rejects it anyway) — we
     * remember the launcher request; the composable fires the quick-capture
     * once `authenticated` is true. A tap while already authenticated re-fires
     * through onNewIntent and the LaunchedEffect consumes it on the next frame.
     */
    private fun handleQuickCaptureIntent(intent: Intent?) {
        if (intent == null) return
        // Intent extras → the pure-JVM Map contract WidgetLaunchPolicy parses
        // (Bundle.toMap() gives Any?, which does not satisfy the typed contract).
        val extras: Map<String, Boolean?> = intent.extras?.keySet()?.associateWith { key ->
            (intent.extras?.get(key) as? Boolean)
        } ?: emptyMap()
        if (WidgetLaunchPolicy.hasQuickCaptureExtra(extras)) {
            quickCaptureRequested = true
        }
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

    // Phase 158 review-fix: a home-widget "new note" tap while the vault is
    // locked sets quickCaptureRequested so the capture fires on the next unlock.
    // A rotation mid-hold would lose a plain activity field, so the pending flag
    // is folded into the saved state (see onCreate's restore).
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_KEY_QUICK_CAPTURE_REQUESTED, quickCaptureRequested)
    }

    // 22.5 + Phase 15 + B1-PLAT-2 (phase-58): classify + validate incoming share
    // content through the ClipShare plugin BEFORE any bytes are copied into the
    // vault. A rejected clip (blank/oversized/unusable) shows the plugin's
    // reason instead of creating a note; an ACCEPTED clip is HELD behind an
    // explicit "Clip into InkFlow?" confirmation dialog — it is never copied on
    // arrival, and the bounded copy runs only after confirmation and unlock.
    private fun readShareIntent(intent: Intent?) {
        if (intent == null) return
        // R2-B1P-05 (phase-140): on a rotated-recreated activity onCreate re-fires
        // the ORIGINAL SEND intent. If a confirm is already pending (or already
        // answered and awaiting the post-unlock copy), do NOT re-parse — the
        // pre-fix behavior re-prompted a confirm the user already handled.
        if (viewModel.pendingShareConfirm.value != null || viewModel.pendingShare.value != null) return
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
        // Phase 190: an APK shared INTO the app is an UPDATE candidate, not note
        // content. Classify the stream (MIME type or file name) and, when the
        // ENTIRE share is APK(s), stage it into app-private cacheDir so the next
        // "Scan App Storage" (or AppUpdateDialog's auto-scan) offers it through
        // the usual B1-PLAT-7 untrusted-confirmation install flow. It never
        // becomes a note clip, and public Downloads are never scanned. The
        // snackbar is non-secret, so a locked vault surfaces it fine.
        if (uriStrings.isNotEmpty()) {
            val apkStreams = uriStrings.mapNotNull { uriString ->
                val uri = Uri.parse(uriString)
                val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
                val name = displayNameOf(uri)
                if (UpdateApkDecisionPolicy.isApkStream(mime, name)) uri else null
            }
            if (apkStreams.size == uriStrings.size) {
                lifecycleScope.launch(Dispatchers.IO) {
                    var staged: Int = 0
                    for (uri in apkStreams) {
                        val file = ImportExportService.stageApkUriToFile(this@MainActivity, uri)
                        if (file != null) staged++
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.showSnackbar(
                            UpdateApkDecisionPolicy.apkStagedMessage(staged),
                            isLong = true
                        )
                    }
                }
                return
            }
        }
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
                            // confirmation. NO bytes are copied here. State lives
                            // in the ViewModel (R2-B1P-05) so rotation cannot
                            // re-prompt or drop an answered confirm.
                            viewModel.stagePendingShare(parsed.clip, parsed.clip.streams.map { it.uriString })
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

    // Phase 190: OpenableColumns.DISPLAY_NAME for APK-stream classification on
    // ACTION_SEND shares (the MIME type is often `application/octet-stream`).
    private fun displayNameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    // R2-B1P-05: the "user explicitly confirmed" transition now lives in the
    // ViewModel (`confirmPendingShare`) — it stages the POST-UNLOCK bounded copy
    // (see the LaunchedEffect above) without copying any bytes, and lock()
    // drops the staged clip so it never auto-applies at the next unlock.

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
 * R2-B1D-02 + R2-b2b1-UI-06 (phase-135): shown when a restore hits the pre-swap
 * gate's zero-row-but-real-schema case ([EmptyVaultRestoreDecisionException]).
 * The prompt lives off the ViewModel channel ([NoteflowViewModel.pendingEmptyVaultConfirm])
 * — a deferred that survives rotation — and the dialog itself is rendered fresh
 * every time it appears, so no screen-local state can be lost mid-prompt.
 */
@Composable
private fun EmptyVaultRestoreConfirmDialog(viewModel: NoteflowViewModel) {
    val pending by viewModel.pendingEmptyVaultConfirm.collectAsState()
    if (pending != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.answerEmptyVaultRestore(false) },
            title = { Text("Backup contains no notes") },
            text = {
                Text(
                    "This backup contains an EMPTY vault (no notes). Restoring it will " +
                        "replace everything on this device with an empty vault. Only continue " +
                        "if you truly want to start fresh."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.answerEmptyVaultRestore(true) }) {
                    Text("Restore Empty Vault")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.answerEmptyVaultRestore(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * 34.8: hard block after a restore whose HMAC baseline could not be verified.
 * The vault is unreachable until a fresh backup restores successfully.
 */
@Composable
private fun RestoreBlockedScreen(viewModel: NoteflowViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // R2-b2b1-UI-06 (phase-135): recovery-screen state must survive rotation /
    // process death so a mid-restore lifecycle event cannot silently clear the
    // password and re-arm a SECOND restore (which raced UI-03's missing gate).
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val isRestoring by viewModel.isRestoring.collectAsState()

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

    EmptyVaultRestoreConfirmDialog(viewModel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            // Phase 167: these recovery screens render edge-to-edge WITHOUT a
            // Scaffold — the scroll container reaches BOTH window edges, so it
            // must consume the dynamic status-bar AND navigation-bar insets
            // itself (the top inset is the phase-167 review fix; the bottom one
            // is the original fix). Without them the first/last content row
            // (title / Buttons / error text) scrolls under the system bars.
            .navigationBarsPadding()
            .statusBarsPadding(),
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
        Button(
            onClick = { pickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            // R2-b2b1-UI-03/-06 (phase-135): disable the restore trigger while one
            // is in flight — the VM gate would refuse anyway; make it visible.
            enabled = !isRestoring
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRestoring) "Restoring…" else "Choose Backup & Restore")
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
    // R2-b2b1-UI-06 (phase-135): rememberSaveable so rotation / process death on
    // the recovery path cannot silently clear the password and re-arm a restore.
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val isRestoring by viewModel.isRestoring.collectAsState()

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

    EmptyVaultRestoreConfirmDialog(viewModel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            // Phase 167: these recovery screens render edge-to-edge WITHOUT a
            // Scaffold — the scroll container reaches BOTH window edges, so it
            // must consume the dynamic status-bar AND navigation-bar insets
            // itself (the top inset is the phase-167 review fix; the bottom one
            // is the original fix). Without them the first/last content row
            // (title / Buttons / error text) scrolls under the system bars.
            .navigationBarsPadding()
            .statusBarsPadding(),
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
        Button(
            onClick = { pickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isRestoring
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRestoring) "Restoring…" else "Choose Backup & Restore")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.startFreshAfterCorruption() },
            enabled = !isRestoring
        ) {
            Text("Start fresh with an empty vault")
        }
        errorMessage?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        // Phase-163: "Don't show again" for THIS corruption event only — keyed to
        // the quarantine timestamp (RecoveryDismissalPolicy), so a NEW corruption
        // (fresh *.corrupt-<ts> stamp) always re-shows this screen.
        Spacer(Modifier.height(20.dp))
        var dontShowAgain by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isRestoring) { dontShowAgain = !dontShowAgain },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                checked = dontShowAgain,
                onCheckedChange = { dontShowAgain = it },
                enabled = !isRestoring
            )
            Text(
                text = "Don't show again for this corruption event",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(
            onClick = { viewModel.dismissCorruptionRecovery(dontShowAgain) },
            enabled = !isRestoring
        ) {
            Text("Dismiss")
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
    // R2-b2b1-UI-06 (phase-135): rememberSaveable so rotation / process death on
    // the recovery path cannot lose the typed password, the error, or the
    // start-fresh confirm while the (seconds-long) restore is in flight.
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmStartFresh by rememberSaveable { mutableStateOf(false) }
    val isRestoring by viewModel.isRestoring.collectAsState()

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

    EmptyVaultRestoreConfirmDialog(viewModel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            // Phase 167: these recovery screens render edge-to-edge WITHOUT a
            // Scaffold — the scroll container reaches BOTH window edges, so it
            // must consume the dynamic status-bar AND navigation-bar insets
            // itself (the top inset is the phase-167 review fix; the bottom one
            // is the original fix). Without them the first/last content row
            // (title / Buttons / error text) scrolls under the system bars.
            .navigationBarsPadding()
            .statusBarsPadding(),
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
        Button(
            onClick = { pickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isRestoring
        ) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRestoring) "Restoring…" else "Choose Backup & Restore")
        }
        Spacer(Modifier.height(12.dp))
        if (!confirmStartFresh) {
            OutlinedButton(
                onClick = { confirmStartFresh = true },
                enabled = !isRestoring
            ) {
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
        // Phase-163: "Don't show again" for THIS lost-key event only — keyed to the
        // recorded wrapper-lost event timestamp (RecoveryDismissalPolicy), so a
        // DIFFERENT lost key (a new event) always re-shows this screen.
        Spacer(Modifier.height(20.dp))
        var dontShowAgain by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isRestoring) { dontShowAgain = !dontShowAgain },
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                checked = dontShowAgain,
                onCheckedChange = { dontShowAgain = it },
                enabled = !isRestoring
            )
            Text(
                text = "Don't show again for this lost device key",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedButton(
            onClick = { viewModel.dismissKeystoreKeyLostRecovery(dontShowAgain) },
            enabled = !isRestoring
        ) {
            Text("Dismiss")
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
