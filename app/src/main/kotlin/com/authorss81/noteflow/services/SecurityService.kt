package com.authorss81.noteflow.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityService(private val context: Context) {
    companion object {
        private const val KEY_ALIAS = "noteflow_dek_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREF_DEK_STORAGE = "noteflow_sec_dek"
    }

    private fun getOrCreateKey(authRequired: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = if (authRequired) "${KEY_ALIAS}_auth" else KEY_ALIAS

        // Phase 31 Part C3: NEVER blindly cast `keyStore.getEntry(alias, null)`
        // to SecretKeyEntry. The stored entry on a given device/emulator may be a
        // PrivateKeyEntry, a TrustedCertificateEntry, a migrated/legacy entry, or
        // unreadable — an unchecked `as SecretKeyEntry` then throws
        // ClassCastException during DEK init and kills cold start. Inspect the
        // entry type instead; a wrong/invalid entry is cleared and re-created.
        resolveSecretKeyEntry(keyStore, alias)?.let { return it.secretKey }
        // Wrong-typed / unreadable entry under the alias: delete it so the
        // generator below mints a fresh SecretKey entry instead of colliding.
        if (keyStore.containsAlias(alias)) {
            runCatching { keyStore.deleteEntry(alias) }
        }
 
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(authRequired)
            .let {
                if (authRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    it
                }
            }
            .build()
 
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
 
    fun getDecryptionCipher(): Cipher? {
        val encoded = context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
            .getString(PREF_DEK_STORAGE, null) ?: return null
        
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 12) return null
            val iv = ByteArray(12)
            System.arraycopy(combined, 0, iv, 0, 12)
            
            val secretKey = getOrCreateKey(authRequired = true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher
        } catch (e: Exception) {
            null
        }
    }

    fun decryptWithCipher(cipher: Cipher): ByteArray? {
        val encoded = context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
            .getString(PREF_DEK_STORAGE, null) ?: return null
        
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val cipherText = ByteArray(combined.size - 12)
            System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            null
        }
    }

    fun storeDek(dek: ByteArray, authRequired: Boolean = false) {
        try {
            val secretKey = getOrCreateKey(authRequired)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedDek = cipher.doFinal(dek)
     
            val combined = ByteArray(iv.size + encryptedDek.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedDek, 0, combined, iv.size, encryptedDek.size)
     
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_DEK_STORAGE, encoded)
                .putBoolean("dek_auth_required", authRequired)
                .apply()
        } catch (e: Exception) {
            // Log/ignore keystore failure to avoid crashing app startup
        }
    }
 
    fun readDek(): ByteArray? {
        val prefs = context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
        val encoded = prefs.getString(PREF_DEK_STORAGE, null) ?: return null
        val authRequired = prefs.getBoolean("dek_auth_required", false)

        if (authRequired) return null // Must use biometric unlock flow

        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 12) return null
            val iv = ByteArray(12)
            System.arraycopy(combined, 0, iv, 0, 12)
            val cipherText = ByteArray(combined.size - 12)
            System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
 
            val secretKey = getOrCreateKey(authRequired = false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            null
        }
    }

    fun getOrCreateDek(): ByteArray? {
        val existing = readDek()
        if (existing != null) return existing
        val prefs = context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
        val authRequired = prefs.getBoolean("dek_auth_required", false)
        if (authRequired) return null

        val newDek = EncryptionService.generateDek()
        storeDek(newDek, authRequired = false)
        return newDek
    }

    fun clearDek() {
        context.getSharedPreferences("noteflow_keystore", Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_DEK_STORAGE)
            .apply()
    }
}

/**
 * Phase 31 Part C3 seam: resolve the DEK-wrapping key stored at [alias] WITHOUT
 * an unchecked cast. Returns the key only when the alias holds a genuine
 * [KeyStore.SecretKeyEntry]; returns null for an absent alias, a wrong-typed
 * entry (PrivateKeyEntry / TrustedCertificateEntry / migrated-legacy entry),
 * or an entry that cannot be read (getEntry threw). A null result means the
 * caller must clear the alias and mint a fresh key — never a crash.
 *
 * Pure JVM (only java.security.KeyStore) so the wrong-entry-type path is
 * unit-testable with a fake/in-memory keystore on the JVM.
 */
internal fun resolveSecretKeyEntry(keyStore: KeyStore, alias: String): KeyStore.SecretKeyEntry? {
    if (!keyStore.containsAlias(alias)) return null
    val entry = try {
        keyStore.getEntry(alias, null)
    } catch (e: Exception) {
        null
    }
    return entry as? KeyStore.SecretKeyEntry
}
