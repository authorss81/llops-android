package com.authorss81.noteflow

import com.authorss81.noteflow.services.CredentialPrefs
import com.authorss81.noteflow.services.WebDavCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 109 — B1-NET-08 (INFO) fix tests.
 *
 * The store is exercised through its internal pure-JVM seam (in-memory
 * CredentialPrefs + a programmable key source), so the exact behaviours the
 * finding calls out are proven without AndroidKeyStore:
 *  1. a simulated keystore/write failure PROPAGATES (save() returns false) and
 *     is never swallowed — the UI can no longer believe a failed save landed;
 *  2. a failed save leaves the old credentials intact (no partial/destroyed
 *     state) but the caller knows the new ones did NOT persist;
 *  3. save()/load() route the biometric opt-in (`authBound`) through the key
 *     source, and load() uses the binding the blob was stored under.
 */
class WebDavCredentialStoreTest {

    private val aesKey: SecretKey = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")

    /** In-memory CredentialPrefs; can be scripted to fail writes. */
    private class FakePrefs : CredentialPrefs {
        val map = mutableMapOf<String, Any>()
        var failWrites = false

        override fun getBoolean(key: String, defaultValue: Boolean) =
            map[key] as? Boolean ?: defaultValue

        override fun getString(key: String, defaultValue: String?) =
            map[key] as? String ?: defaultValue

        override fun putString(key: String, value: String): Boolean {
            if (failWrites) return false
            map[key] = value
            return true
        }

        override fun putBoolean(key: String, value: Boolean): Boolean {
            if (failWrites) return false
            map[key] = value
            return true
        }

        override fun remove(vararg keys: String): Boolean {
            keys.forEach { map.remove(it) }
            return true
        }
    }

    /** Recording key source that can be scripted to fail. */
    private class RecordingKeySource(
        var onRequest: ((Boolean) -> SecretKey?)? = null
    ) {
        val requestedBindings = mutableListOf<Boolean>()
        fun get(authBound: Boolean): SecretKey? {
            requestedBindings.add(authBound)
            return onRequest?.invoke(authBound)
        }
    }

    private fun store(
        prefs: FakePrefs = FakePrefs(),
        keys: RecordingKeySource = RecordingKeySource { aesKey }
    ): Triple<WebDavCredentialStore, FakePrefs, RecordingKeySource> =
        Triple(WebDavCredentialStore(prefs, keys::get), prefs, keys)

    // --- save() propagates failures instead of swallowing them ---

    @Test
    fun saveSuccessPersistsAndLoadsBack() {
        val (s, prefs, _) = store()
        val saved = s.save("https://cloud.example.com/dav/", "user", "tok")
        assertTrue(saved)
        assertTrue(prefs.map.isNotEmpty())
        val creds = s.load()
        assertEquals("https://cloud.example.com/dav/", creds?.serverUrl)
        assertEquals("user", creds?.username)
        assertEquals("tok", creds?.passwordOrToken)
    }

    @Test
    fun saveFailureWhenKeyUnavailablePropagatesNotSwallowed() {
        val (s, prefs, _) = store(keys = RecordingKeySource { null })
        val saved = s.save("https://cloud.example.com/dav/", "user", "tok")
        assertFalse("A missing keystore key must surface as a failed save", saved)
        assertTrue("A failed save must not write anything", prefs.map.isEmpty())
        assertFalse(s.hasRemembered())
        assertNull(s.load())
    }

    @Test
    fun saveFailureWhenKeySourceThrowsPropagatesNotSwallowed() {
        val (s, prefs, _) = store(keys = RecordingKeySource { throw RuntimeException("keystore down") })
        val saved = s.save("https://cloud.example.com/dav/", "user", "tok")
        assertFalse("A throwing keystore must surface as a failed save", saved)
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun saveFailureLeavesPreviousCredentialsIntactButIsReported() {
        val prefs = FakePrefs()
        val (s, _, _) = store(prefs = prefs)
        assertTrue(s.save("https://old.example.com/", "userA", "tokA"))
        // Simulate a durable-write failure on the NEXT save (key stays readable).
        prefs.failWrites = true
        val saved = s.save("https://new.example.com/", "userB", "tokB")
        assertFalse("The new (failed) save must be reported, not swallowed", saved)
        prefs.failWrites = false
        // The previous credentials remain readable — but the caller now KNOWS the
        // new ones did not persist, so the UI cannot claim a stale success.
        val creds = s.load()
        assertEquals("https://old.example.com/", creds?.serverUrl)
        assertEquals("userA", creds?.username)
        assertEquals("tokA", creds?.passwordOrToken)
    }

    @Test
    fun saveFailureWhenDurableWriteFailsPropagates() {
        val prefs = FakePrefs()
        val (s, _, _) = store(prefs = prefs)
        prefs.failWrites = true
        val saved = s.save("https://cloud.example.com/dav/", "user", "tok")
        assertFalse("A failed disk/commit write must surface as a failed save", saved)
        assertFalse(s.hasRemembered())
    }

    // --- biometric opt-in routing (authBound) ---

    @Test
    fun saveWithoutAuthBoundUsesUnboundKeySource() {
        val (s, _, keys) = store()
        s.save("https://cloud.example.com/dav/", "user", "tok", authBound = false)
        assertEquals(listOf(false), keys.requestedBindings)
        assertFalse("Blob saved without biometric opt-in must not be marked auth-bound", s.isAuthBound())
    }

    @Test
    fun saveAndLoadWithAuthBoundRoutesThroughBindableKeySource() {
        val (s, _, keys) = store()
        val saved = s.save("https://cloud.example.com/dav/", "user", "tok", authBound = true)
        assertTrue(saved)
        assertEquals(listOf(true), keys.requestedBindings)
        assertTrue("Blob saved under the biometric opt-in must be marked auth-bound", s.isAuthBound())
        // load() must use the SAME binding the blob was stored under.
        val creds = s.load()
        assertEquals(listOf(true, true), keys.requestedBindings)
        assertEquals("user", creds?.username)
    }

    // --- misc semantics ---

    @Test
    fun loadReturnsNullWhenNothingStored() {
        val (s, _, _) = store()
        assertNull(s.load())
        assertFalse(s.hasRemembered())
    }

    @Test
    fun clearRemovesBlobRememberFlagAndBindingFlag() {
        val (s, prefs, _) = store()
        assertTrue(s.save("https://cloud.example.com/dav/", "user", "tok", authBound = true))
        assertTrue(s.hasRemembered())
        s.clear()
        assertFalse(s.hasRemembered())
        assertFalse(s.isAuthBound())
        assertNull(s.load())
        assertEquals(0, prefs.map.size)
    }

    @Test
    fun loadTreatsLegacyBlobWithoutBindingFlagAsUnbound() {
        // A blob written before this phase has no auth-bound flag — it must be
        // read back with the unbound key (backwards compatibility).
        val (s, prefs, keys) = store()
        assertTrue(s.save("https://cloud.example.com/dav/", "user", "tok"))
        prefs.map.remove("webdav_auth_bound")
        keys.requestedBindings.clear()
        val creds = s.load()
        assertEquals(listOf(false), keys.requestedBindings)
        assertEquals("user", creds?.username)
    }
}