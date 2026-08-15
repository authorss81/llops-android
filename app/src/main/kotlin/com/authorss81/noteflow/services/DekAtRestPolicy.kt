package com.authorss81.noteflow.services

/**
 * B1-CRYPTO-02 (phase-45): the single source of truth for where the vault DEK
 * may live at rest once a master password exists.
 *
 * Pre-fix, [SecurityService.getOrCreateDek] wrapped the DEK under a
 * NON-user-authenticated AndroidKeyStore key and persisted it in plain prefs
 * (`noteflow_sec_dek`); `setMasterPassword` added a SECOND wrapping under the
 * password-derived KEK but left that non-auth device copy in place, so a
 * root/forensic attacker or an in-process plugin recovered the DEK with no
 * credential, no biometric and no lockout.
 *
 * The invariant: when a master password exists, the ONLY at-rest wrapping of
 * the DEK is under the password-derived KEK (the versioned
 * `SettingsManager.masterPasswordCredentialOrLegacy` blob — salt + wrapped DEK
 * committed atomically as ONE value, B1-CRYPTO-03/phase-62), UNLESS the user
 * has EXPLICITLY opted into biometric unlock — and then the device copy may
 * exist ONLY as the `authRequired = true` (biometric-gated) AndroidKeyStore
 * blob. When NO master password exists the vault is device-bound by design:
 * the non-gated device copy is required for passwordless boot.
 *
 * Pure JVM (no Android imports) so the decision table is unit-testable in
 * `app/src/test`. Wired in `NoteflowViewModel.enforceDekAtRestPolicy`.
 *
 * B1-CRYPTO-07 (phase-65): [modeFor] gained the [strongBiometricBindingSupported]
 * input. When a master password exists but the platform cannot bind the auth-gated
 * AndroidKeyStore key to `AUTH_BIOMETRIC_STRONG` (API 26-29, see
 * [BiometricKeyBindingPolicy]), an enabled-biometrics state is DOWNGRADED to
 * [DekAtRestMode.PASSWORD_ONLY] — the weak-bound device copy is cleared, never
 * re-written — because such a key would be authorizable by a device credential or a
 * non-strong biometric, contradicting the "biometric-only" promise of the setting.
 */
internal enum class DekAtRestMode {
    /** No master password: device copy, readable without any credential (passwordless boot). */
    DEVICE_WRAPPED_NOT_AUTHGATED,

    /** Master password set, biometrics OFF (or not STRONG-boundable): NO device-copy DEK blob at rest at all. */
    PASSWORD_ONLY,

    /** Master password set, biometrics ON: device copy exists but ONLY `authRequired=true`. */
    BIOMETRIC_GATED_AUTH_COPY,
}

internal object DekAtRestPolicy {
    /**
     * @param strongBiometricBindingSupported whether the current platform can create
     *   an `AUTH_BIOMETRIC_STRONG`-bound key ([BiometricKeyBindingPolicy]); default
     *   true keeps pre-phase-65 call sites and tests unchanged.
     */
    fun modeFor(
        hasMasterPassword: Boolean,
        biometricAuthEnabled: Boolean,
        strongBiometricBindingSupported: Boolean = true,
    ): DekAtRestMode =
        if (!hasMasterPassword) {
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED
        } else if (biometricAuthEnabled && strongBiometricBindingSupported) {
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY
        } else {
            DekAtRestMode.PASSWORD_ONLY
        }
}
