package com.authorss81.noteflow.services

import java.util.Base64

/**
 * B1-CRYPTO-03 (phase-62): the vault master-password credential — salt, wrapped
 * DEK and format version — as ONE versioned blob.
 *
 * Pre-fix, the salt and the wrapped DEK were persisted as two independent
 * SharedPreferences writes. A process kill exactly between them (low-memory
 * kill, crash, battery pull) could leave e.g. a NEW salt with an OLD/missing
 * wrapper: every subsequent unlock then hit an AEAD tag mismatch permanently,
 * which the phase-09 H2 handler turned into a `*.corrupt-*` quarantine — the
 * entire vault lost from a single unlucky kill, with no settings-level checksum
 * able to detect the half-written pair.
 *
 * This class makes the pair a single VALUE: [serialize] → one blob string,
 * [parse] → credential or null (fail closed on malformed/half values).
 * `SettingsManager.commitMasterPasswordCredential` commits that one value with
 * ONE `commit()` — the SharedPreferences contract whose XML write is an atomic
 * temp-file + rename, so a torn/killed write leaves the PREVIOUS blob intact
 * and a half pair is structurally impossible.
 *
 * Version 1 format: `MPB1|<standard-base64 salt>|<wrappedDek base64>` — single
 * line, no `|` ever appears inside either Base64 payload, so `|` can delimit.
 * The wrapped DEK is produced by `EncryptionService.encrypt` (already Base64).
 *
 * Pure JVM (`java.util.Base64` only — no `android.util.Base64`, which returns
 * defaults under the repo's `isReturnDefaultValues` unit-test config) so the
 * serialization and its fail-closed parsing are unit-testable in `app/src/test`.
 */
class MasterPasswordCredential private constructor(
    val formatVersion: String,
    val saltBase64: String,
    val wrappedDek: String
) {

    /** Complete (usable) only when BOTH halves are present and non-blank. */
    fun isComplete(): Boolean {
        if (formatVersion != FORMAT_VERSION) return false
        if (saltBase64.isBlank() || wrappedDek.isBlank()) return false
        return true
    }

    /** Decoded salt bytes — the exact byte array the KEK was derived from at write time. */
    fun saltBytes(): ByteArray = Base64.getDecoder().decode(saltBase64)

    companion object {
        /** v1 blob. A credential written today carries this format marker. */
        const val FORMAT_VERSION = "MPB1"

        private const val SEP = "|"

        /** Serializes salt + wrappedDek + format into the single versioned blob. */
        fun serialize(salt: ByteArray, wrappedDek: String): String {
            val saltB64 = Base64.getEncoder().encodeToString(salt)
            return listOf(FORMAT_VERSION, saltB64, wrappedDek).joinToString(SEP)
        }

        /**
         * Parses a stored blob; null for absent, malformed or half-written
         * values. Half states (a salt with no wrapper, a blank wrapper, an
         * unknown format) are REFUSED: a value that cannot represent a complete
         * credential is never surfaced as one.
         */
        fun parse(blob: String?): MasterPasswordCredential? {
            if (blob == null) return null
            val parts = blob.split(SEP, limit = 3)
            if (parts.size != 3) return null
            val candidate = MasterPasswordCredential(parts[0], parts[1], parts[2])
            return candidate.validated()
        }

        /**
         * Legacy reader for the pre-phase-62 two-key pair
         * (`master_password_salt` + `master_password_wrapped_dek`). Lets vaults
         * created before this fix keep unlocking until the next
         * set/change-master-password migrates them to the blob (in the same
         * atomic commit). Both halves must be present and well-formed; anything
         * else resolves to null (fail closed).
         */
        fun fromLegacy(saltBase64: String?, wrappedDek: String?): MasterPasswordCredential? {
            if (saltBase64 == null || wrappedDek == null) return null
            val candidate = MasterPasswordCredential(FORMAT_VERSION, saltBase64, wrappedDek)
            return candidate.validated()
        }

        private fun MasterPasswordCredential.validated(): MasterPasswordCredential? {
            if (!isComplete()) return null
            // Reject a present-but-undecodable salt (garbage Base64) as unusable.
            return try {
                saltBytes()
                this
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}