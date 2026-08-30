package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupPortabilityPolicy
import com.authorss81.noteflow.services.DockPosturePolicy
import com.authorss81.noteflow.services.EraseHitBucketPolicy
import com.authorss81.noteflow.services.FloatingWidgetDragPolicy
import com.authorss81.noteflow.services.PaperTextureStrengthPolicy
import com.authorss81.noteflow.services.WetThrottlePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 253 (2026-08-30): the release-gate FINAL audit regression pin.
 *
 * End-to-end honest audit of the codebase AFTER phases 247-252 land. This
 * suite re-pins EVERY claim the six phases made so a silent regression back
 * to any pre-253 defect fails the build loudly. Each section canonical-cites
 * the phase.
 */
class Phase253FinalAuditRegressionTest {

    private fun source(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    private fun res(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/res/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/res/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/res/$rel from ${start.path}")
    }

    // ======================================================================
    // Phase 247 — Paper texture TRUE ZERO at strength 0
    // ======================================================================

    @Test
    fun `247 - PaperTextureStrengthPolicy early-returns true zero at strength 0`() {
        val src = source("services/PaperTextureStrengthPolicy.kt")
        // The three strength-mapped functions are expression bodies (no braces);
        // each early-returns exactly 0f at clamped strength 0.
        val earlyZero = countOccurrences(src, "if (clamp(strength) == 0) 0f")
        assertTrue("grainDrawAlpha must early-return 0f at strength 0", earlyZero >= 3)
        val alphaDef = at(src, "fun grainDrawAlpha(")
        assertTrue("grainDrawAlpha must early-return 0f", alphaDef.contains("if (clamp(strength) == 0) 0f"))
        val scaleDef = at(src, "fun grainScale(")
        assertTrue("grainScale must early-return 0f", scaleDef.contains("if (clamp(strength) == 0) 0f"))
        val gainDef = at(src, "fun shaderGain(")
        assertTrue("shaderGain must early-return 0f", gainDef.contains("if (clamp(strength) == 0) 0f"))
    }

    @Test
    fun `247 - paper strength zero anchors stay byte-identical`() {
        assertEquals(0f, PaperTextureStrengthPolicy.grainDrawAlpha(0), 0f)
        assertEquals(0f, PaperTextureStrengthPolicy.grainScale(0), 0f)
        // The 50 anchor (pre-227 default) and the 100 ceiling are preserved.
        assertEquals(0.045f, PaperTextureStrengthPolicy.grainDrawAlpha(50), 0f)
        assertEquals(1f, PaperTextureStrengthPolicy.grainScale(50), 0f)
        assertEquals(0.07f, PaperTextureStrengthPolicy.grainDrawAlpha(100), 0f)
    }

    // ======================================================================
    // Phase 248 — Minimap pane binding + ink bar topBar reservation
    // ======================================================================

    @Test
    fun `248 - AnnotationCanvas minimap binds to the pane box, not the device screen`() {
        val src = source("ui/components/AnnotationCanvas.kt")
        // No device-wide dims survive anywhere in the file.
        assertFalse(
            "minimap must not read the device-wide screen dims",
            src.contains("LocalConfiguration.current.screenWidthDp")
        )
        assertFalse(
            "minimap must not read the device-wide screen dims",
            src.contains("LocalConfiguration.current.screenHeightDp")
        )
        // The minimap drag pointerInput keys are the pane-local dims.
        val dragInput = src.substring(src.indexOf("pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)"))
        assertTrue("minimap drag clamps within the pane box", dragInput.contains("paneW, paneH, minimapWidthPx, minimapHeightPx"))
    }

    @Test
    fun `248 - FloatingWidgetDragPolicy topReservedPx reserves the top-bar band`() {
        val src = source("services/FloatingWidgetDragPolicy.kt")
        assertTrue(
            "constrainWithinSafeArea must accept topReservedPx",
            src.contains("topReservedPx: Float = 0f")
        )
        assertTrue(
            "the effective top clamp must add the reservation",
            src.contains("(top.coerceAtLeast(0f) + topReservedPx)")
        )
        // Semantic: a drag to y=10 with top=48 and 56 reserved yields 104.
        val o = FloatingWidgetDragPolicy.constrainWithinSafeArea(
            100f, 10f, 600f, 800f, 60f, 40f, top = 48f, bottom = 0f, start = 0f, end = 0f, topReservedPx = 56f
        )
        assertEquals(104f, o.y, 0f)
    }

    @Test
    fun `248 - DockPosturePolicy anchors reserve the top bar`() {
        val src = source("services/DockPosturePolicy.kt")
        assertTrue("horizontalDefaultAnchor must take topReservedPx", src.contains("topReservedPx: Float = 0f"))
        assertTrue("verticalDefaultAnchor must take topReservedPx", src.contains("topReservedPx: Float = 0f"))
        // Semantic: horizontal bottom-center anchor never rests above the reserved line.
        val (_, y) = DockPosturePolicy.horizontalDefaultAnchor(
            600f, 800f, 300f, 56f, topReservedPx = 100f
        )
        assertTrue("horizontal anchor must stay below the reserved line", y >= 100f)
    }

    @Test
    fun `248 - EditorScreen derives topReservedPx from the measured topBar and feeds both rests and drag clamp`() {
        val src = source("ui/screens/EditorScreen.kt")
        assertTrue(
            "the topBar height must be measured",
            src.contains(".onSizeChanged { topBarHeightPx = it.height.toFloat() }")
        )
        assertTrue(
            "the reservation derives content height above the status inset",
            src.contains("val topReservedPx = (topBarHeightPx - topInsetPx).coerceAtLeast(0f)")
        )
        assertTrue("the drag clamp must pass the reservation", src.contains("topReservedPx = topReservedPx"))
    }

    // ======================================================================
    // Phase 249 — Canvas criticals
    // ======================================================================

    @Test
    fun `249 - wet throttle uses the real stored timestamp and raw delta, never a fabricated wall clock`() {
        val src = source("ui/components/AnnotationCanvas.kt")
        // No fabricated `System.currentTimeMillis() - 16L` wall clock remains.
        assertFalse(src.contains("System.currentTimeMillis() - 16L"))
        assertFalse(src.contains("System.currentTimeMillis() - 100L"))
        assertFalse(src.contains("wetBrushEngine.shouldProcessPoint("))
        // The gate reads the real MotionEvent uptime and the previous RAW sample.
        assertTrue(src.contains("val curTime = sampleTimestampMs"))
        assertTrue(src.contains("val lastTime = lastRawWetTimeMs"))
        assertTrue(src.contains("lastRawX = lastRawWetX"))
        assertTrue(src.contains("lastRawY = lastRawWetY"))
        // Semantic of the pure-JVM gate over the real timeline.
        assertTrue(WetThrottlePolicy.shouldProcess(10f, 20f, 1_000L, 15f, 20f, 1_004L))
        assertTrue(WetThrottlePolicy.shouldProcess(10f, 20f, 1_000L, 10f, 20f, 1_020L))
        assertFalse(WetThrottlePolicy.shouldProcess(10f, 20f, 1_000L, 10f, 20f, 1_004L))
        assertTrue("first sample of a stroke must fail open", WetThrottlePolicy.shouldProcess(null, null, null, 10f, 20f, 1_000L))
    }

    @Test
    fun `249 - flushPendingSaves body is wrapped in withContext NonCancellable`() {
        val src = source("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(src.contains("import kotlinx.coroutines.NonCancellable"))
        val flush = src.substring(src.indexOf("fun flushPendingSaves("))
        val block = flush.substring(0, flush.indexOf("}", flush.indexOf("{") + 1))
        assertTrue("flush body must run in NonCancellable", block.contains("withContext(NonCancellable) {"))
        assertTrue("cancel-then-await settle must be preserved", block.contains("pendingDebounce?.cancel()"))
        assertTrue("cancel-then-await settle must be preserved", block.contains("pendingDebounce?.join()"))
    }

    @Test
    fun `249 - card-hit onDragStart drops the predicted tail before claiming the gesture`() {
        val src = source("ui/components/AnnotationCanvas.kt")
        val cardBranch = src.substring(src.indexOf("if (isHittingCard(canvasOffset)) {"))
        val cardBlock = cardBranch.substring(0, cardBranch.indexOf("}", cardBranch.indexOf("{") + 1))
        val strip = cardBlock.indexOf("dropPredictedTail()")
        val claim = cardBlock.indexOf("isDraggingCard = true")
        assertTrue("card-hit branch must drop the predicted tail", strip >= 0)
        assertTrue("the tail strip must precede the card claim", strip < claim)
        assertTrue("the card early return must follow", cardBlock.contains("return@detectDragGestures"))
    }

    @Test
    fun `249 - applyEraser windowing + hard cap + spatial bucket remain`() {
        val src = source("ui/components/AnnotationCanvas.kt")
        assertTrue("applyEraser must track the last processed sample", src.contains("lastProcessedEraseSampleIndex"))
        assertTrue(
            "samples must cap at the coalesced burst size",
            src.contains("takeLast(com.authorss81.noteflow.services.EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY)")
        )
        assertTrue("the lazy spatial bucket must be built on first pass", src.contains("EraseHitBucketPolicy.build("))
        assertTrue("re-tile only changed strokes", src.contains("bucket.replaceStrokes(removed, added)"))
        assertEquals(8, EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY)
    }

    // ======================================================================
    // Phase 250 — Data-loss criticals
    // ======================================================================

    @Test
    fun `250 - generation token is bumped before every flush and checked at the write entry`() {
        val src = source("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue("VM must own the editorSaveGeneration token", src.contains("var editorSaveGeneration: Int = 0"))
        // flushPendingSaves bumps first, flushEditorPageSave bumps, and the
        // write entry re-checks it (generation != null guarded).
        val flush = src.substring(src.indexOf("fun flushPendingSaves("))
        assertTrue("flushPendingSaves must bump the generation", flush.contains("bumpSaveGeneration()"))
        assertTrue(src.contains("fun flushEditorPageSave("))
        assertTrue(
            "write entry must skip a stale generation",
            src.contains("if (generation != null && !isCurrentSaveGeneration(generation))")
        )
        assertTrue(
            "the token must gate only stroke writers via persistEditorSaveSuspend",
            src.contains("generation: Int?")
        )
    }

    @Test
    fun `250 - editor load assigns only while authenticated and sets isInitialLoadComplete inside the same block`() {
        val src = source("ui/screens/EditorScreen.kt")
        assertTrue("the load effect must re-key on the auth state", src.contains("LaunchedEffect(page.id, isAuthenticated)"))
        assertTrue(src.contains("if (viewModel.authenticated.value) {"))
        val load = src.substring(src.indexOf("LaunchedEffect(page.id, isAuthenticated)"))
        val ifBlock = load.substring(load.indexOf("if (viewModel.authenticated.value) {"))
        val elseIdx = ifBlock.indexOf("} else {")
        val trueBranch = ifBlock.substring(0, elseIdx)
        val elseBranch = ifBlock.substring(elseIdx)
        assertTrue(
            "isInitialLoadComplete = true must live INSIDE the authenticated block",
            trueBranch.contains("isInitialLoadComplete = true")
        )
        assertTrue("the else must keep the page not-loaded", elseBranch.contains("isInitialLoadComplete = false"))
        assertTrue("the else must flag the lock-failed load", elseBranch.contains("loadFailedDueToLock = true"))
    }

    @Test
    fun `250 - back paths refuse to flush while loadFailedDueToLock is set`() {
        val src = source("ui/screens/EditorScreen.kt")
        // Both the BackHandler and the top-bar back IconButton gate on the flag.
        val b1 = src.indexOf("BackHandler {")
        val backBlock = src.substring(b1, b1 + 760)
        assertTrue("BackHandler must gate on the lock flag", backBlock.contains("!loadFailedDueToLock"))
        assertTrue("BackHandler must flush when safe", backBlock.contains("flushPendingSaves("))
        // A forward-safe count: the guarded flush condition must appear twice
        // (the BackHandler AND the top-bar back IconButton).
        val guardCount = countOccurrences(src, "if (isInitialLoadComplete && !loadFailedDueToLock) {")
        assertTrue("both back paths must carry the lock guard", guardCount >= 2)
    }

    // ======================================================================
    // Phase 251 — WindowSizeClass refresh + strict default
    // ======================================================================

    @Test
    fun `251 - MainActivity re-derives WindowSizeClass on LocalConfiguration change`() {
        val src = source("MainActivity.kt")
        val effect = src.indexOf("LaunchedEffect(LocalConfiguration.current)")
        assertTrue("config listener must exist", effect >= 0)
        val keyBlock = src.indexOf("key(sizeClassRefreshKey) {")
        assertTrue("key(sizeClassRefreshKey) block must exist", keyBlock >= 0)
        assertTrue("the config listener must precede the block it re-keys", effect < keyBlock)
        val effectBody = src.substring(effect, src.indexOf("}", src.indexOf("{", effect)))
        assertTrue("the listener must bump the same key", effectBody.contains("sizeClassRefreshKey++"))
        assertTrue(
            "derivation must query current window metrics",
            src.contains("calculateWindowSizeClass(activity = this@MainActivity)")
        )
    }

    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun `251 - provider default is the strictest Compact placeholder`() {
        val src = source("ui/WindowSizeClassProvider.kt")
        assertTrue(src.contains("calculateFromSize(DpSize(0.dp, 0.dp))"))
        assertFalse("the old EXPANDED 840dp placeholder must be gone", src.contains("840.dp"))
        // Semantic: 0x0 classifies Compact by both axes (the strictest); the old
        // 840x900 classified Expanded (the old lie) — re-pin the intent.
        val strict = androidx.compose.material3.windowsizeclass.WindowSizeClass.calculateFromSize(
            androidx.compose.ui.unit.DpSize(androidx.compose.ui.unit.Dp(0f), androidx.compose.ui.unit.Dp(0f))
        )
        assertEquals(androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact, strict.widthSizeClass)
        assertEquals(androidx.compose.material3.windowsizeclass.WindowHeightSizeClass.Compact, strict.heightSizeClass)
    }

    // ======================================================================
    // Phase 252 — Passwordless backup portability gate
    // ======================================================================

    @Test
    fun `252 - HomeScreen blocks passwordless backup behind the requirement dialog`() {
        val src = source("ui/screens/HomeScreen.kt")
        assertTrue("the requirement dialog must be imported", src.contains("BackupPasswordRequirementDialog"))
        assertTrue(src.contains("showBackupPasswordRequirementDialog"))
        // The onBackup else (`!hasMasterPassword`) sets the flag; the ONLY
        // exportBackup call sits inside the master-password path. Bound the
        // window between the onBackup and the following onRestore lambdas.
        val onBackupStart = src.indexOf("onBackup = {")
        val onRestoreStart = src.indexOf("onRestore = {")
        assertTrue("onBackup lambda must exist", onBackupStart >= 0)
        assertTrue("onBackup must precede onRestore", onBackupStart < onRestoreStart)
        val window = src.substring(onBackupStart, onRestoreStart)
        assertTrue("passwordless branch must open the requirement dialog", window.contains("showBackupPasswordRequirementDialog = true"))
        assertFalse("no exportBackup call may exist in the passwordless branch", window.contains("exportBackup("))
        // The dialog composite routes "Set Master Password" to the real Security settings.
        assertTrue(src.contains("BackupPasswordRequirementDialog("))
        assertTrue(src.contains("showSecurityDialog = true"))
    }

    @Test
    fun `252 - BackupPortabilityPolicy rejects the device-keyed shape with the exact error`() {
        // The gate throws IllegalArgumentException for `backupPassword == null` +
        // a key is available (the device-keyed archive), and only then.
        try {
            BackupPortabilityPolicy.requirePortableBackup(true, null, keyAvailable = true)
            throw AssertionError("device-keyed + gated export must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must carry the portability lead phrase",
                e.message!!.startsWith(BackupPortabilityPolicy.PASSWORDLESS_DEVICE_KEYED_ERROR)
            )
        }
        // Every legitimate shape passes: password supplied, no key, or opt-out.
        BackupPortabilityPolicy.requirePortableBackup(true, "pw", keyAvailable = true)
        BackupPortabilityPolicy.requirePortableBackup(true, null, keyAvailable = false)
        BackupPortabilityPolicy.requirePortableBackup(false, null, keyAvailable = true)
    }

    @Test
    fun `252 - exportBackup carries the default-safe gate and strings exist`() {
        val src = source("services/ImportExportService.kt")
        assertTrue(src.contains("requireBackupPassword: Boolean = true"))
        assertTrue(src.contains("BackupPortabilityPolicy.requirePortableBackup("))
        assertTrue(src.contains("keyAvailable = key != null"))
        val strings = res("values/strings.xml")
        assertTrue(strings.contains("backup_password_requirement_title"))
        assertTrue(strings.contains("backup_password_requirement_body"))
        assertTrue(strings.contains("backup_password_requirement_set_password"))
        assertTrue(strings.contains("backup_password_requirement_cancel_export"))
        // The WebDAV + LocalSend producers stay explicitly opted into the
        // documented device-keyed model (B1-CRYPTO-05), preserved by design.
        val vm = source("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(vm.contains("requireBackupPassword = false"))
        val lsend = source("ui/components/LocalSendSendDialog.kt")
        assertTrue(lsend.contains("requireBackupPassword = false"))
    }

    // ======================================================================
    // Pre-existing deferred items re-confirmed at HEAD
    // ======================================================================

    @Test
    fun `pre-existing - data extraction rules stay a full root exclusion`() {
        val rules = res("xml/data_extraction_rules.xml")
        assertTrue(rules.contains("cloud-backup"))
        assertTrue(rules.contains("device-transfer"))
        assertTrue(rules.contains("domain=\"root\" path=\".\""))
    }

    @Test
    fun `pre-existing - FloatingWindowPolicy notice stays wired into MainActivity`() {
        val src = source("MainActivity.kt")
        assertTrue(src.contains("FloatingWindowNoticeLauncher("))
        assertTrue(src.contains("FloatingWindowPolicy.isLikelyFloatingWindow("))
        assertTrue(src.contains("FloatingWindowPolicy.noticeDue("))
    }

    private fun at(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val tail = src.substring(start)
        // Expression-body functions end at the next blank-line-separated KDoc,
        // comment, or 'fun'. Cut at the next occurrence of "fun " / a lone KDoc.
        val nextFun = tail.indexOf("\n    fun ")
        val nextKdoc = tail.indexOf("\n    /**")
        val cut = listOf(nextFun, nextKdoc).filter { it >= 0 }.minOrNull() ?: tail.length
        return tail.substring(0, cut)
    }

    private fun countOccurrences(hay: String, needle: String): Int {
        var count = 0
        var idx = hay.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = hay.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
