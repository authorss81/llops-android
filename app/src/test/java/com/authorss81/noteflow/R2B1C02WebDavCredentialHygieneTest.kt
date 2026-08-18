package com.authorss81.noteflow

import com.authorss81.noteflow.services.CredentialPrefs
import com.authorss81.noteflow.services.WebDavCredentialLoadResult
import com.authorss81.noteflow.services.WebDavCredentialStore
import java.io.File
import java.security.KeyStoreException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1C-02 (LOW, phase-145) — the silent-deletion bug:
 *
 * The pre-fix [WebDavCredentialStore.load] collapsed every load-time exception
 * into `clear(); null`. An auth-bound AndroidKeyStore key whose biometric
 * validity window had expired threw `UserNotAuthenticatedException` (the
 * keystore reports it as `KeyStoreException: user not authenticated`) — the app
 * then DELETED the remembered WebDAV credentials as if they were corrupted
 * data, even though the blob was perfectly intact and only required a recent
 * biometric authentication to decrypt.
 *
 * What this test proves on the pure JVM (in-memory CredentialPrefs + a
 * programmable key source; AndroidKeyStore itself is stubbed out):
 *  1. an auth-window-expired load ([KeyStoreException]("user not authenticated")
 *     — the keystore's message-form variant, the same state the Android
 *     [android.security.keystore.UserNotAuthenticatedException] reports) yields
 *     [WebDavCredentialLoadResult.AuthRequired] and NEVER clears;
 *  2. a genuine AEAD/tag failure (tampered ciphertext) yields
 *     [WebDavCredentialLoadResult.Corrupt] and IS cleared — the only state that
 *     may clear;
 *  3. a `BiometricPrompt.CryptoObject` re-auth round-trip works end-to-end
 *     ([prepareReauthCipher] → [decryptWithReauthCipher]) and a FAILED re-auth
 *     never clears the still-recoverable blob.
 */
class R2B1C02WebDavCredentialHygieneTest {

    private val aesKey: SecretKey = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")

