package com.authorss81.noteflow.services

/**
 * Phase 190 (2026-08-20) — pure-JVM decision table for the self-update path.
 *
 * USER REQUIREMENT: "if I upload an APK of the same app, it should update the
 * app." The pre-phase-190 `UpdateService` compared ONLY signer + version, so a
 * same-signer DIFFERENT-package APK was offered as an "update" (the OS then
 * refused it or installed a duplicate app) and there was no honest "not the
 * same app" refusal. This policy owns the NON-trust identity + version
 * decisions so they are unit-testable without a device:
 *
 *  - [samePackage]: the uploaded APK's manifest package MUST equal the
 *    INSTALLED app's runtime `context.packageName`. This is the phase's
 *    package-identity gate. Note the app's runtime applicationId
 *    (`com.aistudio.inkflow.app.bkxjrz`) is NOT the Kotlin namespace
 *    (`com.authorss81.noteflow`) — identity therefore always comes from
 *    `context.packageName`, never a hardcoded string.
 *  - [versionCodeNewer] / [versionNameNewer]: the "is this actually newer?"
 *    compare, done in **Long** (versionCode is a `long` on every API this app
 *    targets; the old `longVersionCode.toInt()` wrap turned a >2^31 code into
 *    a negative number and classified a genuinely-newer APK as stale).
 *  - [isApkStream]: MIME / filename routing for the share-sheet "upload".
 *  - honest, trust-neutral refusal copy ([differentAppMessage],
 *    [signatureMismatchMessage]) — never "new update" conditioning (B1-PLAT-7
 *    social-engineering half).
 *
 * Trust classification stays in [UpdateTrustPolicy]; this policy never
 * decides provenance. A same-package + same-signer + newer APK is still
 * [UpdateSourceTrust.UNTRUSTED_LOCAL] until a remote-verified official channel
 * exists, and its install still fails closed behind the explicit
 * confirmation dialog.
 */
object UpdateApkDecisionPolicy {

    /**
     * The one MIME type Android uses for installed/updatable APK packages.
     * Shared-stream detection + the document-picker filter agree on this.
     */
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * True iff the uploaded APK's manifest package equals the installed app's
     * runtime package name. Fails closed on a null/missing package.
     */
    fun samePackage(apkPackageName: String?, installedPackageName: String): Boolean =
        apkPackageName != null && apkPackageName == installedPackageName

    /** True iff the APK's versionCode is strictly greater (Long compare). */
    fun versionCodeNewer(apkVersionCode: Long, currentVersionCode: Long): Boolean =
        apkVersionCode > currentVersionCode

    /**
     * Digit-led component-wise versionName compare — "1.0" vs "1.0.0" compares
     * equal (not newer), "1.0.1" > "1.0.0". Only the LEADING digit-run of each
     * dot-segment counts, so a pre-release suffix like "2.0.0-rc1" compares
     * equal to "2.0.0" (pre-release < release) instead of leaking the "1" into
     * "101", and malformed/empty segments count as 0 and can never make a
     * non-numeric name claim "newer". VersionCode remains the primary signal;
     * this is the legacy versionName tie-breaker, hardened.
     */
    fun versionNameNewer(newVer: String, currentVer: String): Boolean {
        val newParts = newVer.split(".").map { it.takeWhile { char -> char.isDigit() }.toIntOrNull() ?: 0 }
        val currParts = currentVer.split(".").map { it.takeWhile { char -> char.isDigit() }.toIntOrNull() ?: 0 }
        val maxLen = maxOf(newParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val p1 = newParts.getOrElse(i) { 0 }
            val p2 = currParts.getOrElse(i) { 0 }
            if (p1 > p2) return true
            if (p1 < p2) return false
        }
        return false
    }

    /** The full "is this an update candidate?" decision. VersionCode is primary. */
    fun isNewer(
        apkVersionCode: Long,
        currentVersionCode: Long,
        apkVersionName: String,
        currentVersionName: String
    ): Boolean = versionCodeNewer(apkVersionCode, currentVersionCode) ||
        versionNameNewer(apkVersionName, currentVersionName)

    /**
     * True iff a share-sheet stream is an APK the app should offer as an
     * update candidate (exact MIME, or a file name ending in `.apk`). Routing
     * decision ONLY — see `MainActivity.readShareIntent`.
     */
    fun isApkStream(mimeType: String?, fileName: String?): Boolean {
        if (mimeType?.lowercase() == APK_MIME_TYPE) return true
        return fileName?.lowercase().orEmpty().endsWith(".apk")
    }

    /** Honest refusal copy: the file is a different app, not an update. */
    fun differentAppMessage(): String =
        "This APK is not the same app as the installed one (different package name) and will be ignored."

    /** Honest refusal copy: the signer does not match the installed app. */
    fun signatureMismatchMessage(): String =
        "Signature mismatch! The file does not match the installed app's signer and will be ignored."

    /** Non-alarming snackbar after a share-sheet APK is staged app-privately. */
    fun apkStagedMessage(count: Int): String =
        "$count APK file(s) received and staged in app storage. " +
            "Open ⋮ → App Version & Update to review it."
}