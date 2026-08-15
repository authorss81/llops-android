package com.authorss81.noteflow

import com.authorss81.noteflow.services.DekAtRestMode
import com.authorss81.noteflow.services.DekAtRestPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B1-CRYPTO-02 (phase-45) decision-table tests.
 *
 * The invariant the fix enforces: when a master password exists, the only at-rest
 * wrapping of the vault DEK is under the password-derived KEK — UNLESS the user
 * explicitly enabled biometrics, in which case the device copy may exist only as
 * the `authRequired = true` (biometric-gated) AndroidKeyStore blob. Passwordless
 * vaults keep a non-gated device copy because that is the boot credential by
 * design.
 */
class DekAtRestPolicyTest {

    @Test
    fun `passwordless vault keeps a non-gated device copy`() {
        assertEquals(
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED,
            DekAtRestPolicy.modeFor(hasMasterPassword = false, biometricAuthEnabled = false)
        )
    }

    @Test
    fun `master password with biometrics off removes the device copy entirely`() {
        assertEquals(
            DekAtRestMode.PASSWORD_ONLY,
            DekAtRestPolicy.modeFor(hasMasterPassword = true, biometricAuthEnabled = false)
        )
    }

    @Test
    fun `master password with biometrics enabled keeps only an auth-gated copy`() {
        assertEquals(
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY,
            DekAtRestPolicy.modeFor(hasMasterPassword = true, biometricAuthEnabled = true)
        )
    }

    @Test
    fun `biometrics flag is irrelevant when no password exists`() {
        // (A stale biometricAuthEnabled=true with no password cannot decrypt
        // anything meaningful — readDek's auth gate refuses the blob anyway.)
        assertEquals(
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED,
            DekAtRestPolicy.modeFor(hasMasterPassword = false, biometricAuthEnabled = true)
        )
    }
}