package com.authorss81.noteflow

import com.authorss81.noteflow.services.LockedOpenGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-AUTH-02 (phase-47) decision-table tests for [LockedOpenGuard].
 *
 * The invariant the fix enforces: a database open may proceed WITHOUT in-memory
 * key material ONLY when no master password protects the vault (passwordless
 * vaults legitimately re-read their device-wrapped DEK — that IS the boot
 * credential). A password-protected vault with a zeroized in-memory DEK is a
 * LOCKED open and must fail closed — a stale coroutine or plugin hook can never
 * re-materialize a key through the factory.
 */
class LockedOpenGuardTest {

    @Test
    fun `DEK in memory - open always allowed regardless of password state`() {
        assertTrue(LockedOpenGuard.isOpenAllowed(dekInMemory = true, hasMasterPassword = true))
        assertTrue(LockedOpenGuard.isOpenAllowed(dekInMemory = true, hasMasterPassword = false))
    }

    @Test
    fun `password-protected vault with no DEK in memory is a refused locked open`() {
        // lock() zeroized VaultKeyHolder.dek: any SQLCipher open while locked must
        // fail closed (never re-read/derive a persisted key).
        assertFalse(LockedOpenGuard.isOpenAllowed(dekInMemory = false, hasMasterPassword = true))
    }

    @Test
    fun `passwordless vault with no DEK in memory may re-read its device copy`() {
        // No master password: the device-wrapped copy is the vault's boot
        // credential by design, so an un-locked open is legitimate.
        assertTrue(LockedOpenGuard.isOpenAllowed(dekInMemory = false, hasMasterPassword = false))
    }
}