    private class FakePrefs : CredentialPrefs {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) =
            map[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String?) =
            map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String): Boolean {
            map[key] = value
            return true
        }
        override fun putBoolean(key: String, value: Boolean): Boolean {
            map[key] = value
            return true
        }
        override fun remove(vararg keys: String): Boolean {
            keys.forEach { map.remove(it) }
            return true
        }
    }

    /** Key source that can be scripted to throw exactly what the keystore does. */
    private class RecordingKeySource(
        var onRequest: ((Boolean) -> SecretKey?)? = null
    ) {
        fun get(authBound: Boolean): SecretKey? = onRequest?.invoke(authBound)
    }

    private fun store(
        prefs: FakePrefs = FakePrefs(),
        keys: RecordingKeySource = RecordingKeySource { aesKey }
    ): WebDavCredentialStore = WebDavCredentialStore(prefs, keys::get)

    private fun tamperStoredBlob(prefs: FakePrefs) {
        val blob = prefs.map["webdav_encrypted_blob"] as? String
            ?: error("no stored blob to tamper")
        val decoded = java.util.Base64.getDecoder().decode(blob)
        val tampered = decoded.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        prefs.map["webdav_encrypted_blob"] = java.util.Base64.getEncoder().encodeToString(tampered)
    }

    // ---- the finding: auth-window expiry must NEVER clear -------------------

    @Test
    fun `an expired biometric window yields AuthRequired and never clears the blob`() {
        val prefs = FakePrefs()
        val saveKeys = RecordingKeySource { aesKey }
        assertTrue(store(prefs = prefs, keys = saveKeys).save(
            "https://cloud.example.com/dav/", "user", "tok", authBound = true
        ))
        assertTrue("the blob must exist before the window expires", prefs.map.containsKey("webdav_encrypted_blob"))

        // The keystore's auth gate now refuses: the key reports user-not-authenticated
        // (message-form variant of UserNotAuthenticatedException).
        val lockedKeys = RecordingKeySource {
            throw KeyStoreException("user not authenticated")
        }
        val locked = store(prefs = prefs, keys = lockedKeys)

        val result = locked.loadDetailed()
        assertEquals(
            "an expired auth window must report AuthRequired, not corruption",
            WebDavCredentialLoadResult.AuthRequired, result
        )
        assertNull(locked.load())
        assertTrue("the blob must SURVIVE an auth-window expiry", prefs.map.containsKey("webdav_encrypted_blob"))
        assertTrue("the remember-me flag must survive", locked.hasRemembered())
        assertTrue("the auth-bound flag must survive", locked.isAuthBound())
    }

    // ---- the only clearing state: a genuine AEAD/tag failure ----------------

    @Test
    fun `a genuine AEAD failure yields Corrupt and is the only state that clears`() {
        val prefs = FakePrefs()
        val s = store(prefs = prefs)
        assertTrue(s.save("https://cloud.example.com/dav/", "user", "tok"))
        assertTrue(prefs.map.containsKey("webdav_encrypted_blob"))

        tamperStoredBlob(prefs)
        val result = s.loadDetailed()
        assertEquals(
            "tampered ciphertext is genuinely corrupted",
            WebDavCredentialLoadResult.Corrupt, result
        )
        assertFalse("the irrecoverable blob must be cleared", prefs.map.containsKey("webdav_encrypted_blob"))
        assertFalse(s.hasRemembered())
        assertNull(s.load())
    }

    // ---- biometric re-auth: the R2-B1C-02 remedy ---------------------------

    @Test
    fun `a CryptoObject re-auth decrypts the preserved blob end to end`() {
        val s = store()
        assertTrue(s.save("https://cloud.example.com/dav/", "user", "tok", authBound = true))

        // The auth window is OPEN: a decrypt cipher can be prepared...
        val cipher = s.prepareReauthCipher()
        assertNotNull("an in-window auth-bound key must yield a decrypt cipher", cipher)

        // ...and the UI wraps it in a BiometricPrompt.CryptoObject; on biometric
        // success the CANCELED decrypt completes.
        val creds = s.decryptWithReauthCipher(cipher!!)
        assertEquals("https://cloud.example.com/dav/", creds?.serverUrl)
        assertEquals("user", creds?.username)
        assertEquals("tok", creds?.passwordOrToken)
    }

    @Test
    fun `a failed biometric re-auth never clears the recoverable blob`() {
        val prefs = FakePrefs()
        val s = store(prefs = prefs)
        assertTrue(s.save("https://cloud.example.com/dav/", "user", "tok", authBound = true))

        val cipher = s.prepareReauthCipher()!!
        // Between the prompt and the decrypt the stored blob is tampered with —
        // the user's biometric SUCCEEDED but the data proves corrupt. The remedy
        // must not turn a failed re-auth into a silent deletion of what a fresh
        // save would still overwrite.
        tamperStoredBlob(prefs)

        assertNull("a failed tag check must return null, not credentials", s.decryptWithReauthCipher(cipher))
        assertTrue("a failed re-auth must NOT clear the blob", prefs.map.containsKey("webdav_encrypted_blob"))
        assertTrue(s.hasRemembered())
    }

    // ---- source-level wiring (the Android-bound prompt path) ----------------

    @Test
    fun `the store keeps clear out of the auth-expiry and re-auth paths by construction`() {
        val source = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/WebDavCredentialStore.kt"
        ).readText()

        val authCatch = source.substringAfter("catch (e: UserNotAuthenticatedException)")
            .substringBefore("catch (e: KeyStoreException)")
        assertTrue(
            "the UserNotAuthenticated branch must classify AuthRequired, never clear",
            authCatch.contains("WebDavCredentialLoadResult.AuthRequired")
        )
        assertTrue("the auth-expiry branch must not call clear()", !authCatch.contains("clear()"))

        // The auth arm of the message-form keystore branch must classify AuthRequired
        // BEFORE any clear() can run.
        val authArm = source.substringAfter("if (e.message?.contains(\"user not authenticated\"")
            .substringBefore("WebDavCredentialLoadResult.AuthRequired")
        assertTrue(
            "the auth arm must reach AuthRequired without clearing",
            !authArm.contains("clear()")
        )

        // The remedy API exists and is explicit about never clearing.
        assertTrue(source.contains("fun prepareReauthCipher"))
        assertTrue(source.contains("fun decryptWithReauthCipher"))
    }

    @Test
    fun `the UI wires the CryptoObject re-auth and preserves the locked copy`() {
        val dialog = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/components/WebDavSyncDialog.kt"
        ).readText()
        val launch = dialog.substringAfter("LaunchedEffect(Unit)")
            .substringBefore("val usesInsecureHttp")

        assertTrue("the dialog must classify the load, not assume", launch.contains("loadDetailed()"))
        assertTrue("an expired window keeps remember-me and shows a notice", launch.contains("AuthRequired"))
        assertTrue("the locked state must never clear the saved copy", !launch.contains("clear()"))

        assertTrue("the unlock path binds a BiometricPrompt.CryptoObject", dialog.contains("BiometricPrompt.CryptoObject(it)"))
        assertTrue("the unlock path prepares the decrypt cipher", dialog.contains("prepareReauthCipher()"))
        assertTrue("biometric success completes the decrypt", dialog.contains("decryptWithReauthCipher(cipher)"))
        assertTrue("a failed/cancelled unlock keeps the stored copy", dialog.contains("your remembered credentials are still"))
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}