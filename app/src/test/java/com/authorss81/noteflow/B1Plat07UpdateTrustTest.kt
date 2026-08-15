package com.authorss81.noteflow

import com.authorss81.noteflow.services.UpdateSourceTrust
import com.authorss81.noteflow.services.UpdateTrustPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-7 (phase-61): UpdateService can no longer treat a locally-present APK as a
 * trusted update, and it never scans publicly writable storage.
 *
 * The finding (MEDIUM, `docs/security-report.md`): `UpdateService.checkForDownloadedUpdates`
 * scanned /sdcard/Download, /storage/emulated/0/Download AND the external files dir, and
 * when the signer merely matched the installed app announced "New update detected in local
 * storage" before handing the platform installer the file. Because the release build falls
 * back to the public Android debug key (B1-PLAT-1), a same-signature malicious APK dropped
 * into Downloads installs with no warning — a one-step watering hole, plus the social
 * conditioning that "updates found on the device are official".
 *
 * Fix (phase-61):
 *  - new pure-JVM [UpdateTrustPolicy] owns the trust model: the app has NO official
 *    channel and NO remote-verified signing key, so [UpdateSourceTrust.UNTRUSTED_LOCAL]
 *    for every local file;
 *  - [UpdateTrustPolicy.isPubliclyWritableDirectory] refuses /sdcard|/storage/emulated
 *    downloads + getExternalFilesDir paths structurally — [UpdateService] now scans ONLY
 *    app-private filesDir/cacheDir through that filter;
 *  - [UpdateTrustPolicy.mayInstall] gates every UNTRUSTED install behind explicit user
 *    confirmation (fail closed) — `installApk` refuses without it;
 *  - announcement copy is trust-neutral (never "New update detected").
 *
 * Behavior is pure JVM (the decision table); the UpdateService/Dialog wiring is pinned at
 * source level, same technique as B1Plat03ExportConsentTest / B1Plat04AutoLockTest.
 */
class B1Plat07UpdateTrustTest {

    // ---------- UpdateTrustPolicy behavior (pure JVM) ----------

    @Test
    fun `no local APK is ever official - only a remote key-verified channel would be`() {
        assertEquals(UpdateSourceTrust.UNTRUSTED_LOCAL, UpdateTrustPolicy.classifySource(false))
        assertEquals(UpdateSourceTrust.OFFICIAL, UpdateTrustPolicy.classifySource(true))
        assertFalse("the app ships no official update channel", UpdateTrustPolicy.hasOfficialChannel())
    }

    @Test
    fun `public Downloads mounts are never scan-safe`() {
        for (path in listOf(
            "/sdcard/Download",
            "/storage/emulated/0/Download",
            "/sdcard",
            "/storage/emulated/0",
            "/storage/emulated/0/Android/data/com.aistudio.inkflow.app.bkxjrz/files",
            "/storage/emulated/1/Android/data/com.aistudio.inkflow.app.bkxjrz/files",
            "/SDCARD/Download",
            "/storage/emulated/0/DOWNLOADS",
            "/sdcard//Download"
        )) {
            assertTrue(
                "$path must be refused for update scanning (B1-PLAT-7)",
                UpdateTrustPolicy.isPubliclyWritableDirectory(File(path))
            )
            assertFalse("$path must never be scan-safe", UpdateTrustPolicy.isScanSafeDirectory(File(path)))
        }
    }

    @Test
    fun `app-private storage dirs stay scan-safe`() {
        for (path in listOf(
            "/data/user/0/com.aistudio.inkflow.app.bkxjrz/files",
            "/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/apk",
            "/data/user/0/com.aistudio.inkflow.app.bkxjrz/cache",
            "/data/data/com.aistudio.inkflow.app.bkxjrz/files"
        )) {
            assertFalse(
                "$path is app-private and must not be flagged public",
                UpdateTrustPolicy.isPubliclyWritableDirectory(File(path))
            )
            assertTrue("$path must remain scan-safe", UpdateTrustPolicy.isScanSafeDirectory(File(path)))
        }
    }

    @Test
    fun `untrusted install is fail-closed and yields only to explicit confirmation`() {
        assertFalse(
            "an UNTRUSTED file without confirmation must never install",
            UpdateTrustPolicy.mayInstall(UpdateSourceTrust.UNTRUSTED_LOCAL, userConfirmedUntrusted = false)
        )
        assertTrue(
            "an UNTRUSTED file after the strong confirmation may install",
            UpdateTrustPolicy.mayInstall(UpdateSourceTrust.UNTRUSTED_LOCAL, userConfirmedUntrusted = true)
        )
        assertTrue(
            "an OFFICIAL channel build installs unconditionally",
            UpdateTrustPolicy.mayInstall(UpdateSourceTrust.OFFICIAL, userConfirmedUntrusted = false)
        )
    }

    @Test
    fun `a same-signature APK is still UNTRUSTED - signature equality is not provenance`() {
        // B1-PLAT-1 makes the release key the public debug key, so two files sharing a
        // signer proves NOTHING. classifySource must ignore signature quality entirely.
        for (signedByVendor in listOf(true, false)) {
            assertEquals(
                "local presence + no official channel ⇒ UNTRUSTED regardless of signer",
                UpdateSourceTrust.UNTRUSTED_LOCAL,
                UpdateTrustPolicy.classifySource(false)
            )
        }
    }

