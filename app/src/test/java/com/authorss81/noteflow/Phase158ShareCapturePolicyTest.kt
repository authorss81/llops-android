package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ClipKind
import com.authorss81.noteflow.plugins.SharedClip
import com.authorss81.noteflow.services.AppendResolution
import com.authorss81.noteflow.services.CapturedMarker
import com.authorss81.noteflow.services.PendingShareConfirmState
import com.authorss81.noteflow.services.PendingSharePolicy
import com.authorss81.noteflow.services.PendingShareState
import com.authorss81.noteflow.services.ShareCaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 158 (deferred ROADMAP 22.5a) — share-sheet capture polish: the
 * new-vs-append choice, the per-session confirm expiry, the honest
 * defer/drop-on-lock posture, and the non-secret captured marker.
 */
class Phase158ShareCapturePolicyTest {

    private val textClip = SharedClip(kind = ClipKind.TEXT, text = "hello")

    // ---- capture mode flows through the states ------------------------------

    @Test
    fun `confirm maps the chosen capture mode into the deferred clip`() {
        val request = PendingShareConfirmState(clip = textClip, uriStrings = listOf("content://p/a"))
        assertEquals(
            ShareCaptureMode.NEW_NOTE,
            PendingSharePolicy.toPendingShare(request, ShareCaptureMode.NEW_NOTE).captureMode
        )
        assertEquals(
            ShareCaptureMode.APPEND_TO_ACTIVE,
            PendingSharePolicy.toPendingShare(request, ShareCaptureMode.APPEND_TO_ACTIVE).captureMode
        )
        // Default stays NEW_NOTE for backward compatibility.
        assertEquals(
            ShareCaptureMode.NEW_NOTE,
            PendingSharePolicy.toPendingShare(request).captureMode
        )
        val pending = PendingSharePolicy.toPendingShare(request, ShareCaptureMode.APPEND_TO_ACTIVE)
        assertTrue("deferred state carries the mode", pending.captureMode == ShareCaptureMode.APPEND_TO_ACTIVE)
    }

    @Test
    fun `mode token parsing fails closed`() {
        assertEquals(ShareCaptureMode.NEW_NOTE, ShareCaptureMode.fromToken(null))
        assertEquals(ShareCaptureMode.NEW_NOTE, ShareCaptureMode.fromToken("not_a_mode"))
        assertEquals(ShareCaptureMode.APPEND_TO_ACTIVE, ShareCaptureMode.fromToken("APPEND_TO_ACTIVE"))
        assertEquals(ShareCaptureMode.NEW_NOTE, ShareCaptureMode.fromToken("NEW_NOTE"))
    }

    // ---- per-session expiry of the un-confirmed hold ------------------------

    @Test
    fun `un-confirmed holds expire after the session window`() {
        val now = 1_000_000L
        assertTrue("an over-window hold is expired",
            PendingSharePolicy.isExpired(stagedAtMs = now - PendingSharePolicy.CONFIRM_HOLD_EXPIRY_MS - 1, nowMs = now))
        assertFalse("a fresh hold is not expired",
            PendingSharePolicy.isExpired(stagedAtMs = now, nowMs = now))
        assertFalse("a hold exactly at the window edge is not yet expired",
            PendingSharePolicy.isExpired(stagedAtMs = now - PendingSharePolicy.CONFIRM_HOLD_EXPIRY_MS, nowMs = now))
        assertFalse("a zero-timestamp (legacy) hold is never 'expired' by the clock",
            PendingSharePolicy.isExpired(stagedAtMs = 0L, nowMs = now))
    }

