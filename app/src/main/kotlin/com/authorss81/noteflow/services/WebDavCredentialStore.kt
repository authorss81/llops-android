package com.authorss81.noteflow.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R2-B1C-02 (phase-145): the classified outcome of [WebDavCredentialStore.loadDetailed].
 *
 * The pre-fix [WebDavCredentialStore.load] collapsed every load-time exception into
 * `clear(); null`, so an auth-bound key whose biometric window had expired
 * ([UserNotAuthenticatedException], thrown by `Cipher.init` on an auth-required
 * AndroidKeyStore key outside its `AUTH_VALIDITY_WINDOW_MS`) DELETED the user's
 * remembered credentials as if they were corrupted data. The three non-success
 * states are now distinct so the UI can offer a `BiometricPrompt.CryptoObject`
 * re-auth instead of asking the user to retype what was never lost.
 */
sealed class WebDavCredentialLoadResult {
    /** Nothing is remembered (or the stored blob is absent/undersized). Never cleared. */
    object None : WebDavCredentialLoadResult()

    /** The remembered credentials decrypted successfully. */
    data class Credentials(val value: WebDavCredentialStore.StoredCredentials) : WebDavCredentialLoadResult()

    /**
     * Credentials ARE remembered but the auth-bound AndroidKeyStore key is locked
     * — the biometric/validity window has expired. The blob is intact; a recent
     * biometric authentication would decrypt it again. NEVER cleared, NEVER
     * treated as corruption (the pre-fix catch-all's silent-delete bug).
     */
    object AuthRequired : WebDavCredentialLoadResult()

    /**
     * The stored blob is genuinely undecryptable (a real AEAD/tag failure, e.g.
     * tampered ciphertext or a key invalidated by biometric re-enrollment). It
     * can never decrypt again, so it is cleared for the user to re-enter.
     */
    object Corrupt : WebDavCredentialLoadResult()
}

private const val KEY_ALIAS = "noteflow_webdav_credentials_key"
private const val KEY_ALIAS_AUTH = "noteflow_webdav_credentials_key_auth"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val PREF_NAME = "noteflow_webdav_credentials"
private const val PREF_ENCRYPTED_BLOB = "webdav_encrypted_blob"
private const val PREF_REMEMBER_ME = "webdav_remember_me"
private const val PREF_AUTH_BOUND = "webdav_auth_bound"
private const val GCM_IV_LENGTH = 12

/**
 * B1-NET-08: when the user has opted into biometric unlock, the auth-bound key
 * stays usable for this window after any successful biometric authentication
 * (e.g. the vault unlock that opened the app). Outside the window a locked key
 * fails loudly through the new Boolean returns instead of failing silently.
 * On API 26-29 this is a validity-duration binding (keyguard + biometric);
 * on API 30+ it is BIOMETRIC_STRONG-only via [KeyProperties.AUTH_BIOMETRIC_STRONG].
 */
private const val AUTH_VALIDITY_WINDOW_MS = 10 * 60 * 1000L

/** Tiny persistence seam over SharedPreferences so the store is pure-JVM testable. */
internal interface CredentialPrefs {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getString(key: String, defaultValue: String?): String?
    fun putString(key: String, value: String): Boolean
    fun putBoolean(key: String, value: Boolean): Boolean
    fun remove(vararg keys: String): Boolean
}

/** Production [CredentialPrefs] over the app's private SharedPreferences. */
internal class AndroidCredentialPrefs(private val context: Context) : CredentialPrefs {
    private val prefs get() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, defaultValue: Boolean) =
        prefs.getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String?) =
        prefs.getString(key, defaultValue)

    // commit() (not apply()) so a disk write failure is a synchronous Boolean the
    // caller can surface — the "silent stale credentials" path of B1-NET-08.
    override fun putString(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()

    override fun putBoolean(key: String, value: Boolean): Boolean =
        prefs.edit().putBoolean(key, value).commit()

    override fun remove(vararg keys: String): Boolean =
        prefs.edit().apply { keys.forEach { remove(it) } }.commit()
}

