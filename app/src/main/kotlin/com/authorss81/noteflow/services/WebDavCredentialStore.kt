package com.authorss81.noteflow.services

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed store for remembered WebDAV credentials.
 *
 * Credentials are NEVER written to plain SharedPreferences. A non-extractable
 * AndroidKeyStore AES key encrypts the (server URL + username + token) blob;
 * only the base64 ciphertext (+ IV) lives on disk. Wiping the app's data
 * destroys the key and therefore the credentials with it.
 */
class WebDavCredentialStore(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "noteflow_webdav_credentials_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_NAME = "noteflow_webdav_credentials"
        private const val PREF_ENCRYPTED_BLOB = "webdav_encrypted_blob"
        private const val PREF_REMEMBER_ME = "webdav_remember_me"
        private const val GCM_IV_LENGTH = 12

        private fun List<String?>.joinCredentialBlob(): String =
            joinToString(separator = "\u0000")

        private fun String.splitCredentialBlob(): List<String?> =
            split('\u0000', limit = 3)
    }

    data class StoredCredentials(
        val serverUrl: String,
        val username: String,
        val passwordOrToken: String
    )

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun prefs() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Persists the remembered credentials encrypted under a non-extractable
     * AndroidKeyStore key. Plain SharedPreferences are never used for
     * credentials.
     */
    fun save(serverUrl: String, username: String, passwordOrToken: String) {
        val key = getOrCreateKey() ?: return
        try {
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
            prefs().edit()
                .putString(PREF_ENCRYPTED_BLOB, encoded)
                .putBoolean(PREF_REMEMBER_ME, true)
                .apply()
        } catch (e: Exception) {
            // Never write anything if encryption failed — no plaintext fallback.
        }
    }

    /**
     * Returns the remembered credentials, or null if none are stored (or the
     * stored blob can no longer be decrypted — treated as absent).
     */
    fun load(): StoredCredentials? {
        if (!prefs().getBoolean(PREF_REMEMBER_ME, false)) return null
        val encrypted = prefs().getString(PREF_ENCRYPTED_BLOB, null) ?: return null
        val key = getOrCreateKey() ?: return null
        return try {
            val combined = java.util.Base64.getDecoder().decode(encrypted)
            if (combined.size < GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val parts = plain.splitCredentialBlob()
            if (parts.size < 3) {
                clear()
                null
            } else {
                StoredCredentials(
                    serverUrl = parts[0].orEmpty(),
                    username = parts[1].orEmpty(),
                    passwordOrToken = parts[2].orEmpty()
                )
            }
        } catch (e: Exception) {
            clear()
            null
        }
    }

    fun hasRemembered(): Boolean = prefs().getBoolean(PREF_REMEMBER_ME, false)

    fun clear() {
        prefs().edit()
            .remove(PREF_ENCRYPTED_BLOB)
            .remove(PREF_REMEMBER_ME)
            .apply()
    }
}