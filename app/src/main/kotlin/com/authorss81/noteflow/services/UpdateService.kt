package com.authorss81.noteflow.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.authorss81.noteflow.BuildConfig
import java.io.File

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersionName: String,
    val currentVersionCode: Int,
    val newVersionName: String?,
    val newVersionCode: Int?,
    val apkFile: File?,
    val releaseNotes: String?
)

object UpdateService {

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    @Suppress("DEPRECATION")
    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                pInfo.versionCode
            }
        } catch (e: Exception) {
            BuildConfig.VERSION_CODE
        }
    }

    /**
     * Inspects a local APK file and compares its versionCode and versionName against the current app.
     */
    @Suppress("DEPRECATION")
    fun inspectApkFile(context: Context, apkFile: File): UpdateInfo? {
        if (!apkFile.exists() || !apkFile.name.endsWith(".apk", ignoreCase = true)) {
            return null
        }

        return try {
            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            
            val currentCode = getCurrentVersionCode(context)
            val currentName = getCurrentVersionName(context)

            val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode.toInt()
            } else {
                archiveInfo.versionCode
            }
            val apkVersionName = archiveInfo.versionName ?: "Unknown"

            // Signature Check
            if (!verifyApkSignature(context, apkFile)) {
                return UpdateInfo(
                    hasUpdate = false,
                    currentVersionName = currentName,
                    currentVersionCode = currentCode,
                    newVersionName = apkVersionName,
                    newVersionCode = apkVersionCode,
                    apkFile = null,
                    releaseNotes = "Signature mismatch! Update file is untrusted and will be ignored."
                )
            }

            val isNewer = apkVersionCode > currentCode || isVersionNameNewer(apkVersionName, currentName)

            UpdateInfo(
                hasUpdate = isNewer,
                currentVersionName = currentName,
                currentVersionCode = currentCode,
                newVersionName = apkVersionName,
                newVersionCode = apkVersionCode,
                apkFile = apkFile,
                releaseNotes = if (isNewer) "New update detected in local storage: $apkVersionName ($apkVersionCode)" else "Downloaded file is current or older."
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans typical download locations for downloaded updates.
     */
    fun checkForDownloadedUpdates(context: Context): UpdateInfo {
        val currentName = getCurrentVersionName(context)
        val currentCode = getCurrentVersionCode(context)

        val candidateDirs = listOfNotNull(
            context.getExternalFilesDir(null),
            context.cacheDir,
            context.filesDir,
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download")
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                val apkFiles = dir.listFiles { _, name -> name.lowercase().endsWith(".apk") } ?: emptyArray()
                for (apk in apkFiles) {
                    val info = inspectApkFile(context, apk)
                    if (info != null && info.hasUpdate) {
                        return info
                    }
                }
            }
        }

        return UpdateInfo(
            hasUpdate = false,
            currentVersionName = currentName,
            currentVersionCode = currentCode,
            newVersionName = null,
            newVersionCode = null,
            apkFile = null,
            releaseNotes = null
        )
    }

    /**
     * Helper to install an APK file using FileProvider and ACTION_VIEW Intent.
     *
     * A4/34.6: the APK is staged into filesDir/apk/ (the only filesDir path the
     * FileProvider exposes) before the URI is granted — the provider no longer
     * covers the whole filesDir.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        return try {
            val stagedApk = try {
                val apkDir = File(context.filesDir, "apk").apply { mkdirs() }
                val staged = File(apkDir, apkFile.name)
                if (!staged.exists() || staged.length() != apkFile.length()) {
                    apkFile.copyTo(staged, overwrite = true)
                }
                staged
            } catch (e: Exception) {
                apkFile // fall back to the original path (e.g. external Download dir)
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, stagedApk)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isVersionNameNewer(newVer: String, currentVer: String): Boolean {
        return try {
            val newParts = newVer.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            val currParts = currentVer.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
 
            val maxLen = maxOf(newParts.size, currParts.size)
            for (i in 0 until maxLen) {
                val p1 = newParts.getOrElse(i) { 0 }
                val p2 = currParts.getOrElse(i) { 0 }
                if (p1 > p2) return true
                if (p1 < p2) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            
            // Get signatures of the APK file
            val apkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return false

            val apkSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                apkInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                apkInfo.signatures
            } ?: return false

            // Get signatures of the current app
            val currentInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val currentSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                currentInfo.signatures
            } ?: return false

            // Compare signatures
            if (apkSignatures.size != currentSignatures.size) return false
            
            for (sig in apkSignatures) {
                var found = false
                for (currSig in currentSignatures) {
                    if (sig == currSig) {
                        found = true
                        break
                    }
                }
                if (!found) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