/**
 * AndroidKeyStore-backed store for remembered WebDAV credentials.
 *
 * Credentials are NEVER written to plain SharedPreferences. A non-extractable
 * AndroidKeyStore AES key encrypts the (server URL + username + token) blob;
 * only the base64 ciphertext (+ IV) lives on disk. Wiping the app's data
 * destroys the key and therefore the credentials with it.
 *
 * B1-NET-08 fix:
 * 1. [save] returns a Boolean that reflects success/failure (encryption OR
 *    durable write). Failures are NO LONGER swallowed, so stale credentials are
 *    not silently kept while the UI believes the new ones were saved.
 * 2. When the user has opted into biometric unlock ([save]'s `authBound`), the
 *    keystore key is created with `setUserAuthenticationRequired(true)` +
 *    `setInvalidatedByBiometricEnrollment(true)`, so nothing in the process can
 *    decrypt the stored password without a recent biometric authentication.
 *    The auth-bound key lives under a SEPARATE alias so the stored blob's
 *    binding is physical (the key), never an advisory prefs flag.
 *
 * R2-B1C-02 (phase-145) fix:
 * [loadDetailed] distinguishes *why* the remembered credentials could not be
 * decrypted. [UserNotAuthenticatedException] (the auth-bound key's biometric
 * window expiring — "user not authenticated" from the keystore) is NOT an error
 * in the stored data: the blob is intact and a recent biometric authentication
 * would decrypt it again, so it is reported as [WebDavCredentialLoadResult.AuthRequired]
 * and NEVER cleared. Only a true AEAD/tag/decrypt failure (tampered ciphertext,
 * a key invalidated by biometric re-enrollment) clears. [prepareReauthCipher] /
 * [decryptWithReauthCipher] let the UI run a `BiometricPrompt.CryptoObject`
 * re-auth against the stored blob, mirroring [SecurityService.getDecryptionCipher] /
 * [SecurityService.decryptWithCipher].
 */
