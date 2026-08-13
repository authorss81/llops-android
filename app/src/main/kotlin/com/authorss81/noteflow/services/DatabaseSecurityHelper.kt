package com.authorss81.noteflow.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Tamper-evidence checksum for the on-disk SQLite database.
 *
 * Uses HMAC-SHA256 keyed by a non-extractable AndroidKeyStore key, so an
 * attacker who can modify both the database file and SharedPreferences still
 * cannot forge a matching checksum. The HMAC is computed over the raw database
 * file bytes (works whether the file is plaintext or SQLCipher-encrypted).
 */
object DatabaseSecurityHelper {
    private const val PREF_NAME = "noteflow_sec_prefs"
    private const val PREF_DB_CHECKSUM = "db_hmac_checksum"
    private const val PREF_RESTORE_BLOCKED = "restore_hmac_blocked"
    private const val KEY_ALIAS = "noteflow_db_hmac_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DB_NAME = "noteflow.sqlite"

    private fun getOrCreateHmacKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun computeDatabaseHmac(context: Context, key: SecretKey?, dbFile: File = context.getDatabasePath(DB_NAME)): String? {
        if (key == null || !dbFile.exists() || dbFile.length() == 0L) return null
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            dbFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    mac.update(buffer, 0, bytesRead)
                }
            }
            mac.doFinal().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun updateStoredChecksum(context: Context) {
        val checksum = computeDatabaseHmac(context, getOrCreateHmacKey()) ?: return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DB_CHECKSUM, checksum)
            .apply()
    }

    /**
     * 34.8: re-arms the tamper baseline against a specific DB file (used on the
     * temp copy BEFORE it swaps into the live path, so a failed HMAC never
     * touches live data). Returns false if the file could not be checksummed.
     */
    fun rearmBaselineFromFile(context: Context, dbFile: File): Boolean {
        val checksum = computeDatabaseHmac(context, getOrCreateHmacKey(), dbFile) ?: return false
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DB_CHECKSUM, checksum)
            .apply()
        return true
    }

    /** 34.8: hard-block flag — set when a restored DB failed HMAC verification. */
    fun hasRestoreBlock(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_RESTORE_BLOCKED, false)

    fun setRestoreBlock(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RESTORE_BLOCKED, true)
            .apply()
    }

    fun clearRestoreBlock(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RESTORE_BLOCKED, false)
            .apply()
    }

    fun clearStoredChecksum(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_DB_CHECKSUM)
            .apply()
    }

    fun verifyDatabaseIntegrity(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_DB_CHECKSUM, null) ?: run {
            updateStoredChecksum(context)
            return true
        }
        val current = computeDatabaseHmac(context, getOrCreateHmacKey()) ?: return true
        return stored == current
    }
}
