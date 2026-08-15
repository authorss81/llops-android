package com.authorss81.noteflow.services

/**
 * B1-CRYPTO-07 (phase-65): the single decision table for whether the platform can
 * create a DEK-wrapping AndroidKeyStore key bound to BIOMETRIC_STRONG only.
 *
 * ## Why the API floor matters
 *
 * `KeyGenParameterSpec.Builder.setUserAuthenticationParameters(timeout, AUTH_BIOMETRIC_STRONG)`
 * — the ONLY API that binds a key to class-3 (strong) biometrics — was added in
 * API 30. Below that (API 26-29) the platform CANNOT express a strong-only key.
 *
 * Worse, the pre-30 default is dangerous: on API 26-29 a key built with only
 * `.setUserAuthenticationRequired(true)` leaves `userAuthenticationValidityDurationSeconds`
 * at its default of **0**, and the keystore daemon maps "anything that is not -1" to
 * `KM_TAG_USER_AUTH_TYPE = HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC` — i.e. ANY secure
 * device credential (PIN/pattern/password) authorizes the unwrap. Only an explicit
 * `.setUserAuthenticationValidityDurationSeconds(-1)` maps to `HW_AUTH_BIOMETRIC`
 * (biometric-only, per use) on those API levels — and even that is "any biometric",
 * never STRONG-guaranteed.
 *
 * Because the app UI promises strong-biometric-only protection for the vault DEK
 * wrapper (and the at-rest policy keeps the device copy ONLY as the auth-gated
 * blob, B1-CRYPTO-02), the fix is:
 *
 * 1. REFUSE to enable biometric unlock on API 26-29 ([strongBiometricKeyBindingSupported]
 *    is false) with a clear, non-alarming message ([refuseEnableMessage]) — the
 *    authoritative gate lives in `NoteflowViewModel.setBiometricEnabled`.
 * 2. DOWNGRADE any legacy `biometricAuthEnabled = true` state on API 26-29 to
 *    password-only inside `NoteflowViewModel.enforceDekAtRestPolicy` (B1-CRYPTO-02
 *    policy) so the weak-bound device copy is cleared, never re-written.
 * 3. DEFENSIVELY, when an auth key is ever (re)created below API 30,
 *    `SecurityService.getOrCreateKey` binds it with
 *    `setUserAuthenticationValidityDurationSeconds(-1)` — the strongest pre-30
 *    expression (biometric-only per use), never the bare `setUserAuthenticationRequired(true)`
 *    whose default lands in the device-credential path.
 *
 * Pure JVM (no Android imports) so the decision table is unit-testable in
 * `app/src/test`. Wired from `SecurityService`, `NoteflowViewModel`,
 * `BiometricAuthHelper` and the settings dialog.
 */
internal object BiometricKeyBindingPolicy {

    /** The first API level whose Keystore can bind a key to `AUTH_BIOMETRIC_STRONG`. */
    const val MIN_API_FOR_STRONG_BIOMETRIC_BINDING = 30

    /**
     * True only when [apiLevel] can create a key bound to BIOMETRIC_STRONG only.
     * False on API 26-29 (and below) — the platform cannot express
     * `AUTH_BIOMETRIC_STRONG`, so the app refuses the biometric-lock feature there.
     */
    fun strongBiometricKeyBindingSupported(apiLevel: Int): Boolean =
        apiLevel >= MIN_API_FOR_STRONG_BIOMETRIC_BINDING

    /**
     * The pre-30 keystore maps every non-(-1) validity to
     * `HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC`, so a device credential would satisfy
     * the key. -1 is the ONLY pre-30 binding that excludes device credentials
     * (biometric-only, per use). This is what `SecurityService.getOrCreateKey`
     * applies defensively below API 30; it is still NOT STRONG-guaranteed, which is
     * why [strongBiometricKeyBindingSupported] refuses the feature entirely.
     */
    const val PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS = -1

    /**
     * A human-readable, NON-alarming refusal message to show when the user tries to
     * enable biometric unlock on a platform that cannot bind a key to
     * `AUTH_BIOMETRIC_STRONG`. Returns null when the platform supports it.
     */
    fun refuseEnableMessage(apiLevel: Int): String? =
        if (strongBiometricKeyBindingSupported(apiLevel)) {
            null
        } else {
            "Biometric unlock needs Android 11 (API 30) or newer to bind the key to a " +
                "strong biometric only. This device can't create that key, so biometric " +
                "unlock stays off — your vault remains protected by your Master Password."
        }
}