class WebDavCredentialStore internal constructor(
    private val prefs: CredentialPrefs,
    private val keySource: (authBound: Boolean) -> SecretKey?
) {

    constructor(context: Context) : this(
        prefs = AndroidCredentialPrefs(context),
        keySource = { authBound -> getOrCreateKey(context, authBound) }
    )

    data class StoredCredentials(
        val serverUrl: String,
        val username: String,
        val passwordOrToken: String
    )

    companion object {
        /**
         * B1-NET-08: mint (or reuse) the AES-GCM key for [context]. `authBound`
         * selects the bound alias and adds the user-authentication gate; the two
         * aliases are kept distinct so an auth-bound blob can never silently fall
         * back to the unbound key (the binding is physical, not a stored flag).
         */
        fun getOrCreateKey(context: Context, authBound: Boolean): SecretKey? {
            val alias = if (authBound) KEY_ALIAS_AUTH else KEY_ALIAS
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                resolveSecretKeyEntry(keyStore, alias)?.let { return it.secretKey }
                if (keyStore.containsAlias(alias)) {
                    runCatching { keyStore.deleteEntry(alias) }
                }
                if (authBound && !BiometricAuthHelper.isBiometricAvailable(context)) {
                    // No strong biometric enrolled: refuse to mint an un-unlockable
                    // key — the caller sees a save failure and surfaces it.
                    return null
                }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val specBuilder = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(authBound)
                    .setInvalidatedByBiometricEnrollment(authBound)
                if (authBound) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        specBuilder.setUserAuthenticationParameters(
                            AUTH_VALIDITY_WINDOW_MS.toInt(),
                            KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        specBuilder.setUserAuthenticationValidityDurationSeconds(
                            (AUTH_VALIDITY_WINDOW_MS / 1000).toInt()
                        )
                    }
                }
                generator.init(specBuilder.build())
                generator.generateKey()
            } catch (e: Exception) {
                null
            }
        }

        private fun List<String?>.joinCredentialBlob(): String =
            joinToString(separator = "\u0000")

        private fun String.splitCredentialBlob(): List<String?> =
            split('\u0000', limit = 3)
    }

    /**
     * Persists the remembered credentials encrypted under a non-extractable
     * AndroidKeyStore key. Plain SharedPreferences are never used for
     * credentials. Returns false (never silent) when the key is unavailable,
     * encryption fails, OR the durable write fails — the caller decides how to
     * surface it. Previous credentials are left untouched on failure so a
     * transient keystore error cannot destroy data, but the failed save is
     * reported instead of being swallowed.
     *
     * `authBound` = true binds decryption to a recent biometric unlock of the
     * user (opt-in, matching the app's global biometric-unlock setting).
     */
    fun save(
        serverUrl: String,
        username: String,
        passwordOrToken: String,
        authBound: Boolean = false
    ): Boolean {
        return try {
            val key = keySource(authBound) ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(
                listOf(serverUrl, username, passwordOrToken).joinCredentialBlob()
                    .toByteArray(Charsets.UTF_8)
            )
            val combined = ByteArray(GCM_IV_LENGTH + cipherText.size)
            System.arraycopy(cipher.iv, 0, combined, 0, GCM_IV_LENGTH)
            System.arraycopy(cipherText, 0, combined, GCM_IV_LENGTH, cipherText.size)
            val encoded = java.util.Base64.getEncoder().encodeToString(combined)
            val blobWritten = prefs.putString(PREF_ENCRYPTED_BLOB, encoded)
            val flagWritten = prefs.putBoolean(PREF_REMEMBER_ME, true)
            val authBoundWritten = prefs.putBoolean(PREF_AUTH_BOUND, authBound)
            blobWritten && flagWritten && authBoundWritten
        } catch (e: Exception) {
            // B1-NET-08: no plaintext fallback, no swallowing — the caller must
            // know the write did not land so it cannot claim success to the UI.
            false
        }
    }

    /**
     * Returns the remembered credentials, or null when there is nothing to show
     * ([WebDavCredentialLoadResult.None]), the auth window has expired
     * ([WebDavCredentialLoadResult.AuthRequired]) or a genuine AEAD/tag failure has
     * cleared them ([WebDavCredentialLoadResult.Corrupt]). The distinction matters
     * to the UI — use [loadDetailed] where a biometric re-auth must be offered.
     *
     * Uses the auth-bound key (requiring a recent biometric unlock) only for
     * blobs that were originally saved with `authBound = true`.
     */
    fun load(): StoredCredentials? = when (val r = loadDetailed()) {
        is WebDavCredentialLoadResult.Credentials -> r.value
        else -> null
    }

    /**
     * R2-B1C-02 (phase-145): the classified load. Unlike the pre-fix [load],
     * [UserNotAuthenticatedException] (the auth window expiring) NEVER clears the
     * remembered credentials — nothing is wrong with them, only with the current
     * authentication state. Only a genuine AEAD/tag/decrypt failure (or a stored
     * blob that structurally cannot be a credential) clears.
     */
    fun loadDetailed(): WebDavCredentialLoadResult {
        if (!prefs.getBoolean(PREF_REMEMBER_ME, false)) return WebDavCredentialLoadResult.None
        val encrypted = prefs.getString(PREF_ENCRYPTED_BLOB, null) ?: return WebDavCredentialLoadResult.None
        val authBound = prefs.getBoolean(PREF_AUTH_BOUND, false)
        return try {
            val key = keySource(authBound)
                ?: return WebDavCredentialLoadResult.None
            val combined = java.util.Base64.getDecoder().decode(encrypted)
            if (combined.size < GCM_IV_LENGTH) return WebDavCredentialLoadResult.None
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val parts = plain.splitCredentialBlob()
            if (parts.size < 3) {
                clear()
                WebDavCredentialLoadResult.Corrupt
            } else {
                WebDavCredentialLoadResult.Credentials(
                    StoredCredentials(
                        serverUrl = parts[0].orEmpty(),
                        username = parts[1].orEmpty(),
                        passwordOrToken = parts[2].orEmpty()
                    )
                )
            }
        } catch (e: UserNotAuthenticatedException) {
            // R2-B1C-02: the auth-bound key's 10-minute biometric window expired —
            // a `KeyStoreException: user not authenticated`. The credentials are
            // INTACT and a recent biometric authentication would decrypt them
            // again. This MUST NOT clear (the pre-fix catch-all deleted them and
            // the gate became unexercisable).
            WebDavCredentialLoadResult.AuthRequired
        } catch (e: KeyStoreException) {
            // Defensive for the message-form variant of the same keystore state.
            if (e.message?.contains("user not authenticated", ignoreCase = true) == true) {
                WebDavCredentialLoadResult.AuthRequired
            } else {
                clear()
                WebDavCredentialLoadResult.Corrupt
            }
        } catch (e: Exception) {
            // Any other decrypt failure means the stored blob is unrecoverable —
            // tampered ciphertext (AEADBadTagException), a key invalidated by
            // biometric re-enrollment, etc. Only THIS state may clear.
            clear()
            WebDavCredentialLoadResult.Corrupt
        }
    }

    /**
     * R2-B1C-02 (phase-145): prepares a DECRYPT cipher over the stored auth-bound
     * blob so the UI can wrap it in a `BiometricPrompt.CryptoObject` re-auth. When
     * the biometric window is still open, `Cipher.init` succeeds and the re-auth
     * binds the whole decrypt to the biometric result; when the window has closed
     * ([UserNotAuthenticatedException]) it returns null and the caller runs a PLAIN
     * biometric prompt whose success refreshes the keystore window, then re-runs
     * [loadDetailed]. Never clears anything.
     */
    fun prepareReauthCipher(): Cipher? {
        if (!prefs.getBoolean(PREF_REMEMBER_ME, false)) return null
        val encrypted = prefs.getString(PREF_ENCRYPTED_BLOB, null) ?: return null
        if (!prefs.getBoolean(PREF_AUTH_BOUND, false)) return null
        val key = keySource(true) ?: return null
        return try {
            val combined = java.util.Base64.getDecoder().decode(encrypted)
            if (combined.size < GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /**
     * R2-B1C-02 (phase-145): completes the decrypt of the stored blob with the
     * cipher returned by [prepareReauthCipher] after a successful biometric
     * authentication. Returns null on failure WITHOUT clearing (a failed re-auth
     * must never delete what is still recoverable by retrying).
     */
    fun decryptWithReauthCipher(cipher: Cipher): StoredCredentials? {
        if (!prefs.getBoolean(PREF_REMEMBER_ME, false)) return null
        val encrypted = prefs.getString(PREF_ENCRYPTED_BLOB, null) ?: return null
        return try {
            val combined = java.util.Base64.getDecoder().decode(encrypted)
            if (combined.size < GCM_IV_LENGTH) return null
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val parts = plain.splitCredentialBlob()
            if (parts.size < 3) {
                null
            } else {
                StoredCredentials(
                    serverUrl = parts[0].orEmpty(),
                    username = parts[1].orEmpty(),
                    passwordOrToken = parts[2].orEmpty()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasRemembered(): Boolean = prefs.getBoolean(PREF_REMEMBER_ME, false)

    fun isAuthBound(): Boolean = prefs.getBoolean(PREF_AUTH_BOUND, false)

    fun clear() {
        prefs.remove(PREF_ENCRYPTED_BLOB, PREF_REMEMBER_ME, PREF_AUTH_BOUND)
    }
}