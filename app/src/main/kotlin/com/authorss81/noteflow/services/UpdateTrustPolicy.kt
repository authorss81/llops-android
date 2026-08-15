package com.authorss81.noteflow.services

import java.io.File

/**
 * B1-PLAT-7 trust policy for locally-present APK files (pure JVM, unit-testable).
 *
 * The finding (`docs/security-report.md` B1-PLAT-7, MEDIUM): `UpdateService`
 * scanned publicly writable directories (`/sdcard/Download`, `/storage/emulated/0/Download`,
 * external files dir) for `.apk` files and, when the signer merely matched the
 * installed app, announced "New update detected in local storage" and drove the
 * platform installer with no warning. Because the release build falls back to the
 * public Android debug key (B1-PLAT-1), an attacker who obtains that key drops a
 * same-signature malicious higher-versionCode APK into Downloads and the app
 * installs it as a legitimate update — watering-hole for full vault compromise.
 *
 * This policy encodes the post-fix rules:
 *  - the app has NO official update channel and NO remote-verified signing key, so
 *    EVERY locally-present APK is [UpdateSourceTrust.UNTRUSTED_LOCAL];
 *  - publicly writable shared-storage directories are off-limits to update scanning —
 *    a file the device received via Downloads/Browser/another app was never vetted;
 *  - an UNTRUSTED file installs ONLY after explicit user confirmation — the install
 *    gate [mayInstall] fails closed by default.
 */
enum class UpdateSourceTrust {
    /** A channel-verified vendor build. No such channel exists in this app today. */
    OFFICIAL,

    /** Any APK present on the device that did not arrive via the official channel. */
    UNTRUSTED_LOCAL
}

object UpdateTrustPolicy {

    /** Whether the app currently ships a remote-verified official update channel. */
    fun hasOfficialChannel(): Boolean = false

    /**
     * Trust classification. Until a key-verified official channel exists, every
     * locally-present APK (however it reached the device) is [UpdateSourceTrust.UNTRUSTED_LOCAL].
     */
    fun classifySource(hasOfficialChannel: Boolean): UpdateSourceTrust =
        if (hasOfficialChannel) UpdateSourceTrust.OFFICIAL else UpdateSourceTrust.UNTRUSTED_LOCAL

    /**
     * True when [dir] is publicly writable shared storage — the B1-PLAT-7 attack
     * surface. Public Downloads, the legacy sdcard mounts, and the external files
     * dirs (primary + secondary volumes, `getExternalFilesDir`) must NEVER be
     * scanned for updates, because ANY storage-writable app or a poisoned browser
     * download can place a file there. App-private dirs ([Dir.cacheDir]/[Dir.filesDir])
     * are not publicly writable and return false. Classification is purely structural
     * on the path — a public mount is public whether or not the directory exists yet.
     */
    fun isPubliclyWritableDirectory(dir: File): Boolean {
        val p = normalize(dir.absolutePath)
        if (p == "/sdcard" || p == "/sdcard/download" ||
            p == "/storage/emulated/0" || p == "/storage/emulated/0/download"
        ) {
            return true
        }
        if (p.startsWith("/sdcard/") || p.startsWith("/storage/emulated/")) return true
        // getExternalFilesDir on any volume lives under /Android/data/<pkg> — the
        // path marker for world-readable external storage.
        if (p.contains("/android/data/")) return true
        return false
    }

    /** A scan candidate directory is only claimable when it is NOT publicly writable. */
    fun isScanSafeDirectory(dir: File): Boolean = !isPubliclyWritableDirectory(dir)

    /**
     * The install gate. [UpdateSourceTrust.OFFICIAL] updates install without further
     * gate; [UpdateSourceTrust.UNTRUSTED_LOCAL] files install ONLY after the user
     * explicitly confirmed, in a strong dialog, that the file is not from a trusted
     * source (fail closed: default `false` refuses).
     */
    fun mayInstall(trust: UpdateSourceTrust, userConfirmedUntrusted: Boolean): Boolean =
        when (trust) {
            UpdateSourceTrust.OFFICIAL -> true
            UpdateSourceTrust.UNTRUSTED_LOCAL -> userConfirmedUntrusted
        }

    fun confirmationTitle(): String = "Untrusted update file"

    fun confirmationMessage(): String =
        "This APK was NOT delivered by the app's official channel and its signer was NOT " +
            "verified against a vendor key. It could have been created by anyone. Only install " +
            "it if you obtained the file personally from a source you trust."

    /**
     * Trust-neutral announcement for a locally-present APK. Deliberately does NOT say
     * "new update" (that wording conditions users to treat files found on the device
     * as official releases — the social-engineering half of B1-PLAT-7).
     */
    fun announcementForLocal(versionName: String?, versionCode: Int?): String {
        val v = versionName ?: "?"
        val c = versionCode?.toString() ?: "?"
        return "Local APK file v$v ($c). Not from the app's official channel — verify the file yourself before installing."
    }

    /** Trust-neutral wording for "this file is not newer". */
    fun staleFileMessage(): String =
        "Selected file is equal to or older than the installed app."

    private fun normalize(path: String): String {
        var p = path.lowercase()
        while (p.endsWith("/")) p = p.dropLast(1)
        // collapse duplicate slashes so /storage//emulated/0/... never escapes the match.
        while (p.contains("//")) p = p.replace("//", "/")
        return p
    }
}