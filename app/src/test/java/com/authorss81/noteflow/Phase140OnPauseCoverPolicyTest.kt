package com.authorss81.noteflow

import com.authorss81.noteflow.services.OnPauseCoverPolicy
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1A-03 (phase-140) — ON_PAUSE opaque-cover policy: decision table + wiring
 * source pins.
 *
 * The finding: decrypted content stays on screen across ON_PAUSE-only covers
 * (a `SYSTEM_ALERT_WINDOW` overlay, OEM in-call UI, PIP, translucent anti-theft
 * app); lock() only fired on ON_STOP, SCREEN_OFF, or foreground-idle, and
 * ON_PAUSE only scrubbed the clipboard. Locking on ON_PAUSE was rejected in
 * phase-60 because it breaks SAF pickers / biometric prompts / share sheets, so
 * this phase covers the vault with an OPAQUE full-screen surface that goes up
 * on ON_PAUSE and is dismissed on ANY resume.
 */
class Phase140OnPauseCoverPolicyTest {

    // ---- decision table -----------------------------------------------------

    @Test
    fun `cover is raised only for an authenticated has-master-password vault`() {
        // Passwordless vault: no lock boundary exists (B1-AUTH-02 skipped it).
        assertFalse(
            "passwordless + authenticated must NOT cover (no lock boundary)",
            OnPauseCoverPolicy.shouldCoverOnPause(hasMasterPassword = false, authenticated = true)
        )
        assertFalse(
            "passwordless + not-authenticated must NOT cover",
            OnPauseCoverPolicy.shouldCoverOnPause(hasMasterPassword = false, authenticated = false)
        )
        // Master-password vault that is ALREADY locked: nothing decrypted is up.
        assertFalse(
            "locked master-password vault must NOT cover",
            OnPauseCoverPolicy.shouldCoverOnPause(hasMasterPassword = true, authenticated = false)
        )
        // The covered state: unlocked master-password vault hitting ON_PAUSE.
        assertTrue(
            "unlocked master-password vault on ON_PAUSE MUST cover",
            OnPauseCoverPolicy.shouldCoverOnPause(hasMasterPassword = true, authenticated = true)
        )
    }

    @Test
    fun `every legitimate resume dismisses the cover`() {
        assertTrue(
            "a resume while covered MUST dismiss (picker / biometric / share-sheet return)",
            OnPauseCoverPolicy.shouldDismissOnResume(coverActive = true)
        )
        assertFalse(
            "resume with no cover is a no-op",
            OnPauseCoverPolicy.shouldDismissOnResume(coverActive = false)
        )
    }

    // ---- wiring source pins -------------------------------------------------

    @Test
    fun `ON_PAUSE raises the cover and ON_RESUME clears it`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"
        ).readText()

        val observer = source.substringAfter("lifecycle.addObserver")
            .substringBefore("// B1-PLAT-4: runtime screen-off hook")
        assertTrue(
            "ON_PAUSE must consult the cover decision table",
            observer.contains("OnPauseCoverPolicy.shouldCoverOnPause(")
        )
        assertTrue(
            "ON_PAUSE must raise pauseCoverActive",
            observer.contains("pauseCoverActive = true")
        )
        assertTrue(
            "the share-confirm dialog (attacker preview text) must be dismissed on pause",
            observer.contains("viewModel.cancelPendingShareConfirm()")
        )
        assertTrue(
            "the command palette (decrypted note-title list window) must close on pause",
            observer.contains("showCommandPalette = false")
        )
        assertTrue(
            "ON_RESUME must consult shouldDismissOnResume",
            observer.contains("OnPauseCoverPolicy.shouldDismissOnResume(")
        )
        assertTrue(
            "ON_RESUME must clear the cover",
            observer.contains("pauseCoverActive = false")
        )
    }

    @Test
    fun `composition renders an opaque full-screen cover gated by the policy`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"
        ).readText()
        val cover = source.substringAfter("// R2-B1A-03 (phase-140): opaque cover over the WHOLE content")
            .substringBefore("// Phase 38: global Command Palette HUD")
        assertTrue(
            "the cover must be an opaque fillMaxSize Surface",
            cover.contains("androidx.compose.material3.Surface(") &&
                cover.contains("Modifier.fillMaxSize()")
        )
        assertTrue(
            "the cover must be gated on the policy + pause state",
            cover.contains("pauseCoverActive &&") &&
                cover.contains("OnPauseCoverPolicy.shouldCoverOnPause(")
        )
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}