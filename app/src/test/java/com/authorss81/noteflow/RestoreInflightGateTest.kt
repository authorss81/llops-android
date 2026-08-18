package com.authorss81.noteflow

import com.authorss81.noteflow.services.RestoreInflightGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b1-UI-03 (phase-135) behavioral tests for the shared one-in-flight gate
 * that serializes all four restore entry points.
 *
 * A restore is `closeDatabase → importBackup → reopen/exitProcess`. A double
 * trigger runs two of those sequences concurrently: two coroutines each close
 * the same DB, swap the same backing files, and schedule a process kill 500 ms
 * later — the second kill can land mid-swap of the first, producing a torn
 * `noteflow.sqlite` with corruption/tamper flags armed on a user-triggered
 * restore. The gate is optimistic: exactly one caller wins; everyone else is
 * refused BEFORE touching the live DB.
 */
class RestoreInflightGateTest {

    @Test
    fun `a second begin while one restore is in flight is refused`() {
        val gate = RestoreInflightGate()

        assertTrue("first caller wins the gate", gate.tryBegin())
        assertTrue("isRestoring surfaces to the UI", gate.isRestoring.value)
        assertFalse("second caller must be refused", gate.tryBegin())
        assertFalse("a third concurrent caller is refused too", gate.tryBegin())
    }

    @Test
    fun `end releases the gate so the next restore can begin`() {
        val gate = RestoreInflightGate()
        assertTrue(gate.tryBegin())

        gate.end()
        assertFalse("gate must read idle after end", gate.isRestoring.value)
        assertTrue("a fresh restore can begin after the previous one finished", gate.tryBegin())
    }

    @Test
    fun `begin end begin is a legal keep-alive pattern matching recovery+WebDAV retries`() {
        // The empty-vault confirm path calls import (EmptyVault throw), reopens,
        // waits for a confirm, then imports AGAIN — all within one logical restore.
        val gate = RestoreInflightGate()
        assertTrue(gate.tryBegin())
        gate.end()
        assertTrue(gate.tryBegin())
        gate.end()
        assertFalse(gate.isRestoring.value)
    }

    @Test
    fun `end on an idle gate leaves it idle - never the inverse`() {
        val gate = RestoreInflightGate()
        gate.end()
        assertFalse(gate.isRestoring.value)
        assertTrue("idle gate still grants the first begin", gate.tryBegin())
    }
}