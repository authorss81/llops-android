package com.authorss81.noteflow.services

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R2-b2b1-UI-03 (phase-135): the SINGLE shared one-in-flight gate that
 * serializes every restore entry point (recovery screens, keystore-lost
 * recovery, HomeScreen local restore, WebDAV download+restore).
 *
 * A restore sequence is `closeDatabase → importBackup → reopen/exitProcess`. A
 * double trigger races two file swaps of the SAME SQLCipher file and both
 * schedule process kills, so a torn/mismatched vault can swap in and the
 * corruption/tamper flags arm on a user-triggered restore. The gate is
 * optimistic-try: the winning caller proceeds, every loser is refused before it
 * can touch the live DB.
 *
 * Pure JVM (AtomicBoolean + StateFlow) so tryBegin/end semantics are
 * unit-testable on the CI runner.
 */
class RestoreInflightGate {
    private val inFlight = AtomicBoolean(false)
    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    /** [true] if this caller won the gate (no restore currently in flight). */
    fun tryBegin(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        _isRestoring.value = true
        return true
    }

    /** Releases the gate — the winning caller MUST call this in a `finally`. */
    fun end() {
        inFlight.set(false)
        _isRestoring.value = false
    }
}