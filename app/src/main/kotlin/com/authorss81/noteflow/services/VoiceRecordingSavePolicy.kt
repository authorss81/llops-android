package com.authorss81.noteflow.services

/**
 * Phase 192 — `VoiceNoteManager.finalizeRecording` stop-time save decision table.
 *
 * The user-facing banner "The recording could not be saved securely. Please try
 * again." (`VoiceNoteManager.kt:263`) was produced for EVERY failed save with no
 * distinction, which hid three different realities:
 *
 *  1. a GENUINELY-LOCKED password vault — the DEK was zeroized mid-recording
 *     (auto-lock / ON_STOP lock) and must never be re-derived without the
 *     credential (B1-AUTH-02 / phase-47);
 *  2. a PASSWORDLESS vault whose in-memory DEK holder happened to be null at
 *     stop time — the device-wrapped copy IS the boot credential by design
 *     (`NoteflowSqlcipherFactory` re-reads it on every DB open,
 *     `NoteflowDatabase.kt:440-444`), so a false "locked" failure was shown for
 *     a state that was still legitimately recoverable;
 *  3. a recoverable cipher/IO failure (e.g. storage full) that deserved a
 *     truthful, non-alarming reason instead of the generic wording.
 *
 * This object owns the decision table + the truthful message mapping. It is
 * pure JVM (no Android imports) so the whole table is unit-testable with a
 * [passwordlessReader] lambda.
 */
object VoiceRecordingSavePolicy {

    /**
     * The stop-time DEK resolution outcome.
     *
     * [key] is the DEK to encrypt with, or null when it could not be obtained.
     * Carried on the sealed subtypes so the caller can sync it back into
     * [VaultKeyHolder] without asking the reader twice.
     */
    sealed class StopTimeKey {
        abstract val key: ByteArray?

        /** The in-memory holder was already live — normal path. */
        data class InMemory(override val key: ByteArray) : StopTimeKey()

        /** Passwordless vault: in-memory holder was null, the device copy was re-read OK. */
        data class PasswordlessReread(override val key: ByteArray) : StopTimeKey()

        /**
         * A password vault whose DEK is zeroized at stop: genuinely locked. The
         * DEK must never be re-derived/re-minted without the credential —
         * resolve stops here and the save fails closed.
         */
        object LockedVault : StopTimeKey() {
            override val key: ByteArray? get() = null
        }

        /**
         * A passwordless vault whose device copy is absent or unreadable
         * (keystore-key lost / anomalous). Fail closed — never mint, never
         * plaintext — with an honest message.
         */
        object KeyUnavailable : StopTimeKey() {
            override val key: ByteArray? get() = null
        }
    }

    /**
     * Resolves the DEK for a recording stop.
     *
     * Decision table:
     *  - in-memory DEK present → [StopTimeKey.InMemory];
     *  - in-memory DEK null on a PASSWORDLESS vault → re-read the stored device
     *    copy through [passwordlessReader]; available →
     *    [StopTimeKey.PasswordlessReread], absent/unreadable →
     *    [StopTimeKey.KeyUnavailable];
     *  - in-memory DEK null on a PASSWORD vault → [StopTimeKey.LockedVault].
     *
     * The passwordless re-read is the mirror the DB factory already uses (`a
     * passwordless vault still re-reads its device-wrapped copy (the boot
     * credential by design)`, `LockedOpenGuard`/`NoteflowSqlcipherFactory`). It
     * is NOT a mint and it NEVER runs for a password vault, so nothing here can
     * bypass a real lock boundary.
     */
    fun resolveStopTimeKey(
        inMemoryDek: ByteArray?,
        vaultHasPassword: Boolean,
        passwordlessReader: () -> ByteArray?,
    ): StopTimeKey = when {
        inMemoryDek != null -> StopTimeKey.InMemory(inMemoryDek)
        !vaultHasPassword -> {
            val reread = runCatching { passwordlessReader() }.getOrNull()
            if (reread != null) StopTimeKey.PasswordlessReread(reread)
            else StopTimeKey.KeyUnavailable
        }
        else -> StopTimeKey.LockedVault
    }

    /** True iff the resolution produced a DEK the save can encrypt with. */
    fun isKeyPresent(stopTimeKey: StopTimeKey): Boolean = when (stopTimeKey) {
        is StopTimeKey.InMemory -> true
        is StopTimeKey.PasswordlessReread -> true
        is StopTimeKey.LockedVault, is StopTimeKey.KeyUnavailable -> false
    }

    // ---- truthful failure messages (non-alarming, recoverable-first) -------

    /** Recoverable failures never claim the audio was "saved securely". */

    const val KEY_UNAVAILABLE_MESSAGE =
        "The recording couldn't be saved — the vault key isn't available. Unlock the vault and try again."

    const val STORAGE_FULL_MESSAGE =
        "The recording couldn't be saved — not enough storage space. Free some space and try again."

    const val SOURCE_MISSING_MESSAGE =
        "No audio was captured — the microphone may be busy or permission was revoked."

    const val TRANSIENT_FAILURE_MESSAGE =
        "The recording couldn't be saved right now. Please try again."

    /**
     * Maps a failed save to the truthful message.
     *
     * The GENUINELY-LOCKED case (`StopTimeKey.LockedVault`) keeps the historic
     * "The recording could not be saved securely. Please try again." — it is
     * surfaced inline by `VoiceNoteManager` so the exact wording stays pinned
     * there (see B1Db03VoiceNoteEncryptionTest / Phase153LockedSnackbarPolicyTest).
     * Every other failure gets a recoverable-oriented, non-alarming message from
     * [VoiceEncryptOutcome]'s reason.
     */
    fun messageFor(
        stopTimeKey: StopTimeKey,
        outcome: VoiceEncryptOutcome?,
    ): String = when (stopTimeKey) {
        is StopTimeKey.InMemory, is StopTimeKey.PasswordlessReread -> messageForEncrypt(outcome)
        is StopTimeKey.LockedVault -> LOCKED_VAULT_MESSAGE
        is StopTimeKey.KeyUnavailable -> KEY_UNAVAILABLE_MESSAGE
    }

    /**
     * Kept as an internal constant mirror only so the policy owns one copy of
     * the exact wording; `VoiceNoteManager.finalizeRecording` inlines the
     * literal for the source pins. Public to tests so the decision table is
     * fully pinned on the pure JVM.
     */
    internal const val LOCKED_VAULT_MESSAGE =
        "The recording could not be saved securely. Please try again."

    private fun messageForEncrypt(outcome: VoiceEncryptOutcome?): String = when (outcome) {
        is VoiceEncryptOutcome.Failed -> when (outcome.reason) {
            VoiceEncryptFailure.SOURCE -> SOURCE_MISSING_MESSAGE
            VoiceEncryptFailure.BLOB_TARGET -> TRANSIENT_FAILURE_MESSAGE
            VoiceEncryptFailure.ENOSPC -> STORAGE_FULL_MESSAGE
            VoiceEncryptFailure.IO_OR_CIPHER -> TRANSIENT_FAILURE_MESSAGE
        }
        // Called only on a failed save; a null outcome means "no DEK" which the
        // caller resolves via the key branches above — defensive fallback.
        null -> TRANSIENT_FAILURE_MESSAGE
        is VoiceEncryptOutcome.Saved -> TRANSIENT_FAILURE_MESSAGE
    }
}