    @Test
    fun `announcements never condition the user into trusting a local file`() {
        val note = UpdateTrustPolicy.announcementForLocal("1.2.3", 45)
        assertTrue("the announcement must keep the version info", note.contains("Local APK file v1.2.3 (45)"))
        assertTrue("the announcement must flag the missing official channel", note.contains("Not from the app's official channel"))
        assertFalse("it must never say 'New update'", note.contains("New update"))
        assertFalse("it must never say 'detected'", note.contains("detected"))
        val stale = UpdateTrustPolicy.staleFileMessage()
        assertFalse("the stale-file wording must not say 'Downloaded'", stale.contains("Downloaded"))
        assertFalse("the stale-file wording must not imply an update", stale.contains("update", ignoreCase = true))
    }

    // ---------- UpdateService wiring (source pins) ----------

    @Test
    fun `UpdateService never references a publicly writable directory`() {
        val src = codeSourceOnly(File(servicesDir(), "UpdateService.kt"))
        assertFalse("getExternalFilesDir must be gone", src.contains("getExternalFilesDir"))
        assertFalse("the sdcard scan path must be gone", src.contains("sdcard"))
        assertFalse("the emulated-storage scan path must be gone", src.contains("storage/emulated"))
        assertFalse("Environment must not be referenced", src.contains("getExternalStoragePublicDirectory"))
        assertFalse("DIRECTORY_DOWNLOADS must not be referenced", src.contains("DIRECTORY_DOWNLOADS"))
        assertFalse("the old hardcoded public-Download File literals must be gone", src.contains("File(\"/sdcard"))
    }

    @Test
    fun `UpdateService routes every scan candidate through the policy gate`() {
        val src = codeSourceOnly(File(servicesDir(), "UpdateService.kt"))
        assertTrue(
            "candidate dirs must be filtered by the public-dir refusal",
            src.contains("UpdateTrustPolicy.isScanSafeDirectory")
        )
        assertTrue(
            "every local file must be classified through the trust policy",
            src.contains("UpdateTrustPolicy.classifySource(UpdateTrustPolicy.hasOfficialChannel())")
        )
    }

    @Test
    fun `UpdateService announcement copy is trust-neutral`() {
        val src = codeSourceOnly(File(servicesDir(), "UpdateService.kt"))
        assertTrue("release notes must come from the policy", src.contains("UpdateTrustPolicy.announcementForLocal"))
        assertTrue("stale-file wording must come from the policy", src.contains("UpdateTrustPolicy.staleFileMessage"))
        assertFalse(
            "the former conditioning wording must be gone",
            src.contains("New update detected in local storage")
        )
    }

    @Test
    fun `installApk refuses an unconfirmed untrusted file before any byte moves`() {
        val src = codeSourceOnly(File(servicesDir(), "UpdateService.kt"))
        val installBody = src.substringAfter("fun installApk(").substringBefore("private fun isVersionNameNewer")
        assertTrue(
            "install must be gated by mayInstall",
            installBody.contains("UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)")
        )
        assertTrue(
            "the gate must run before any staging I/O",
            installBody.indexOf("UpdateTrustPolicy.mayInstall") < installBody.indexOf("stagedApk")
        )
        assertTrue("a refused install must short-circuit", installBody.contains("return false"))
    }

    // ---------- AppUpdateDialog wiring (source pins) ----------

    @Test
    fun `install is gated behind the strong untrusted confirmation in the dialog`() {
        val src = codeSourceOnly(File(uiDir(), "components/Dialogs.kt"))
        val dialog = src.substringAfter("fun AppUpdateDialog(")
        assertTrue("the dialog must own the confirmation state", dialog.contains("showUntrustedConfirm"))
        assertTrue("the confirmation dialog must render the policy warning", dialog.contains("UpdateTrustPolicy.confirmationMessage()"))
        assertTrue(
            "install must only be invoked with explicit confirmation",
            dialog.contains("userConfirmedUntrusted = true")
        )
        assertTrue("the warning must use the error colour", dialog.contains("MaterialTheme.colorScheme.error"))
        assertFalse(
            "the Downloads-scan wording must be gone",
            dialog.contains("Scan Downloads for APK") || dialog.contains("Checking Downloads")
        )
        assertFalse(
            "the 'Found downloaded update' conditioning must be gone",
            dialog.contains("Found downloaded update")
        )
    }

    // ---------- helpers ----------

    private fun servicesDir(): File {
        val dir = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services")
        assertTrue("services dir must exist", dir.isDirectory)
        return dir
    }

    private fun uiDir(): File {
        val dir = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui")
        assertTrue("ui dir must exist", dir.isDirectory)
        return dir
    }

    /** Production source with comment lines stripped so prose no longer counts as a hit. */
    private fun codeSourceOnly(file: File): String {
        assertTrue("${file.name} must exist", file.isFile)
        return file.readLines()
            .filter { line ->
                val trimmed = line.trimStart()
                !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") &&
                    !trimmed.startsWith("*/")
            }
            .joinToString("\n")
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}