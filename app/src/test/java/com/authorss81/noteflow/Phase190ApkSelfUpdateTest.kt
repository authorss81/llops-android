package com.authorss81.noteflow

import com.authorss81.noteflow.services.UpdateApkDecisionPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 190 (2026-08-20): "if I upload an APK of the same app, it should update
 * the app." Two layers are pinned here:
 *
 * 1. REAL pure-JVM behavior of [UpdateApkDecisionPolicy] — the non-trust
 *    decision table (identity, Long version compare, versionName compare, APK
 *    stream classification, honest refusal copy) that was NOT unit-testable
 *    before because it lived inline in `UpdateService`.
 * 2. SOURCE WIRING pins (the repo's B1Plat07 style) proving the app actually
 *    routes through the policy: no `verifyApkSignature` leftover, package gate
 *    before the signature gate, install-time (TOCTOU) re-verify, the streaming
 *    picker, and the share-sheet APK interception that never reaches the note
 *    clip path. B1-PLAT-7 trust semantics are untouched by design.
 */
class Phase190ApkSelfUpdateTest {

    //
    // ---- 1. real decision-policy behavior -------------------------------------
    //

    @Test
    fun `samePackage requires an exact runtime-package match and fails closed on null`() {
        val installed = "com.aistudio.inkflow.app.bkxjrz"
        assertTrue(UpdateApkDecisionPolicy.samePackage(installed, installed))
        assertFalse(UpdateApkDecisionPolicy.samePackage("com.authorss81.noteflow", installed))
        assertFalse(UpdateApkDecisionPolicy.samePackage("com.other.app", installed))
        assertFalse(UpdateApkDecisionPolicy.samePackage(null, installed))
        assertFalse(UpdateApkDecisionPolicy.samePackage("", installed))
    }

    @Test
    fun `versionCodeNewer is a Long compare and survives the old Int wrap`() {
        // The pre-phase-190 `longVersionCode.toInt()` wrapped >2^31-1 codes to a
        // negative number, classifying a HELD-BACK genuinely-newer APK as "older".
        val current = 2_000_000_000L
        assertFalse("equal codes are not newer", UpdateApkDecisionPolicy.versionCodeNewer(current, current))
        assertTrue("+1 is newer", UpdateApkDecisionPolicy.versionCodeNewer(current + 1, current))
        assertFalse("older is not newer", UpdateApkDecisionPolicy.versionCodeNewer(current - 1, current))
        assertTrue(
            "a code above Int.MAX_VALUE must still be newer (the phase-190 Long fix)",
            UpdateApkDecisionPolicy.versionCodeNewer(current + Int.MAX_VALUE.toLong() * 2, current)
        )
        assertFalse(
            "a wrapped-negative reading must never be newer",
            UpdateApkDecisionPolicy.versionCodeNewer(-50_000_000L, current)
        )
    }

    @Test
    fun `versionNameNewer is digit-led and never lets junk claim newer`() {
        assertFalse("equal names are not newer", UpdateApkDecisionPolicy.versionNameNewer("1.2.3", "1.2.3"))
        assertFalse(
            "padded equal (1.0 vs 1.0.0) is equal, not newer",
            UpdateApkDecisionPolicy.versionNameNewer("1.0", "1.0.0")
        )
        assertTrue("1.0.1 is newer than 1.0.0", UpdateApkDecisionPolicy.versionNameNewer("1.0.1", "1.0.0"))
        assertFalse("1.0 is not newer than 1.0.1", UpdateApkDecisionPolicy.versionNameNewer("1.0", "1.0.1"))
        assertFalse(
            "non-numeric segments count as 0 and cannot claim newer",
            UpdateApkDecisionPolicy.versionNameNewer("beta", "1.0.0")
        )
        assertFalse(
            "a release candidate of the SAME code must not win on name when it is not newer",
            UpdateApkDecisionPolicy.versionNameNewer("2.0.0-rc1", "2.0.0")
        )
    }

    @Test
    fun `isNewer treats code as primary and name as tie-breaker`() {
        assertTrue(
            "code-newer wins even with an equal name",
            UpdateApkDecisionPolicy.isNewer(101L, 100L, "1.0", "1.0")
        )
        assertTrue(
            "name-newer with equal code",
            UpdateApkDecisionPolicy.isNewer(100L, 100L, "1.1", "1.0")
        )
        assertFalse(
            "older code AND older name are not newer",
            UpdateApkDecisionPolicy.isNewer(99L, 100L, "0.9", "1.0")
        )
        assertFalse(
            "equal code and equal name are not newer",
            UpdateApkDecisionPolicy.isNewer(100L, 100L, "1.0", "1.0")
        )
        assertFalse(
            "a LOWER code can never be overridden by a 'newer' name",
            UpdateApkDecisionPolicy.isNewer(99L, 100L, "1.9", "1.0")
        )
        assertFalse(
            "a LOWER code with a padded-equal name is still stale",
            UpdateApkDecisionPolicy.isNewer(99L, 100L, "1.0", "1.0.0")
        )
    }

    @Test
    fun `isApkStream accepts the exact package MIME or an apk file name`() {
        assertTrue(UpdateApkDecisionPolicy.isApkStream("application/vnd.android.package-archive", "update.apk"))
        // MIME is often flattened to octet-stream on shares -> the filename fallback.
        assertTrue(UpdateApkDecisionPolicy.isApkStream("application/octet-stream", "InkFlow-v2.4.1.apk"))
        assertTrue(UpdateApkDecisionPolicy.isApkStream("application/octet-stream", "UPDATE.APK"))
        assertFalse("neither MIME nor apk name", UpdateApkDecisionPolicy.isApkStream("image/png", "photo.png"))
        assertFalse("no MIME and no apk name", UpdateApkDecisionPolicy.isApkStream(null, "notes.md"))
        assertFalse("no MIME and null name", UpdateApkDecisionPolicy.isApkStream(null, null))
    }

    @Test
    fun `refusal copy is honest and trust-neutral`() {
        val different = UpdateApkDecisionPolicy.differentAppMessage()
        assertTrue(different.contains("not the same app"))
        assertFalse("never 'new update detected' conditioning", different.contains("update detected"))

        val signature = UpdateApkDecisionPolicy.signatureMismatchMessage()
        assertTrue(signature.contains("Signature"))
        assertFalse(signature.contains("new update detected"))

        val unreadable = UpdateApkDecisionPolicy.unreadableApkMessage()
        assertTrue(unreadable.contains("signature"))
        assertFalse(unreadable.contains("new update detected"))

        assertEquals(
            "single staged file wording",
            "1 APK file(s) received and staged in app storage. Open ⋮ → App Version & Update to review it.",
            UpdateApkDecisionPolicy.apkStagedMessage(1)
        )
        assertTrue(UpdateApkDecisionPolicy.apkStagedMessage(2).startsWith("2 APK file(s)"))
        assertEquals(
            "nothing staged wording",
            "None of the 2 shared APK file(s) could be staged in app storage. Open ⋮ → App Version & Update to retry with the picker.",
            UpdateApkDecisionPolicy.apkStageFailureMessage(0, 2)
        )
        assertTrue(UpdateApkDecisionPolicy.apkStageFailureMessage(1, 2).startsWith("1 of 2 shared APK file(s)"))
    }

    //
    // ---- 2. source wiring pins (B1Plat07 style) --------------------------------
    //

    private val updateServiceSrc by lazy {
        codeSourceOnly(File(servicesDir(), "UpdateService.kt"))
    }

    private val importExportSrc by lazy {
        codeSourceOnly(File(servicesDir(), "ImportExportService.kt"))
    }

    private val mainActivitySrc by lazy {
        codeSourceOnly(File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"))
    }

    private val dialogsSrc by lazy {
        codeSourceOnly(File(uiDir(), "components/Dialogs.kt"))
    }

    @Test
    fun `inspectApkFile gates on the package identity BEFORE the signer check`() {
        val inspect = updateServiceSrc.substringAfter("fun inspectApkFile(").substringBefore("fun checkForDownloadedUpdates")
        assertTrue(
            "offer-time must consult the same-package policy",
            inspect.contains("UpdateApkDecisionPolicy.samePackage(archiveInfo.packageName, context.packageName)")
        )
        assertTrue(
            "the signer verify must still run after the package gate",
            inspect.contains("verifyApkIdentity(context, apkFile)")
        )
        assertTrue(
            "the package gate must precede the signer gate (order of definition in the source)",
            inspect.indexOf("UpdateApkDecisionPolicy.samePackage") < inspect.indexOf("verifyApkIdentity")
        )
        assertTrue(
            "the refusal must carry the honest different-app copy",
            inspect.contains("UpdateApkDecisionPolicy.differentAppMessage()")
        )
        assertTrue(
            "the refusal must carry the honest signature copy",
            inspect.contains("UpdateApkDecisionPolicy.signatureMismatchMessage()")
        )
        assertTrue(
            "an unreadable/unsigned APK must refuse with its own honest copy, not a signer mismatch",
            inspect.contains("UpdateApkDecisionPolicy.unreadableApkMessage()")
        )
        assertFalse(
            "the un-gated pre-phase-190 signature helper must be gone from main source",
            updateServiceSrc.contains("verifyApkSignature")
        )
    }

    @Test
    fun `the version compare flows through the policy in Long - no Int wrap at inspect time`() {
        val currentCodeSrc = updateServiceSrc.substringAfter("fun getCurrentVersionCode(").substringBefore("fun inspectApkFile(")
        assertFalse(
            "the installed current code must never be truncated to Int (the pre-190 wrap)",
            currentCodeSrc.contains("longVersionCode.toInt()")
        )
        val inspect = updateServiceSrc.substringAfter("fun inspectApkFile(").substringBefore("fun checkForDownloadedUpdates")
        assertTrue(
            "versionCode must be read as Long on API 28+",
            inspect.contains("archiveInfo.longVersionCode")
        )
        assertTrue(
            "the decision must delegate to the Long-based policy",
            inspect.contains("UpdateApkDecisionPolicy.isNewer(")
        )
        assertFalse(
            "the pre-P Int read that truncated Long codes to Int must be gone",
            inspect.contains("archiveInfo.versionCode.toInt()")
        )
    }

    @Test
    fun `installApk re-verifies package + signer at install time`() {
        val install = updateServiceSrc.substringAfter("fun installApk(").substringBefore("private fun isVersionNameNewer")
        assertTrue(
            "B1-PLAT-7 mayInstall gate must still run first",
            install.indexOf("UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)") >= 0
        )
        assertTrue(
            "install-time re-verify must exist (TOCTOU)",
            install.contains("verifyApkIdentity(context, apkFile)")
        )
        assertTrue(
            "install must fail closed unless identity FULLY matches (package + signer)",
            install.contains("ApkIdentityResult.Match")
        )
        val unified = updateServiceSrc.substringAfter("private fun verifyApkIdentity")
        assertTrue(
            "the unified check must assert the package too, not only signers",
            unified.contains("UpdateApkDecisionPolicy.samePackage(apkInfo.packageName, context.packageName)")
        )
    }

    @Test
    fun `the picker streams into app-private storage instead of the heap`() {
        val dialog = dialogsSrc.substringAfter("fun AppUpdateDialog(")
        assertTrue(
            "the picker must stage via the streaming helper",
            dialog.contains("ImportExportService.stageApkUriToFile(context, uri)")
        )
        assertFalse(
            "the in-heap readUriBytes picker path must be gone",
            dialog.substringAfter("apkPickerLauncher", "").contains("readUriBytes")
        )
        assertFalse(
            "no secondary whole-file heap copy via writeBytes",
            dialog.contains("file.writeBytes(bytes)")
        )
        assertTrue(
            "a refusal surfaces the honest releaseNotes copy instead of 'equal to or older'",
            dialog.contains("inspected.releaseNotes")
        )
    }

    @Test
    fun `the app-storage scan runs once on dialog open`() {
        val dialog = dialogsSrc.substringAfter("fun AppUpdateDialog(")
        assertTrue(
            "opening the dialog must perform one private-storage scan",
            dialog.contains("UpdateService.checkForDownloadedUpdates(context)")
        )
        assertFalse(
            "the public Downloads scan wording must stay gone",
            dialog.contains("Scan Downloads for APK")
        )
    }

    @Test
    fun `MainActivity intercepts APK shares before the note-clip path`() {
        val region = mainActivitySrc.substringAfter("private fun readShareIntent").substringBefore("private fun displayNameOf")
        assertTrue(
            "shared streams must be classified through the policy",
            region.contains("UpdateApkDecisionPolicy.isApkStream(mime, name)")
        )
        assertTrue(
            "an all-APK share must be staged via the streaming helper",
            region.contains("ImportExportService.stageApkUriToFile(this@MainActivity, uri)")
        )
        assertTrue(
            "the staged success must surface a non-secret snackbar",
            region.contains("UpdateApkDecisionPolicy.apkStagedMessage(staged)")
        )
        assertTrue(
            "a single failed stream must be caught so the staging loop never crashes the app",
            region.contains("catch (e: Exception)")
        )
        assertTrue(
            "a partial (or fully failed) staging batch must be surfaced honestly, not over-reported",
            region.contains("UpdateApkDecisionPolicy.apkStageFailureMessage(staged, received)")
        )
        assertTrue(
            "the interception must happen before a note clip is ever built",
            region.indexOf("UpdateApkDecisionPolicy.isApkStream") < region.indexOf("SharedInput(")
        )
        assertTrue(
            "the confirmed clip path must still exist for non-APK shares",
            region.contains("viewModel.stagePendingShare")
        )
    }

    @Test
    fun `ImportExportService stages APK bytes with a bounded stream`() {
        assertTrue(
            "the streaming staging helper must exist",
            importExportSrc.contains("fun stageApkUriToFile(")
        )
        assertTrue(
            "it must stream through a bounded buffer, never byte-array-in-heap",
            importExportSrc.contains("val buffer = ByteArray(64 * 1024)")
        )
        assertTrue(
            "an over-budget input must fail loudly",
            importExportSrc.contains("APK file too large (max ")
        )
        assertTrue(
            "the staging cap constant must be defined",
            importExportSrc.contains("MAX_APK_INPUT_BYTES")
        )
        assertTrue(
            "staged file names must be collision-free so a multi-APK share can never overwrite itself",
            importExportSrc.contains("UUID.randomUUID()")
        )
    }

    //
    // ---- helpers ----------------------------------------------------------------
    //

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