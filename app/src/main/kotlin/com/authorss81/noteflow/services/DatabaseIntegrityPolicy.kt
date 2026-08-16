package com.authorss81.noteflow.services

import com.authorss81.noteflow.utils.ConstantTime

/**
 * B1-CRYPTO-06 (phase-91): fail-closed decision table for the vault tamper
 * baseline verification.
 *
 * Pre-fix, `DatabaseSecurityHelper.verifyDatabaseIntegrity` collapsed every
 * "cannot verify" situation into `true` (verified):
 *  - a MISSING stored checksum ran `updateStoredChecksum(context); return true`
 *    — silently RE-BASELINING the HMAC against whatever SQLCipher file was on
 *    disk, so an attacker who can delete the `db_hmac_checksum` pref gets the
 *    app to bless a possibly-tampered file ("verified"); and
 *  - `computeDatabaseHmac` returning `null` (DB file absent/empty, keystore key
 *    missing, or a stream error) fell through `?: return true`.
 *
 * This policy is the SINGLE three-outcome decision table:
 *  - [Verified]      stored baseline present AND recomputed main+`-wal` HMAC
 *                    matches it — no tamper evidence.
 *  - [Mismatch]      stored baseline present BUT the current bytes do not match —
 *                    a genuine tamper signal (the DB may have been modified
 *                    outside the app since the last trusted arm).
 *  - [CannotVerify]  a stored baseline is MISSING or the current HMAC is
 *                    UN-COMPUTABLE — the vault state is ambiguous and MUST NOT be
 *                    trusted. Nothing is ever re-baselined from a verification;
 *                    the recovery UI surfaces "cannot verify / possibly tampered"
 *                    and the baseline is (re)armed only by the trusted arm sites
 *                    (fresh-vault creation, migration, re-encrypt, backup, and
 *                    the validate-then-arm restore paths).
 *
 * Pure JVM: the comparison funnels through [ConstantTime.hexEqual]
 * (B2-CRYPTO-01 full-length `MessageDigest.isEqual` — never early-exit string
 * equality), the compared tabs are the same fixed-length lowercase hex the
 * helper always writes, and there are no Android calls — API 26+ floor, no
 * fallback needed, fully unit-testable.
 */
sealed interface DatabaseIntegrityVerdict {
    data object Verified : DatabaseIntegrityVerdict
    data object Mismatch : DatabaseIntegrityVerdict
    data object CannotVerify : DatabaseIntegrityVerdict
}

object DatabaseIntegrityPolicy {

    /**
     * The single three-outcome decision. NEVER writes, NEVER re-baselines:
     * a missing [storedChecksum] or an un-computable [currentChecksum]
     * (null from `DatabaseSecurityHelper.computeDatabaseHmac`) is
     * [DatabaseIntegrityVerdict.CannotVerify], not "verified".
     */
    fun verdictFor(storedChecksum: String?, currentChecksum: String?): DatabaseIntegrityVerdict {
        if (storedChecksum == null) return DatabaseIntegrityVerdict.CannotVerify
        if (currentChecksum == null) return DatabaseIntegrityVerdict.CannotVerify
        return if (ConstantTime.hexEqual(storedChecksum, currentChecksum)) {
            DatabaseIntegrityVerdict.Verified
        } else {
            DatabaseIntegrityVerdict.Mismatch
        }
    }

    fun isVerified(verdict: DatabaseIntegrityVerdict): Boolean =
        verdict == DatabaseIntegrityVerdict.Verified

    fun isTamperedOrUnverifiable(verdict: DatabaseIntegrityVerdict): Boolean =
        verdict != DatabaseIntegrityVerdict.Verified

    /**
     * Honest, non-alarming wording for the "cannot verify" state surfaced by
     * the recovery banner. Distinct from the genuine-tamper message: the vault
     * is NOT locked and NOT proven compromised — it just could not be verified.
     */
    const val CANNOT_VERIFY_NOTICE: String =
        "Vault integrity could not be verified: the checksum baseline is missing or " +
            "unreadable, so tamper detection could not run. Your vault is not locked. " +
            "Restore from a trusted backup to re-establish the baseline, or dismiss for " +
            "this session."
}
