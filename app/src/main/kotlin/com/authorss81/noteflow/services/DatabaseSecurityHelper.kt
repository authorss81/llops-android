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
    private const val PREF_CORRUPTION_DETECTED = "corruption_detected"
    private const val PREF_CORRUPTION_TIMESTAMP = "corruption_timestamp"
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
        if (key == null || !dbFile.exists()) return null
        // B1-CRYPTO-06 review (phase-91): a 0-length MAIN file is NOT unverifiable when
        // its `-wal` companion still holds committed frames (a WAL-resident vault, e.g.
        // one whose main file was truncated/replaced). The policy hashes main + `-wal`,
        // so hash what is real. Return null only when there is genuinely nothing to
        // authenticate (empty main AND no/empty wal) — that is `CannotVerify`, never a
        // silent re-baseline.
        val walFile = DatabaseHmacPolicy.walFile(dbFile)
        if (dbFile.length() == 0L && !(walFile.isFile && walFile.length() > 0L)) return null
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            // B1-DB-6 (phase-87): the vault runs WRITE_AHEAD_LOGGING, so committed
            // bytes may live in `noteflow.sqlite-wal` rather than the main file; the
            // pre-fix loop streamed only the main file, so a WAL-only mutation
            // between checkpoints evaded the baseline. Route through the policy
            // which streams main + `-wal` so the WAL frames are authenticated too.
            if (DatabaseHmacPolicy.streamDbAndWal(mac, dbFile) == 0L) return null
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

    /** B1-CRYPTO-06 (phase-91): read-only accessor — a usable baseline exists. */
    fun hasStoredChecksum(context: Context): Boolean =
        !context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DB_CHECKSUM, null)
            .isNullOrEmpty()

    /**
     * H2 (phase-09): records that the SQLCipher vault failed to open with a
     * corrupt/wrong-key exception. The offending files are ALWAYS quarantined
     * (renamed to *.corrupt-<timestamp>, never deleted) and this flag drives a
     * dedicated recovery screen so the vault is only re-created when the user
     * explicitly chooses to start fresh.
     */
    fun setCorruptionDetected(context: Context, timestampMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_CORRUPTION_DETECTED, true)
            .putLong(PREF_CORRUPTION_TIMESTAMP, timestampMs)
            .apply()
    }

    fun hasCorruptionDetected(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_CORRUPTION_DETECTED, false)

    fun getCorruptionTimestamp(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_CORRUPTION_TIMESTAMP, 0L)

    fun clearCorruptionDetected(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_CORRUPTION_DETECTED, false)
            .remove(PREF_CORRUPTION_TIMESTAMP)
            .apply()
    }

    /**
     * B1-CRYPTO-06 (phase-91): FAIL-CLOSED tamper verification.
     *
     * Returns a [DatabaseIntegrityVerdict] — NEVER a bare boolean. A MISSING
     * stored checksum or an UN-COMPUTABLE current HMAC (DB file absent/empty,
     * keystore key missing, or a stream error) yields
     * [DatabaseIntegrityVerdict.CannotVerify] — "cannot verify / possibly
     * tampered" — which the recovery banner surfaces instead of trusting the
     * vault.
     *
     * The pre-fix path reported `true` in both cases AND silently re-baselined:
     * `stored == null` ran `updateStoredChecksum(context); return true`
     * (arming the baseline against whatever file was on disk, so an attacker
     * who can delete the `db_hmac_checksum` pref gets the app to bless a
     * possibly-tampered file as "verified") and the `computeDatabaseHmac() ?:
     * return true` collapse trusted an unverifiable state. Both are gone: this
     * function NEVER writes and NEVER re-baselines. The checksum pref is
     * write-only through [updateStoredChecksum] / [rearmBaselineFromFile], and
     * the only (re)armers are the trusted sites (fresh-vault creation,
     * migration, re-encrypt, backup, and the validate-then-arm restore paths) —
     * never a live-file verification.
     */
    fun verifyDatabaseIntegrity(context: Context): DatabaseIntegrityVerdict {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_DB_CHECKSUM, null)
        val current = computeDatabaseHmac(context, getOrCreateHmacKey())
        return DatabaseIntegrityPolicy.verdictFor(stored, current)
    }
}