    @Test
    fun `expired confirms fail closed and never move bytes`() {
        val expired = PendingShareConfirmState(
            clip = textClip,
            uriStrings = listOf("content://p/secret"),
            stagedAtMs = System.currentTimeMillis() - PendingSharePolicy.CONFIRM_HOLD_EXPIRY_MS - 60_000L
        )
        assertTrue(PendingSharePolicy.isExpired(expired.stagedAtMs, System.currentTimeMillis()))
        // Even if a buggy caller confirmed it, the deferred clip still exists
        // and must still flow ONLY through the authenticated apply gate.
        val pending = PendingSharePolicy.toPendingShare(expired, ShareCaptureMode.NEW_NOTE)
        assertFalse("a deferred clip never auto-applies while locked",
            PendingSharePolicy.deferredAppliesNow(authenticated = false))
        assertEquals(listOf("content://p/secret"), pending.rawUris)
    }

    // ---- honest defer-vs-drop posture ---------------------------------------

    @Test
    fun `confirmed clip applies only on an authenticated frame`() {
        assertTrue(PendingSharePolicy.deferredAppliesNow(authenticated = true))
        assertFalse(PendingSharePolicy.deferredAppliesNow(authenticated = false))
    }

    @Test
    fun `lock still clears both states for a password vault (fail-closed)`() {
        assertTrue("password vault clears on lock", PendingSharePolicy.clearOnLock(hasMasterPassword = true))
        assertFalse("passwordless vault keeps the deferral", PendingSharePolicy.clearOnLock(hasMasterPassword = false))
    }

    // ---- append vs new-note resolution --------------------------------------

    @Test
    fun `append resolves to append only for text with an active note`() {
        assertEquals(AppendResolution.APPEND_TO_ACTIVE, PendingSharePolicy.resolveAppendTarget(
            hasActivePage = true, clipHasImages = false, mode = ShareCaptureMode.APPEND_TO_ACTIVE))
    }

    @Test
    fun `append degrades honestly to a new note without an active note`() {
        assertEquals(AppendResolution.CREATE_NEW_NOTE, PendingSharePolicy.resolveAppendTarget(
            hasActivePage = false, clipHasImages = false, mode = ShareCaptureMode.APPEND_TO_ACTIVE))
    }

    @Test
    fun `append degrades honestly to a new note for image clips`() {
        assertEquals(AppendResolution.CREATE_NEW_NOTE, PendingSharePolicy.resolveAppendTarget(
            hasActivePage = true, clipHasImages = true, mode = ShareCaptureMode.APPEND_TO_ACTIVE))
    }

    @Test
    fun `new-note mode always creates a new note`() {
        for (hasActive in listOf(true, false)) {
            for (hasImages in listOf(true, false)) {
                assertEquals(AppendResolution.CREATE_NEW_NOTE, PendingSharePolicy.resolveAppendTarget(
                    hasActivePage = hasActive, clipHasImages = hasImages, mode = ShareCaptureMode.NEW_NOTE))
            }
        }
    }

    // ---- non-secret captured marker -----------------------------------------

    @Test
    fun `captured marker carries the stamp but never clip content`() {
        val marker: CapturedMarker = PendingSharePolicy.capturedMarkerPayload(stagedAtMs = 7_321L)
        assertTrue(marker.pending)
        assertEquals(7_321L, marker.stagedAtMs)
        // The marker API surface must never accept a text clip — the forcing is
        // structural: the payload type has no content field at all, and its
        // toString only ever shows the flag + stamp.
        assertTrue(marker.toString().contains("pending=true"))
        assertFalse(
            "no clip content may leak into the marker string",
            marker.toString().contains("clip") || marker.toString().contains("text")
        )
    }

    @Test
    fun `marker pref keys are stable and distinct from any content store`() {
        assertEquals("captured_share_pending", PendingSharePolicy.CAPTURED_MARKER_KEY)
        assertEquals("captured_share_pending_at_ms", PendingSharePolicy.CAPTURED_MARKER_AT_MS_KEY)
        assertTrue(
            "marker keys must never look like a content/plaintext store",
            listOf(PendingSharePolicy.CAPTURED_MARKER_KEY, PendingSharePolicy.CAPTURED_MARKER_AT_MS_KEY)
                .none { it.contains("text") || it.contains("clip_") }
        )
    }
}