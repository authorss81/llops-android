package com.authorss81.noteflow.services

/**
 * R2-B1A-03 (phase-140) — pure-JVM decision table for the ON_PAUSE opaque cover.
 *
 * The finding: for a has-master-password vault, decrypted content stays on
 * screen across ON_PAUSE-only covers (a `SYSTEM_ALERT_WINDOW` overlay, an OEM
 * in-call UI, or a translucent anti-theft app drawn OVER the unlocked vault
 * while the activity lingers at ON_PAUSE). Locking on ON_PAUSE was explicitly
 * rejected in phase-60 (it breaks SAF pickers / biometric prompts / share
 * sheets), so this phase adds an OPAQUE COVER instead: it goes up the instant
 * the activity pauses and is dismissed on ANY resume — the legitimate return
 * paths from a picker, a biometric prompt, or a share sheet.
 *
 * Decision table (cover → show / ??? = not covered):
 *
 * | hasMasterPassword | authenticated | Pause    | Resume |
 * |-------------------|---------------|----------|--------|
 * | true              | true          | COVER    | clear  |
 * | true              | false         | (locked) | clear  |
 * | false             | true          | (no lock | clear  |
 * |                   |               | boundary)|        |
 * | false             | false         | (no lock | clear  |
 * |                   |               | boundary)|        |
 *
 * Passwordless vaults are never covered: there is no lock boundary there (the
 * device-wrapped DEK is the boot credential by design, B1-AUTH-02 skipped
 * them), so a cover would only hide a session that never "locks".
 */
object OnPauseCoverPolicy {

    /**
     * Whether the opaque cover must be shown on an ON_PAUSE event. TRUE only for
     * a vault that has a master password AND is currently authenticated — the
     * only state where decrypted content can be sitting on screen.
     */
    fun shouldCoverOnPause(hasMasterPassword: Boolean, authenticated: Boolean): Boolean =
        hasMasterPassword && authenticated

    /**
     * Any resume event dismisses the cover — picker / biometric / share-sheet
     * returns are the legitimate return paths and must land on the live vault.
     */
    fun shouldDismissOnResume(coverActive: Boolean): Boolean = coverActive
}