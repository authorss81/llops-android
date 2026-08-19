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
    // Phase-163: timestamp of the corruption event the user permanently dismissed
    // ("Don't show again"). 0 = none. Keyed to the event, never a bare boolean,
    // so a NEW quarantine stamp always re-shows the screen.
    private const val PREF_CORRUPTION_DISMISSED_TIMESTAMP = "corruption_dismissed_timestamp"
    // Phase-163: identity of the CURRENT keystore-key-lost event (the non-secret
    // wrapper alias of the lost DEK wrapper) plus the timestamp recorded on first
    // detection, and the timestamp the user permanently dismissed. A NEW wrapper
    // alias (a different lost key) always re-shows the screen.
    private const val PREF_KEYSTORE_LOST_EVENT_ALIAS = "keystore_lost_event_alias"
    private const val PREF_KEYSTORE_LOST_EVENT_TIMESTAMP = "keystore_lost_event_timestamp"
    private const val PREF_KEYSTORE_LOST_DISMISSED_TIMESTAMP = "keystore_lost_dismissed_timestamp"
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
        // R2-B1D-01 review (phase-136): commit() (not apply()) so the baseline is
        // on disk BEFORE the caller considers the re-arm done — apply() is async
        // and a hard kill right after lock/app-exit could drop the re-arm and
        // re-introduce the false Mismatch at the next start. Callers are off the
        // hot UI path (vault creation/migration/backup/restore, or the re-arm
        // executor in dispose()).
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DB_CHECKSUM, checksum)
            .commit()
    }

    /**
     * 34.8: re-arms the tamper baseline against a specific DB file (used on the
     * temp copy BEFORE it swaps into the live path, so a failed HMAC never
     * touches live data). Returns false if the file could not be checksummed.
     */
    fun rearmBaselineFromFile(context: Context, dbFile: File): Boolean {
        val checksum = computeDatabaseHmac(context, getOrCreateHmacKey(), dbFile) ?: return false
        // R2-B1D-01 review (phase-136): commit() not apply() — a restored vault's
        // baseline must be durable before the app is restarted (see
        // [updateStoredChecksum]).
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_DB_CHECKSUM, checksum)
            .commit()
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
            // Phase-163: the dismissal is keyed to the (now cleared) event
            // timestamp — drop it with the event so a future NEW corruption can
            // never be suppressed by a stale dismissal.
            .remove(PREF_CORRUPTION_DISMISSED_TIMESTAMP)
            .apply()
    }

    /**
     * Phase-163: the timestamp of the corruption event the user permanently
     * dismissed with "Don't show again" (`RecoveryDismissalPolicy`). Keyed to the
     * event (`PREF_CORRUPTION_TIMESTAMP`), never a bare boolean.
     */
    fun getCorruptionDismissedTimestamp(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_CORRUPTION_DISMISSED_TIMESTAMP, 0L)

    /** Phase-163: persists the permanent dismissal for the CURRENT corruption event. */
    fun setCorruptionDismissedTimestamp(context: Context, timestampMs: Long) {
        // R2-B1D-01-style commit(): the dismissal must outlive a process kill
        // that lands right after the tap — that is the whole point of the fix.
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_CORRUPTION_DISMISSED_TIMESTAMP, timestampMs)
            .commit()
    }

    /**
     * Phase-163: records the CURRENT keystore-key-lost event the FIRST time a
     * given lost key is detected in-process, and returns the event timestamp.
     * A NEW lost key (a different wrapper alias) starts a fresh event, so an old
     * dismissal can never hide it. Alias may be null only for legacy blobs that
     * carry no marker — those still get a distinct timestamp per detection.
     */
    fun recordKeystoreLostEvent(context: Context, alias: String?): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val storedAlias = prefs.getString(PREF_KEYSTORE_LOST_EVENT_ALIAS, null)
        val storedTs = prefs.getLong(PREF_KEYSTORE_LOST_EVENT_TIMESTAMP, 0L)
        if (storedAlias == alias && storedTs > 0L) return storedTs
        val timestamp = System.currentTimeMillis()
        prefs.edit()
            .putString(PREF_KEYSTORE_LOST_EVENT_ALIAS, alias)
            .putLong(PREF_KEYSTORE_LOST_EVENT_TIMESTAMP, timestamp)
            .commit()
        return timestamp
    }

    fun getKeystoreLostEventTimestamp(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_KEYSTORE_LOST_EVENT_TIMESTAMP, 0L)

    fun getKeystoreLostDismissedTimestamp(context: Context): Long =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_KEYSTORE_LOST_DISMISSED_TIMESTAMP, 0L)

    /** Phase-163: persists the permanent dismissal for the CURRENT key-lost event. */
    fun setKeystoreLostDismissedTimestamp(context: Context, timestampMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_KEYSTORE_LOST_DISMISSED_TIMESTAMP, timestampMs)
            .commit()
    }

    /** Phase-163: clears the event + dismissal (successful restore / start-fresh). */
    fun clearKeystoreLostDismissal(context: Context) {
        // R2-B1D-01-style commit(): the restore path calls this immediately before
        // exiting the process — an async apply() could be lost before the exit.
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEYSTORE_LOST_EVENT_ALIAS)
            .remove(PREF_KEYSTORE_LOST_EVENT_TIMESTAMP)
            .remove(PREF_KEYSTORE_LOST_DISMISSED_TIMESTAMP)
            .commit()
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
