package com.authorss81.noteflow.services

/**
 * Phase 204: decision table for the keystore-key-lost "start fresh" vault reset.
 *
 * Pre-fix, `NoteflowViewModel.quarantineVaultFiles` wrapped each vault-file
 * rename in `runCatching { renameTo(...) }` with the results swallowed and
 * unconditionally proceeded to `clearDek()` + fresh-DEK boot. If
 * `noteflow.sqlite` (or its wal/shm sidecars) FAILED to move aside — ENOSPC,
 * EBUSY, a file watcher holding the fd — the brand-new vault was opened on top
 * of the OLD ciphertext: decrypt/integrity failed and the user landed in the
 * recovery loop again. The escape hatch bricked exactly when it was needed.
 *
 * The rule is fail-closed: start-fresh may proceed ONLY when every vault file
 * that EXISTED before the rename actually moved. Any leftover old-ciphertext
 * sidecar (even `-wal`) poisons a fresh database created at the same path
 * (SQLite would attempt WAL recovery against the wrong bytes), so an unmoved
 * WAL is just as fatal as an unmoved main DB. Files that did not exist are
 * irrelevant; if NOTHING existed there is nothing to preserve and start-fresh
 * proceeds (brand-new install).
 *
 * Pure JVM — no Android imports, API 26+ floor.
 */
object StartFreshVaultResetPolicy {

    /** Which vault file a rename attempt targeted. */
    enum class VaultFileRole { MAIN_DB, WAL, SHM, JOURNAL }

    /**
     * Outcome of one rename attempt.
     * [sourceExisted] == false ⇒ [moved] is meaningless (nothing to move).
     */
    data class VaultFileRename(
        val fileName: String,
        val role: VaultFileRole,
        val sourceExisted: Boolean,
        val moved: Boolean
    )

    sealed interface Decision {
        /** Every existing vault file moved aside — safe to boot the fresh vault. */
        data object Proceed : Decision

        /**
         * At least one EXISTING vault file could not be moved. Start-fresh must
         * ABORT: the old ciphertext still occupies the live path and a fresh
         * vault opened there would fail integrity/decrypt and loop the recovery
         * screen. [blockedBy] lists the offending file NAMES (never paths).
         */
        data class Abort(val blockedBy: List<String>) : Decision
    }

    /**
     * Fixed, honest, non-alarming abort text (UiFailureTextPolicy fixed-text
     * discipline — never exception text, never absolute paths). Rendered on the
     * KeystoreKeyLostScreen when start-fresh aborts; the screen stays up with
     * the old vault untouched.
     */
    const val ABORT_MESSAGE: String =
        "Couldn't safely reset the vault — some of its files could not be moved " +
            "aside, so your existing vault was left unchanged. Free up storage " +
            "space (or restart the device) and try again."

    /**
     * The outcome matrix:
     *  - no attempts / none existed                       → Proceed
     *  - all attempted renames succeeded                  → Proceed
     *  - ANY existed-but-unmoved file (main DB included)  → Abort(blockedBy)
     */
    fun decide(renames: List<VaultFileRename>): Decision {
        val blocked = renames.filter { it.sourceExisted && !it.moved }
        return if (blocked.isEmpty()) {
            Decision.Proceed
        } else {
            Decision.Abort(blocked.map { it.fileName })
        }
    }

    /** The canonical quarantine order + roles used by the single caller. */
    val QUARANTINE_FILES: List<Pair<String, VaultFileRole>> = listOf(
        "noteflow.sqlite" to VaultFileRole.MAIN_DB,
        "noteflow.sqlite-wal" to VaultFileRole.WAL,
        "noteflow.sqlite-shm" to VaultFileRole.SHM,
        "noteflow.sqlite-journal" to VaultFileRole.JOURNAL
    )
}
